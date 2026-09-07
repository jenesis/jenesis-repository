package build.jenesis.repository.store.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.StoreCache;
import build.jenesis.repository.store.StoreCacheObservability;
import build.jenesis.repository.store.testkit.FaultInjectingStore;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link StoreCache}: read-through, write-through, the time bound, {@code 0} as off, and the clear. */
class StoreCacheTest {

    @TempDir
    Path root;

    private FaultInjectingStore store;

    @BeforeEach
    void setUp() throws IOException {
        ArtifactStore filesystem = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        store = FaultInjectingStore.wrap(filesystem);
        filesystem.write("auth/k/grants", new ByteArrayInputStream("releases=read".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void a_read_is_answered_from_the_store_once_and_from_the_cache_until_the_ttl() throws Exception {
        StoreCache cache = new StoreCache("test", store, Duration.ofMillis(300));
        assertThat(cache.readVersioned("auth/k/grants")).isPresent();
        assertThat(cache.readVersioned("auth/k/grants")).isPresent();
        assertThat(cache.readVersioned("auth/absent")).as("an absent key is remembered too").isEmpty();
        assertThat(cache.readVersioned("auth/absent")).isEmpty();
        assertThat(store.calls(FaultInjectingStore.Op.READ_VERSIONED)).as("two keys, two store reads").isEqualTo(2);
        assertThat(cache.hits()).isEqualTo(2);
        assertThat(cache.misses()).isEqualTo(2);
        Thread.sleep(350);
        assertThat(cache.readVersioned("auth/k/grants")).isPresent();
        assertThat(store.calls(FaultInjectingStore.Op.READ_VERSIONED)).as("past the ttl the store is asked again").isEqualTo(3);
    }

    @Test
    void a_write_or_delete_through_the_cache_is_seen_by_the_next_read_on_this_node() throws Exception {
        StoreCache cache = new StoreCache("test", store, Duration.ofMinutes(5));
        assertThat(new String(cache.readVersioned("auth/k/grants").orElseThrow().content(), StandardCharsets.UTF_8))
                .isEqualTo("releases=read");
        cache.write("auth/k/grants", "releases=write".getBytes(StandardCharsets.UTF_8));
        assertThat(new String(cache.readVersioned("auth/k/grants").orElseThrow().content(), StandardCharsets.UTF_8))
                .as("write-through: the node's own write is what it reads next").isEqualTo("releases=write");
        cache.delete("auth/k/grants");
        assertThat(cache.readVersioned("auth/k/grants")).as("and so is its own delete").isEmpty();
        cache.write("auth/k/grants", "releases=read".getBytes(StandardCharsets.UTF_8));
        Object token = cache.readVersioned("auth/k/grants").orElseThrow().token();
        assertThat(cache.writeVersioned("auth/k/grants", "releases=admin".getBytes(StandardCharsets.UTF_8), token)).isTrue();
        assertThat(new String(cache.readVersioned("auth/k/grants").orElseThrow().content(), StandardCharsets.UTF_8))
                .as("a compare-and-set through the cache drops the entry it would otherwise contradict").isEqualTo("releases=admin");
    }

    @Test
    void a_write_past_the_cache_is_invisible_until_invalidated_or_expired_which_is_the_other_nodes_shape() throws Exception {
        StoreCache cache = new StoreCache("test", store, Duration.ofMinutes(5));
        assertThat(cache.readVersioned("auth/k/grants")).isPresent();
        store.delete("auth/k/grants");                       // another node, as far as this cache can tell
        assertThat(cache.readVersioned("auth/k/grants")).as("stale for up to the ttl").isPresent();
        cache.invalidate("auth/k/grants");
        assertThat(cache.readVersioned("auth/k/grants")).as("invalidated, the store is asked").isEmpty();
    }

    @Test
    void a_zero_ttl_is_no_cache_at_all() throws Exception {
        StoreCache cache = new StoreCache("test", store, Duration.ZERO);
        cache.readVersioned("auth/k/grants");
        cache.readVersioned("auth/k/grants");
        assertThat(store.calls(FaultInjectingStore.Op.READ_VERSIONED)).isEqualTo(2);
        assertThat(cache.hits()).isZero();
        assertThat(cache.size()).isZero();
    }

    @Test
    void the_registry_clears_every_cache_and_reports_each_one() throws Exception {
        StoreCache one = new StoreCache("one", store, Duration.ofMinutes(5));
        StoreCache two = new StoreCache("two", store, Duration.ofMinutes(5));
        one.readVersioned("auth/k/grants");
        two.readVersioned("auth/k/grants");
        two.readVersioned("auth/other");
        assertThat(StoreCache.caches()).contains(one, two);
        assertThat(StoreCache.clearAll()).as("three entries across the two caches").isGreaterThanOrEqualTo(3);
        assertThat(one.size() + two.size()).isZero();
        assertThat(two.metrics()).extracting(metric -> metric.name())
                .contains("jenreg.cache.two.hits", "jenreg.cache.two.misses", "jenreg.cache.two.entries");
        assertThat(new StoreCacheObservability().metrics()).extracting(metric -> metric.name())
                .as("the report carries the node-wide totals under fixed names, then each live cache's own")
                .contains("jenreg.cache.hits", "jenreg.cache.misses", "jenreg.cache.entries", "jenreg.cache.two.hits");
    }

    @Test
    void the_setting_reads_as_a_duration_and_refuses_what_is_not_one() {
        assertThat(StoreCache.ttl(null)).isEqualTo(StoreCache.DEFAULT_TTL);
        assertThat(StoreCache.ttl("0")).isEqualTo(Duration.ZERO);
        assertThat(StoreCache.ttl("PT30S")).isEqualTo(Duration.ofSeconds(30));
        assertThat(StoreCache.ttl("2m")).isEqualTo(Duration.ofMinutes(2));
        assertThat(StoreCache.ttl("500ms")).isEqualTo(Duration.ofMillis(500));
        assertThatThrownBy(() -> StoreCache.ttl("soon")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jenreg.cache.ttl");
    }

    @Test
    void the_shared_cache_is_one_per_store_identity_and_name_so_a_second_holder_sees_the_first_holders_write()
            throws IOException {
        StoreCache first = StoreCache.of("shared", store, Duration.ofMinutes(5));
        StoreCache second = StoreCache.of("shared", store, Duration.ofMinutes(5));
        assertThat(second).as("the same store under the same name is the same cache").isSameAs(first);
        assertThat(StoreCache.of("other", store, Duration.ofMinutes(5))).isNotSameAs(first);
        assertThat(StoreCache.of("shared", FaultInjectingStore.peer(store), Duration.ofMinutes(5)))
                .as("another node's store, of another identity, has a cache of its own").isNotSameAs(first);

        assertThat(first.readVersioned("doc")).isEmpty();
        second.write("doc", "v1".getBytes(StandardCharsets.UTF_8));
        assertThat(first.readVersioned("doc")).as("written through one holder, read fresh through the other")
                .map(versioned -> new String(versioned.content(), StandardCharsets.UTF_8)).hasValue("v1");
    }

    @Test
    void a_cache_name_that_is_not_a_signal_segment_is_refused_when_the_cache_is_made() {
        // Its counters are named jenreg.cache.<name>.*, and a name the signal grammar refuses would drop every
        // cache's counters from the report at render time; it is refused here instead, where the boot fails loudly.
        assertThatThrownBy(() -> new StoreCache("Not-A-Segment", store, Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Not-A-Segment");
    }
}
