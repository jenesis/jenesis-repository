package build.jenesis.repository.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.format.DetachedExchange;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.maven.MavenFormat;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A proxied modular jar is laid out under its Maven coordinate and cross-published to its module views, proven with
 * <em>both</em> layout formats on the module path so the cross-publish actually runs (the focused Maven unit test
 * carries no module-view provider). Its {@code .sha1} plays no part: a checksum is the publisher's, relayed to the
 * client rather than judged here, so a fill whose upstream declares a different digest lays out the same views.
 * Answered from a fixed in-memory upstream, no network.
 */
class MavenProxyModuleViewTest {

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
                "filesystem", key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null);
        publication = new Publication(store);
    }

    @Test
    void a_proxied_modular_jar_is_cross_published_to_its_module_views_whatever_its_checksum_says() throws IOException {
        byte[] jar = automaticModuleJar("test.proxied");

        boolean served = format.proxy(new CaptureExchange(PATH), store, UPSTREAM,
                upstream(jar, 200, "0000000000000000000000000000000000000000"));

        assertThat(served).isTrue();
        assertThat(publication.located(PATH)).as("cached under its Maven coordinate").isPresent();
        assertThat(publication.located("/module/test.proxied/1.0/test.proxied.jar"))
                .as("the cross-publish links the versioned module view").isPresent();
        assertThat(publication.located("/module/test.proxied/test.proxied.jar"))
                .as("the cross-publish links the latest module view").isPresent();
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

    /** A minimal {@link FormatExchange} that captures the served status and body - all the proxy needs to serve a
     *  fill. */
    private static final class CaptureExchange implements DetachedExchange {

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
