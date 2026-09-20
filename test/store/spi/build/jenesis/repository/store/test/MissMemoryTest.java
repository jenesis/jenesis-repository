package build.jenesis.repository.store.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.MissMemoStore;
import build.jenesis.repository.store.MissMemory;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.ServableNames;
import build.jenesis.repository.store.StoreCache;
import build.jenesis.repository.store.testkit.FaultInjectingStore;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The node's memory of misses, driven through the serve probe it spares: a path read and found unpublished is
 * unpublished from memory until the ttl, a write of the key on this node forgets it, a published path is never an
 * entry, a raw store remembers nothing, and the memory is bounded whatever is probed.
 */
class MissMemoryTest {

    private static final String PATH = "/maven/org/acme/lib/1.0/lib-1.0.jar";

    /** A clock a test moves by hand, so expiry is proved without sleeping. */
    private static final class Moving extends Clock {

        private Instant now = Instant.parse("2026-09-20T12:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }
    }

    @TempDir
    Path root;

    private FaultInjectingStore counting;
    private Moving clock;
    private MissMemory memory;
    private ArtifactStore store;

    @BeforeEach
    void setUp() {
        ArtifactStore filesystem = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        counting = FaultInjectingStore.wrap(filesystem);
        clock = new Moving();
        memory = new MissMemory(Duration.ofSeconds(10), clock);
        store = MissMemoStore.over(counting, memory);
    }

    private ServableNames.Location locate() throws IOException {
        return new ServableNames(store, new Publication(store)).located(PATH);
    }

    @Test
    void a_probe_of_an_unpublished_path_reads_the_store_once_within_the_ttl() throws IOException {
        assertThat(locate().state()).isEqualTo(ServableNames.State.UNPUBLISHED);
        assertThat(locate().state()).isEqualTo(ServableNames.State.UNPUBLISHED);
        assertThat(locate().state()).isEqualTo(ServableNames.State.UNPUBLISHED);
        assertThat(counting.calls(FaultInjectingStore.Op.READ_VERSIONED))
                .as("the pointer was read once; the two probes after it were answered from memory")
                .isEqualTo(1);
        assertThat(memory.recorded()).isEqualTo(1);
        assertThat(memory.spared()).isEqualTo(2);
        assertThat(memory.size()).isEqualTo(1);
    }

    @Test
    void an_entry_expires_with_the_clock_and_the_store_is_asked_again() throws IOException {
        locate();
        clock.advance(Duration.ofSeconds(9));
        locate();
        assertThat(counting.calls(FaultInjectingStore.Op.READ_VERSIONED)).as("still remembered").isEqualTo(1);
        clock.advance(Duration.ofSeconds(2));
        locate();
        assertThat(counting.calls(FaultInjectingStore.Op.READ_VERSIONED)).as("expired, so read again").isEqualTo(2);
    }

    @Test
    void a_publish_through_the_store_is_served_at_once_on_the_node_that_made_it() throws IOException {
        assertThat(locate().state()).isEqualTo(ServableNames.State.UNPUBLISHED);
        Publication publication = new Publication(store);
        String hash = store.writeBlob(new ByteArrayInputStream("bytes".getBytes(StandardCharsets.UTF_8)));
        publication.link(PATH, hash);

        ServableNames.Location located = locate();
        assertThat(located.state())
                .as("the pointer write went through the remembering store, which forgot the key - no ttl to wait out")
                .isEqualTo(ServableNames.State.SERVABLE);
        assertThat(located.hash()).isEqualTo(hash);
    }

    @Test
    void a_published_path_is_never_remembered() throws IOException {
        String hash = store.writeBlob(new ByteArrayInputStream("bytes".getBytes(StandardCharsets.UTF_8)));
        new Publication(store).link(PATH, hash);
        assertThat(locate().state()).isEqualTo(ServableNames.State.SERVABLE);
        assertThat(locate().state()).isEqualTo(ServableNames.State.SERVABLE);
        assertThat(memory.recorded()).as("a hit is served, not remembered").isZero();
        assertThat(memory.size()).isZero();
    }

    @Test
    void a_delete_on_this_node_forgets_the_key_it_deleted() throws IOException {
        String hash = store.writeBlob(new ByteArrayInputStream("bytes".getBytes(StandardCharsets.UTF_8)));
        Publication publication = new Publication(store);
        publication.link(PATH, hash);
        publication.unpublish(PATH);
        assertThat(locate().state()).isEqualTo(ServableNames.State.UNPUBLISHED);
        publication.link(PATH, hash);
        assertThat(locate().state()).as("relinked after an unpublish, served at once").isEqualTo(ServableNames.State.SERVABLE);
    }

    @Test
    void two_scopes_of_one_store_do_not_answer_for_each_other() throws IOException {
        ArtifactStore a = store.scope("tenant-a");
        ArtifactStore b = store.scope("tenant-b");
        assertThat(new ServableNames(a, new Publication(a)).located(PATH).state()).isEqualTo(ServableNames.State.UNPUBLISHED);
        assertThat(new ServableNames(a, new Publication(a)).located(PATH).state()).isEqualTo(ServableNames.State.UNPUBLISHED);
        int afterA = counting.calls(FaultInjectingStore.Op.READ_VERSIONED);
        assertThat(new ServableNames(b, new Publication(b)).located(PATH).state()).isEqualTo(ServableNames.State.UNPUBLISHED);
        assertThat(counting.calls(FaultInjectingStore.Op.READ_VERSIONED))
                .as("the second tenant's probe is its own store's identity, so it read the store")
                .isEqualTo(afterA + 1);
    }

    @Test
    void with_the_ttl_off_every_probe_is_the_stores() throws IOException {
        ArtifactStore off = MissMemoStore.over(counting, new MissMemory(Duration.ZERO, clock));
        new ServableNames(off, new Publication(off)).located(PATH);
        new ServableNames(off, new Publication(off)).located(PATH);
        assertThat(counting.calls(FaultInjectingStore.Op.READ_VERSIONED)).isEqualTo(2);
    }

    @Test
    void a_store_nobody_decorated_remembers_nothing() throws IOException {
        new ServableNames(counting, new Publication(counting)).located(PATH);
        new ServableNames(counting, new Publication(counting)).located(PATH);
        assertThat(counting.calls(FaultInjectingStore.Op.READ_VERSIONED))
                .as("only a composition that opted in pays the window; a raw store probes as before")
                .isEqualTo(2);
        assertThat(MissMemoStore.memory(counting)).isEmpty();
    }

    @Test
    void the_memory_is_bounded_whatever_is_probed() {
        MissMemory bounded = new MissMemory(Duration.ofHours(1), clock);
        for (int i = 0; i < 101_000; i++) {
            bounded.remember(store, "publish/probe/" + i);
        }
        assertThat(bounded.size())
                .as("a client probing more names than the bound evicts the oldest, never grows the heap")
                .isLessThanOrEqualTo(100_000);
        assertThat(bounded.recorded()).isEqualTo(101_000);
    }

    @Test
    void the_node_memory_is_dropped_with_the_caches() throws IOException {
        MissMemory.reset();
        try {
            MissMemory node = MissMemory.node();
            node.remember(store, "publish/anything");
            assertThat(node.size()).isEqualTo(1);
            assertThat(StoreCache.clearAll()).isGreaterThanOrEqualTo(1);
            assertThat(node.size()).as("POST /api/admin/caches/clear drops the memory of misses too").isZero();
        } finally {
            MissMemory.reset();
        }
    }
}
