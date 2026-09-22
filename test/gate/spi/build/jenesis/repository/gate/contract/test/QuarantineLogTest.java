package build.jenesis.repository.gate.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.Verdict;
import build.jenesis.repository.gate.QuarantineLog;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The durable quarantine ledger over a real filesystem artifact store: a REJECT/quarantine decision is recorded and
 * reads back with its reasons, a re-record of the same decision is idempotent (no duplicate row), the ledger survives a
 * store reload (it is store-backed, not in-memory), the latest-verdict-by-path index tracks the newest decision without
 * regressing on an out-of-order write, the review queue is keyed off the live hold pointers (so a hold whose log row
 * never landed still surfaces), retention prunes by age and count, and a discarded path's rows are reaped.
 */
class QuarantineLogTest {

    private static final Instant T1 = Instant.parse("2026-07-01T00:00:00Z");
    private static final Instant T2 = Instant.parse("2026-07-02T00:00:00Z");

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeEach
    void setUp() {
        store = resolve();
    }

    private ArtifactStore resolve() {
        return ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
    }

    @Test
    void a_decision_is_durably_recorded_and_reads_back() throws IOException {
        QuarantineLog log = new QuarantineLog(store);
        log.record(T1, "/maven/org/gnu/tool/1.0/tool-1.0.pom", "org.gnu:tool:1.0", Verdict.REJECT,
                List.of("Disallowed license GPL-3.0"));

        assertThat(log.events()).singleElement().satisfies(event -> {
            assertThat(event.verdict()).isEqualTo(Verdict.REJECT);
            assertThat(event.coordinate()).isEqualTo("org.gnu:tool:1.0");
            assertThat(event.path()).isEqualTo("/maven/org/gnu/tool/1.0/tool-1.0.pom");
            assertThat(event.reasons()).containsExactly("Disallowed license GPL-3.0");
        });
        assertThat(log.latest("/maven/org/gnu/tool/1.0/tool-1.0.pom"))
                .as("the latest-verdict index answers a single-path lookup")
                .get().extracting(QuarantineLog.Event::verdict).isEqualTo(Verdict.REJECT);
    }

    @Test
    void a_re_record_of_the_same_decision_is_idempotent() throws IOException {
        QuarantineLog log = new QuarantineLog(store);
        String path = "/npm/stealer/-/stealer-9.9.9.tgz";
        log.record(T1, path, "stealer:9.9.9", Verdict.QUARANTINE, List.of("Malicious package"));
        log.record(T1, path, "stealer:9.9.9", Verdict.QUARANTINE, List.of("Malicious package"));

        assertThat(log.events()).as("the same decision recorded twice is one row, not two").hasSize(1);
    }

    @Test
    void two_distinct_paths_withheld_in_the_same_millisecond_each_keep_their_own_row() throws IOException {
        // Regression guard (7d9140f): the event object was named "<millis>-<hashCode(path)>", so two DISTINCT paths
        // withheld in the same millisecond whose 32-bit String hashCodes collided overwrote each other's audit row.
        // These two paths are distinct but share a hashCode (the classic "Aa"/"BB" equal-hash pair, same length,
        // differing only in that adjacent pair), so under the old naming they mapped to one object and one survived;
        // the SHA-256 path digest keeps them apart. The instant is fixed, so both names share the same millis prefix.
        QuarantineLog log = new QuarantineLog(store);
        String a = "/maven/org/x/1.0/x-Aa.jar";
        String b = "/maven/org/x/1.0/x-BB.jar";
        assertThat(a.hashCode()).as("the guard's premise: a hashCode collision on two distinct paths")
                .isEqualTo(b.hashCode());
        assertThat(a).isNotEqualTo(b);

        log.record(T1, a, "org.x:aa:1.0", Verdict.QUARANTINE, List.of("held a"));
        log.record(T1, b, "org.x:bb:1.0", Verdict.QUARANTINE, List.of("held b"));

        assertThat(log.events()).as("both same-millisecond decisions survive as their own audit row")
                .extracting(QuarantineLog.Event::coordinate)
                .containsExactlyInAnyOrder("org.x:aa:1.0", "org.x:bb:1.0");
        assertThat(log.latest(a)).get().extracting(QuarantineLog.Event::coordinate).isEqualTo("org.x:aa:1.0");
        assertThat(log.latest(b)).get().extracting(QuarantineLog.Event::coordinate).isEqualTo("org.x:bb:1.0");
    }

    @Test
    void the_log_survives_a_store_reload() throws IOException {
        new QuarantineLog(store).record(T1, "/maven/a/b/1.0/b-1.0.pom", "a:b:1.0", Verdict.REJECT,
                List.of("some reason"));

        // A fresh store handle over the same root - the ledger is store-backed, so the row is still there.
        QuarantineLog reloaded = new QuarantineLog(resolve());
        assertThat(reloaded.events()).singleElement()
                .extracting(QuarantineLog.Event::coordinate).isEqualTo("a:b:1.0");
    }

    @Test
    void the_latest_index_keeps_the_newest_verdict_and_does_not_regress() throws IOException {
        QuarantineLog log = new QuarantineLog(store);
        String path = "/maven/org/x/lib/1.0/lib-1.0.pom";
        log.record(T2, path, "org.x:lib:1.0", Verdict.QUARANTINE, List.of("held"));
        // An out-of-order (older) record must not regress the indexed latest verdict.
        log.record(T1, path, "org.x:lib:1.0", Verdict.REJECT, List.of("older"));

        assertThat(log.latest(path)).get().satisfies(event -> {
            assertThat(event.when()).isEqualTo(T2);
            assertThat(event.verdict()).as("the newer QUARANTINE is not regressed by the older REJECT write")
                    .isEqualTo(Verdict.QUARANTINE);
        });
    }

    @Test
    void the_review_queue_surfaces_a_hold_even_when_its_log_row_never_landed() throws IOException {
        String logged = "/maven/org/held/lib/1.0/lib-1.0.pom";
        String bare = "/maven/org/bare/lib/1.0/lib-1.0.pom";
        // Two live holds; only one has a log row (the other models a gate enabled after the hold, or a lost row).
        Publication publication = new Publication(store);
        publication.link("/quarantine" + logged,
                publication.storeBlob(new ByteArrayInputStream("held".getBytes(StandardCharsets.UTF_8))));
        publication.link("/quarantine" + bare,
                publication.storeBlob(new ByteArrayInputStream("bare".getBytes(StandardCharsets.UTF_8))));
        QuarantineLog log = new QuarantineLog(store);
        log.record(T1, logged, "org.held:lib:1.0", Verdict.QUARANTINE, List.of("held for review"));

        List<QuarantineLog.Held> queue = log.reviewQueue();
        assertThat(queue).extracting(QuarantineLog.Held::path).containsExactlyInAnyOrder(logged, bare);
        assertThat(queue).filteredOn(held -> held.path().equals(bare)).singleElement()
                .satisfies(held -> assertThat(held.event()).as("a log-less hold still surfaces, releasable").isEmpty());
        assertThat(queue).filteredOn(held -> held.path().equals(logged)).singleElement()
                .satisfies(held -> assertThat(held.event()).isPresent());
    }

    @Test
    void the_review_queue_pages_by_cursor_and_a_page_never_splits_the_queue_short() throws IOException {
        // A queue larger than one page: each page carries at most `limit` holds and the cursor of the next, the
        // pages together are the whole queue, and a page past the end answers empty with no cursor.
        Publication publication = new Publication(store);
        List<String> held = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            String path = "/maven/org/held/lib" + i + "/1.0/lib" + i + "-1.0.pom";
            publication.link("/quarantine" + path,
                    publication.storeBlob(new ByteArrayInputStream(("held" + i).getBytes(StandardCharsets.UTF_8))));
            held.add(path);
        }
        QuarantineLog log = new QuarantineLog(store);
        List<String> seen = new ArrayList<>();
        String after = null;
        int pages = 0;
        do {
            QuarantineLog.QueuePage page = log.reviewQueue(after, 3);
            assertThat(page.holds()).as("a page holds at most the limit").hasSizeLessThanOrEqualTo(3);
            page.holds().forEach(hold -> seen.add(hold.path()));
            after = page.next();
            pages++;
        } while (after != null);
        assertThat(pages).isEqualTo(3);
        assertThat(seen).containsExactlyInAnyOrderElementsOf(held);
        assertThat(log.reviewQueue(null, 10).next()).as("one page that fits the whole queue has no cursor").isNull();
    }

    @Test
    void retention_prunes_by_age_and_a_discard_reaps_a_paths_rows() throws IOException {
        QuarantineLog log = new QuarantineLog(store);
        String old = "/maven/org/old/lib/1.0/lib-1.0.pom";
        String recent = "/maven/org/new/lib/1.0/lib-1.0.pom";
        log.record(T1, old, "org.old:lib:1.0", Verdict.REJECT, List.of("old"));
        log.record(T2, recent, "org.new:lib:1.0", Verdict.REJECT, List.of("recent"));

        // Prune everything older than a day before T2: the T1 row ages out, the T2 row stays.
        int removed = log.prune(T2, Duration.ofHours(1), 0);
        assertThat(removed).isGreaterThanOrEqualTo(1);
        assertThat(log.events()).extracting(QuarantineLog.Event::coordinate).containsExactly("org.new:lib:1.0");

        // A discard reaps the remaining path's rows and its index entry.
        log.discarded(recent);
        assertThat(log.events()).as("a discarded path's rows are gone").isEmpty();
        assertThat(log.latest(recent)).isEmpty();
    }

    @Test
    void the_paged_read_serves_the_newest_rows_without_ever_listing_the_trail() throws IOException {
        QuarantineLog log = new QuarantineLog(new ListRefusingStore(store));
        for (int index = 0; index < 25; index++) {
            log.record(T1.plusSeconds(index), "/maven/org/lib" + index + "/1.0/lib-1.0.pom",
                    "org.lib" + index + ":1.0", Verdict.QUARANTINE, List.of("r" + index));
        }

        // the object names sort newest-first by construction, so the bounded read is one forward page through
        // the store's own ordering. A store that refuses list(ROOT) proves it: before the fix this leg materialised
        // and sorted the whole name list to serve five rows, so a page cost exactly what the unpaged events() does.
        assertThat(log.events(5)).extracting(QuarantineLog.Event::coordinate)
                .as("the newest five decisions, newest first, off the names alone")
                .containsExactly("org.lib24:1.0", "org.lib23:1.0", "org.lib22:1.0", "org.lib21:1.0", "org.lib20:1.0");
        assertThat(log.events(0)).as("a non-positive limit is an empty page, never the whole trail").isEmpty();
        assertThat(log.events(1000)).as("a limit past the trail's length serves it whole and stops").hasSize(25);

        // The Lease-guarded sweep pays the same way, so a long trail is never listed entire to be pruned.
        assertThat(log.prune(T1.plusSeconds(24), null, 10)).as("everything past the newest ten is reaped").isEqualTo(15);
        assertThat(log.events(1000)).hasSize(10);
    }

    /** A store that refuses {@code list} under the quarantine trail while delegating everything else - so a read or
     *  sweep that still materialises the whole namespace fails by name rather than merely being slow. */
    private record ListRefusingStore(ArtifactStore delegate) implements ArtifactStore {
        @Override
        public Object identity() {
            return delegate.identity();   // a decorator answers its delegate's subspace
        }

        @Override
        public List<String> list(String prefix) {
            if (prefix.startsWith("audit/quarantine")) {
                throw new AssertionError("a bounded quarantine read must page '" + prefix + "', never list it");
            }
            return delegate.list(prefix);
        }

        @Override
        public void page(String prefix, String startAfter, int limit, Consumer<String> consumer) {
            delegate.page(prefix, startAfter, limit, consumer);
        }

        @Override
        public ArtifactStore scope(String tenant) {
            return delegate.scope(tenant);
        }

        @Override
        public boolean exists(String key) {
            return delegate.exists(key);
        }

        @Override
        public void read(String key, OutputStream out) throws IOException {
            delegate.read(key, out);
        }

        @Override
        public InputStream open(String key) throws IOException {
            return delegate.open(key);
        }

        @Override
        public void write(String key, InputStream in) throws IOException {
            delegate.write(key, in);
        }

        @Override
        public Optional<Versioned> readVersioned(String key) throws IOException {
            return delegate.readVersioned(key);
        }

        @Override
        public boolean writeVersioned(String key, byte[] content, Object expected) throws IOException {
            return delegate.writeVersioned(key, content, expected);
        }

        @Override
        public String writeBlob(InputStream in) throws IOException {
            return delegate.writeBlob(in);
        }

        @Override
        public long size(String key) throws IOException {
            return delegate.size(key);
        }

        @Override
        public void delete(String key) throws IOException {
            delegate.delete(key);
        }
    
    @Override
    public Scan scan(String prefix, String startAfter, int limit, Consumer<Listed> consumer) throws IOException {
        return ArtifactStore.scanByListing(this, prefix, startAfter, limit, consumer);
    }
}
}
