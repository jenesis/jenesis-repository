package build.jenesis.repository.server.kernel.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.store.metering.MeteringArtifactStore;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The metering decorator times every store operation, but the {@code (op, outcome)} tag set is stable so each
 * {@code jenreg.store.operations} timer is resolved once and reused rather than rebuilt per call. Because routing
 * mints a fresh decorator per request through {@link MeteringArtifactStore#scope}, the memo must be shared with
 * scoped views - a per-instance map would be discarded each request and never pay off. This demonstrates, through a
 * registry that counts every timer-resolution, that repeated operations across the root and a scoped view resolve
 * the series exactly once while still recording every operation.
 */
class MeteringArtifactStoreTest {

    /** A registry that counts each {@code timer(name, tags...)} resolution the metering decorator asks for, so the
     *  test can demonstrate the lookup is memoized (one resolution for many operations) rather than repeated. */
    private static final class CountingRegistry extends SimpleMeterRegistry {

        private final AtomicInteger resolutions = new AtomicInteger();

        @Override
        public Timer timer(String name, String... tags) {
            resolutions.incrementAndGet();
            return super.timer(name, tags);
        }
    }

    @Test
    void the_timer_is_resolved_once_and_reused_across_the_root_and_its_scoped_views(@TempDir Path root)
            throws IOException {
        CountingRegistry registry = new CountingRegistry();
        ArtifactStore backend = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        ArtifactStore store = new MeteringArtifactStore(backend, registry, "filesystem");
        ArtifactStore scoped = store.scope("acme").scope("releases");

        // Twelve operations of the same op/outcome (exists on a missing key never throws, so outcome is always ok),
        // half at the root and half through the doubly-scoped view routing hands a request.
        for (int i = 0; i < 6; i++) {
            store.exists("blobs/missing-" + i);
            scoped.exists("blobs/missing-" + i);
        }

        assertThat(registry.resolutions.get())
                .as("the jenreg.store.operations{op=exists,outcome=ok} timer is resolved once, not per call, "
                        + "and the scoped view shares the same memo")
                .isEqualTo(1);
        assertThat(registry.find("jenreg.store.operations")
                .tag("op", "exists").tag("backend", "filesystem").tag("outcome", "ok").timer())
                .as("every operation, root and scoped, is recorded into the one shared series")
                .isNotNull()
                .satisfies(timer -> assertThat(timer.count()).isEqualTo(12L));
    }

    @Test
    void page_delegates_to_the_backend_and_never_falls_back_to_a_whole_namespace_list() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AtomicBoolean listed = new AtomicBoolean(false);
        ArtifactStore store = new MeteringArtifactStore(new PagingProbe(listed), registry, "filesystem");

        List<String> seen = new ArrayList<>();
        store.page("blobs", "", 100, seen::add);

        assertThat(seen).as("the backend's native bounded paging flows through the metering decorator")
                .containsExactly("blobs/a", "blobs/b");
        assertThat(listed.get())
                .as("page() must not inherit the SPI default (list() + sort), which would materialise the whole "
                        + "blobs namespace into heap and OOM the GC/walk/quota passes that page through this store")
                .isFalse();
        assertThat(registry.find("jenreg.store.operations").tag("op", "page").tag("outcome", "ok").timers())
                .as("the paged read is metered under op=page").isNotEmpty();
    }

    /** A backend that answers {@code page()} natively and flags any whole-namespace {@code list()} - so a decorator
     *  that inherited the SPI default {@code page()} (which lists then sorts) is caught reintroducing the
     *  materialisation. Every other operation is unused by the test. */
    private static final class PagingProbe implements ArtifactStore {
        @Override
        public Object identity() {
            return this;   // a standalone fake IS its own subspace
        }

        private final AtomicBoolean listed;

        private PagingProbe(AtomicBoolean listed) {
            this.listed = listed;
        }

        @Override
        public void page(String prefix, String startAfter, int limit, Consumer<String> consumer) {
            consumer.accept("blobs/a");
            consumer.accept("blobs/b");
        }

        @Override
        public List<String> list(String prefix) {
            listed.set(true);
            return List.of();
        }

        @Override public ArtifactStore scope(String tenant) { return this; }
        @Override public boolean exists(String key) { throw new UnsupportedOperationException(); }
        @Override public void read(String key, OutputStream out) { throw new UnsupportedOperationException(); }
        @Override public InputStream open(String key) { throw new UnsupportedOperationException(); }
        @Override public void write(String key, InputStream in) { throw new UnsupportedOperationException(); }
        @Override public String writeBlob(InputStream in) { throw new UnsupportedOperationException(); }
        @Override public long size(String key) { throw new UnsupportedOperationException(); }
        @Override public void delete(String key) { throw new UnsupportedOperationException(); }
        @Override public Optional<Versioned> readVersioned(String key) { throw new UnsupportedOperationException(); }
        @Override public boolean writeVersioned(String key, byte[] content, Object expected) {
            throw new UnsupportedOperationException();
        }
    
        @Override
        public Scan scan(String prefix, String startAfter, int limit, Consumer<Listed> consumer) {
            consumer.accept(new Listed("blobs/a", OptionalLong.empty(), Optional.empty()));
            return new Scan(1, 1, Optional.empty());
        }
    }

    @Test
    void scan_delegates_to_the_backend_and_is_metered_as_the_listing_request_it_is() throws IOException {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AtomicBoolean listed = new AtomicBoolean(false);
        ArtifactStore store = new MeteringArtifactStore(new PagingProbe(listed), registry, "filesystem");

        List<String> seen = new ArrayList<>();
        ArtifactStore.Scan scan = store.scan("blobs", "", 100, entry -> seen.add(entry.key()));

        assertThat(seen).containsExactly("blobs/a");
        assertThat(scan.delivered()).isEqualTo(1L);
        assertThat(listed).as("the backend's native scan flows through, never the list-and-sort fallback").isFalse();
        assertThat(registry.find("jenreg.store.operations").tag("op", "scan").tag("outcome", "ok").timers())
                .as("a scan is a listing request on every object store, and counted as one").isNotEmpty();
        assertThat(MeteringArtifactStore.writes("scan")).as("it reads").isFalse();
    }

    @Test
    void a_scoped_view_meters_under_the_same_backend_tag_as_the_root(@TempDir Path root) throws IOException {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ArtifactStore backend = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        ArtifactStore store = new MeteringArtifactStore(backend, registry, "s3");
        store.scope("acme").write("blobs/one",
                new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));

        // The scope() decorator carries the backend tag through, so a scoped write is timed under the same backend as
        // a root one - not a fresh untagged/filesystem-defaulted series.
        assertThat(registry.find("jenreg.store.operations").tag("op", "write").tag("outcome", "ok").timers())
                .as("a scoped write meters under the root's backend tag")
                .isNotEmpty()
                .allSatisfy(timer -> assertThat(timer.getId().getTag("backend")).isEqualTo("s3"));
    }
}
