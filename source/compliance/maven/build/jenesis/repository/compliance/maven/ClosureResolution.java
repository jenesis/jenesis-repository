package build.jenesis.repository.compliance.maven;

import module java.base;
import module org.slf4j;
import build.jenesis.Repository;
import build.jenesis.RepositoryItem;
import build.jenesis.maven.MavenDefaultRepository;
import build.jenesis.maven.MavenDependencyKey;
import build.jenesis.maven.MavenDependencyScope;
import build.jenesis.maven.MavenPomResolver;
import build.jenesis.maven.MavenRepository;
import build.jenesis.maven.MavenResolver;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.ManifestSubjectBuilder;
import build.jenesis.repository.store.Durations;

/**
 * A published POM's dependency closure resolved over the network: through the one Maven repository an operator named
 * for it, within a bound, or not at all.
 *
 * <p><b>Nothing is fetched until {@value #REPOSITORY} names a repository.</b> Empty, the default, a POM without a
 * CycloneDX document beside it is screened on itself and its declarations, not its transitive dependencies. Named, the
 * walk goes to that repository alone: no local Maven repository is read or written, and no {@code jenesis.maven.*}
 * property redirects it.
 *
 * <p><b>The walk is bounded</b>: at most {@value #DOCUMENTS} documents ({@value #DOCUMENTS_DEFAULT} unless set), none
 * taken once {@value #TIMEOUT} has passed ({@value #TIMEOUT_DEFAULT} unless set), each connection and read timing out
 * after the same, so a publish holds its thread for about twice the timeout at most. A walk that reaches a bound or
 * fails yields nothing, never part of a closure.
 *
 * <p><b>An unresolved closure is said.</b> A POM screened without one because nothing is named is counted on
 * {@value #UNRESOLVED}. A failed or bounded walk, or one completing without some dependency's POM, is logged with the
 * artifact and reason and counted on {@value #INCOMPLETE}. Either way the publish proceeds on what was screened: the
 * network is evidence here, never a reason to refuse.
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

    /** The scope prefix the resolver keys a closure's dependencies by. */
    static final String PREFIX = "dep";

    /** The identifier the root POM is resolved under, so the closure names it among its roots. */
    private static final String ROOT = "root";

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

    /** The dependencies of the POM published at {@code path} as gate subjects in {@code ecosystem}'s namespace, each
     *  placed on the build graph, or none when nothing is named or the walk does not complete. The root is resolved
     *  under {@link #ROOT} and skipped, since the POM's own subject stands for it. */
    static List<ComplianceGate.Subject> dependencies(String path, byte[] pom, String ecosystem,
                                                     UnaryOperator<String> config) {
        String named = config.apply(REPOSITORY);
        if (named == null || named.isBlank()) {
            UNRESOLVED_COUNT.incrementAndGet();
            return List.of();
        }
        try {
            Duration timeout = Durations.parse(setting(config, TIMEOUT, TIMEOUT_DEFAULT));
            int documents = Integer.parseInt(setting(config, DOCUMENTS, DOCUMENTS_DEFAULT));
            Walk walk = new Walk(repository(URI.create(named.strip()), timeout), documents,
                    Instant.now().plus(timeout));
            MavenResolver.Closure closure;
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                closure = new MavenPomResolver().dependencies(executor, walk,
                        List.of(new MavenResolver.RootPom(new ByteArrayInputStream(pom), null, ROOT, false, null)),
                        Map.of(), MavenDependencyScope.COMPILE, PREFIX);
            }
            if (!walk.missing.isEmpty()) {
                INCOMPLETE_COUNT.incrementAndGet();
                LOGGER.warn("{} holds no POM for {}, so the dependency closure of {} is screened without what those "
                        + "depend on", named.strip(), new TreeSet<>(walk.missing), path);
            }
            return subjects(closure, ecosystem);
        } catch (Exception failed) {
            INCOMPLETE_COUNT.incrementAndGet();
            LOGGER.warn("Could not resolve the dependency closure of {} through {}: {}. The compliance gate screened "
                    + "the artifact and what it declares, not its transitive dependencies", path, named.strip(),
                    reason(failed));
            return List.of();
        }
    }

    private static String setting(UnaryOperator<String> config, String key, String fallback) {
        String value = config.apply(key);
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    /** The named repository and nothing else: no local repository, checksums from the same place, every connection and
     *  read timing out after {@code timeout}. */
    private static MavenRepository repository(URI named, Duration timeout) {
        URI uri = named.toString().endsWith("/") ? named : URI.create(named + "/");
        SequencedMap<String, URI> validations = new LinkedHashMap<>();
        validations.put("SHA512", uri);
        validations.put("SHA256", uri);
        validations.put("SHA1", uri);
        int millis = (int) Math.min(Integer.MAX_VALUE, timeout.toMillis());
        return new MavenDefaultRepository(uri, null, Collections.unmodifiableMap(validations), null, null)
                .connection(new Repository.Connection().retries(0).connectTimeout(millis).readTimeout(millis));
    }

    /** The named repository as one closure reads it: refusing a fetch past the {@code documents}-th or after
     *  {@code deadline}, and noting each dependency whose POM it lacks. */
    private static final class Walk implements MavenRepository {

        private final MavenRepository repository;

        private final int documents;

        private final Instant deadline;

        private final AtomicInteger remaining;

        private final Set<String> missing = ConcurrentHashMap.newKeySet();

        Walk(MavenRepository repository, int documents, Instant deadline) {
            this.repository = repository;
            this.documents = documents;
            this.deadline = deadline;
            this.remaining = new AtomicInteger(documents);
        }

        @Override
        public Optional<RepositoryItem> fetch(Executor executor, String groupId, String artifactId, String version,
                                              String type, String classifier, String checksum) throws IOException {
            String document = groupId + ":" + artifactId + ":" + version;
            admit(document);
            Optional<RepositoryItem> fetched =
                    repository.fetch(executor, groupId, artifactId, version, type, classifier, checksum);
            if (fetched.isEmpty() && "pom".equals(type) && checksum == null) {
                missing.add(document);
            }
            return fetched;
        }

        @Override
        public Optional<RepositoryItem> fetchMetadata(Executor executor, String groupId, String artifactId,
                                                      String checksum) throws IOException {
            admit(groupId + ":" + artifactId);
            return repository.fetchMetadata(executor, groupId, artifactId, checksum);
        }

        private void admit(String document) throws IOException {
            if (remaining.getAndDecrement() <= 0) {
                throw new IOException("the closure reads more than " + documents + " documents (" + DOCUMENTS
                        + "), reaching " + document);
            }
            if (Instant.now().isAfter(deadline)) {
                throw new IOException("the closure took longer than " + TIMEOUT + " allows, reaching " + document);
            }
        }
    }

    private static List<ComplianceGate.Subject> subjects(MavenResolver.Closure closure, String ecosystem) {
        Map<MavenDependencyKey, ComplianceGate.Reachability> reachability = BuildGraphReachability.of(closure, PREFIX);
        Set<MavenDependencyKey> roots = new HashSet<>(closure.roots().values());
        List<ComplianceGate.Subject> subjects = new ArrayList<>();
        closure.dependencies().forEach((key, value) -> {
            if (roots.contains(key)) {
                return;
            }
            ManifestSubjectBuilder declared = ManifestSubjectBuilder.of(ecosystem);
            for (var license : closure.licenses().getOrDefault(key.coordinate(PREFIX, value.version()), List.of())) {
                declared = declared.license(license.name(), license.url());
            }
            subjects.addAll(declared.subject(key.groupId() + ":" + key.artifactId(), value.version(),
                    reachability.getOrDefault(key, ComplianceGate.Reachability.UNKNOWN)));
        });
        return subjects;
    }

    /** The innermost message of a failure, where the resolver says what it could not read. */
    private static String reason(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
