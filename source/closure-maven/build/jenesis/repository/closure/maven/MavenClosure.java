package build.jenesis.repository.closure.maven;

import module java.base;
import module org.slf4j;
import build.jenesis.repository.closure.ClosureSection;
import build.jenesis.repository.closure.EcosystemClosure;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.ServableNames;
import org.eclipse.aether.AbstractRepositoryListener;
import org.eclipse.aether.RepositoryEvent;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.CollectResult;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.RepositoryPolicy;
import org.eclipse.aether.repository.WorkspaceReader;
import org.eclipse.aether.repository.WorkspaceRepository;
import org.eclipse.aether.resolution.ArtifactDescriptorException;
import org.eclipse.aether.resolution.ArtifactDescriptorPolicy;
import org.eclipse.aether.resolution.ArtifactDescriptorRequest;
import org.eclipse.aether.resolution.ArtifactDescriptorResult;
import org.eclipse.aether.resolution.VersionRangeResolutionException;
import org.eclipse.aether.spi.connector.transport.Transporter;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.supplier.RepositorySystemSupplier;
import org.eclipse.aether.supplier.SessionBuilderSupplier;
import org.eclipse.aether.transfer.NoTransporterException;
import org.eclipse.aether.util.repository.SimpleArtifactDescriptorPolicy;

/**
 * Resolves a Maven release's closure with Maven Resolver over the POMs one repository holds. The release's own POM is
 * read for its descriptor; the dependencies it ships - compile and runtime, not optional - are collected with its
 * managed dependencies, so a parent's or an imported BOM's versions and a property's value apply exactly as Maven
 * applies them, and the collected graph after version mediation is the closure.
 *
 * <p>Nothing is fetched: the session is offline and the system has no transporter, so a repository a POM declares is
 * never asked. A POM the repository does not hold, or holds only for review, is a cut, and so is a range no held
 * version satisfies; what was collected besides is kept. A release with no POM is not this resolver's to answer.
 */
public final class MavenClosure implements EcosystemClosure {

    private static final Logger LOGGER = LoggerFactory.getLogger(MavenClosure.class);

    /** The scopes a consumer of the release pulls in. */
    private static final Set<String> SHIPPED = Set.of("compile", "runtime", "");

    /** The most components a closure records, as the walk by declarations bounds it. */
    static final int MAX_COMPONENTS = 2_000;

    /** The most POMs one resolution reads, so a pathological parent chain or BOM fan-out ends. */
    static final int MAX_DOCUMENTS = 4 * MAX_COMPONENTS;

    /** The largest POM read. */
    static final int LARGEST_POM = 4 * 1024 * 1024;

    @Override
    public String ecosystem() {
        return "Maven";
    }

    @Override
    public Optional<ClosureSection.Closure> resolve(ArtifactStore store, String coordinate, String version,
                                                    Instant now) throws IOException {
        String[] ga = coordinate.split(":");
        if (ga.length != 2) {
            return Optional.empty();
        }
        Held held = new Held(store);
        if (!held.servesPom(ga[0], ga[1], version)) {
            return Optional.empty();
        }
        Path local = Files.createTempDirectory("jenesis-closure");
        try {
            return Optional.of(collect(store, held, local, new DefaultArtifact(ga[0], ga[1], "pom", version), now));
        } finally {
            delete(local);
        }
    }

    private ClosureSection.Closure collect(ArtifactStore store, Held held, Path local, Artifact root, Instant now) {
        Set<String> missing = ConcurrentHashMap.newKeySet();
        RepositorySystem system = new Offline().get();
        List<ClosureSection.Cut> cuts = new ArrayList<>();
        DependencyNode collected;
        try (RepositorySystemSession.CloseableSession session = new SessionBuilderSupplier(system).get()
                .withLocalRepositoryBaseDirectories(local.resolve("repository"))
                .setWorkspaceReader(held.reader(local.resolve("held")))
                .setOffline(true)
                .setArtifactDescriptorPolicy(new SimpleArtifactDescriptorPolicy(
                        ArtifactDescriptorPolicy.IGNORE_MISSING))
                .setChecksumPolicy(RepositoryPolicy.CHECKSUM_POLICY_IGNORE)
                .setRepositoryListener(new AbstractRepositoryListener() {
                    @Override
                    public void artifactDescriptorMissing(RepositoryEvent event) {
                        Artifact artifact = event.getArtifact();
                        missing.add(artifact.getGroupId() + ":" + artifact.getArtifactId() + ":"
                                + artifact.getVersion());
                    }
                })
                .build()) {
            ArtifactDescriptorResult descriptor = system.readArtifactDescriptor(session,
                    new ArtifactDescriptorRequest(root, List.of(), null));
            CollectRequest request = new CollectRequest();
            request.setRootArtifact(root);
            request.setDependencies(descriptor.getDependencies().stream()
                    .filter(dependency -> SHIPPED.contains(dependency.getScope()) && !dependency.isOptional())
                    .toList());
            request.setManagedDependencies(descriptor.getManagedDependencies());
            request.setRepositories(List.of());
            try {
                collected = system.collectDependencies(session, request).getRoot();
            } catch (DependencyCollectionException partial) {
                CollectResult result = partial.getResult();
                collected = result.getRoot();
                for (Exception failure : result.getExceptions()) {
                    cuts.add(cut(failure));
                }
            }
        } catch (ArtifactDescriptorException unreadable) {
            return new ClosureSection.Closure(ClosureSection.Status.PARTIAL, List.of(),
                    List.of(new ClosureSection.Cut(root.getGroupId() + ":" + root.getArtifactId(), root.getVersion(),
                            "its POM could not be read: " + unreadable.getMessage())), false, now);
        } finally {
            system.shutdown();
        }
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(store);
        List<ClosureSection.Component> components = new ArrayList<>();
        boolean truncated = held.exhausted();
        Set<String> seen = new HashSet<>();
        seen.add(root.getGroupId() + ":" + root.getArtifactId());
        record Visit(DependencyNode node, int depth) {
        }
        Deque<Visit> queue = new ArrayDeque<>();
        if (collected != null) {
            collected.getChildren().forEach(child -> queue.add(new Visit(child, 1)));
        }
        while (!queue.isEmpty()) {
            Visit visit = queue.poll();
            Artifact artifact = visit.node().getArtifact();
            if (artifact == null || !seen.add(artifact.getGroupId() + ":" + artifact.getArtifactId())) {
                continue;
            }
            String ga = artifact.getGroupId() + ":" + artifact.getArtifactId();
            String gav = ga + ":" + artifact.getVersion();
            if (missing.contains(gav)) {
                cuts.add(new ClosureSection.Cut(ga, artifact.getVersion(), held.heldForReview(artifact)
                        ? "held for review" : "not held by this repository"));
                continue;
            }
            if (components.size() >= MAX_COMPONENTS) {
                truncated = true;
                break;
            }
            components.add(new ClosureSection.Component(ga, artifact.getVersion(), cached(inventory, ga, artifact),
                    visit.depth()));
            visit.node().getChildren().forEach(child -> queue.add(new Visit(child, visit.depth() + 1)));
        }
        return new ClosureSection.Closure(cuts.isEmpty() && !truncated ? ClosureSection.Status.RESOLVED
                : ClosureSection.Status.PARTIAL, components, cuts, truncated, now);
    }

    private static boolean cached(StoreRepositoryInventory inventory, String ga, Artifact artifact) {
        try {
            return inventory.publishedAt("Maven", ga, artifact.getVersion()).isEmpty();
        } catch (IOException unreadable) {
            return false;
        }
    }

    /** A collection failure as the cut it is: an unsatisfied range names its dependency and its range. */
    private static ClosureSection.Cut cut(Exception failure) {
        if (failure instanceof VersionRangeResolutionException range && range.getResult() != null
                && range.getResult().getRequest() != null) {
            Artifact artifact = range.getResult().getRequest().getArtifact();
            return new ClosureSection.Cut(artifact.getGroupId() + ":" + artifact.getArtifactId(),
                    artifact.getVersion(), "no held version satisfies the range");
        }
        LOGGER.debug("A closure subtree did not resolve", failure);
        return new ClosureSection.Cut("", "", String.valueOf(failure.getMessage()));
    }

    private static void delete(Path folder) {
        try (Stream<Path> paths = Files.walk(folder)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException ignored) {
            // a temporary folder left behind is reclaimed with the temporary directory
        }
    }

    /** The resolver with no transporter: a repository any POM declares is never reached. */
    private static final class Offline extends RepositorySystemSupplier {

        @Override
        protected Map<String, TransporterFactory> createTransporterFactories() {
            return Map.of("none", new TransporterFactory() {
                @Override
                public Transporter newInstance(RepositorySystemSession session, RemoteRepository repository)
                        throws NoTransporterException {
                    throw new NoTransporterException(repository);
                }

                @Override
                public float getPriority() {
                    return 0;
                }
            });
        }
    }

    /** What one repository holds, as the resolver reads it: its served POMs and the versions it serves. */
    private static final class Held {

        private final ArtifactStore store;
        private final Publication publication;
        private final StoreRepositoryInventory inventory;
        private final AtomicInteger read = new AtomicInteger();

        Held(ArtifactStore store) {
            this.store = store;
            this.publication = new Publication(store);
            this.inventory = new StoreRepositoryInventory(store);
        }

        boolean servesPom(String group, String artifact, String version) throws IOException {
            return publication.located(path(group, artifact, version)).isPresent();
        }

        boolean exhausted() {
            return read.get() > MAX_DOCUMENTS;
        }

        boolean heldForReview(Artifact artifact) {
            try {
                return Publication.reviewPending(store,
                        path(artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion()));
            } catch (IOException unreadable) {
                return false;
            }
        }

        WorkspaceReader reader(Path folder) {
            WorkspaceRepository repository = new WorkspaceRepository("held");
            return new WorkspaceReader() {
                @Override
                public WorkspaceRepository getRepository() {
                    return repository;
                }

                @Override
                public File findArtifact(Artifact artifact) {
                    if (!"pom".equals(artifact.getExtension()) || !artifact.getClassifier().isEmpty()
                            || read.incrementAndGet() > MAX_DOCUMENTS) {
                        return null;
                    }
                    try {
                        Optional<Publication.Located> located = publication.locate(
                                path(artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion()));
                        if (located.isEmpty() || located.get().size() > LARGEST_POM) {
                            return null;
                        }
                        Path file = folder.resolve(artifact.getGroupId() + "/" + artifact.getArtifactId() + "/"
                                + artifact.getVersion() + ".pom");
                        Files.createDirectories(file.getParent());
                        try (InputStream in = store.open(located.get().key())) {
                            Files.write(file, in.readNBytes(LARGEST_POM));
                        }
                        return file.toFile();
                    } catch (IOException unreadable) {
                        return null;
                    }
                }

                /** The versions the repository holds and serves: what a range is resolved against. */
                @Override
                public List<String> findVersions(Artifact artifact) {
                    String ga = artifact.getGroupId() + ":" + artifact.getArtifactId();
                    List<String> versions = new ArrayList<>();
                    try {
                        String after = null;
                        int examined = 0;
                        do {
                            StoreRepositoryInventory.HoldingPage page = inventory.holdings("Maven", ga, after, 200);
                            for (StoreRepositoryInventory.Holding holding : page.holdings()) {
                                if (inventory.disclosable("Maven", ga, holding.version(),
                                        ServableNames.Policy.HIDE_WITHHELD)) {
                                    versions.add(holding.version());
                                }
                            }
                            examined += page.holdings().size();
                            after = page.next();
                        } while (after != null && examined < MAX_COMPONENTS);
                    } catch (IOException unreadable) {
                        return List.of();
                    }
                    return versions;
                }
            };
        }

        private static String path(String group, String artifact, String version) {
            return "/maven/" + group.replace('.', '/') + "/" + artifact + "/" + version + "/" + artifact + "-"
                    + version + ".pom";
        }
    }
}
