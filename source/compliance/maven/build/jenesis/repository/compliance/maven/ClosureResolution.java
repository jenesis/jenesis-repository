package build.jenesis.repository.compliance.maven;

import module java.base;
import module java.net.http;
import module org.slf4j;
import build.jenesis.repository.compliance.GateDimension;
import build.jenesis.repository.compliance.QualityInspector;
import build.jenesis.repository.compliance.Verdict;
import build.jenesis.repository.dependency.DependencyComponent;
import build.jenesis.repository.dependency.DependencyEdge;
import build.jenesis.repository.dependency.DependencyGraph;
import build.jenesis.repository.dependency.DependencyLicense;
import build.jenesis.repository.net.http.ScreenedHttpClient;
import build.jenesis.repository.store.Durations;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.xml.Xml;
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
import org.eclipse.aether.resolution.ArtifactDescriptorResult;
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
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * A published POM's dependency closure, resolved with Maven's own rules - parents, imported BOMs, properties, version
 * mediation, exclusions - through Apache Maven Resolver, within a bound, or not at all.
 *
 * <p><b>Each POM is read first from the repository the artifact is being published into</b> - served or held for
 * review - so a dependency published here, an internal one no public repository holds, resolves with what it depends
 * on. A publish then reads the upstreams the deployment proxies for Maven, and {@value #REPOSITORY} when it names one,
 * into a local repository of the walk's own that is deleted after it; no Maven settings, local repository or property
 * redirects it. A proxy's fill walks only when {@value #REPOSITORY} names a repository, since a walk per document a
 * build pulls through a proxy would multiply what the proxy fetches. A repository a POM declares is read only when it is
 * one of those places or {@value #ALLOWED} lists it, so a publisher cannot make the server call a host of their choosing.
 *
 * <p><b>The walk is bounded</b>: at most {@value #DOCUMENTS} documents read ({@value #DOCUMENTS_DEFAULT} unless set),
 * from any of them, none once {@value #TIMEOUT} has passed ({@value #TIMEOUT_DEFAULT} unless set), each connection and
 * read timing out after the same, so a publish holds its thread for about twice the timeout at most. A walk that
 * reaches a bound or fails yields nothing, never part of a closure. A remote document is read through
 * {@link ScreenedHttpClient}, as every outbound call is.
 *
 * <p><b>An unresolved closure is said, and decided on.</b> A walk with nowhere but this repository to read from screens
 * a dependency from outside on its coordinate and counts the publish on {@value #UNRESOLVED}. A walk that fails, reaches
 * a bound, or finds a dependency's POM in none of the places it reads is logged with the artifact and reason, counted on
 * {@value #INCOMPLETE}, and given {@value #ON_INCOMPLETE} - held for review unless set otherwise - on what it did read.
 */
final class ClosureResolution {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClosureResolution.class);

    /** The Maven repository a closure is resolved through after this one and the upstreams the deployment proxies. */
    static final String REPOSITORY = "maven-closure-repository";

    /** The most POM and metadata documents one closure reads. */
    static final String DOCUMENTS = "maven-closure-documents";

    /** The repositories a POM may declare that a closure reads from, besides the upstreams the deployment proxies and
     *  {@value #REPOSITORY}: URL prefixes, separated by commas or spaces. Empty, the default, reads none a POM names. */
    static final String ALLOWED = "maven-closure-allowed";

    /** What a publish whose closure could not be resolved in full, from the places it had to read, is given. */
    static final String ON_INCOMPLETE = "maven-closure-incomplete";

    static final String ON_INCOMPLETE_DEFAULT = "QUARANTINE";

    static final String DOCUMENTS_DEFAULT = "256";

    /** How long one closure may take new documents for, and how long each connection and read may take. */
    static final String TIMEOUT = "maven-closure-timeout";

    static final String TIMEOUT_DEFAULT = "PT30S";

    /** What a POM's own publish could not screen because its closure left this repository with nothing named to
     *  read from. */
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

    /** POM publishes whose closure left this repository with nothing named, since this node started. */
    static long unresolved() {
        return UNRESOLVED_COUNT.get();
    }

    /** POM publishes screened without all of their closure since this node started. */
    static long incomplete() {
        return INCOMPLETE_COUNT.get();
    }

    /**
     * What a closure walk found: the graph it resolved, rooted at the published POM - empty when it resolved none - and,
     * when it fell short of the whole closure where it had places to read from, why and what the deployment does about
     * it ({@value #ON_INCOMPLETE}).
     */
    record Resolved(Optional<DependencyGraph> graph, Optional<String> incomplete, Verdict verdict) {

        static final Resolved NOTHING = new Resolved(Optional.empty(), Optional.empty(), Verdict.ALLOW);
    }

    /**
     * The closure of the POM published at {@code path} as {@code group:artifact:version} {@code coordinate}, rooted at
     * it, each dependency with the licences its own POM declares. A walk on a publish reads this repository, then the
     * upstreams the deployment proxies for Maven, then {@value #REPOSITORY}; a walk elsewhere - a proxy's fill - only
     * when that names a repository. A repository a POM declares is read only when {@value #ALLOWED} lists it or it is
     * one of those places.
     */
    static Resolved graph(String path, byte[] pom, String[] coordinate, QualityInspector.Lookup lookup) {
        UnaryOperator<String> config = lookup.settings();
        String named = config.apply(REPOSITORY);
        boolean naming = named != null && !named.isBlank();
        Optional<List<URI>> proxied = lookup.resolvesFrom("maven");
        if (proxied.isEmpty() && !naming) {
            return Resolved.NOTHING;
        }
        if (!declares(pom)) {
            return Resolved.NOTHING;
        }
        List<URI> sources = new ArrayList<>(proxied.orElse(List.of()));
        if (naming) {
            sources.add(URI.create(named.strip()));
        }
        Verdict verdict = GateDimension.verdict(ON_INCOMPLETE, config.apply(ON_INCOMPLETE),
                Verdict.valueOf(ON_INCOMPLETE_DEFAULT));
        String from = sources.isEmpty() ? "this repository alone" : "this repository and " + sources;
        Path local = null;
        try {
            Duration timeout = Durations.parse(setting(config, TIMEOUT, TIMEOUT_DEFAULT));
            Budget budget = new Budget(Integer.parseInt(setting(config, DOCUMENTS, DOCUMENTS_DEFAULT)),
                    Instant.now().plus(timeout));
            local = Files.createTempDirectory("jenesis-closure");
            Artifact root = new DefaultArtifact(coordinate[0], coordinate[1], "pom", coordinate[2]);
            Set<String> missing = ConcurrentHashMap.newKeySet();
            List<String> allowed = new ArrayList<>(sources.stream().map(URI::toString).toList());
            allowed.addAll(Arrays.asList(setting(config, ALLOWED, "").split("[,\\s]+")));
            RepositorySystemSupplier supplier = new Supplier(budget, timeout, allowed);
            RepositorySystem system = supplier.get();
            try (RepositorySystemSession.CloseableSession session = new SessionBuilderSupplier(system).get()
                    .withLocalRepositoryBaseDirectories(local.resolve("repository"))
                    .setWorkspaceReader(new Published(root, pom, lookup, budget, local.resolve("published")))
                    .setArtifactDescriptorPolicy(new SimpleArtifactDescriptorPolicy(
                            ArtifactDescriptorPolicy.IGNORE_MISSING))
                    // A POM read for a closure is evidence about what an artifact pulls in, never bytes served, so
                    // no checksum is fetched beside it - which a repository a POM declares would otherwise demand.
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
                List<RemoteRepository> repositories = new ArrayList<>();
                for (URI source : sources) {
                    repositories.add(new RemoteRepository.Builder("closure-" + repositories.size(), "default",
                            source.toString())
                            .setPolicy(new RepositoryPolicy(true, RepositoryPolicy.UPDATE_POLICY_NEVER,
                                    RepositoryPolicy.CHECKSUM_POLICY_WARN))
                            .build());
                }
                // Collected from the dependencies the published artifact ships - not its test, provided or optional
                // ones, which no consumer pulls in - so a walk never fetches a POM only a build of it would need.
                ArtifactDescriptorResult descriptor = system.readArtifactDescriptor(session,
                        new ArtifactDescriptorRequest(root, repositories, null));
                CollectRequest request = new CollectRequest();
                request.setRootArtifact(root);
                request.setDependencies(descriptor.getDependencies().stream()
                        .filter(dependency -> SHIPPED.contains(dependency.getScope()) && !dependency.isOptional())
                        .toList());
                request.setManagedDependencies(descriptor.getManagedDependencies());
                List<RemoteRepository> declared = new ArrayList<>(repositories);
                declared.addAll(descriptor.getRepositories());
                request.setRepositories(declared);
                DependencyNode resolved = system.collectDependencies(session, request).getRoot();
                budget.failure().ifPresent(failure -> {
                    throw failure;
                });
                Optional<DependencyGraph> graph = Optional.of(graph(system, session, resolved));
                if (missing.isEmpty()) {
                    return new Resolved(graph, Optional.empty(), verdict);
                }
                if (sources.isEmpty()) {
                    // A deployment that proxies and names nothing reads this repository alone: a dependency from
                    // outside it is screened on its coordinate, counted rather than logged on every such publish.
                    UNRESOLVED_COUNT.incrementAndGet();
                    return new Resolved(graph, Optional.empty(), verdict);
                }
                INCOMPLETE_COUNT.incrementAndGet();
                String reason = "Neither " + from + " holds a POM for " + new TreeSet<>(missing) + ", so the "
                        + "dependency closure of " + path + " is screened without what those depend on";
                LOGGER.warn(reason);
                return new Resolved(graph, Optional.of(reason), verdict);
            } finally {
                system.shutdown();
            }
        } catch (Exception failed) {
            INCOMPLETE_COUNT.incrementAndGet();
            String reason = "Could not resolve the dependency closure of " + path + " through " + from + ": "
                    + reason(failed) + ". The compliance gate screened the artifact and what it declares, not its "
                    + "transitive dependencies";
            LOGGER.warn(reason);
            return new Resolved(Optional.empty(), Optional.of(reason), verdict);
        } finally {
            if (local != null) {
                delete(local);
            }
        }
    }

    /** Whether {@code pom} declares anything a closure is made of - a dependency, or a parent that may declare some -
     *  so a POM declaring neither, most of them, costs no walk; one that does not parse is left to the walk to say. */
    private static boolean declares(byte[] pom) {
        try {
            Document document = Xml.parse(pom);
            return document.getElementsByTagName("dependency").getLength() > 0
                    || document.getElementsByTagName("parent").getLength() > 0;
        } catch (IOException | SAXException unparsed) {
            return true;
        }
    }

    private static String setting(UnaryOperator<String> config, String key, String fallback) {
        String value = config.apply(key);
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    /** The collected closure as the dependency graph a published CycloneDX document would declare: a component per
     *  dependency that ships, an edge per dependency, and the licences each one's POM declares. */
    private static DependencyGraph graph(RepositorySystem system, RepositorySystemSession session,
                                         DependencyNode root) throws Exception {
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
                    // Read where the walk found it - a repository a POM declared among them - from the local
                    // repository the walk filled, so nothing is fetched twice.
                    components.put(ref, component(child.getArtifact(), licences(system.readArtifactDescriptor(
                            session, new ArtifactDescriptorRequest(child.getArtifact(), child.getRepositories(), null))
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

    /** The published POM, and every POM the repository being published into holds - served or held for review - as
     *  the resolver's workspace, read there before the named repository is asked. */
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
                    String path = "/maven/" + artifact.getGroupId().replace('.', '/') + "/" + artifact.getArtifactId()
                            + "/" + artifact.getVersion() + "/" + artifact.getArtifactId() + "-" + artifact.getVersion()
                            + ".pom";
                    Optional<QualityInspector.Lookup.Bounded> read = lookup.fetchBounded(path, LARGEST_POM);
                    if (read.isEmpty()) {
                        // A dependency this repository holds for review still declares what it declares, and a
                        // dependent pulls that in once it is released: its held POM is read, so the dependent is
                        // screened on everything it would bring, the reason the dependency is held among it.
                        read = lookup.fetchStored(Publication.QUARANTINE_PATH + path, LARGEST_POM);
                    }
                    found = read.filter(bounded -> !bounded.truncated()).map(QualityInspector.Lookup.Bounded::content);
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

        /** The versions this repository lists for {@code artifact} in its {@code maven-metadata.xml}, and the
         *  published one's own: what a version range on an internal dependency is resolved against. */
        @Override
        public List<String> findVersions(Artifact artifact) {
            Set<String> versions = new LinkedHashSet<>();
            if (same(artifact, root)) {
                versions.add(root.getVersion());
            }
            try {
                budget.admit(artifact.getGroupId() + ":" + artifact.getArtifactId() + ":maven-metadata.xml");
                Optional<QualityInspector.Lookup.Bounded> metadata = lookup.fetchBounded("/maven/"
                        + artifact.getGroupId().replace('.', '/') + "/" + artifact.getArtifactId()
                        + "/maven-metadata.xml", LARGEST_POM);
                if (metadata.isPresent() && !metadata.get().truncated()) {
                    NodeList listed = Xml.parse(metadata.get().content()).getElementsByTagName("version");
                    for (int index = 0; index < listed.getLength(); index++) {
                        if ("versions".equals(listed.item(index).getParentNode().getNodeName())) {
                            versions.add(listed.item(index).getTextContent().strip());
                        }
                    }
                }
            } catch (IOException | SAXException unreadable) {
                // Listed nowhere readable here: the range is resolved against what the other places list.
            }
            return List.copyOf(versions);
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
        private final List<String> allowed;

        Supplier(Budget budget, Duration timeout, List<String> allowed) {
            this.budget = budget;
            this.timeout = timeout;
            this.allowed = allowed.stream().filter(entry -> !entry.isBlank()).map(Supplier::canonical).toList();
        }

        /** A repository URL as its scheme, host, port and path, ending in a slash, so a prefix compares one way
         *  however the URL was spelled - {@code file:/x} and {@code file:///x} are one repository. */
        static String canonical(String url) {
            URI uri = URI.create(url.strip()).normalize();
            String path = uri.getPath() == null ? "" : uri.getPath();
            return String.valueOf(uri.getScheme()).toLowerCase(Locale.ROOT) + "://"
                    + (uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT))
                    + (uri.getPort() < 0 ? "" : ":" + uri.getPort()) + (path.endsWith("/") ? path : path + "/");
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
                    // A repository a POM declares - Maven Central among them, which every POM inherits - is a host
                    // its publisher chose: read only where the deployment already reads, or where an operator allowed
                    // it, so a publish never makes the server call a host of a stranger's choosing. Any other holds
                    // nothing, asked of no one.
                    if (allowed.stream().noneMatch(canonical(base.toString())::startsWith)) {
                        return new Refused();
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

    /** A repository a closure does not read: every document is absent, and nothing is asked of its host. */
    private static final class Refused extends AbstractTransporter {

        @Override
        public int classify(Throwable error) {
            return ERROR_NOT_FOUND;
        }

        @Override
        protected void implPeek(PeekTask task) throws Exception {
            throw new NotFound(task.getLocation());
        }

        @Override
        protected void implGet(GetTask task) throws Exception {
            throw new NotFound(task.getLocation());
        }

        @Override
        protected void implPut(PutTask task) {
            throw new UnsupportedOperationException("a closure walk writes nothing");
        }

        @Override
        protected void implClose() {
        }
    }

    /** A document the repository answered it does not hold. */
    private static final class NotFound extends IOException {

        NotFound(URI target) {
            super(target + " is not there");
        }
    }
}
