package build.jenesis.repository.format.maven.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.maven.MavenFormat;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.UpstreamMemory;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A proxied {@code maven-metadata.xml} is relayed as the upstream serves it and remembered for the node's upstream
 * memory ttl, so a burst of builds costs the upstream one fetch; an answer the leg refuses is never remembered, so
 * the next request asks the upstream again. Answered from a fixed in-memory upstream, no network.
 */
class MavenMetadataRelayTest {

    private static final URI UPSTREAM = URI.create("https://upstream.example/maven2/");
    private static final String METADATA = "/maven/org/example/lib/maven-metadata.xml";
    private static final byte[] DOCUMENT = ("<metadata><groupId>org.example</groupId><artifactId>lib</artifactId>"
            + "<versioning><versions><version>1.0</version></versions></versioning></metadata>")
            .getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path root;

    private ArtifactStore store;
    private final MavenFormat format = new MavenFormat();
    private final List<String> asked = new ArrayList<>();

    @BeforeEach
    void setUp() {
        UpstreamMemory.reset();
        store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null);
    }

    @AfterEach
    void forget() {
        UpstreamMemory.reset();
    }

    @Test
    void a_proxied_metadata_document_costs_the_upstream_one_fetch() throws IOException {
        ProxyFormat.Fetcher upstream = answering(200);

        for (int build = 0; build < 3; build++) {
            FakeExchange get = new FakeExchange("GET", METADATA);
            assertThat(format.proxy(get, store, UPSTREAM, upstream)).isTrue();
            assertThat(get.status()).isEqualTo(200);
            assertThat(get.responseBytes()).as("relayed as the upstream serves it").isEqualTo(DOCUMENT);
        }
        assertThat(asked).as("three builds, one fetch").hasSize(1);
    }

    @Test
    void a_refused_answer_is_never_remembered() throws IOException {
        FakeExchange refused = new FakeExchange("GET", METADATA);
        assertThat(format.proxy(refused, store, UPSTREAM, answering(503))).isTrue();
        assertThat(refused.status()).as("an upstream that cannot answer is no empty version list").isEqualTo(502);

        FakeExchange served = new FakeExchange("GET", METADATA);
        assertThat(format.proxy(served, store, UPSTREAM, answering(200))).isTrue();
        assertThat(served.status()).isEqualTo(200);
        assertThat(asked).as("the refusal left nothing behind, so the upstream is asked again").hasSize(2);
    }

    @Test
    void a_metadata_checksum_is_relayed_and_never_stored() throws IOException {
        for (String suffix : List.of(".sha1", ".md5", ".sha256", ".sha512")) {
            FakeExchange get = new FakeExchange("GET", METADATA + suffix);
            assertThat(format.proxy(get, store, UPSTREAM, answering(200))).as(suffix).isTrue();
            assertThat(new Publication(store).located(METADATA + suffix))
                    .as("%s changes with the document it covers, so it is never pinned in the store", suffix).isEmpty();
        }
    }

    /** An upstream answering every metadata request with {@code status}, noting each one it is asked. */
    private ProxyFormat.Fetcher.Buffered answering(int status) {
        return (url, headers) -> {
            asked.add(url.toString());
            return Optional.of(new ProxyFormat.Fetched(status, status == 200 ? DOCUMENT : new byte[0], Map.of()));
        };
    }
}
