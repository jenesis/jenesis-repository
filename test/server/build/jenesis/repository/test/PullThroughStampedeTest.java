package build.jenesis.repository.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.format.DetachedExchange;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.server.PullThroughCache;
import build.jenesis.repository.server.PullThroughHooks;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import io.micrometer.observation.ObservationRegistry;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrent readers of one artifact nothing has cached yet make one upstream request between them: the first reader
 * fills the cache while the rest wait on its fill and are then served locally. An upstream rate-limits per address and
 * one node is one address, so a stampede that reached the upstream once per reader is the amplification that gets a
 * deployment throttled.
 *
 * <p>The upstream answers only once every reader has missed locally and is about to fetch, so each reader is past the
 * local-first leg while the first fill is still in flight - the one ordering in which coalescing is the only thing
 * standing between the readers and the upstream.
 */
public class PullThroughStampedeTest {

    private static final int READERS = 16;
    private static final URI UPSTREAM = URI.create("https://upstream.test/");
    private static final String PATH = "/stampede/artifact-1.0.bin";
    private static final byte[] BODY = "the artifact".getBytes(StandardCharsets.UTF_8);

    /** How long the upstream waits, once every reader has reached the fetch, for the last of them to join the fill
     *  in flight: the gap is a few instructions, so this is room for a saturated machine rather than for the work. */
    private static final Duration JOINING = Duration.ofMillis(500);

    @TempDir
    Path root;

    @Test
    void concurrent_readers_of_one_cold_artifact_make_one_upstream_fetch() throws Exception {
        ArtifactStore store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null);
        Upstream format = new Upstream();
        CountDownLatch arrived = new CountDownLatch(READERS);
        // Every reader asks whether the path is held between missing locally and joining the fill, so this is where
        // its arrival at the fetch is counted.
        PullThroughCache.Withheld counting = (_, _) -> {
            arrived.countDown();
            return false;
        };
        format.before = () -> {
            assertThat(arrived.await(30, TimeUnit.SECONDS)).as("every reader missed locally").isTrue();
            Thread.sleep(JOINING);
            return null;
        };
        PullThroughCache cache = new PullThroughCache(format.fetcher, ObservationRegistry.NOOP, PullThroughHooks.NONE,
                counting);

        List<Exchange> exchanges = new ArrayList<>();
        try (ExecutorService readers = Executors.newFixedThreadPool(READERS)) {
            List<Future<?>> reads = new ArrayList<>();
            for (int i = 0; i < READERS; i++) {
                Exchange exchange = new Exchange();
                exchanges.add(exchange);
                reads.add(readers.submit(() -> {
                    cache.serve(format, format, UPSTREAM, exchange, store);
                    return null;
                }));
            }
            for (Future<?> read : reads) {
                read.get(60, TimeUnit.SECONDS);
            }
        }

        assertThat(exchanges).as("every reader is served the artifact")
                .allSatisfy(exchange -> {
                    assertThat(exchange.status).isEqualTo(200);
                    assertThat(exchange.body).isEqualTo(BODY);
                });
        assertThat(format.fetches.get()).as("%d readers asked at once and the upstream was asked once", READERS)
                .isEqualTo(1);
    }

    /** A format that is its own proxy: a local map answers hits, and a miss fetches from the upstream, after
     *  {@link #before}, and keeps what it fetched. */
    private static final class Upstream implements RepositoryFormat, ProxyFormat {

        private final Map<String, byte[]> local = new ConcurrentHashMap<>();
        private final AtomicInteger fetches = new AtomicInteger();
        private volatile Callable<?> before = () -> null;

        private final ProxyFormat.Fetcher.Buffered fetcher = (url, headers) -> {
            fetches.incrementAndGet();
            try {
                before.call();
            } catch (Exception interrupted) {
                throw new IOException(interrupted);
            }
            return Optional.of(url.equals(UPSTREAM.resolve(PATH.substring(1)))
                    ? new ProxyFormat.Fetched(200, BODY, Map.of())
                    : new ProxyFormat.Fetched(404, new byte[0], Map.of()));
        };

        @Override
        public String name() {
            return "stampede";
        }

        @Override
        public boolean handles(String path) {
            return path.startsWith("/stampede/");
        }

        @Override
        public void serve(FormatExchange exchange, ArtifactStore store) throws IOException {
            byte[] body = local.get(exchange.path());
            if (body == null) {
                exchange.respond(404);
                return;
            }
            exchange.respond(200, body);
        }

        @Override
        public boolean proxy(FormatExchange exchange, ArtifactStore store, URI base, ProxyFormat.Fetcher fetcher)
                throws IOException {
            Optional<ProxyFormat.Fetched> fetched =
                    fetcher.fetch(base.resolve(exchange.path().substring(1)), Map.of());
            if (fetched.isEmpty() || fetched.get().status() != 200) {
                return false;
            }
            local.put(exchange.path(), fetched.get().body());
            exchange.respond(200, fetched.get().body());
            return true;
        }

        @Override
        public boolean mergesUpstream(FormatExchange exchange) {
            return false;
        }
    }

    /** A {@code GET} of {@link #PATH}, keeping the status and body a serve wrote. */
    private static final class Exchange implements DetachedExchange {

        private volatile int status = -1;
        private volatile byte[] body = new byte[0];

        @Override
        public String method() {
            return "GET";
        }

        @Override
        public String path() {
            return PATH;
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
            this.status = status;
            return new ByteArrayOutputStream() {
                @Override
                public void close() {
                    body = toByteArray();
                }
            };
        }
    }
}
