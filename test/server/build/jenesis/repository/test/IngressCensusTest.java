package build.jenesis.repository.test;

import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.importer.ImportSource;
import build.jenesis.repository.server.FormatDispatcher;
import build.jenesis.repository.server.RepositoryImport;
import build.jenesis.repository.server.ScreenedDispatch;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The <b>ingress census</b> (T-104a): an executable inventory of every place an ingress body enters the core,
 * classified, plus the proof that each <em>hosted</em> one runs the single hosted-publish operation
 * {@link Publication#commit} - one screen, pointer-last visibility, one post-commit notification. It is a census and
 * not a claim: the classification is checked against the sources in both directions (an unclassified body consumer
 * fails, a classification whose file stopped consuming a body fails), and each hosted route is then <em>driven for
 * real</em> over a store that records the order of its writes.
 *
 * <h2>Why a census and not three route tests</h2>
 * Route tests prove the routes that exist behave; they cannot notice a <em>fourth</em> route being added that screens
 * twice, links its pointer before its sidecars, or fires the observers itself. The static legs close that: they anchor
 * on the two ways an ingress body reaches this codebase ({@code FormatExchange.requestStream()} for an HTTP write,
 * {@code ImportSource.Content} for a migration walk), so a new edge is unclassified the moment it is written, and they
 * assert the screen and the after-commit notification are unreachable outside the operation.
 *
 * <h2>What the runtime legs prove per hosted route</h2>
 * <ul>
 *   <li><b>one screen</b> - the discovered {@link CountingInterceptor} assessed the body exactly once, so no format
 *       re-screens bytes the edge already screened;</li>
 *   <li><b>pointer-last visibility</b> - the last durable write the route made is the key that makes the artifact
 *       servable, so every parse result and sidecar landed before anything served;</li>
 *   <li><b>one post-commit notification</b> - the after-commit observers were notified exactly once, and the
 *       {@link CensusObserver} recorded that the route's serving key already existed at the instant it was
 *       notified.</li>
 * </ul>
 *
 * <h2>Scope and honest limitations</h2>
 * A new hosted route has to do at least one of three things, and each is caught: run the operation (its file must be
 * declared HOSTED), screen by hand (there is no legal caller), or fire the after-commit notification itself (it must
 * be justified). The ingress-body anchors are the fourth, earliest net - they catch the route while it is still just a
 * body consumer. They are a text scan over {@code source/}: the HTTP anchor
 * ({@code FormatExchange.requestStream()}) is exhaustive because it is the only way a request body is reached, while
 * the migration-walk anchor ({@code ImportSource.Content}) matches the consumer side of the walk, not the
 * {@code ImportSource} implementations that merely <em>produce</em> asset bodies and publish nothing. A second walk
 * consumer that avoided naming the type would slip past this leg alone - and would then be caught by the operation,
 * screen or notification legs the moment it tried to publish anything.
 */
public class IngressCensusTest {

    /** How an ingress body reaches durable state - the classification an inventory entry carries. */
    private enum Kind {
        /** A hosted publish: it must run {@link Publication#commit}. */
        HOSTED,
        /** A layout writer reached only through a hosted route's accepted-layout callback, over an already-screened,
         *  already-stored body restreamed from the CAS. It never sees an unscreened ingress body. */
        LAYOUT,
        /** Plumbing that carries a body without deciding anything: an exchange implementation or a restream view. */
        TRANSPORT,
        /** A fan-out that re-dispatches each part of one request into a hosted route, adding no publish path. */
        FANOUT,
        /** A read-path cache fill of an upstream response - not a hosted publish, and observed best-effort. */
        PROXY,
        /** A multi-request upload session with no single body to screen; its screened choke point is elsewhere. */
        SESSION,
        /** Local demo/bootstrap data seeding, not a client ingress. */
        SEEDER
    }

    /** One inventory entry: the source file, what kind of ingress it is, and why. */
    private record Route(String file, Kind kind, String justification) {
    }

    /**
     * The executable ingress inventory. Every source file that consumes an ingress body, plus every file that runs the
     * hosted-publish operation, appears here exactly once with its classification. Adding an edge without classifying
     * it fails {@link #every_ingress_body_consumer_is_classified()}; leaving an entry whose file no longer consumes a
     * body or no longer commits fails {@link #the_inventory_has_no_stale_entry()}.
     */
    private static final List<Route> INVENTORY = List.of(
            new Route("ScreenedDispatch.java", Kind.HOSTED,
                    "the deploy edge: a claimed single-body PUT/POST/PATCH, committed through Publication.commit with "
                            + "RepositoryFormat.handle as the opaque accepted layout"),
            new Route("RepositoryImport.java", Kind.HOSTED,
                    "the migration walk: each described asset is committed through Publication.commit with "
                            + "RepositoryImporter.importArtifact as the opaque accepted layout"),
            new Route("OciManifests.java", Kind.HOSTED,
                    "the OCI manifest choke point: a /v2/ push is multi-request so it has no single-body edge to ride, "
                            + "and it commits the manifest through Publication.commit with the media-type sidecar "
                            + "written first and the tag pointer and stale-hold clear declared as the visibility"),
            new Route("MavenFormat.java", Kind.LAYOUT,
                    "lays an already-screened body out into the /maven/ namespace (and cross-publishes a modular jar's "
                            + "module view); its PUT body arrives as the edge's RestreamExchange over blobs/<hash>"),
            new Route("RawFormat.java", Kind.LAYOUT,
                    "lays an already-screened body out into the /raw/ namespace; its proxy leg is a cache fill"),
            new Route("JenesisFormat.java", Kind.LAYOUT,
                    "lays an already-screened body out into the /module/ namespace"),
            new Route("FormatExchange.java", Kind.TRANSPORT,
                    "the format SPI's request/response abstraction - it declares requestStream(), it consumes nothing"),
            new Route("ServletFormatExchange.java", Kind.TRANSPORT,
                    "the servlet-backed FormatExchange implementation: it exposes the socket body, it publishes nothing"),
            new Route("RestreamExchange.java", Kind.TRANSPORT,
                    "the edge's restream view: it replaces requestStream() with a fresh open of the accepted blob so "
                            + "the layout never re-reads the socket"),
            new Route("BatchIngestion.java", Kind.FANOUT,
                    "explodes one archive upload into per-entry writes and re-dispatches each through ScreenedDispatch, "
                            + "so every entry passes the one hosted route rather than a second publish path"),
            new Route("PullThroughCache.java", Kind.PROXY,
                    "a proxy cache fill on the read path: an upstream miss is fetched, stored and served, and the "
                            + "after-commit observers are fired best-effort for parity - it is not a hosted publish "
                            + "and has no screen of its own (an edition screens its proxy ingress upstream of here)"),
            new Route("OciFormat.java", Kind.SESSION,
                    "the /v2/ blob upload session (initiate, PATCH chunks, PUT digest) carries no single body to "
                            + "screen; the manifest that names those blobs is the screened choke point, and this file "
                            + "routes its manifest PUT into OciManifests"),
            new Route("DemoSeeder.java", Kind.SEEDER,
                    "seeds local demonstration artifacts through the plain dispatcher at boot; not a client ingress "
                            + "surface and not reachable from a request"));

    /** The one non-hosted site that may fire the after-commit observers itself, with the reason it may. */
    private static final Map<String, String> POST_COMMIT_NOTIFIERS = Map.of(
            "PullThroughCache.java",
            "the proxy cache fill has no hosted-publish operation to ride, so it fires published() itself - "
                    + "best-effort, only when the fetched artifact is actually located, and swallowed on failure");

    /** How an ingress body reaches this codebase. A file containing either anchor is an ingress-body consumer. */
    private static final List<String> INGRESS_ANCHORS = List.of("requestStream()", "ImportSource.Content");

    /**
     * A file runs the hosted-publish operation only if it both invokes {@code commit} and names {@link
     * build.jenesis.repository.store.Publication} - the type it must hold to reach that method. {@code .commit(} alone
     * is not enough: unrelated durable writes spell their own commit (the feed client commits a feed snapshot through
     * {@code FeedSnapshots.commit}), and pulling those into an <em>ingress</em> census would dilute exactly the signal
     * it exists to carry. A new hosted route cannot evade this - it cannot call the operation without the type.
     */
    private static boolean runsTheHostedPublish(String code) {
        return code.contains(".commit(") && code.contains("Publication");
    }

    // --- the static legs -------------------------------------------------------------------------------------------

    @Test
    void every_ingress_body_consumer_is_classified() throws IOException {
        Map<String, String> sources = sources();
        Set<String> classified = INVENTORY.stream().map(Route::file).collect(Collectors.toSet());

        List<String> unclassified = new ArrayList<>();
        for (Map.Entry<String, String> source : sources.entrySet()) {
            String code = stripped(source.getValue());
            boolean ingress = INGRESS_ANCHORS.stream().anyMatch(code::contains);
            boolean commits = runsTheHostedPublish(code);
            if ((ingress || commits) && !classified.contains(source.getKey())) {
                unclassified.add("  - " + source.getKey() + (commits ? "  (runs Publication.commit)" : "")
                        + (ingress ? "  (consumes an ingress body)" : ""));
            }
        }

        assertThat(unclassified)
                .as("these sources take an ingress body (or run the hosted-publish operation) but carry no entry in "
                        + "the ingress census. Every ingress into the core is classified: HOSTED (must run "
                        + "Publication.commit), LAYOUT, TRANSPORT, FANOUT, PROXY, SESSION or SEEDER. Add the entry "
                        + "with its justification - a new hosted route that quietly re-implements screen/layout/notify "
                        + "is exactly what this census exists to catch.%n%s",
                        String.join(System.lineSeparator(), unclassified))
                .isEmpty();
    }

    @Test
    void the_inventory_has_no_stale_entry() throws IOException {
        Map<String, String> sources = sources();

        List<String> stale = new ArrayList<>();
        for (Route route : INVENTORY) {
            String source = sources.get(route.file());
            if (source == null) {
                stale.add("  - " + route.file() + "  (classified, but no such source exists any more)");
                continue;
            }
            if (route.justification().isBlank()) {
                stale.add("  - " + route.file() + "  (classified without a justification)");
            }
            String body = stripped(source);
            boolean ingress = INGRESS_ANCHORS.stream().anyMatch(body::contains);
            if (route.kind() == Kind.HOSTED) {
                if (!runsTheHostedPublish(body)) {
                    stale.add("  - " + route.file() + "  (classified HOSTED, but it no longer runs Publication.commit "
                            + "- either it stopped being a hosted route, or it grew a second publish path)");
                }
            } else if (!ingress) {
                stale.add("  - " + route.file() + "  (classified " + route.kind() + ", but it no longer takes an "
                        + "ingress body - drop the entry)");
            }
        }

        assertThat(stale)
                .as("the ingress census tracks the sources rather than rotting beside them.%n%s",
                        String.join(System.lineSeparator(), stale))
                .isEmpty();
    }

    @Test
    void the_screen_is_reachable_only_through_the_hosted_publish_operation() throws IOException {
        List<String> callers = sources().entrySet().stream()
                .filter(source -> stripped(source.getValue()).contains(".screen("))
                .map(source -> "  - " + source.getKey())
                .sorted()
                .toList();

        assertThat(callers)
                .as("Publication.screen has no caller in the core outside Publication.commit itself, and that is "
                        + "the point: an ingress edge that screens by hand can screen twice, screen after laying out, "
                        + "or forget to gate the republish. A hosted route runs Publication.commit; a route that "
                        + "genuinely cannot must be argued for in review, not added silently.%n%s",
                        String.join(System.lineSeparator(), callers))
                .isEmpty();
    }

    @Test
    void the_after_commit_notification_is_fired_only_by_the_operation() throws IOException {
        List<String> unjustified = sources().entrySet().stream()
                .filter(source -> stripped(source.getValue()).contains(".published("))
                .filter(source -> !POST_COMMIT_NOTIFIERS.containsKey(source.getKey()))
                .map(source -> "  - " + source.getKey())
                .sorted()
                .toList();

        assertThat(unjustified)
                .as("Publication.published is fired by Publication.commit, strictly after the declared visibility "
                        + "committed. A site that fires it itself is claiming a publish the operation did not "
                        + "sequence, so it needs an entry in POST_COMMIT_NOTIFIERS with the reason.%n%s",
                        String.join(System.lineSeparator(), unjustified))
                .isEmpty();
    }

    @Test
    void every_justified_post_commit_notifier_still_notifies() throws IOException {
        Map<String, String> sources = sources();
        List<String> stale = POST_COMMIT_NOTIFIERS.keySet().stream()
                .filter(file -> !sources.containsKey(file) || !stripped(sources.get(file)).contains(".published("))
                .map(file -> "  - " + file)
                .sorted()
                .toList();

        assertThat(stale)
                .as("these post-commit-notifier carve-outs no longer fire published() - drop them rather than let "
                        + "them mask a future one.%n%s", String.join(System.lineSeparator(), stale))
                .isEmpty();
    }

    // --- the runtime legs: each hosted route, driven for real ------------------------------------------------------

    @TempDir
    Path root;

    private Recording store;

    @BeforeEach
    void setUp() {
        store = new Recording(ArtifactStoreProvider.resolve(
                "filesystem", key -> "JENESIS_STORE_ROOT".equals(key) ? root.toString() : null));
        CountingInterceptor.reset();
        RecordingObserver.reset();
    }

    @Test
    void the_deploy_edge_screens_once_commits_pointer_last_and_notifies_once() throws IOException {
        String path = "/raw/count-me/" + CensusObserver.MARKER + "/deploy.txt";
        CensusObserver.expect("publish" + path);
        ScreenedDispatch edge = new ScreenedDispatch(new FormatDispatcher(
                List.of(RepositoryFormat.installed("raw").orElseThrow()), Map.of(), ProxyFormat.Fetcher.NONE));

        assertThat(edge.dispatch(new Upload("PUT", path, "deployed".getBytes(StandardCharsets.UTF_8)), store)).isTrue();

        assertRoute("the deploy edge", "publish" + path);
        assertThat(new Publication(store).located(path)).as("the artifact serves").isPresent();
    }

    @Test
    void the_import_edge_screens_once_commits_pointer_last_and_notifies_once() throws IOException {
        String asset = "count-me/" + CensusObserver.MARKER + "/import.bin";
        CensusObserver.expect("publish/raw/" + asset);

        RepositoryImport.Result result = new RepositoryImport().run(
                (consumer, checkpoint) -> {
                    consumer.accept("raw", asset, () -> new ByteArrayInputStream("imported".getBytes(StandardCharsets.UTF_8)));
                    checkpoint.reached(null);
                }, store);

        assertThat(result.imported()).isEqualTo(1);
        assertRoute("the import edge", "publish/raw/" + asset);
        assertThat(new Publication(store).located("/raw/" + asset)).isPresent();
    }

    @Test
    void the_oci_choke_point_screens_once_commits_pointer_last_and_notifies_once() throws IOException {
        String name = "count-me/" + CensusObserver.MARKER;
        CensusObserver.expect("oci/" + name + "/tags/v1");
        byte[] manifest = ("{\"schemaVersion\":2,\"mediaType\":\"application/vnd.oci.image.manifest.v1+json\","
                + "\"config\":{},\"layers\":[]}").getBytes(StandardCharsets.UTF_8);

        Upload push = new Upload("PUT", "/v2/" + name + "/manifests/v1", manifest);
        RepositoryFormat.installed("oci").orElseThrow().handle(push, store);

        assertThat(push.status).as("the manifest was accepted").isEqualTo(201);
        assertRoute("the OCI manifest choke point", "oci/" + name + "/tags/v1");
        List<String> mutations = store.mutations;
        List<Integer> sidecars = IntStream.range(0, mutations.size())
                .filter(index -> mutations.get(index).startsWith("write:oci/types/"))
                .boxed()
                .toList();
        assertThat(sidecars)
                .as("OCI writes its media-type sidecar exactly once. Writes in order: " + mutations).hasSize(1);
        assertThat(sidecars.getFirst())
                .as("the media-type sidecar is a parse result, so it lands before the tag pointer that makes the "
                        + "manifest servable, never after. Writes in order: " + mutations)
                .isLessThan(mutations.size() - 1);
    }

    /** The three census properties, asserted the same way for every hosted route. */
    private void assertRoute(String route, String servingKey) {
        assertThat(CountingInterceptor.count())
                .as(route + " screens the body exactly once - no format re-screens what the operation screened")
                .isEqualTo(1);
        assertThat(store.mutations)
                .as(route + " must have written something durable").isNotEmpty();
        assertThat(store.mutations.getLast())
                .as(route + " commits pointer-last: the final durable write is the key that makes the artifact "
                        + "servable, so every sidecar and parse result landed before it. Writes in order: "
                        + store.mutations)
                .endsWith(":" + servingKey);
        assertThat(CensusObserver.notifications())
                .as(route + " notifies the after-commit observers exactly once").hasSize(1);
        assertThat(CensusObserver.notifications().getFirst().servingKeyPresent())
                .as(route + " notifies only after visibility committed - the serving key " + servingKey
                        + " already existed when the observer was called")
                .isTrue();
        assertThat(CensusObserver.notifications().getFirst().hash())
                .as(route + " carries the accepted blob's identity into the notification").isNotNull();
    }

    // --- fixtures --------------------------------------------------------------------------------------------------

    /** An {@link ArtifactStore} decorator that records every durable mutation in order, so "pointer-last" is read off
     *  the real write sequence a route produced rather than inferred from the source. */
    private static final class Recording implements ArtifactStore {

        private final ArtifactStore delegate;
        private final List<String> mutations = new ArrayList<>();

        private Recording(ArtifactStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public ArtifactStore scope(String tenant) {
            return new Recording(delegate.scope(tenant));
        }

        @Override
        public boolean exists(String key) {
            return delegate.exists(key);
        }

        @Override
        public void read(String key, OutputStream out) throws IOException {
            delegate.read(key, out);
        }

        @Override
        public InputStream open(String key) throws IOException {
            return delegate.open(key);
        }

        @Override
        public void write(String key, InputStream in) throws IOException {
            delegate.write(key, in);
            mutations.add("write:" + key);
        }

        @Override
        public String writeBlob(InputStream in) throws IOException {
            String hash = delegate.writeBlob(in);
            mutations.add("writeBlob:blobs/" + hash);
            return hash;
        }

        @Override
        public long size(String key) throws IOException {
            return delegate.size(key);
        }

        @Override
        public void delete(String key) throws IOException {
            delegate.delete(key);
            mutations.add("delete:" + key);
        }

        @Override
        public List<String> list(String prefix) {
            return delegate.list(prefix);
        }

        @Override
        public Optional<Versioned> readVersioned(String key) throws IOException {
            return delegate.readVersioned(key);
        }

        @Override
        public boolean writeVersioned(String key, byte[] content, Object expected) throws IOException {
            boolean written = delegate.writeVersioned(key, content, expected);
            if (written) {
                mutations.add("writeVersioned:" + key);
            }
            return written;
        }
    }

    /** A minimal upload exchange: one method/path/body, capturing the status the route set. */
    private static final class Upload implements FormatExchange {

        private final String method;
        private final String path;
        private final byte[] body;
        private int status = -1;

        private Upload(String method, String path, byte[] body) {
            this.method = method;
            this.path = path;
            this.body = body;
        }

        @Override
        public String method() {
            return method;
        }

        @Override
        public String path() {
            return path;
        }

        @Override
        public String queryParameter(String name) {
            return null;
        }

        @Override
        public String requestHeader(String name) {
            return null;
        }

        @Override
        public InputStream requestStream() {
            return new ByteArrayInputStream(body);
        }

        @Override
        public void setResponseHeader(String name, String value) {
        }

        @Override
        public OutputStream respond(int status, long contentLength) {
            this.status = status;
            return new ByteArrayOutputStream();
        }
    }

    // --- the source scan -------------------------------------------------------------------------------------------

    private static final Pattern COMMENTS = Pattern.compile("//[^\\r\\n]*|/\\*.*?\\*/", Pattern.DOTALL);

    /** Comment-stripped, so a javadoc sentence naming a seam is never mistaken for an invocation of it. */
    private static String stripped(String source) {
        return COMMENTS.matcher(source).replaceAll(" ");
    }

    /** Every core source file as simple name &rarr; body. File names are unique across the free {@code source/}
     *  tree; the census asserts that, so a same-named second file cannot silently shadow a classification. */
    private static Map<String, String> sources() throws IOException {
        Map<String, String> sources = new TreeMap<>();
        List<String> duplicates = new ArrayList<>();
        Path sourceRoot = sourceRoot();
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.getFileName().toString().equals("module-info.java"))
                    .sorted()::iterator) {
                String name = file.getFileName().toString();
                if (sources.put(name, Files.readString(file)) != null) {
                    duplicates.add("  - " + name);
                }
            }
        }
        assertThat(sources).as("the source scan found the free module sources - the census is not vacuous").isNotEmpty();
        assertThat(duplicates)
                .as("the census keys classifications by simple file name, so two sources sharing one name would let "
                        + "an unclassified edge hide behind a classified namesake. Rename one, or key the census by "
                        + "path.%n%s", String.join(System.lineSeparator(), duplicates))
                .isEmpty();
        return sources;
    }

    /** The free repository's {@code source/} tree, located by walking up to the ancestor holding it beside
     *  {@code build/jenesis}, so the scan runs from the reactor's working directory or a nested module directory. */
    private static Path sourceRoot() {
        Path start = Path.of("").toAbsolutePath();
        for (Path directory = start; directory != null; directory = directory.getParent()) {
            if (Files.isDirectory(directory.resolve("source")) && Files.isDirectory(directory.resolve("build/jenesis"))) {
                return directory.resolve("source");
            }
        }
        throw new AssertionError("could not locate the free repo root (an ancestor holding source/ beside "
                + "build/jenesis) from working directory " + start + " - the ingress census must run against the "
                + "module sources so it can never pass vacuously");
    }
}
