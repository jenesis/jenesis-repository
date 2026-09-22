package build.jenesis.repository.gateway.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the index-shadowing fix (EPIC 4) for {@link build.jenesis.repository.format.pypi.PyPiFormat}: the PEP 503
 * project index is derived from the very {@code pypi/<project>/files} namespace the pull-through proxy caches
 * distribution files into, so before the fix a single cached wheel made the index a local {@code 200} that shadowed the
 * upstream Simple index - an uncached version was no longer discoverable. The fix gates the derived index on a
 * hosted-publish marker a real {@code twine} upload stamps (the RPM hosted-revision idiom): a proxy repository, whose
 * files are cached but never uploaded, has no marker, so its index read misses locally ({@code 404}) and the
 * pull-through fetches the authoritative upstream index. A genuine hosted upload still serves its own index.
 *
 * <p>Driven directly against the format over a real filesystem store (PyPI's proxy leg screens a file's download URL
 * for SSRF and declines a loopback upstream, so the cache is simulated by the pointer the proxy would write; the
 * discovery {@code 404} is what hands control to {@code proxy()}).
 */
class PyPiProxyShadowTest {

    private static final String BOUNDARY = "BoUnDaRyPyPiShadow";

    @TempDir
    Path root;

    private ArtifactStore store;
    private RepositoryFormat pypi;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        pypi = discover("pypi");
    }

    @Test
    void a_proxy_cached_project_index_misses_locally_so_the_upstream_index_wins() throws IOException {
        // Simulate exactly what the pull-through proxy caches: a distribution file pointer under the project's files
        // namespace, with no hosted-publish marker (the proxy caches files, it never uploads).
        new Blobs(store).write("pypi/proxied/files/proxied-1.0.0-py3-none-any.whl",
                "a cached wheel".getBytes(StandardCharsets.UTF_8));

        Exchange read = new Exchange("GET", "/pypi/simple/proxied/", null);
        pypi.handle(read, store);

        assertThat(read.status)
                .as("a proxy repo's project index misses locally so the pull-through fetches the upstream index")
                .isEqualTo(404);
        assertThat(store.readVersioned("pypi/proxied/.hosted"))
                .as("no hosted marker was written for a proxy-cached project").isEmpty();
    }

    @Test
    void the_proxy_leg_declines_the_root_index_and_never_reads_the_whole_project_list() throws IOException {
        // The leg a pure pass-through repository reaches: no store-backed project list, so /pypi/simple/ falls
        // through to proxy(). It used to treat the empty project name as a project - fetching <upstream>simple// and
        // handing the body to the index rewriter, which reduces every href to the text after its last '/'. PyPI's
        // root links are /simple/<project>/ and END in '/', so every rewritten link came out empty: a 200 carrying a
        // page of links to nowhere (D-218). A store-backed repository never saw it, because handle() splits the
        // empty case off to projects() and answers locally - which is why this drives proxy() directly.
        Set<String> fetched = new LinkedHashSet<>();
        ProxyFormat.Fetcher.Buffered fetcher = (url, headers) -> {
            fetched.add(url.toString());
            return Optional.of(new ProxyFormat.Fetched(200,
                    ("<html><body><a href=\"/simple/alpha/\">alpha</a>"
                            + "<a href=\"/simple/beta/\">beta</a></body></html>").getBytes(StandardCharsets.UTF_8),
                    Map.of()));
        };

        Exchange root = new Exchange("GET", "/pypi/simple/", null);
        boolean answered = ((ProxyFormat) pypi).proxy(root, store, URI.create("https://pypi.example/"), fetcher);

        assertThat(answered)
                .as("the root index is not this repository's to republish: a pass-through declines it rather than "
                        + "serving a document whose every link is empty")
                .isFalse();
        assertThat(fetched)
                .as("and it is never fetched. The root index is PyPI's entire project list and this leg reads through "
                        + "the BUFFERED fetch, so answering it at all materialises the one index in the product that "
                        + "is categorically not small - against clause 4, which lets only small metadata be "
                        + "materialised")
                .isEmpty();
    }

    @Test
    void a_hosted_upload_serves_its_own_project_index() throws IOException {
        // A real twine upload publishes the distribution and stamps the hosted marker, so the derived index serves.
        Exchange upload = new Exchange("POST", "/pypi/", multipart("HostedPkg", "hostedpkg-1.0.0-py3-none-any.whl"));
        upload.contentType = "multipart/form-data; boundary=" + BOUNDARY;
        pypi.handle(upload, store);
        assertThat(upload.status).as("the upload succeeds").isEqualTo(200);
        assertThat(store.readVersioned("pypi/hostedpkg/.hosted"))
                .as("the upload stamped the hosted marker").isPresent();

        Exchange read = new Exchange("GET", "/pypi/simple/hostedpkg/", null);
        pypi.handle(read, store);
        assertThat(read.status).as("a hosted repo serves its own project index").isEqualTo(200);
        assertThat(read.body()).as("the uploaded distribution is listed")
                .contains("hostedpkg-1.0.0-py3-none-any.whl");
    }

    private static byte[] multipart(String project, String filename) {
        return ("--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"name\"\r\n\r\n"
                + project + "\r\n"
                + "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"content\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n"
                + "wheel-bytes\r\n"
                + "--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8);
    }

    private static RepositoryFormat discover(String name) {
        for (RepositoryFormat format : ServiceLoader.load(RepositoryFormat.class)) {
            if (format.name().equals(name)) {
                return format;
            }
        }
        throw new AssertionError("no format named " + name + " on the module path");
    }

    /** A {@link FormatExchange} that supplies a request body and captures the response status and body. */
    private static final class Exchange implements FormatExchange {

        private final String method;
        private final String path;
        private final byte[] body;
        private String contentType;
        private int status = -1;
        private final ByteArrayOutputStream captured = new ByteArrayOutputStream();

        private Exchange(String method, String path, byte[] body) {
            this.method = method;
            this.path = path;
            this.body = body;
        }

        String body() {
            return captured.toString(StandardCharsets.UTF_8);
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
            return name.equalsIgnoreCase("Content-Type") ? contentType : null;
        }

        @Override
        public InputStream requestStream() {
            return new ByteArrayInputStream(body == null ? new byte[0] : body);
        }

        @Override
        public void setResponseHeader(String name, String value) {
        }

        @Override
        public OutputStream respond(int status, long contentLength) {
            this.status = status;
            return captured;
        }
    }
}
