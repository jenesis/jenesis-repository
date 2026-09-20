package build.jenesis.repository.store.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.DocumentMemory;
import build.jenesis.repository.store.MissMemory;
import build.jenesis.repository.store.NodeMemoStore;
import build.jenesis.repository.store.StoreCache;
import build.jenesis.repository.store.StoredListing;
import build.jenesis.repository.store.testkit.FaultInjectingStore;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The node's memory of listings, driven through the store faces a listing read takes: a document opened twice is
 * read from the store once within the ttl, its existence and size answer from memory, a write of the key on this
 * node forgets it, a document past the cap streams uncached, a key outside the listing family is untouched, the
 * memory is bounded by bytes, and a raw store reads as before.
 */
class DocumentMemoryTest {

    private static final String KEY = StoredListing.key("npm/@acme/widget");

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
    private DocumentMemory memory;
    private ArtifactStore store;

    @BeforeEach
    void setUp() throws IOException {
        ArtifactStore filesystem = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        counting = FaultInjectingStore.wrap(filesystem);
        clock = new Moving();
        memory = new DocumentMemory(Duration.ofSeconds(30), clock);
        store = NodeMemoStore.over(counting, new MissMemory(Duration.ZERO, clock), memory);
        filesystem.write(KEY, new ByteArrayInputStream(document("1.0.0")));
    }

    private static byte[] document(String version) {
        return ("{\"name\":\"@acme/widget\",\"versions\":{\"" + version + "\":{}}}").getBytes(StandardCharsets.UTF_8);
    }

    private byte[] open(ArtifactStore over, String key) throws IOException {
        try (InputStream in = over.open(key)) {
            return in.readAllBytes();
        }
    }

    @Test
    void a_listing_opened_twice_is_read_from_the_store_once_within_the_ttl() throws IOException {
        assertThat(open(store, KEY)).isEqualTo(document("1.0.0"));
        assertThat(open(store, KEY)).isEqualTo(document("1.0.0"));
        ByteArrayOutputStream copied = new ByteArrayOutputStream();
        store.read(KEY, copied);
        assertThat(copied.toByteArray()).isEqualTo(document("1.0.0"));
        assertThat(counting.calls(FaultInjectingStore.Op.OPEN)).as("one store open for three reads").isEqualTo(1);
        assertThat(memory.hits()).isEqualTo(2);
        assertThat(memory.misses()).isEqualTo(1);
        assertThat(memory.bytes()).isEqualTo(document("1.0.0").length);
    }

    @Test
    void a_remembered_listing_exists_and_has_its_size_without_the_store() throws IOException {
        open(store, KEY);
        assertThat(store.exists(KEY)).isTrue();
        assertThat(store.size(KEY)).isEqualTo(document("1.0.0").length);
        assertThat(counting.calls(FaultInjectingStore.Op.EXISTS)).as("the probe a listing read pays first").isZero();
        assertThat(counting.calls(FaultInjectingStore.Op.SIZE)).isZero();
    }

    @Test
    void a_write_through_the_store_serves_the_new_document_at_once_on_this_node() throws IOException {
        open(store, KEY);
        store.write(KEY, new ByteArrayInputStream(document("1.1.0")));
        assertThat(open(store, KEY)).as("the write forgot the key; no ttl to wait out").isEqualTo(document("1.1.0"));
        store.delete(KEY);
        assertThat(store.exists(KEY)).as("and a delete forgets it too").isFalse();
    }

    @Test
    void an_entry_expires_with_the_clock() throws IOException {
        open(store, KEY);
        clock.advance(Duration.ofSeconds(29));
        open(store, KEY);
        assertThat(counting.calls(FaultInjectingStore.Op.OPEN)).isEqualTo(1);
        clock.advance(Duration.ofSeconds(2));
        open(store, KEY);
        assertThat(counting.calls(FaultInjectingStore.Op.OPEN)).as("expired, so read again").isEqualTo(2);
    }

    @Test
    void a_document_past_the_cap_streams_whole_and_is_not_kept() throws IOException {
        byte[] large = new byte[(1 << 20) + 17];
        Arrays.fill(large, (byte) 'x');
        String key = StoredListing.key("maven/org/acme/huge");
        counting.write(key, new ByteArrayInputStream(large));
        assertThat(open(store, key)).as("every byte, in order, through the cap").isEqualTo(large);
        assertThat(open(store, key)).isEqualTo(large);
        assertThat(counting.calls(FaultInjectingStore.Op.OPEN)).as("never remembered, so read both times").isEqualTo(2);
        assertThat(memory.size()).isZero();
    }

    @Test
    void a_key_outside_the_listing_family_is_never_remembered() throws IOException {
        String pointer = "publish/maven/org/acme/lib/1.0/lib-1.0.jar";
        counting.write(pointer, new ByteArrayInputStream("hash".getBytes(StandardCharsets.UTF_8)));
        open(store, pointer);
        open(store, pointer);
        assertThat(counting.calls(FaultInjectingStore.Op.OPEN)).as("a pointer carries the hold flag; never a copy").isEqualTo(2);
        assertThat(memory.size()).isZero();
    }

    @Test
    void with_the_ttl_off_every_read_is_the_stores() throws IOException {
        ArtifactStore off = NodeMemoStore.over(counting, new MissMemory(Duration.ZERO, clock),
                new DocumentMemory(Duration.ZERO, clock));
        open(off, KEY);
        open(off, KEY);
        assertThat(counting.calls(FaultInjectingStore.Op.OPEN)).isEqualTo(2);
    }

    @Test
    void a_store_nobody_decorated_reads_as_before() throws IOException {
        open(counting, KEY);
        open(counting, KEY);
        assertThat(counting.calls(FaultInjectingStore.Op.OPEN)).isEqualTo(2);
        assertThat(NodeMemoStore.documents(counting)).isEmpty();
    }

    @Test
    void the_memory_is_bounded_by_bytes() {
        DocumentMemory bounded = new DocumentMemory(Duration.ofHours(1), clock);
        byte[] half = new byte[1 << 19];
        for (int i = 0; i < 200; i++) {
            bounded.put(store, StoredListing.key("bulk/" + i), half);
        }
        assertThat(bounded.bytes())
                .as("two hundred half-megabyte listings are a hundred megabytes; the memory holds sixty-four")
                .isLessThanOrEqualTo(64L << 20);
    }

    @Test
    void the_node_memory_is_dropped_with_the_caches() {
        DocumentMemory.reset();
        MissMemory.reset();
        try {
            DocumentMemory node = DocumentMemory.node();
            node.put(store, KEY, document("1.0.0"));
            assertThat(node.size()).isEqualTo(1);
            assertThat(StoreCache.clearAll()).isGreaterThanOrEqualTo(1);
            assertThat(node.size()).isZero();
        } finally {
            DocumentMemory.reset();
            MissMemory.reset();
        }
    }

    @Test
    void the_default_is_thirty_seconds_and_a_value_is_read_as_the_store_cache_reads_one() {
        assertThat(DocumentMemory.ttl(null)).isEqualTo(Duration.ofSeconds(30));
        assertThat(DocumentMemory.ttl("")).isEqualTo(DocumentMemory.DEFAULT_TTL);
        assertThat(DocumentMemory.ttl("0")).isZero();
        assertThat(DocumentMemory.ttl("2m")).isEqualTo(Duration.ofMinutes(2));
        assertThatThrownBy(() -> DocumentMemory.ttl("later")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jenreg.cache.document-ttl");
    }
}
