package build.jenesis.repository.compliance.maven;

import module java.base;
import module java.net.http;
import module org.slf4j;
import build.jenesis.repository.compliance.QualityInspector;
import build.jenesis.repository.dependency.DependencyComponent;
import build.jenesis.repository.dependency.DependencyEdge;
import build.jenesis.repository.dependency.DependencyGraph;
import build.jenesis.repository.dependency.DependencyLicense;
import build.jenesis.repository.net.http.ScreenedHttpClient;
import build.jenesis.repository.store.Durations;
import org.eclipse.aether.AbstractRepositoryListener;
import org.eclipse.aether.RepositoryEvent;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.RepositoryPolicy;
import org.eclipse.aether.repository.WorkspaceReader;
import org.eclipse.aether.repository.WorkspaceRepository;
import org.eclipse.aether.resolution.ArtifactDescriptorPolicy;
import org.eclipse.aether.resolution.ArtifactDescriptorRequest;
import org.eclipse.aether.spi.connector.transport.AbstractTransporter;
import org.eclipse.aether.spi.connector.transport.GetTask;
import org.eclipse.aether.spi.connector.transport.PeekTask;
import org.eclipse.aether.spi.connector.transport.PutTask;
import org.eclipse.aether.spi.connector.transport.Transporter;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.supplier.RepositorySystemSupplier;
import org.eclipse.aether.supplier.SessionBuilderSupplier;
import org.eclipse.aether.transfer.NoTransporterException;
import org.eclipse.aether.util.repository.SimpleArtifactDescriptorPolicy;

/**
 * A published POM's dependency closure, resolved with Maven's own rules - parents, imported BOMs, properties, version
 * mediation, exclusions - through Apache Maven Resolver, within a bound, or not at all.
 *
 * <p><b>Nothing is resolved until {@value #REPOSITORY} names a repository.</b> Empty, the default, a POM without a
 * CycloneDX document beside it is screened on itself and its declarations, not its transitive dependencies. Named, each
 * POM is read first from the repository the artifact is being published into - so a dependency published here, an
 * internal one no public repository holds, resolves - and then from the named repository alone, into a local
 * repository of the walk's own that is deleted after it; no Maven settings, local repository or property redirects it.
 *
 * <p><b>The walk is bounded</b>: at most {@value #DOCUMENTS} documents read ({@value #DOCUMENTS_DEFAULT} unless set),
 * from either place, none once {@value #TIMEOUT} has passed ({@value #TIMEOUT_DEFAULT} unless set), each connection and
 * read timing out after the same, so a publish holds its thread for about twice the timeout at most. A walk that
 * reaches a bound or fails yields nothing, never part of a closure. A remote document is read through
 * {@link ScreenedHttpClient}, as every outbound call is.
 *
 * <p><b>An unresolved closure is said.</b> A POM screened without one because nothing is named is counted on
 * {@value #UNRESOLVED}. A failed or bounded walk, or one completing without some dependency's POM - which Maven takes as
 * a dependency declaring nothing - is logged with the artifact and reason and counted on {@value #INCOMPLETE}. Either
 * way the publish proceeds on what was screened: the network is evidence here, never a reason to refuse.
 */
final class ClosureResolution {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClosureResolution.class);

    /** The Maven repository a closure is resolved through; empty, the default, resolves none. */
    static final String REPOSITORY = "maven-closure-repository";

    /** The most POM and metadata documents one closure reads. */
    static final String DOCUMENTS = "maven-closure-documents";

    static final String DOCUMENTS_DEFAULT = "256";

    /** How long one closure may take new documents for, and how long each connection and read may take. */
    static final String TIMEOUT = "maven-closure-timeout";

    static final String TIMEOUT_DEFAULT = "PT30S";

    /** What a POM's own publish could not screen because no repository is named to resolve its closure through. */
    static final String UNRESOLVED = "jenrepo.compliance.closure.unresolved";

    /** What a POM's publish could not screen because its closure's walk failed, reached a bound or missed a
     *  dependency's POM. */
    static final String INCOMPLETE = "jenrepo.compliance.closure.incomplete";

    /** The largest POM read from the repository being published into. */
    private static final int LARGEST_POM = 1 << 20;

    /** The scopes whose dependencies ship with the artifact, and so are screened. */
    private static final Set<String> SHIPPED = Set.of("compile", "runtime");

    private static final AtomicLong UNRESOLVED_COUNT = new AtomicLong();

    private static final AtomicLong INCOMPLETE_COUNT = new AtomicLong();

    private ClosureResolution() {
    }

    /** POM publishes screened without their closure because no repository was named, since this node started. */
    static long unresolved() {
        return UNRESOLVED_COUNT.get();
    }

    /** POM publishes screened without all of their closure since this node started. */
    static long incomplete() {
        return INCOMPLETE_COUNT.get();
    }

    /**
     * The closure of the POM published at {@code path} as {@code group:artifact:version} {@code coordinate}, rooted at
     * it, each dependency with the licences its own POM declares; empty when nothing is named or the walk does not
     * complete.
     */
    static Optional<DependencyGraph> graph(String path, byte[] pom, String[] coordinate,
                                           QualityInspector.Lookup lookup) {
        UnaryOperator<String> config = lookup.settings();
        String named = config.apply(REPOSITORY);
        if (named == null || named.isBlank()) {
            UNRESOLVED_COUNT.incrementAndGet();
            return Optional.empty();
        }
        Path local = null;
        try {
            Duration timeout = Durations.parse(setting(config, TIMEOUT, TIMEOUT_DEFAULT));
            Budget budget = new Budget(Integer.parseInt(setting(config, DOCUMENTS, DOCUMENTS_DEFAULT)),
                    Instant.now().plus(timeout));
            local = Files.createTempDirectory("jenesis-closure");
            Artifact root = new DefaultArtifact(coordinate[0], coordinate[1], "pom", coordinate[2]);
            Set<String> missing = ConcurrentHashMap.newKeySet();
            RepositorySystemSupplier supplier = new Supplier(budget, timeout);
            RepositorySystem system = supplier.get();
            try (RepositorySystemSession.CloseableSession session = new SessionBuilderSupplier(system).get()
                    .withLocalRepositoryBaseDirectories(local.resolve("repository"))
                    .setWorkspaceReader(new Published(root, pom, lookup, budget, local.resolve("published")))
                    .setArtifactDescriptorPolicy(new SimpleArtifactDescriptorPolicy(
                            ArtifactDescriptorPolicy.IGNORE_MISSING))
                    .setRepositoryListener(new AbstractRepositoryListener() {
                        @Override
                        public void artifactDescriptorMissing(RepositoryEvent event) {
                            Artifact artifact = event.getArtifact();
                            missing.add(artifact.getGroupId() + ":" + artifact.getArtifactId() + ":"
                                    + artifact.getVersion());
                        }
                    })
                    .build()) {
                List<RemoteRepository> repositories = List.of(new RemoteRepository.Builder("closure", "default",
                        named.strip())
                        .setPolicy(new RepositoryPolicy(true, RepositoryPolicy.UPDATE_POLICY_NEVER,
                                RepositoryPolicy.CHECKSUM_POLICY_WARN))
                        .build());
                DependencyNode resolved = system.collectDependencies(session,
                        new CollectRequest(new Dependency(root, "compile"), repositories)).getRoot();
                budget.failure().ifPresent(failure -> {
                    throw failure;
                });
                if (!missing.isEmpty()) {
                    INCOMPLETE_COUNT.incrementAndGet();
                    LOGGER.warn("Neither this repository nor {} holds a POM for {}, so the dependency closure of {} is "
                            + "screened without what those depend on", named.strip(), new TreeSet<>(missing), path);
                }
                return Optional.of(graph(system, session, repositories, resolved));
            } finally {
                system.shutdown();
            }
        } catch (Exception failed) {
            INCOMPLETE_COUNT.incrementAndGet();
            LOGGER.warn("Could not resolve the dependency closure of {} through {}: {}. The compliance gate screened "
                    + "the artifact and what it declares, not its transitive dependencies", path, named.strip(),
                    reason(failed));
            return Optional.empty();
        } finally {
            if (local != null) {
                delete(local);
            }
        }
    }

    private static String setting(UnaryOperator<String> config, String key, String fallback) {
        String value = config.apply(key);
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    /** The collected closure as the dependency graph a published CycloneDX document would declare: a component per
     *  dependency that ships, an edge per dependency, and the licences each one's POM declares. */
    private static DependencyGraph graph(RepositorySystem system, RepositorySystemSession session,
                                         List<RemoteRepository> repositories, DependencyNode root) throws Exception {
        String rootRef = ref(root.getArtifact());
        Map<String, DependencyComponent> components = new LinkedHashMap<>();
        components.put(rootRef, component(root.getArtifact(), List.of()));
        List<DependencyEdge> edges = new ArrayList<>();
        Deque<DependencyNode> pending = new ArrayDeque<>(List.of(root));
        while (!pending.isEmpty()) {
            DependencyNode node = pending.poll();
            for (DependencyNode child : node.getChildren()) {
                Dependency dependency = child.getDependency();
                if (dependency == null || !SHIPPED.contains(dependency.getScope())) {
                    continue;
                }
                String ref = ref(child.getArtifact());
                edges.add(new DependencyEdge(ref(node.getArtifact()), ref));
                if (!components.containsKey(ref)) {
                    components.put(ref, component(child.getArtifact(), licences(system.readArtifactDescriptor(
                            session, new ArtifactDescriptorRequest(child.getArtifact(), repositories, null))
                            .getProperties())));
                    pending.add(child);
                }
            }
        }
        return new DependencyGraph(rootRef, List.copyOf(components.values()), edges);
    }

    private static String ref(Artifact artifact) {
        return artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + artifact.getVersion();
    }

    private static DependencyComponent component(Artifact artifact, List<DependencyLicense> licences) {
        return new DependencyComponent(ref(artifact), artifact.getGroupId(), artifact.getArtifactId(),
                artifact.getVersion(), null, null, licences);
    }

    /** The licences a POM declares, as Maven's descriptor reader lists them: {@code license.<n>.name} and
     *  {@code .url}. */
    private static List<DependencyLicense> licences(Map<String, Object> properties) {
        int count = properties.get("license.count") instanceof Number number ? number.intValue() : 0;
        List<DependencyLicense> licences = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            Object name = properties.get("license." + index + ".name");
            Object url = properties.get("license." + index + ".url");
            if (name != null || url != null) {
                licences.add(new DependencyLicense(null, name == null ? null : name.toString(),
                        url == null ? null : url.toString()));
            }
        }
        return licences;
    }

    /** The innermost message of a failure, where the resolver says what it could not read. */
    private static String reason(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    private static void delete(Path folder) {
        try (Stream<Path> paths = Files.walk(folder)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // a file the walk left behind in a temporary folder holds nothing of anyone's
                }
            });
        } catch (IOException ignored) {
            // the folder is already gone
        }
    }

    /** How much one closure may read: a count of documents and a deadline, and the first refusal, which ends the walk
     *  however the resolver reports the read it refused. */
    private static final class Budget {

        private final int documents;
        private final Instant deadline;
        private final AtomicInteger remaining;
        private final AtomicReference<IllegalStateException> refused = new AtomicReference<>();

        Budget(int documents, Instant deadline) {
            this.documents = documents;
            this.deadline = deadline;
            this.remaining = new AtomicInteger(documents);
        }

        /** Admit one more read of {@code document}, or refuse it. */
        void admit(String document) {
            IllegalStateException refusal = remaining.getAndDecrement() <= 0
                    ? new IllegalStateException("the closure reads more than " + documents + " documents (" + DOCUMENTS
                            + "), reaching " + document)
                    : Instant.now().isAfter(deadline)
                    ? new IllegalStateException("the closure took longer than " + TIMEOUT + " allows, reaching "
                            + document)
                    : null;
            if (refusal != null) {
                refused.compareAndSet(null, refusal);
                throw refusal;
            }
        }

        Optional<IllegalStateException> failure() {
            return Optional.ofNullable(refused.get());
        }
    }

    /** The published POM, and every POM the repository being published into holds, as the resolver's workspace - read
     *  there before the named repository is asked. */
    private static final class Published implements WorkspaceReader {

        private final WorkspaceRepository repository = new WorkspaceRepository("published");
        private final Artifact root;
        private final byte[] pom;
        private final QualityInspector.Lookup lookup;
        private final Budget budget;
        private final Path folder;

        Published(Artifact root, byte[] pom, QualityInspector.Lookup lookup, Budget budget, Path folder) {
            this.root = root;
            this.pom = pom;
            this.lookup = lookup;
            this.budget = budget;
            this.folder = folder;
        }

        @Override
        public WorkspaceRepository getRepository() {
            return repository;
        }

        @Override
        public File findArtifact(Artifact artifact) {
            if (!"pom".equals(artifact.getExtension()) || !artifact.getClassifier().isEmpty()) {
                return null;
            }
            try {
                Optional<byte[]> found;
                if (same(artifact, root)) {
                    found = Optional.of(pom);
                } else {
                    budget.admit(ref(artifact));
                    found = lookup.fetchBounded("/maven/" + artifact.getGroupId().replace('.', '/') + "/"
                            + artifact.getArtifactId() + "/" + artifact.getVersion() + "/" + artifact.getArtifactId()
                            + "-" + artifact.getVersion() + ".pom", LARGEST_POM)
                            .filter(bounded -> !bounded.truncated()).map(QualityInspector.Lookup.Bounded::content);
                }
                if (found.isEmpty()) {
                    return null;
                }
                Path file = folder.resolve(ref(artifact).replace(':', '/') + ".pom");
                Files.createDirectories(file.getParent());
                Files.write(file, found.get());
                return file.toFile();
            } catch (IOException unreadable) {
                return null;                // the named repository is asked instead
            }
        }

        @Override
        public List<String> findVersions(Artifact artifact) {
            return artifact.getGroupId().equals(root.getGroupId()) && artifact.getArtifactId().equals(root.getArtifactId())
                    ? List.of(root.getVersion()) : List.of();
        }

        private static boolean same(Artifact one, Artifact other) {
            return one.getGroupId().equals(other.getGroupId()) && one.getArtifactId().equals(other.getArtifactId())
                    && one.getVersion().equals(other.getVersion());
        }
    }

    /** The resolver's components, reading a remote repository through {@link Transport} alone. */
    private static final class Supplier extends RepositorySystemSupplier {

        private final Budget budget;
        private final Duration timeout;

        Supplier(Budget budget, Duration timeout) {
            this.budget = budget;
            this.timeout = timeout;
        }

        @Override
        protected Map<String, TransporterFactory> createTransporterFactories() {
            return Map.of("screened", new TransporterFactory() {
                @Override
                public Transporter newInstance(RepositorySystemSession session, RemoteRepository repository)
                        throws NoTransporterException {
                    URI base = URI.create(repository.getUrl().endsWith("/") ? repository.getUrl()
                            : repository.getUrl() + "/");
                    if (!Set.of("http", "https", "file").contains(String.valueOf(base.getScheme()))) {
                        throw new NoTransporterException(repository);
                    }
                    return new Transport(base, budget, timeout);
                }

                @Override
                public float getPriority() {
                    return 10;
                }
            });
        }
    }

    /** A remote repository read for one closure: every document admitted by the budget, an HTTP one through
     *  {@link ScreenedHttpClient} with the walk's timeout, a {@code file:} one from the disk. Nothing is written. */
    private static final class Transport extends AbstractTransporter {

        private final URI base;
        private final Budget budget;
        private final Duration timeout;
        private final HttpClient client;

        Transport(URI base, Budget budget, Duration timeout) {
            this.base = base;
            this.budget = budget;
            this.timeout = timeout;
            this.client = "file".equals(base.getScheme()) ? null
                    : ScreenedHttpClient.newBuilder().connectTimeout(timeout)
                            .followRedirects(HttpClient.Redirect.NORMAL).build();
        }

        @Override
        public int classify(Throwable error) {
            return error instanceof NotFound ? ERROR_NOT_FOUND : ERROR_OTHER;
        }

        @Override
        protected void implPeek(PeekTask task) throws Exception {
            URI target = base.resolve(task.getLocation());
            if (client == null) {
                if (!Files.isRegularFile(Path.of(target))) {
                    throw new NotFound(target);
                }
                return;
            }
            int status = client.send(HttpRequest.newBuilder(target).timeout(timeout)
                    .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode();
            answered(target, status);
        }

        @Override
        protected void implGet(GetTask task) throws Exception {
            URI target = base.resolve(task.getLocation());
            if (!checksum(target)) {
                budget.admit(target.toString());
            }
            if (client == null) {
                Path file = Path.of(target);
                if (!Files.isRegularFile(file)) {
                    throw new NotFound(target);
                }
                utilGet(task, Files.newInputStream(file), true, Files.size(file), false);
                return;
            }
            HttpResponse<InputStream> response = client.send(HttpRequest.newBuilder(target).timeout(timeout).GET()
                    .build(), HttpResponse.BodyHandlers.ofInputStream());
            try {
                answered(target, response.statusCode());
            } catch (Exception refused) {
                response.body().close();
                throw refused;
            }
            utilGet(task, response.body(), true, response.headers().firstValueAsLong("Content-Length").orElse(-1L),
                    false);
        }

        @Override
        protected void implPut(PutTask task) {
            throw new UnsupportedOperationException("a closure reads its repository and writes nothing");
        }

        @Override
        protected void implClose() {
        }

        private static boolean checksum(URI target) {
            String path = target.getPath();
            return path.endsWith(".sha1") || path.endsWith(".md5") || path.endsWith(".sha256")
                    || path.endsWith(".sha512") || path.endsWith(".asc");
        }

        private static void answered(URI target, int status) throws IOException {
            if (status == 404 || status == 410) {
                throw new NotFound(target);
            }
            if (status / 100 != 2) {
                throw new IOException(target + " answered " + status);
            }
        }
    }

    /** A document the repository answered it does not hold. */
    private static final class NotFound extends IOException {

        NotFound(URI target) {
            super(target + " is not there");
        }
    }
}
