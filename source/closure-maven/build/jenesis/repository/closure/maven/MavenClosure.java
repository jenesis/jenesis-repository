package build.jenesis.repository.closure.maven;

import module java.base;
import module org.slf4j;
import build.jenesis.repository.closure.ClosureSection;
import build.jenesis.repository.closure.ClosureWalk;
import build.jenesis.repository.closure.ClosureSource;
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
 * Resolves a Maven release's closure with Maven Resolver over the POMs the repositories of a {@link ClosureWalk} hold -
 * the first of them holding a POM serving it, and a range resolved against the versions any of them serves, as a
 * request through the repository walks them. The release's own POM is
 * read for its descriptor; the dependencies it ships - compile and runtime, not optional - are collected with its
 * managed dependencies, so a parent's or an imported BOM's versions and a property's value apply exactly as Maven
 * applies them, and the collected graph after version mediation is the closure.
 *
 * <p>Nothing is fetched: the session is offline and the system has no transporter, so a repository a POM declares is
 * never asked. A POM the repository does not hold, or holds only for review, is a cut, and so is a range no held
 * version satisfies; what was collected besides is kept. A release with no POM is not this resolver's to answer.
 */
public final class MavenClosure implements ClosureSource {

    /** The source's name. */
    public static final String NAME = "maven-resolver";

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
    public String name() {
        return NAME;
    }

    /** Maven alone: the resolver reads POMs, and no other ecosystem's releases are described by one. */
    @Override
    public Set<String> ecosystems() {
        return Set.of("Maven");
    }

    @Override
    public Kind kind() {
        return Kind.RESOLVER;
    }

    @Override
    public Optional<ClosureSection.Closure> resolve(ClosureWalk walk, String ecosystem, String coordinate,
                                                    String version, Instant now) throws IOException {
        String[] ga = coordinate.split(":");
        if (ga.length != 2) {
            return Optional.empty();
        }
        Held held = new Held(walk);
        if (!held.servesOwnPom(ga[0], ga[1], version)) {
            return Optional.empty();
        }
        Path local = Files.createTempDirectory("jenesis-closure");
        try {
            return Optional.of(collect(held, local, new DefaultArtifact(ga[0], ga[1], "pom", version), now));
        } finally {
            delete(local);
        }
    }

    private ClosureSection.Closure collect(Held held, Path local, Artifact root, Instant now) {
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
                            "its POM could not be read: " + unreadable.getMessage())), false, now,
                    Kind.RESOLVER, NAME);
        } finally {
            system.shutdown();
        }
        List<ClosureSection.Component> components = new ArrayList<>();
        boolean truncated = held.exhausted();
        Set<String> seen = new HashSet<>();
        seen.add(root.getGroupId() + ":" + root.getArtifactId());
        // The node to place, and the dependency whose children it is, or none for the release's own.
        record Visit(DependencyNode node, int depth, String viaCoordinate, String viaVersion) {
        }
        Deque<Visit> queue = new ArrayDeque<>();
        if (collected != null) {
            collected.getChildren().forEach(child -> queue.add(new Visit(child, 1, "", "")));
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
                        ? "held for review" : held.single() ? "not held by this repository"
                        : "not held by this repository or a repository its fallbacks name"));
                continue;
            }
            if (components.size() >= MAX_COMPONENTS) {
                truncated = true;
                break;
            }
            Optional<Held.Member> holder = held.holder(artifact);
            components.add(new ClosureSection.Component(ga, artifact.getVersion(),
                    holder.map(member -> member.cached(ga, artifact.getVersion())).orElse(false), visit.depth(),
                    holder.map(Held.Member::repository).orElse(""), visit.viaCoordinate(), visit.viaVersion()));
            visit.node().getChildren().forEach(child -> queue.add(new Visit(child, visit.depth() + 1, ga,
                    artifact.getVersion())));
        }
        return new ClosureSection.Closure(cuts.isEmpty() && !truncated ? ClosureSection.Status.RESOLVED
                : ClosureSection.Status.PARTIAL, components, cuts, truncated, now,
                Kind.RESOLVER, NAME);
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

    /** What the repositories of a walk hold, as the resolver reads them: their served POMs and the versions they
     *  serve. */
    private static final class Held {

        /** One repository of the walk; its name is empty for the repository the release was published to. */
        record Member(String repository, ArtifactStore store, Publication publication,
                      StoreRepositoryInventory inventory) {

            boolean servesPom(String group, String artifact, String version) throws IOException {
                return publication.located(path(group, artifact, version)).isPresent();
            }

            /** Whether this repository holds the version as a cached copy rather than a release. */
            boolean cached(String ga, String version) {
                try {
                    return inventory.publishedAt("Maven", ga, version).isEmpty();
                } catch (IOException unreadable) {
                    return false;
                }
            }
        }

        private final List<Member> members;
        private final AtomicInteger read = new AtomicInteger();

        Held(ClosureWalk walk) {
            List<Member> members = new ArrayList<>();
            for (ClosureWalk.Member member : walk.members()) {
                members.add(new Member(members.isEmpty() ? "" : member.repository(), member.store(),
                        new Publication(member.store()), new StoreRepositoryInventory(member.store())));
            }
            this.members = List.copyOf(members);
        }

        boolean single() {
            return members.size() == 1;
        }

        boolean servesOwnPom(String group, String artifact, String version) throws IOException {
            return members.getFirst().servesPom(group, artifact, version);
        }

        /** The first repository of the walk serving {@code artifact}'s POM - the one the resolver read it from. */
        Optional<Member> holder(Artifact artifact) {
            for (Member member : members) {
                try {
                    if (member.servesPom(artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion())) {
                        return Optional.of(member);
                    }
                } catch (IOException unreadable) {
                    // a repository that cannot be read holds nothing the closure can name
                }
            }
            return Optional.empty();
        }

        boolean exhausted() {
            return read.get() > MAX_DOCUMENTS;
        }

        boolean heldForReview(Artifact artifact) {
            for (Member member : members) {
                try {
                    if (Publication.reviewPending(member.store(),
                            path(artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion()))) {
                        return true;
                    }
                } catch (IOException unreadable) {
                    // an unreadable marker reads as no hold; the cut then says the POM is not held
                }
            }
            return false;
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
                    String pom = path(artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion());
                    for (Member member : members) {
                        try {
                            Optional<Publication.Located> located = member.publication().locate(pom);
                            if (located.isEmpty()) {
                                continue;
                            }
                            if (located.get().size() > LARGEST_POM) {
                                return null;
                            }
                            Path file = folder.resolve(artifact.getGroupId() + "/" + artifact.getArtifactId() + "/"
                                    + artifact.getVersion() + ".pom");
                            Files.createDirectories(file.getParent());
                            try (InputStream in = member.store().open(located.get().key())) {
                                Files.write(file, in.readNBytes(LARGEST_POM));
                            }
                            return file.toFile();
                        } catch (IOException unreadable) {
                            return null;
                        }
                    }
                    return null;
                }

                /** The versions the repositories of the walk hold and serve: what a range is resolved against. */
                @Override
                public List<String> findVersions(Artifact artifact) {
                    String ga = artifact.getGroupId() + ":" + artifact.getArtifactId();
                    Set<String> versions = new LinkedHashSet<>();
                    int examined = 0;
                    for (Member member : members) {
                        try {
                            String after = null;
                            do {
                                StoreRepositoryInventory.HoldingPage page = member.inventory().holdings("Maven", ga,
                                        after, 200);
                                for (StoreRepositoryInventory.Holding holding : page.holdings()) {
                                    if (member.inventory().disclosable("Maven", ga, holding.version(),
                                            ServableNames.Policy.HIDE_WITHHELD)) {
                                        versions.add(holding.version());
                                    }
                                }
                                examined += page.holdings().size();
                                after = page.next();
                            } while (after != null && examined < MAX_COMPONENTS);
                        } catch (IOException unreadable) {
                            // a repository that cannot be read offers no version
                        }
                    }
                    return List.copyOf(versions);
                }
            };
        }

        private static String path(String group, String artifact, String version) {
            return "/maven/" + group.replace('.', '/') + "/" + artifact + "/" + version + "/" + artifact + "-"
                    + version + ".pom";
        }
    }
}
