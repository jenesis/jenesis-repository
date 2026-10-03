package build.jenesis.repository.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.format.DetachedExchange;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.maven.MavenFormat;
import build.jenesis.repository.server.PullThroughCache;
import build.jenesis.repository.server.PullThroughHooks;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fill's notices: a pull-through that fetched, stored and served an upstream miss tells every
 * {@link build.jenesis.repository.store.PublicationObserver} it published the artifact and then that it cached it
 * from that upstream - the one point every route to a fill passes, which is what the inventory records a cached copy
 * from. Driven over the Maven format and a store, with the upstream a map: a hit and a fill the screen withholds say
 * nothing at all.
 */
public class PullThroughFillNoticeTest {

    private static final URI UPSTREAM = URI.create("https://repo1.example/maven2/");
    private static final String JAR = "/maven/org/acme/" + RecordingObserver.MARKER + "/1.0/"
            + RecordingObserver.MARKER + "-1.0.jar";
    private static final byte[] BODY = "jar-bytes".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null);
        RecordingObserver.reset();
    }

    @AfterEach
    void tearDown() {
        RecordingObserver.reset();
    }

    @Test
    void a_fill_is_announced_as_published_and_then_as_cached_from_its_upstream() throws IOException {
        FakeExchange miss = new FakeExchange(JAR);

        new PullThroughCache(upstream()).serve(new MavenFormat(), new MavenFormat(), UPSTREAM, miss, store);

        assertThat(miss.status).isEqualTo(200);
        assertThat(RecordingObserver.notices()).as("the publish notice first, then the cached one naming the upstream")
                .containsExactly("published " + JAR, "cached " + JAR + " from " + UPSTREAM);
        ArtifactDescriptor cached = RecordingObserver.cached().getFirst();
        assertThat(cached.hash()).as("the cached notice carries the blob the fill linked")
                .isEqualTo(new Publication(store).located(JAR).orElseThrow().substring("blobs/".length()));
        assertThat(cached.coordinate()).isEqualTo("org.acme:" + RecordingObserver.MARKER);
        assertThat(cached.version()).isEqualTo("1.0");
    }

    @Test
    void a_hit_announces_nothing() throws IOException {
        new PullThroughCache(upstream()).serve(new MavenFormat(), new MavenFormat(), UPSTREAM, new FakeExchange(JAR),
                store);
        RecordingObserver.reset();

        FakeExchange hit = new FakeExchange(JAR);
        new PullThroughCache(upstream()).serve(new MavenFormat(), new MavenFormat(), UPSTREAM, hit, store);

        assertThat(hit.status).isEqualTo(200);
        assertThat(RecordingObserver.notices()).as("a hit writes and announces nothing").isEmpty();
    }

    @Test
    void a_fill_the_screen_withholds_announces_nothing() throws IOException {
        PullThroughHooks withholding = new PullThroughHooks() {
            @Override
            public ProxyFormat.Fetcher screenFetch(String path, ProxyFormat.Fetcher upstream, ArtifactStore store) {
                return (ProxyFormat.Fetcher.Buffered) (url, headers) -> Optional.empty();
            }
        };
        FakeExchange held = new FakeExchange(JAR);

        new PullThroughCache(upstream(), withholding).serve(new MavenFormat(), new MavenFormat(), UPSTREAM, held,
                store);

        assertThat(held.status).isEqualTo(404);
        assertThat(RecordingObserver.notices()).as("nothing was stored or served, so nothing is held").isEmpty();
    }

    /** The upstream: the jar, and a 404 for everything else - its checksum sibling among them, which Maven reads as
     *  an upstream that publishes none. */
    private static ProxyFormat.Fetcher upstream() {
        return (ProxyFormat.Fetcher.Buffered) (url, headers) -> url.toString().endsWith(JAR.substring("/maven/".length()))
                ? Optional.of(new ProxyFormat.Fetched(200, BODY, Map.of()))
                : Optional.of(new ProxyFormat.Fetched(404, new byte[0], Map.of()));
    }

    /** A minimal {@code GET} exchange capturing the status a serve wrote. */
    private static final class FakeExchange implements DetachedExchange {

        private final String path;
        private int status = -1;

        private FakeExchange(String path) {
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
            return OutputStream.nullOutputStream();
        }
    }
}
