package build.jenesis.repository.findings.store.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.findings.CleanScanMarker;
import build.jenesis.repository.findings.Finding;
import build.jenesis.repository.findings.Findings;
import build.jenesis.repository.findings.store.StoreFindings;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The negative clean-scan marker the vulnerability report persists for a coordinate the feeds reported nothing for, so
 * a known-clean coordinate is served from the ledger instead of triggering a full live rescan on every read. This
 * exercises the mechanism the way the report's per-coordinate loop does - serve stored advisories, else a still-fresh
 * clean marker, else query the feed and record the marker - over a real filesystem-backed ledger, counting the feed
 * round-trips to prove a second render of a clean coordinate does not re-hit the feed.
 */
class CleanScanMarkerTest {

    private static final String ECO = "npm";
    private static final String COORD = "left-pad";
    private static final String VERSION = "1.0.0";
    private static final Instant NOW = Instant.parse("2026-07-18T00:00:00Z");

    @TempDir
    Path root;

    private Findings ledger;
    private final AtomicInteger feedHits = new AtomicInteger();

    @BeforeEach
    void setUp() {
        ArtifactStore store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        ledger = new StoreFindings(store);
    }

    /** The report's clean-coordinate decision in miniature: serve stored advisories, else a still-fresh clean marker
     *  (no feed), else query the feed (counted) and, when it reports nothing, record the negative marker. */
    private boolean renderIsClean(Instant now) throws IOException {
        List<Finding> rows = ledger.of(ECO, COORD, VERSION);
        boolean anyAdvisory = rows.stream().anyMatch(
                f -> f.kind() == Finding.Kind.VULNERABILITY || f.kind() == Finding.Kind.MALWARE);
        if (anyAdvisory) {
            return false;
        }
        if (CleanScanMarker.fresh(rows, now, CleanScanMarker.DEFAULT_TTL)) {
            return true;                                        // served from the ledger, no feed round-trip
        }
        feedHits.incrementAndGet();                            // the live feed query - the cost this fix spares a re-read
        ledger.record(ECO, COORD, VERSION, CleanScanMarker.of(now));   // the feed reported nothing: persist the marker
        return true;
    }

    @Test
    void a_second_render_of_a_clean_coordinate_does_not_re_hit_the_feed() throws IOException {
        assertThat(renderIsClean(NOW)).isTrue();
        assertThat(feedHits.get()).as("the first render queries the feed once").isEqualTo(1);

        assertThat(renderIsClean(NOW.plus(Duration.ofHours(1)))).isTrue();
        assertThat(renderIsClean(NOW.plus(Duration.ofHours(2)))).isTrue();
        assertThat(feedHits.get()).as("renders within the TTL are served from the ledger marker").isEqualTo(1);
    }

    @Test
    void the_marker_lapses_past_its_ttl_and_the_feed_is_consulted_again() throws IOException {
        renderIsClean(NOW);
        assertThat(feedHits.get()).isEqualTo(1);

        assertThat(renderIsClean(NOW.plus(CleanScanMarker.DEFAULT_TTL).plusSeconds(1))).isTrue();
        assertThat(feedHits.get()).as("past the freshness window the feed is consulted again").isEqualTo(2);
    }

    @Test
    void the_marker_is_recorded_as_a_clean_row_never_a_vulnerability() throws IOException {
        renderIsClean(NOW);

        assertThat(ledger.of(ECO, COORD, VERSION)).singleElement().satisfies(finding -> {
            assertThat(finding.kind()).isEqualTo(Finding.Kind.CLEAN);
            assertThat(finding.source()).isEqualTo(CleanScanMarker.SOURCE);
            assertThat(finding.id()).isEqualTo(CleanScanMarker.ID);
        });
    }
}
