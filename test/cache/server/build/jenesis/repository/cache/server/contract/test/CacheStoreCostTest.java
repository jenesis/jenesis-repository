package build.jenesis.repository.cache.server.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.cache.server.Cache;
import build.jenesis.repository.cache.server.CacheController;
import build.jenesis.repository.cache.storage.delegating.DelegatingCacheStorage;
import build.jenesis.repository.scope.Scopes;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.settings.StoredSettings;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.metering.MeteringArtifactStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What a cache request costs the store it delegates into, counted on the real request path - {@link CacheController}
 * over a {@link Cache} over the delegating storage - through the metering store a node counts its operations with.
 *
 * <p>On an object store a write bills about twelve reads, and a warm build is thousands of hits, so these are the
 * figures a deployment's bill is made of: a store reads once and writes once, a hit on the node that stored it reads
 * once and writes nothing, a hit on a node that never saw the entry reads three times once and once after, and a
 * miss reads once. The first store into a project no write has typed also reads and writes its marker, once. Each is counted after the credential and the project's policy have been read, which a node holds
 * for {@code jenrepo.cache.ttl}, so the figure is the steady state a build meets rather than a first request's.
 */
class CacheStoreCostTest {

    private static final String KEY = Authorization.mint("acme");
    private static final String PROJECT = "demo";
    private static final String STEP = "0123456789abcdef0123456789abcdef";

    @TempDir
    private Path root;

    private ArtifactStore counted;
    private int entries;

    @BeforeEach
    void arrange() throws IOException {
        ArtifactStore store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null);
        counted = new MeteringArtifactStore(store, new SimpleMeterRegistry(), "filesystem");
        Authorization.enforcing(store).setGrant("acme", Authorization.hash(KEY), "*", "cache:read,cache:write");
    }

    @Test
    void a_store_reads_once_and_writes_once() throws IOException {
        CacheController node = node();
        warm(node);
        put(node, inputs(), "payload");
        String inputs = inputs();
        Cost cost = Cost.of(() -> assertThat(put(node, inputs, "payload")).isEqualTo(201));
        assertThat(cost).as("the existence probe and the entry").isEqualTo(new Cost(1, 1));
    }

    @Test
    void the_first_store_into_an_untyped_project_types_it_once() throws IOException {
        CacheController node = node();
        warm(node);
        String inputs = inputs();
        Cost first = Cost.of(() -> assertThat(put(node, inputs, "payload")).isEqualTo(201));
        assertThat(first).as("the store, and the project's marker read and written once")
                .isEqualTo(new Cost(3, 2));
    }

    @Test
    void a_repeated_store_reads_once_and_writes_nothing() throws IOException {
        CacheController node = node();
        warm(node);
        String inputs = inputs();
        put(node, inputs, "payload");
        Cost cost = Cost.of(() -> assertThat(put(node, inputs, "payload")).isEqualTo(204));
        assertThat(cost).as("the existence probe declines it").isEqualTo(new Cost(1, 0));
    }

    @Test
    void a_hit_on_the_storing_node_reads_once_and_writes_nothing() throws IOException {
        CacheController node = node();
        warm(node);
        String inputs = inputs();
        put(node, inputs, "payload");
        for (int build = 0; build < 3; build++) {
            Cost cost = Cost.of(() -> assertThat(get(node, inputs)).isEqualTo("payload"));
            assertThat(cost).as("warm build %d: the read, and no recency written inside the window", build)
                    .isEqualTo(new Cost(1, 0));
        }
    }

    @Test
    void a_head_on_a_hit_reads_once_and_writes_nothing() throws IOException {
        CacheController node = node();
        warm(node);
        String inputs = inputs();
        put(node, inputs, "payload");
        Cost cost = Cost.of(() -> assertThat(head(node, inputs)).isEqualTo(200));
        assertThat(cost).as("a HEAD has no body, so it pays the existence probe").isEqualTo(new Cost(1, 0));
    }

    @Test
    void a_miss_reads_once_and_writes_nothing() throws IOException {
        CacheController node = node();
        warm(node);
        Cost cost = Cost.of(() -> assertThat(get(node, inputs())).isNull());
        assertThat(cost).as("the read that finds nothing, and no stamp for an entry that is not there")
                .isEqualTo(new Cost(1, 0));
    }

    @Test
    void a_hit_on_a_node_that_never_saw_the_entry_asks_its_recency_once() throws IOException {
        CacheController storing = node();
        warm(storing);
        String inputs = inputs();
        put(storing, inputs, "payload");
        CacheController other = node();
        warm(other);
        Cost first = Cost.of(() -> assertThat(get(other, inputs)).isEqualTo("payload"));
        assertThat(first).as("the read, the stamps' scan and the entry's own listed time - and no stamp, since the "
                + "entry is younger than the window").isEqualTo(new Cost(3, 0));
        Cost second = Cost.of(() -> assertThat(get(other, inputs)).isEqualTo("payload"));
        assertThat(second).as("after which this node remembers it").isEqualTo(new Cost(1, 0));
    }

    /** A node: the controller over a cache of its own over the counted store, reading project policies from it. */
    private CacheController node() {
        Cache cache = new Cache(new DelegatingCacheStorage(counted.scope(Scopes.SYSTEM).scope(Scopes.CACHE)),
                Authorization.enforcing(counted), 1L << 31, 256, null, 0, 0, "default", false, null, "default",
                new SimpleMeterRegistry())
                .policies((tenant, project) -> StoredSettings.projectChain(counted, tenant, project));
        return new CacheController(cache);
    }

    /** One miss, so what the node holds for a while - the credential, the project's policy - is held. */
    private void warm(CacheController node) throws IOException {
        get(node, inputs());
    }

    /** A fresh inputs hash, so no two requests of a test share an entry by accident. */
    private String inputs() {
        return "%032x".formatted(++entries);
    }

    private static String get(CacheController node, String inputs) throws IOException {
        HttpServletRequest request = request("GET", inputs, null);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int[] status = {200};
        HttpServletResponse response = response(status, body);
        node.dispatch(request, response);
        return status[0] == 200 ? body.toString(StandardCharsets.UTF_8) : null;
    }

    private static int head(CacheController node, String inputs) throws IOException {
        int[] status = {200};
        node.dispatch(request("HEAD", inputs, null), response(status, new ByteArrayOutputStream()));
        return status[0];
    }

    private static int put(CacheController node, String inputs, String content) throws IOException {
        int[] status = {200};
        node.dispatch(request("PUT", inputs, content.getBytes(StandardCharsets.UTF_8)),
                response(status, new ByteArrayOutputStream()));
        return status[0];
    }

    private static HttpServletRequest request(String method, String inputs, byte[] body) throws IOException {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn(method);
        when(request.getRequestURI()).thenReturn("/build/acme/" + STEP + "/" + inputs);
        when(request.getContextPath()).thenReturn("");
        when(request.getHeader("Jenesis-Cache-Key")).thenReturn(KEY);
        when(request.getHeader("Jenesis-Cache-Project")).thenReturn(PROJECT);
        if (body != null) {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            when(request.getContentLengthLong()).thenReturn((long) body.length);
            when(request.getInputStream()).thenReturn(new ServletInputStream() {
                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(byte[] buffer, int offset, int length) {
                    return in.read(buffer, offset, length);
                }

                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                }
            });
        }
        return request;
    }

    private static HttpServletResponse response(int[] status, ByteArrayOutputStream body) throws IOException {
        HttpServletResponse response = mock(HttpServletResponse.class);
        doAnswer(call -> status[0] = call.getArgument(0)).when(response).setStatus(anyInt());
        doAnswer(call -> {
            body.reset();
            return null;
        }).when(response).reset();
        when(response.getOutputStream()).thenReturn(new ServletOutputStream() {
            @Override
            public void write(int b) {
                body.write(b);
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setWriteListener(WriteListener listener) {
            }
        });
        return response;
    }

    /** The reads and writes a call made, as the metering store counts and classes them. */
    private record Cost(long reads, long writes) {

        static Cost of(IoAction action) throws IOException {
            Cost before = now();
            action.run();
            Cost after = now();
            return new Cost(after.reads - before.reads, after.writes - before.writes);
        }

        private static Cost now() {
            long reads = 0;
            long writes = 0;
            for (Map.Entry<String, Long> op : MeteringArtifactStore.operations().entrySet()) {
                if (MeteringArtifactStore.writes(op.getKey())) {
                    writes += op.getValue();
                } else {
                    reads += op.getValue();
                }
            }
            return new Cost(reads, writes);
        }
    }

    @FunctionalInterface
    private interface IoAction {
        void run() throws IOException;
    }
}
