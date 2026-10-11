package build.jenesis.repository.gateway.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.format.DetachedExchange;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.UpstreamMemory;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A NuGet proxy fills each file of a flat-container version against what the upstream declares for that file: the
 * {@code .nupkg} against the SHA-512 its registration publishes, and a file beside it - the {@code .nuspec} - against
 * nothing, since NuGet publishes a digest for the package alone. Held to the package's digest, the manifest would never
 * verify and could never be proxied.
 */
class NuGetFlatContainerProxyTest {

    private static final String UPSTREAM = "https://upstream.example/";
    private static final byte[] PACKAGE = "the acme.lib 1.1.0 package".getBytes(StandardCharsets.UTF_8);
    private static final byte[] MANIFEST = "<package><metadata><id>acme.lib</id></metadata></package>"
            .getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeEach
    void setUp() {
        UpstreamMemory.reset();
        store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null);
    }

    @AfterEach
    void forget() {
        UpstreamMemory.reset();
    }

    @Test
    void the_package_is_held_to_its_declared_digest_and_the_manifest_beside_it_to_none() throws IOException {
        Exchange nupkg = proxy("/nuget/v3-flatcontainer/acme.lib/1.1.0/acme.lib.1.1.0.nupkg");
        assertThat(nupkg.status).as("the package verifies against its registration's digest").isEqualTo(200);
        assertThat(nupkg.captured.toByteArray()).isEqualTo(PACKAGE);

        Exchange nuspec = proxy("/nuget/v3-flatcontainer/acme.lib/1.1.0/acme.lib.nuspec");
        assertThat(nuspec.status).as("the manifest is kept and served, held to no digest of its own").isEqualTo(200);
        assertThat(nuspec.captured.toByteArray()).isEqualTo(MANIFEST);
    }

    @Test
    void a_package_whose_bytes_do_not_match_its_declared_digest_is_still_refused() throws IOException {
        Map<String, byte[]> answers = new HashMap<>(answers());
        answers.put("v3-flatcontainer/acme.lib/1.1.0/acme.lib.1.1.0.nupkg",
                "tampered".getBytes(StandardCharsets.UTF_8));
        Exchange nupkg = new Exchange("/nuget/v3-flatcontainer/acme.lib/1.1.0/acme.lib.1.1.0.nupkg");
        assertThat(format().proxy(nupkg, store, URI.create(UPSTREAM), upstream(answers)))
                .as("a package that does not match the digest its registration declares is not kept").isFalse();
    }

    private Exchange proxy(String path) throws IOException {
        Exchange exchange = new Exchange(path);
        assertThat(format().proxy(exchange, store, URI.create(UPSTREAM), upstream(answers())))
                .as("the leg answers %s", path).isTrue();
        return exchange;
    }

    /** The upstream's service index, the package's registration leaf declaring its SHA-512, and the flat container's
     *  two files, each by the end of the URL it answers. */
    private static Map<String, byte[]> answers() {
        String digest = Base64.getEncoder().encodeToString(sha512(PACKAGE));
        return Map.of(
                "v3/index.json", ("{\"resources\":[{\"@id\":\"" + UPSTREAM + "v3/registration/\","
                        + "\"@type\":\"RegistrationsBaseUrl\"}]}").getBytes(StandardCharsets.UTF_8),
                "v3/registration/acme.lib/1.1.0.json", ("{\"packageHash\":\"" + digest + "\","
                        + "\"packageHashAlgorithm\":\"SHA512\"}").getBytes(StandardCharsets.UTF_8),
                "v3-flatcontainer/acme.lib/1.1.0/acme.lib.1.1.0.nupkg", PACKAGE,
                "v3-flatcontainer/acme.lib/1.1.0/acme.lib.nuspec", MANIFEST);
    }

    private static ProxyFormat.Fetcher.Buffered upstream(Map<String, byte[]> answers) {
        return (url, _) -> answers.entrySet().stream()
                .filter(answer -> url.toString().endsWith(answer.getKey()))
                .findFirst()
                .map(answer -> new ProxyFormat.Fetched(200, answer.getValue(), Map.of()))
                .or(() -> Optional.of(new ProxyFormat.Fetched(404, new byte[0], Map.of())));
    }

    private static byte[] sha512(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-512").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ProxyFormat format() {
        for (RepositoryFormat format : ServiceLoader.load(RepositoryFormat.class)) {
            if (format.name().equals("nuget")) {
                return (ProxyFormat) format;
            }
        }
        throw new AssertionError("no nuget format on the module path");
    }

    /** A {@code GET} of one path, capturing what is answered. */
    private static final class Exchange implements DetachedExchange {

        private final String path;
        private int status = -1;
        private final ByteArrayOutputStream captured = new ByteArrayOutputStream();

        private Exchange(String path) {
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
            return new ByteArrayInputStream(new byte[0]);
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
