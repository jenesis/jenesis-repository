package build.jenesis.repository.health.store.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.HealthSource.Health;
import build.jenesis.repository.health.HealthLedger;
import build.jenesis.repository.health.store.StoreHealthLedger;
import build.jenesis.repository.metadata.MetadataKey;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The durable weakest-first health rank index behind the console panel, exercised end to end through the ledger's public
 * {@link StoreHealthLedger#reindex()} / {@link StoreHealthLedger#worstFirst} seam over a real filesystem store:
 *
 * <ul>
 *   <li>a rebuilt index pages the scored coordinates ascending-overall (weakest first) without buffering and sorting the
 *       whole set - proven by paging in small bounded pages whose union is the complete set in order;</li>
 *   <li>the total scored count rides the page so the panel can say "N of M", never silently truncating;</li>
 *   <li>a read whose freshness stamp no longer matches the records (a scan moved them since the last build) still serves
 *       the last committed ranking, carrying that ranking's own build-time instant rather than the live one;</li>
 *   <li><b>an index no pass has committed answers {@link HealthLedger.Ranking.NotBuilt}, never a ranking</b> -
 *       there is no recompute-on-read left, so a repository full of scored coordinates whose ranking has not been built
 *       yields no entries at all rather than a whole-ledger sort wearing a "weakest first" label;</li>
 *   <li>a rebuild reclaims a superseded/orphan generation, so the index never accumulates dead generations;</li>
 *   <li>a rebuild whose records have not moved since the last build is a no-op (no generation flip).</li>
 * </ul>
 */
class HealthRankIndexTest {

    private static final Instant SCAN = Instant.parse("2026-07-01T00:00:00Z");
    private static final Instant RESCAN = Instant.parse("2026-07-08T00:00:00Z");

    @TempDir
    Path root;

    private ArtifactStore store;
    private StoreHealthLedger ledger;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        ledger = new StoreHealthLedger(store);
    }

    @Test
    void a_rebuilt_index_serves_the_scored_coordinates_weakest_first() throws IOException {
        record("npm", "steady", 9.0);
        record("Maven", "org.example:middling", 5.0);
        record("Maven", "org.example:weak", 2.0);
        record("npm", "abandoned", 0.5);
        HealthLedger.scanned(store).mark(SCAN);
        ledger.reindex();

        HealthLedger.Ranking.Ranked page = ranked(ledger.worstFirst(null, 10));
        assertThat(page.total()).isEqualTo(4);
        assertThat(page.nextCursor()).as("the whole set fits one page").isNull();
        assertThat(page.entries()).extracting(HealthLedger.Located::coordinate)
                .as("weakest first: ascending overall score")
                .containsExactly("abandoned", "org.example:weak", "org.example:middling", "steady");
    }

    @Test
    void the_index_pages_the_scored_set_in_order_without_dropping_a_coordinate() throws IOException {
        for (int rank = 0; rank < 7; rank++) {
            record("Maven", "org.example:c" + rank, rank);      // overall 0.0, 1.0, ... 6.0 - already ascending
        }
        HealthLedger.scanned(store).mark(SCAN);
        ledger.reindex();

        List<String> walked = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            HealthLedger.Ranking.Ranked page = ranked(ledger.worstFirst(cursor, 3));
            assertThat(page.total()).as("every page carries the full scored total").isEqualTo(7);
            assertThat(page.entries().size()).isLessThanOrEqualTo(3);
            page.entries().forEach(located -> walked.add(located.coordinate()));
            cursor = page.nextCursor();
            pages++;
        } while (cursor != null);

        assertThat(pages).as("7 coordinates in pages of 3 is three pages").isEqualTo(3);
        assertThat(walked).as("the union of the pages is the whole set, weakest first, none dropped or repeated")
                .containsExactly("org.example:c0", "org.example:c1", "org.example:c2", "org.example:c3",
                        "org.example:c4", "org.example:c5", "org.example:c6");
    }

    @Test
    void the_read_is_eventually_consistent_serving_the_last_built_index_until_the_next_rebuild() throws IOException {
        record("Maven", "org.example:old", 4.0);
        HealthLedger.scanned(store).mark(SCAN);
        ledger.reindex();

        // A later scan records a new coordinate and stamps a newer freshness, but the index has not been rebuilt yet.
        record("npm", "fresh", 1.0);
        HealthLedger.scanned(store).mark(RESCAN);

        // Eventually consistent: the read serves the last built generation rather than re-deriving anything on the
        // request thread, so a request never pays a sort once a ranking stands. It therefore serves the last ranking -
        // the just-scanned coordinate appears only after the next rebuild - and carries the index's own build-time
        // freshness, not the moved live scan stamp, so the page is never shown fresher than it is.
        HealthLedger.Ranking.Ranked stale = ranked(ledger.worstFirst(null, 10));
        assertThat(stale.entries()).extracting(HealthLedger.Located::coordinate)
                .as("it serves the last built ranking - the newly-scanned coordinate is not folded in until a rebuild")
                .containsExactly("org.example:old");
        assertThat(stale.scannedAt()).as("the page's honest as-of instant is the build time, not the live scan")
                .contains(SCAN);

        // The next rank-index pass rebuilds from truth and folds the new coordinate in, weakest first.
        ledger.reindex();
        HealthLedger.Ranking.Ranked rebuilt = ranked(ledger.worstFirst(null, 10));
        assertThat(rebuilt.entries()).extracting(HealthLedger.Located::coordinate).containsExactly("fresh", "org.example:old");
        assertThat(rebuilt.scannedAt()).contains(RESCAN);
    }

    @Test
    void an_eviction_epoch_bump_rebuilds_the_index_even_when_the_scan_stamp_has_not_moved() throws IOException {
        record("Maven", "org.example:weak", 2.0);
        record("npm", "steady", 9.0);
        HealthLedger.scanned(store).mark(SCAN);
        ledger.reindex();                                       // built at SCAN, both coordinates
        assertThat(ranked(ledger.worstFirst(null, 10)).entries()).extracting(HealthLedger.Located::coordinate)
                .containsExactly("org.example:weak", "steady");

        // An eviction removes a coordinate's health record WITHOUT running a scan, so the freshness stamp does not move.
        // Without the eviction epoch the rebuild would no-op on the unchanged stamp and keep paging the departed
        // coordinate until the next scan - the whole staleness this signal closes. The inventory's evict deletes the
        // record (its @coordinate metadata document here, the cutover home) and bumps the epoch; both are reproduced
        // here (this suite has no inventory).
        store.delete(MetadataKey.coordinate("npm", "steady"));
        HealthLedger.evictions(store).bump();

        ledger.reindex();                                      // same scan stamp, but the moved epoch triggers a rebuild
        HealthLedger.Ranking.Ranked page = ranked(ledger.worstFirst(null, 10));
        assertThat(page.entries()).extracting(HealthLedger.Located::coordinate)
                .as("the evicted coordinate drops on the next rank-index pass, not the next scan")
                .containsExactly("org.example:weak");
        assertThat(page.scannedAt())
                .as("the panel's freshness is still the scan stamp - the eviction epoch never leaks into it")
                .contains(SCAN);
    }

    @Test
    void an_index_no_pass_has_committed_reports_not_built_and_recomputes_nothing() throws IOException {
        // Records exist and are perfectly sortable - which is exactly why this used to buffer and sort them into
        // a "weakest first" page. It must not: a ranking derived on the request thread claims to be the worst of the
        // repository while being whatever the ledger answered, and an operator reading it concludes nothing worse
        // exists. The honest answer is the state itself, carrying the ledger's last sweep so the panel can say "scored
        // at SCAN, not yet ranked" rather than showing a blank list.
        record("Maven", "org.example:a", 6.0);
        record("npm", "b", 1.0);
        HealthLedger.scanned(store).mark(SCAN);
        // No reindex: no pass has committed a ranking yet.

        HealthLedger.Ranking ranking = ledger.worstFirst(null, 10);
        assertThat(ranking).as("no committed ranking: the not-built state, never a page of entries")
                .isInstanceOf(HealthLedger.Ranking.NotBuilt.class);
        assertThat(ranking.scannedAt()).as("the ledger's own last sweep rides the not-built answer (Principle 10)")
                .contains(SCAN);

        // And the type is the enforcement: there is no accessor on the not-built state that could hand a renderer an
        // empty list to mistake for "nothing is unhealthy".
        assertThat(HealthLedger.Ranking.NotBuilt.class.getRecordComponents())
                .as("the not-built state carries its as-of instant and nothing that reads as a ranking")
                .hasSize(1);
    }

    @Test
    void a_never_scanned_repository_reports_not_built_with_no_instant_at_all() throws IOException {
        // The second shape the panel must tell apart: nothing scored, nothing stamped, nothing ranked. It reads as
        // "never scanned" rather than as a swept-and-clean repository.
        HealthLedger.Ranking ranking = ledger.worstFirst(null, 10);
        assertThat(ranking).isInstanceOf(HealthLedger.Ranking.NotBuilt.class);
        assertThat(ranking.scannedAt()).as("never swept: no instant to show, and none invented").isEmpty();
    }

    @Test
    void a_rebuild_orders_equal_score_coordinates_by_a_stable_tie_break() throws IOException {
        // Two coordinates with the identical overall score: the durable index tie-breaks within the score band by a
        // SHA-256 of ecosystem+coordinate, so the weakest-first list is deterministic rather than dependent on the
        // order the walk happened to emit. Proven by rebuilding into the OTHER generation and getting the same order.
        record("npm", "alpha", 5.0);
        record("Maven", "org.example:beta", 5.0);
        HealthLedger.scanned(store).mark(SCAN);
        ledger.reindex();
        List<String> first = ranked(ledger.worstFirst(null, 10)).entries().stream()
                .map(HealthLedger.Located::coordinate).toList();

        record("npm", "alpha", 5.0);                            // a re-score at the same value
        HealthLedger.scanned(store).mark(RESCAN);
        ledger.reindex();                                       // rebuilds into the other generation
        List<String> second = ranked(ledger.worstFirst(null, 10)).entries().stream()
                .map(HealthLedger.Located::coordinate).toList();

        assertThat(first).as("both equal-score coordinates present")
                .containsExactlyInAnyOrder("alpha", "org.example:beta");
        assertThat(second).as("the tie-break is stable across rebuilds, not an artefact of walk order").isEqualTo(first);
    }

    @Test
    void a_rebuild_reclaims_a_superseded_or_orphan_generation() throws IOException {
        record("Maven", "org.example:a", 3.0);
        HealthLedger.scanned(store).mark(SCAN);
        ledger.reindex();

        // A generation a crashed rebuild could have orphaned: it names a live generation the marker does not point at.
        store.write("healthrank/g7/00000-orphan", new ByteArrayInputStream(new byte[] {1}));
        assertThat(generationDirectories()).contains("g7");

        record("npm", "b", 8.0);
        HealthLedger.scanned(store).mark(RESCAN);
        ledger.reindex();

        assertThat(generationDirectories())
                .as("the orphan generation is reclaimed and the index never holds a stray generation")
                .doesNotContain("g7").hasSizeLessThanOrEqualTo(2);
        assertThat(ranked(ledger.worstFirst(null, 10)).entries()).extracting(HealthLedger.Located::coordinate)
                .as("the rebuilt index reflects the new record set").containsExactly("org.example:a", "b");
    }

    @Test
    void a_rebuild_whose_records_have_not_moved_is_a_no_op() throws IOException {
        record("Maven", "org.example:a", 3.0);
        HealthLedger.scanned(store).mark(SCAN);
        ledger.reindex();
        Set<String> afterFirst = generationDirectories();

        ledger.reindex();                                       // same stamp, same records: nothing to do
        assertThat(generationDirectories())
                .as("an unchanged rebuild neither flips a generation nor writes a new one").isEqualTo(afterFirst);
    }

    /** The ranked page of a ranking that must have been committed - it fails naming the state rather than
     *  {@code ClassCastException}-ing, so a test that lands on the not-built state says so. */
    private static HealthLedger.Ranking.Ranked ranked(HealthLedger.Ranking ranking) {
        assertThat(ranking).as("a committed ranking was expected here").isInstanceOf(HealthLedger.Ranking.Ranked.class);
        return (HealthLedger.Ranking.Ranked) ranking;
    }

    private void record(String ecosystem, String coordinate, double overall) throws IOException {
        ledger.record(ecosystem, coordinate, new Health("github.com/example/" + coordinate, overall, overall, overall,
                overall), SCAN);
    }

    private Set<String> generationDirectories() {
        return store.list(HealthRankIndexTest.prefix()).stream()
                .filter(name -> name.length() > 1 && name.charAt(0) == 'g'
                        && name.chars().skip(1).allMatch(Character::isDigit))
                .collect(java.util.stream.Collectors.toSet());
    }

    private static String prefix() {
        return "healthrank";
    }
}
