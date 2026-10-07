package build.jenesis.repository.format.raw.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.raw.RawFormat;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.UpstreamMemory;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The raw format's pull-through proxy answered from a fixed in-memory upstream (no network): a local miss is fetched,
 * cached content-addressed and served in one call, a subsequent read is a local hit, an upstream miss lets the local
 * {@code 404} stand, and a directory listing is never proxied. A file the repository names as moving is relayed as the
 * upstream serves it, remembered for the upstream document ttl and never kept; an upstream that cannot say whether it
 * exists is a {@code 502} rather than an absence.
 */
class RawProxyTest {

    @TempDir
    Path root;

    private ArtifactStore store;
    private final RawFormat format = new RawFormat();
    private final URI upstream = URI.create("https://upstream.example/root");

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null);
        UpstreamMemory.node().clear();
    }

    @AfterEach
    void forget() {
        UpstreamMemory.node().clear();
    }

    private static ProxyFormat.Fetcher.Buffered serving(int status, byte[] body) {
        return (url, headers) -> Optional.of(new ProxyFormat.Fetched(status, body, Map.of()));
    }

    @Test
    void a_miss_is_proxied_cached_and_served_then_read_locally() throws IOException {
        byte[] body = "upstream file".getBytes(StandardCharsets.UTF_8);
        FakeExchange get = new FakeExchange("GET", "/raw/pkg/file.bin");

        boolean served = format.proxy(get, store, upstream, serving(200, body));
        assertThat(served).isTrue();
        assertThat(get.status()).isEqualTo(200);
        assertThat(get.responseBytes()).isEqualTo(body);

        assertThat(new Publication(store).located("/raw/pkg/file.bin"))
                .as("the fetched artifact is cached for a later local hit").isPresent();
    }

    @Test
    void an_upstream_miss_lets_the_local_404_stand() throws IOException {
        FakeExchange get = new FakeExchange("GET", "/raw/pkg/absent.bin");
        boolean served = format.proxy(get, store, upstream, serving(404, new byte[0]));
        assertThat(served).isFalse();
    }

    @Test
    void a_file_that_moves_is_relayed_as_the_upstream_serves_it_and_never_kept() throws IOException {
        List<String> served = new ArrayList<>(List.of("{\"built\": \"monday\"}", "{\"built\": \"tuesday\"}"));
        AtomicInteger asked = new AtomicInteger();
        ProxyFormat.Fetcher.Buffered upstreamNow = (url, headers) -> {
            asked.incrementAndGet();
            return Optional.of(new ProxyFormat.Fetched(200, served.getFirst().getBytes(StandardCharsets.UTF_8),
                    Map.of("Content-Type", "application/json")));
        };

        FakeExchange first = moving("/raw/databases/v6/latest.json");
        assertThat(format.mergesUpstream(first)).as("asked of the upstream whatever is held").isTrue();
        assertThat(format.proxy(first, store, upstream, upstreamNow)).isTrue();
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.responseText()).contains("monday");
        assertThat(first.responseHeader("Content-Type")).isEqualTo("application/json");
        assertThat(new Publication(store).located("/raw/databases/v6/latest.json")).as("nothing is kept").isEmpty();

        served.removeFirst();
        FakeExchange burst = moving("/raw/databases/v6/latest.json");
        format.proxy(burst, store, upstream, upstreamNow);
        assertThat(burst.responseText()).as("a reader within the ttl is answered from the node's memory")
                .contains("monday");
        assertThat(asked).hasValue(1);

        UpstreamMemory.node().clear();
        FakeExchange later = moving("/raw/databases/v6/latest.json");
        format.proxy(later, store, upstream, upstreamNow);
        assertThat(later.responseText()).as("past it, what the upstream serves now").contains("tuesday");
    }

    @Test
    void a_file_named_by_no_glob_is_kept_as_before() throws IOException {
        FakeExchange get = moving("/raw/databases/v6/vulnerability-db_v6.1.10.tar.zst");
        assertThat(format.mergesUpstream(get)).isFalse();
        assertThat(format.proxy(get, store, upstream, serving(200, new byte[]{1, 2, 3}))).isTrue();
        assertThat(new Publication(store).located("/raw/databases/v6/vulnerability-db_v6.1.10.tar.zst")).isPresent();
    }

    @Test
    void a_moving_file_the_upstream_does_not_publish_lets_the_local_404_stand() throws IOException {
        assertThat(format.proxy(moving("/raw/latest.json"), store, upstream, serving(404, new byte[0]))).isFalse();
        assertThat(format.proxy(moving("/raw/latest.json"), store, upstream, serving(410, new byte[0]))).isFalse();
    }

    @Test
    void a_moving_file_the_upstream_could_not_be_asked_for_is_a_502() throws IOException {
        FakeExchange unavailable = moving("/raw/latest.json");
        assertThat(format.proxy(unavailable, store, upstream, serving(503, new byte[0]))).isTrue();
        assertThat(unavailable.status()).isEqualTo(502);

        FakeExchange unreachable = moving("/raw/latest.json");
        ProxyFormat.Fetcher.Buffered nothing = (url, headers) -> Optional.empty();
        assertThat(format.proxy(unreachable, store, upstream, nothing)).isTrue();
        assertThat(unreachable.status()).isEqualTo(502);
        assertThat(new Publication(store).located("/raw/latest.json")).isEmpty();
    }

    @Test
    void a_glob_matches_within_a_segment_across_segments_and_at_the_root() throws IOException {
        String globs = "**/latest.json, tools/*.txt  exact/name.bin";
        for (String path : List.of("latest.json", "a/b/latest.json", "tools/list.txt", "exact/name.bin")) {
            assertThat(format.mergesUpstream(new FakeExchange("GET", "/raw/" + path).setting(RawFormat.MOVING,
                    globs))).as(path).isTrue();
        }
        for (String path : List.of("latest.json.sha256", "tools/sub/list.txt", "exact/name.bin2", "other.json")) {
            assertThat(format.mergesUpstream(new FakeExchange("GET", "/raw/" + path).setting(RawFormat.MOVING,
                    globs))).as(path).isFalse();
        }
        assertThat(format.mergesUpstream(new FakeExchange("GET", "/raw/latest.json"))).as("no glob, nothing moves")
                .isFalse();
    }

    private static FakeExchange moving(String path) {
        return new FakeExchange("GET", path).setting(RawFormat.MOVING, "**/latest.json");
    }

    @Test
    void a_directory_listing_is_not_proxied() throws IOException {
        FakeExchange listing = new FakeExchange("GET", "/raw/pkg/");
        boolean served = format.proxy(listing, store, upstream, serving(200, new byte[]{1}));
        assertThat(served).isFalse();
    }

}
