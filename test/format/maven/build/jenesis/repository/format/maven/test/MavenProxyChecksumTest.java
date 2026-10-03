package build.jenesis.repository.format.maven.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.maven.MavenFormat;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Maven proxy relays a checksum rather than judging one: a fetched artifact is cached and served as the upstream
 * serves it, with no request for its checksum, and the {@code .sha1} is relayed as the upstream serves that - even
 * where the two disagree, since a checksum is the publisher's to provide and the client's to check. Answered from a
 * fixed in-memory upstream, no network.
 */
class MavenProxyChecksumTest {

    private static final URI UPSTREAM = URI.create("https://upstream.example/maven2/");
    private static final String PATH = "/maven/org/example/lib/1.0/lib-1.0.jar";

    @TempDir
    Path root;

    private ArtifactStore store;
    private Publication publication;
    private final MavenFormat format = new MavenFormat();
    private final List<String> asked = new ArrayList<>();

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null);
        publication = new Publication(store);
    }

    @Test
    void a_proxied_artifact_is_cached_without_asking_for_its_checksum() throws IOException {
        byte[] jar = "jar bytes".getBytes(StandardCharsets.UTF_8);
        FakeExchange get = new FakeExchange("GET", PATH);

        assertThat(format.proxy(get, store, UPSTREAM, upstream(jar, 200, sha1(jar)))).isTrue();

        assertThat(get.status()).isEqualTo(200);
        assertThat(get.responseBytes()).isEqualTo(jar);
        assertThat(publication.located(PATH)).as("cached for a later hit").isPresent();
        assertThat(asked).as("the fill asks for the artifact alone").containsExactly(UPSTREAM + "org/example/lib/1.0/lib-1.0.jar");
    }

    @Test
    void a_checksum_that_disagrees_with_the_bytes_is_relayed_for_the_client_to_judge() throws IOException {
        byte[] jar = "jar bytes".getBytes(StandardCharsets.UTF_8);
        String declared = "0000000000000000000000000000000000000000";
        ProxyFormat.Fetcher upstream = upstream(jar, 200, declared);

        FakeExchange artifact = new FakeExchange("GET", PATH);
        assertThat(format.proxy(artifact, store, UPSTREAM, upstream)).isTrue();
        FakeExchange checksum = new FakeExchange("GET", PATH + ".sha1");
        assertThat(format.proxy(checksum, store, UPSTREAM, upstream)).isTrue();

        assertThat(artifact.responseBytes()).as("the artifact is served as the upstream serves it").isEqualTo(jar);
        assertThat(new String(checksum.responseBytes(), StandardCharsets.UTF_8))
                .as("and its checksum as the upstream serves that").isEqualTo(declared);
    }

    @Test
    void an_artifact_the_upstream_publishes_no_checksum_for_is_served() throws IOException {
        byte[] jar = "unchecksummed jar".getBytes(StandardCharsets.UTF_8);
        FakeExchange get = new FakeExchange("GET", PATH);

        assertThat(format.proxy(get, store, UPSTREAM, upstream(jar, 404, null))).isTrue();
        assertThat(get.responseBytes()).isEqualTo(jar);
    }

    /** An upstream that serves {@code artifact} for the jar and {@code sha1Hex} (at {@code sha1Status}) for its
     *  {@code .sha1} sibling, noting every URL it is asked for. */
    private ProxyFormat.Fetcher.Buffered upstream(byte[] artifact, int sha1Status, String sha1Hex) {
        return (url, headers) -> {
            asked.add(url.toString());
            return url.toString().endsWith(".sha1")
                    ? Optional.of(new ProxyFormat.Fetched(sha1Status,
                            sha1Hex == null ? new byte[0] : sha1Hex.getBytes(StandardCharsets.UTF_8), Map.of()))
                    : Optional.of(new ProxyFormat.Fetched(200, artifact, Map.of()));
        };
    }

    private static String sha1(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
