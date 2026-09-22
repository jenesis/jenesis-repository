package build.jenesis.repository.ui.store.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.cache.storage.CacheStorage;
import build.jenesis.repository.cache.storage.Pages;
import build.jenesis.repository.walk.Traversal;
import build.jenesis.repository.ui.store.Eviction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cache eviction policy deletes the right entries: a size cap removes least-recently-used entries first (or
 * most-recently-used when asked) until the total is within the limit, a TTL sweep removes only entries older than the
 * window, and the free-space check reads "below target" by absolute bytes or by percent without integer overflow.
 * These are the data-deleting decisions, exercised over a stub {@link CacheStorage} that records what it was asked to
 * delete.
 */
public class EvictionTest {

    @Test
    public void a_size_cap_deletes_least_recently_used_entries_first() {
        FakeStorage storage = new FakeStorage(List.of(
                new CacheStorage.Stored(100, Instant.ofEpochSecond(3), "new"),
                new CacheStorage.Stored(100, Instant.ofEpochSecond(1), "old"),
                new CacheStorage.Stored(100, Instant.ofEpochSecond(2), "mid")), 0, 0);
        Eviction.Result result = Eviction.enforceSizeCap(storage, "p", 150, true);
        assertThat(storage.deleted).containsExactlyInAnyOrder("old", "mid");
        assertThat(result.entriesDeleted()).isEqualTo(2);
        assertThat(result.bytesFreed()).isEqualTo(200);
    }

    @Test
    public void a_size_cap_can_delete_most_recently_used_entries_first() {
        FakeStorage storage = new FakeStorage(List.of(
                new CacheStorage.Stored(100, Instant.ofEpochSecond(3), "new"),
                new CacheStorage.Stored(100, Instant.ofEpochSecond(1), "old"),
                new CacheStorage.Stored(100, Instant.ofEpochSecond(2), "mid")), 0, 0);
        Eviction.enforceSizeCap(storage, "p", 150, false);
        assertThat(storage.deleted).containsExactlyInAnyOrder("new", "mid");
    }

    @Test
    public void a_ttl_sweep_deletes_only_entries_older_than_the_window() {
        FakeStorage storage = new FakeStorage(List.of(
                new CacheStorage.Stored(10, Instant.now().minus(Duration.ofHours(2)), "stale"),
                new CacheStorage.Stored(10, Instant.now().minus(Duration.ofMinutes(5)), "recent")), 0, 0);
        Eviction.expireTtl(storage, "p", Duration.ofHours(1));
        assertThat(storage.deleted).containsExactly("stale");
    }

    @Test
    public void coldest_selects_the_k_coldest_in_one_bounded_pass_without_materialising_the_whole_set() {
        int total = 100_000, k = 100;
        AtomicInteger yielded = new AtomicInteger();
        // A lazily-generated stream of entries (recency = index, so index 0 is the coldest), never a materialised list:
        // were the selection to hold or sort the whole set this would be O(total) heap, but the bounded max-heap keeps
        // only k - the size bound that keeps a low-disk reclaim from an enumerate-and-sort OOM.
        Iterable<CacheStorage.Stored> entries = () -> IntStream.range(0, total)
                .mapToObj(index -> {
                    yielded.incrementAndGet();
                    return new CacheStorage.Stored(1, Instant.ofEpochSecond(index), index);
                })
                .iterator();

        List<CacheStorage.Stored> coldest = Eviction.coldest(entries, k);

        assertThat(coldest).as("only k are ever retained, however large the source").hasSize(k);
        assertThat(coldest).as("returned coldest-first")
                .isSortedAccordingTo(Comparator.comparing(CacheStorage.Stored::recency));
        assertThat(coldest.stream().map(stored -> (int) stored.recency().getEpochSecond()).toList())
                .as("exactly the k coldest indices").containsExactlyElementsOf(IntStream.range(0, k).boxed().toList());
        assertThat(yielded.get()).as("the source is streamed exactly once, never snapshotted").isEqualTo(total);
    }

    @Test
    public void reclaim_drops_the_globally_coldest_first_and_never_snapshots_the_whole_store() {
        ReclaimStorage storage = new ReclaimStorage();
        // Entries spread across three projects; usable space rises 100 bytes per delete, the 350-byte target is met
        // once the three coldest are gone. The globally-coldest-first order must hold across projects.
        storage.put("p1", new CacheStorage.Stored(100, Instant.ofEpochSecond(1), "a"));
        storage.put("p1", new CacheStorage.Stored(100, Instant.ofEpochSecond(4), "d"));
        storage.put("p2", new CacheStorage.Stored(100, Instant.ofEpochSecond(2), "b"));
        storage.put("p2", new CacheStorage.Stored(100, Instant.ofEpochSecond(5), "e"));
        storage.put("p3", new CacheStorage.Stored(100, Instant.ofEpochSecond(3), "c"));
        storage.put("p3", new CacheStorage.Stored(100, Instant.ofEpochSecond(6), "f"));

        Eviction.Result result = Eviction.reclaim(storage, 350, 0);

        assertThat(storage.deleted).as("the three coldest, oldest-first across projects, until the target is met")
                .containsExactly("a", "b", "c");
        assertThat(result.entriesDeleted()).isEqualTo(3);
        assertThat(result.bytesFreed()).isEqualTo(300);
        assertThat(storage.largestBound)
                .as("the reclaim streams per project through bounded pages and never asks a backend for the whole "
                        + "store in one answer - the SPI no longer offers a whole-store sweep to ask for, and the "
                        + "per-project enumeration it drives is bounded by the SPI's own page width")
                .isLessThanOrEqualTo(CacheStorage.PAGE);
    }

    @Test
    public void the_free_space_check_reads_below_target_without_overflow() {
        assertThat(Eviction.low(new FakeStorage(List.of(), 10, 100), 0, 20))
                .as("10% free is below the 20% target").isTrue();
        assertThat(Eviction.low(new FakeStorage(List.of(), 30, 100), 0, 20))
                .as("30% free is above the 20% target").isFalse();
        assertThat(Eviction.low(new FakeStorage(List.of(), 40, 100), 50, 0))
                .as("40 bytes free is below the 50 byte target").isTrue();
    }

    /** A per-project stub whose free space rises as entries are deleted, so {@code low()} clears mid-sweep, and which
     *  records the largest per-call bound the reclaim ever asked it for - the reclaim must never ask for the store. */
    private static final class ReclaimStorage implements CacheStorage {

        private final Map<String, List<Stored>> byProject = new LinkedHashMap<>();
        private final List<Object> deleted = new ArrayList<>();
        private long freed;
        private int largestBound;

        private void put(String project, Stored entry) {
            byProject.computeIfAbsent(project, _ -> new ArrayList<>()).add(entry);
        }

        @Override
        public Traversal.Result projects(String cursor, int limit, Consumer<String> names) {
            largestBound = Math.max(largestBound, Pages.limit(limit));
            return Pages.names("", containers(byProject.keySet(), Pages.child("", cursor), limit), limit, 1, names);
        }

        @Override
        public Traversal.Result entries(String project, String cursor, int limit, Consumer<Stored> entries) {
            largestBound = Math.max(largestBound, Pages.limit(limit));
            return Pages.entries(project, stored(byProject.getOrDefault(project, List.of()),
                    Pages.entry(project, cursor), limit), limit, 1, entries);
        }

        @Override
        public void delete(Stored entry) {
            byProject.values().forEach(entries -> entries.remove(entry));
            deleted.add(entry.token());
            freed += entry.size();
        }

        @Override
        public long usableSpace() {
            return 100 + freed;                     // starts below the 350-byte target, rises 100 per delete
        }

        @Override
        public long totalSpace() {
            return 1_000_000;
        }

        @Override
        public CacheStorage scope(String tenant) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean projectExists(String project) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Properties readConfig(String project, String file) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object configVersion(String project) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean exists(Entry entry) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<CacheStorage.Recency> recency(Entry entry) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void stamp(Entry entry, Instant at, Instant previous) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void read(Entry entry, OutputStream out) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void store(Entry entry, InputStream in) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void createProject(String project) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void writeConfig(String project, String file, Properties properties) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Properties readFile(String path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void writeFile(String path, Properties properties) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean writeFileVersioned(String path, Properties properties, Object expected) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object fileVersion(String path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Traversal.Result listDir(String prefix, String cursor, int limit, Consumer<String> names) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteDir(String path) {
            throw new UnsupportedOperationException();
        }
    }

    /** One page of container names past {@code after}, in the SPI's container-key order, with the one extra name that
     *  tells {@link Pages#names} whether the container is drained. */
    private static List<String> containers(Collection<String> all, String after, int limit) {
        return all.stream()
                .filter(name -> Pages.beyond(name, after))
                .sorted(Comparator.comparing(Pages::container))
                .limit(limit + 1L)
                .toList();
    }

    /** The same, for entries: keyed by the stub's own token, which stands in for the entry's store key. */
    private static SequencedMap<String, CacheStorage.Stored> stored(List<CacheStorage.Stored> all, String after,
                                                                    int limit) {
        SequencedMap<String, CacheStorage.Stored> page = new TreeMap<>();
        for (CacheStorage.Stored entry : all) {
            String key = String.valueOf(entry.token());
            if (key.compareTo(after) > 0) {
                page.put(key, entry);
                if (page.size() > limit + 1) {
                    page.pollLastEntry();
                }
            }
        }
        return page;
    }

    private static final class FakeStorage implements CacheStorage {

        private final List<Stored> entries;
        private final long usable;
        private final long total;
        private final List<Object> deleted = new ArrayList<>();

        private FakeStorage(List<Stored> entries, long usable, long total) {
            this.entries = entries;
            this.usable = usable;
            this.total = total;
        }

        @Override
        public Traversal.Result entries(String project, String cursor, int limit, Consumer<Stored> sink) {
            return Pages.entries(project, stored(entries, Pages.entry(project, cursor), limit), limit, 1, sink);
        }

        @Override
        public void delete(Stored entry) {
            deleted.add(entry.token());
        }

        @Override
        public long usableSpace() {
            return usable;
        }

        @Override
        public long totalSpace() {
            return total;
        }

        @Override
        public CacheStorage scope(String tenant) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean projectExists(String project) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Properties readConfig(String project, String file) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object configVersion(String project) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean exists(Entry entry) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<CacheStorage.Recency> recency(Entry entry) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void stamp(Entry entry, Instant at, Instant previous) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void read(Entry entry, OutputStream out) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void store(Entry entry, InputStream in) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Traversal.Result projects(String cursor, int limit, Consumer<String> names) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void createProject(String project) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void writeConfig(String project, String file, Properties properties) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Properties readFile(String path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void writeFile(String path, Properties properties) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean writeFileVersioned(String path, Properties properties, Object expected) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object fileVersion(String path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Traversal.Result listDir(String prefix, String cursor, int limit, Consumer<String> names) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteDir(String path) {
            throw new UnsupportedOperationException();
        }
    }
}
