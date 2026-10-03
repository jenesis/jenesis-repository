package build.jenesis.repository.format.maven.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.format.LifecycleMark;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.lifecycle.Lifecycle;
import build.jenesis.repository.format.maven.MavenFormat;
import build.jenesis.repository.format.maven.MavenMetadata;
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

    @Test
    void under_the_computation_option_the_upstreams_versions_and_the_local_ones_are_one_document() throws IOException {
        Publication publication = new Publication(store);
        publication.link("/maven/org/example/lib/2.0/lib-2.0.jar", "abc20");
        Map<String, String> computing = Map.of(MavenMetadata.COMPUTE_SETTING, "true");
        FakeExchange get = FakeExchange.get(METADATA, computing);

        assertThat(format.mergesUpstream(get)).as("a local document is only part of the answer").isTrue();
        assertThat(format.mergesUpstream(FakeExchange.get("/maven/org/example/lib/1.0/lib-1.0.jar", computing)))
                .as("an artifact is the upstream's or this repository's, never a merge").isFalse();
        assertThat(format.mergesUpstream(new FakeExchange("GET", METADATA))).as("the option is off by default")
                .isFalse();
        assertThat(format.proxy(get, store, UPSTREAM, answering(200))).isTrue();

        String xml = new String(get.responseBytes(), StandardCharsets.UTF_8);
        assertThat(xml).contains("<version>1.0</version>", "<version>2.0</version>").contains("<latest>2.0</latest>");
        FakeExchange checksum = FakeExchange.get(METADATA + ".sha1", computing);
        assertThat(format.proxy(checksum, store, UPSTREAM, answering(200))).isTrue();
        assertThat(new String(checksum.responseBytes(), StandardCharsets.UTF_8))
                .as("the checksum is the merged document's").isEqualTo(sha1(get.responseBytes()));
        assertThat(asked).as("the checksum is derived from the remembered document, not fetched")
                .containsExactly(UPSTREAM + "org/example/lib/maven-metadata.xml");
    }

    @Test
    void a_yanked_version_leaves_the_merged_document_whichever_side_lists_it() throws IOException {
        new Publication(store).link("/maven/org/example/lib/2.0/lib-2.0.jar", "abc20");
        Lifecycle.mark(store, "org.example:lib", "1.0", new Lifecycle.Flag(LifecycleMark.YANKED, "broken"));
        FakeExchange get = FakeExchange.get(METADATA, Map.of(MavenMetadata.COMPUTE_SETTING, "true"));

        assertThat(format.proxy(get, store, UPSTREAM, answering(200))).isTrue();
        assertThat(new String(get.responseBytes(), StandardCharsets.UTF_8))
                .as("1.0, listed by the upstream, is yanked here").doesNotContain("<version>1.0</version>")
                .contains("<version>2.0</version>");
    }

    @Test
    void an_upstream_with_no_document_leaves_the_local_versions() throws IOException {
        new Publication(store).link("/maven/org/example/lib/2.0/lib-2.0.jar", "abc20");
        FakeExchange get = FakeExchange.get(METADATA, Map.of(MavenMetadata.COMPUTE_SETTING, "true"));

        assertThat(format.proxy(get, store, UPSTREAM, answering(404))).isTrue();
        assertThat(new String(get.responseBytes(), StandardCharsets.UTF_8)).contains("<version>2.0</version>")
                .doesNotContain("<version>1.0</version>");
    }

    private static String sha1(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
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
