package build.jenesis.repository.test;

import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.maven.MavenFormat;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Maven proxy's checksum refusal, proven with <em>both</em> layout formats on the module path so the one required
 * cross-publish actually runs (the focused Maven unit test carries no module-view provider, so its
 * {@code MODULE_VIEWS} is empty). A proxied jar whose bytes cannot be held to the upstream's own {@code .sha1} must be
 * reachable under no coordinate at all - not the {@code /maven/} pointer and not the {@code /module/} views a modular
 * jar would gain - because a tampered modular jar refused only at its Maven coordinate would still serve by module
 * name. The matching case is the control: it shows the cross-publish really happens, so the refusal cases are refusing
 * something that would otherwise have been linked rather than passing vacuously. Answered from a fixed in-memory
 * upstream, no network.
 *
 * <p><b>This used to be a retraction, and D-059 is why it no longer is.</b> The leg laid the fetched bytes out first
 * and un-linked them again when the checksum did not hold, which meant the tampered jar was briefly reachable by
 * coordinate and - if any step of the un-linking failed - stayed reachable, with nothing to repair it (a local hit
 * never re-enters the proxy leg, so the retraction had no second chance). The verification now happens before the
 * commit point, exactly as the OCI leg has always held a mismatched digest: the bytes are stored content-addressed as
 * they stream, nothing is linked until they have been held to the checksum, and a refused fill leaves only an
 * unreferenced blob for garbage collection. The assertions below are unchanged in what they demand - nothing serves -
 * and their <em>reason</em> is now "never linked" rather than "linked, then retracted".
 */
class MavenProxyChecksumRefusalTest {

    private static final URI UPSTREAM = URI.create("https://upstream.example/maven2/");
    private static final String PATH = "/maven/org/example/widget/1.0/widget-1.0.jar";

    @TempDir
    Path root;

    private ArtifactStore store;
    private Publication publication;
    private final MavenFormat format = new MavenFormat();

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        publication = new Publication(store);
    }

    @Test
    void a_matching_modular_jar_is_cross_published_to_its_module_views() throws IOException {
        byte[] jar = automaticModuleJar("test.verified");

        boolean served = format.proxy(new CaptureExchange(PATH), store, UPSTREAM, upstream(jar, 200, sha1(jar)));

        assertThat(served).isTrue();
        assertThat(publication.located(PATH)).as("cached under its Maven coordinate").isPresent();
        assertThat(publication.located("/module/test.verified/1.0/test.verified.jar"))
                .as("the cross-publish links the versioned module view").isPresent();
        assertThat(publication.located("/module/test.verified/test.verified.jar"))
                .as("the cross-publish links the latest module view").isPresent();
    }

    @Test
    void a_tampered_modular_jar_is_unreachable_by_its_maven_coordinate_and_by_module_name() throws IOException {
        byte[] jar = automaticModuleJar("test.tampered");

        boolean served = format.proxy(new CaptureExchange(PATH), store, UPSTREAM,
                upstream(jar, 200, "0000000000000000000000000000000000000000"));

        assertThat(served).as("a body that does not match its upstream checksum is refused").isFalse();
        assertThat(publication.located(PATH)).as("never linked under its Maven coordinate").isEmpty();
        assertThat(publication.located("/module/test.tampered/1.0/test.tampered.jar"))
                .as("and so never cross-published to its versioned module view").isEmpty();
        assertThat(publication.located("/module/test.tampered/test.tampered.jar"))
                .as("nor to its latest module view").isEmpty();
        assertThat(store.list("publish/maven/org/example/widget/1.0"))
                .as("no pointer was linked and un-linked either: the refusal happens before the commit point, so the "
                        + "coordinate's key space was never written at all").isEmpty();
    }

    @Test
    void a_checksum_sibling_this_repository_could_not_read_refuses_the_fill() throws IOException {
        // Clause 5's split (D-236). "The upstream publishes no .sha1 for this artifact" is a documented fact about
        // Maven repositories and is the one thing that may downgrade a fill to unverified. A sibling fetch that never
        // landed, or one answered by a 429 under a shared egress IP or a 5xx, is not that fact - it is this repository
        // failing to read what the upstream published - and reading it as that fact means anyone who can drop one
        // sidecar request turns verification off for that pull. The sibling is read while the fetched bytes are stored
        // but not yet laid out, so this refusal declines the layout entirely, exactly as a mismatch does, and the jar
        // is reachable under neither coordinate for the same reason the mismatch case gives.
        for (Optional<ProxyFormat.Fetched> sibling : List.of(
                Optional.<ProxyFormat.Fetched>empty(),                                   // a transport failure
                Optional.of(new ProxyFormat.Fetched(429, new byte[0], Map.of())),
                Optional.of(new ProxyFormat.Fetched(503, new byte[0], Map.of())),
                Optional.of(new ProxyFormat.Fetched(401, new byte[0], Map.of())))) {
            byte[] jar = automaticModuleJar("test.unverifiable");
            ProxyFormat.Fetcher.Buffered upstream = (url, headers) -> url.toString().endsWith(".sha1")
                    ? sibling
                    : Optional.of(new ProxyFormat.Fetched(200, jar, Map.of()));

            boolean served = format.proxy(new CaptureExchange(PATH), store, UPSTREAM, upstream);

            assertThat(served).as("an artifact whose declaring checksum could not be read is refused, not cached "
                    + "unverified (the sibling answered %s)", sibling.map(ProxyFormat.Fetched::status).orElse(null))
                    .isFalse();
            assertThat(publication.located(PATH)).as("never linked under its Maven coordinate").isEmpty();
            assertThat(publication.located("/module/test.unverifiable/1.0/test.unverifiable.jar"))
                    .as("and so never cross-published to its versioned module view").isEmpty();
            assertThat(publication.located("/module/test.unverifiable/test.unverifiable.jar"))
                    .as("nor to its latest module view").isEmpty();
        }
    }

    @Test
    void an_upstream_that_answers_it_publishes_no_checksum_still_caches_unverified() throws IOException {
        // The half that must NOT change, and the reason D-236 is a split rather than a blanket refusal: the upstream
        // ANSWERED that it carries no .sha1 beside this artifact, and clause 5 says such a repository is proxied
        // unverified rather than having a check fabricated for it. A leg that refused here would stop serving every
        // Maven repository that publishes no checksums at all.
        byte[] jar = automaticModuleJar("test.unchecksummed");

        boolean served = format.proxy(new CaptureExchange(PATH), store, UPSTREAM, upstream(jar, 404, ""));

        assertThat(served).as("an upstream with no .sha1 sibling is proxied unverified, as clause 5 documents")
                .isTrue();
        assertThat(publication.located(PATH)).as("cached under its Maven coordinate").isPresent();
        assertThat(publication.located("/module/test.unchecksummed/1.0/test.unchecksummed.jar"))
                .as("and cross-published, so this is a real fill rather than a vacuous pass").isPresent();
    }

    /** An upstream that serves {@code artifact} for the jar and {@code sha1Hex} (at {@code sha1Status}) for its
     *  {@code .sha1} sibling. */
    private static ProxyFormat.Fetcher.Buffered upstream(byte[] artifact, int sha1Status, String sha1Hex) {
        return (url, headers) -> url.toString().endsWith(".sha1")
                ? Optional.of(new ProxyFormat.Fetched(sha1Status,
                        sha1Hex.getBytes(StandardCharsets.UTF_8), Map.of()))
                : Optional.of(new ProxyFormat.Fetched(200, artifact, Map.of()));
    }

    private static byte[] automaticModuleJar(String moduleName) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Automatic-Module-Name", moduleName);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes, manifest)) {
            jar.flush();
        }
        return bytes.toByteArray();
    }

    private static String sha1(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A minimal {@link FormatExchange} that captures the served status and body - all the proxy needs to serve the
     *  matching-checksum control; the mismatch case returns before it responds. */
    private static final class CaptureExchange implements FormatExchange {

        private final String path;
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();

        CaptureExchange(String path) {
            this.path = path;
        }

        @Override
        public String method() {
            return "GET";
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
            return InputStream.nullInputStream();
        }

        @Override
        public void setResponseHeader(String name, String value) {
        }

        @Override
        public OutputStream respond(int status, long contentLength) {
            return body;
        }
    }
}
