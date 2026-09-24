package build.jenesis.repository.findings.store.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.Severity;
import build.jenesis.repository.findings.Finding;
import build.jenesis.repository.findings.Findings;
import build.jenesis.repository.findings.store.StoreFindings;
import build.jenesis.repository.metadata.MetadataKey;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The durable findings-filter index behind a selective {@code /api/findings} query: a rebuild indexes each finding under
 * its filter facets (severity, kind, category, source), a selective read serves a bounded page from the index rather than
 * scanning the whole plane, the read is eventually consistent (it serves the last built generation until the next
 * rebuild), an eviction rebuilds it even when the scan stamp has not moved, and a rebuild reclaims the superseded
 * generation so the space stays bounded.
 */
class FindingsFilterIndexTest {

    private static final Instant WHEN = Instant.parse("2026-07-01T00:00:00Z");
    private static final Instant LATER = Instant.parse("2026-07-05T00:00:00Z");

    @TempDir
    Path root;

    private ArtifactStore store;
    private StoreFindings findings;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve(
                "filesystem", key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        findings = new StoreFindings(store);
    }

    private void record(String coordinate, String source, Finding.Kind kind, String category, Severity severity)
            throws IOException {
        findings.record("Maven", coordinate, "1.0", Finding.of(
                "id-" + coordinate, source, kind, category, severity, "desc", WHEN));
    }

    private List<String> coordinates(Findings.Filter filter) throws IOException {
        return findings.all(filter, 0, 100).located().stream().map(Findings.Located::coordinate).sorted().toList();
    }

    @Test
    void a_selective_severity_filter_is_served_from_the_built_index() throws IOException {
        record("org.acme:a", "osv", Finding.Kind.VULNERABILITY, "advisory", Severity.CRITICAL);
        record("org.acme:b", "osv", Finding.Kind.VULNERABILITY, "advisory", Severity.HIGH);
        record("org.acme:c", "osv", Finding.Kind.VULNERABILITY, "advisory", Severity.CRITICAL);
        findings.reindex();

        assertThat(coordinates(new Findings.Filter(null, null, null, null, Severity.CRITICAL, null)))
                .as("the built index serves exactly the critical findings")
                .containsExactly("org.acme:a", "org.acme:c");
        assertThat(coordinates(new Findings.Filter(null, null, null, null, Severity.HIGH, null)))
                .containsExactly("org.acme:b");
    }

    @Test
    void the_read_is_eventually_consistent_serving_the_last_built_index_until_the_next_rebuild() throws IOException {
        record("org.acme:a", "osv", Finding.Kind.VULNERABILITY, "advisory", Severity.CRITICAL);
        Findings.scanned(store).mark(WHEN);
        findings.reindex();

        // A later scan records a new finding and moves the scan stamp, but the index has not been rebuilt for it yet: the
        // selective read still serves the last built generation (eventual consistency), unlike a live walk that would
        // already see it.
        record("org.acme:b", "osv", Finding.Kind.VULNERABILITY, "advisory", Severity.CRITICAL);
        Findings.scanned(store).mark(LATER);
        assertThat(coordinates(new Findings.Filter(null, null, null, null, Severity.CRITICAL, null)))
                .as("the stale index does not yet see the new critical finding").containsExactly("org.acme:a");

        findings.reindex();
        assertThat(coordinates(new Findings.Filter(null, null, null, null, Severity.CRITICAL, null)))
                .as("a rebuild at the moved stamp picks up the new finding")
                .containsExactly("org.acme:a", "org.acme:b");
    }

    @Test
    void the_index_served_page_carries_the_built_scan_stamp_not_the_live_one() throws IOException {
        record("org.acme:a", "osv", Finding.Kind.VULNERABILITY, "advisory", Severity.CRITICAL);
        Findings.scanned(store).mark(WHEN);
        findings.reindex();

        // A later scan advances the live scan stamp, but the filter index has not been rebuilt for it yet: the selective,
        // index-served page must be labelled with the freshness the index was BUILT at (WHEN), never the live stamp
        // (LATER) the ledger has moved to - Principle 10, so a reader never shows the filtered list fresher than the
        // index it was served from. Revert the split and the page would leak the live stamp.
        Findings.scanned(store).mark(LATER);
        Findings.Page page = findings.all(new Findings.Filter(null, null, null, null, Severity.CRITICAL, null), 0, 100);

        assertThat(page.located()).as("still served from the built index (eventual consistency)").hasSize(1);
        assertThat(page.builtScanStamp())
                .as("the index-served page carries the build-time scan stamp, not the live one")
                .isEqualTo(WHEN.toString());
        assertThat(Findings.scanned(store).read()).as("the live stamp has advanced past the built index").contains(LATER);
    }

    @Test
    void a_live_walk_page_carries_no_built_stamp_so_a_reader_renders_the_live_scan_stamp() throws IOException {
        record("org.acme:a", "osv", Finding.Kind.VULNERABILITY, "advisory", Severity.CRITICAL);
        Findings.scanned(store).mark(WHEN);
        // No reindex: a selective read falls back to the live walk, which is not index-served - its page carries no
        // built stamp (null), the signal a reader uses to render the live scan stamp rather than a build-time one.
        Findings.Page page = findings.all(new Findings.Filter(null, null, null, null, Severity.CRITICAL, null), 0, 100);

        assertThat(page.located()).hasSize(1);
        assertThat(page.builtScanStamp())
                .as("a live-walk fallback page carries no built stamp, so the reader falls back to the live stamp")
                .isNull();
    }

    @Test
    void an_unbuilt_index_falls_back_to_the_live_walk() throws IOException {
        record("org.acme:a", "osv", Finding.Kind.VULNERABILITY, "advisory", Severity.CRITICAL);
        // No reindex: the index has never been built, so the selective read falls back to the live walk and still finds
        // the finding (never worse than before the index existed).
        assertThat(coordinates(new Findings.Filter(null, null, null, null, Severity.CRITICAL, null)))
                .containsExactly("org.acme:a");
    }

    @Test
    void a_combined_filter_post_filters_the_seek_bucket() throws IOException {
        record("org.acme:a", "osv", Finding.Kind.VULNERABILITY, "advisory", Severity.HIGH);
        record("org.acme:b", "github", Finding.Kind.VULNERABILITY, "advisory", Severity.HIGH);
        findings.reindex();

        // Two HIGH findings, different sources: the combined filter seeks one facet bucket and post-filters the other.
        assertThat(coordinates(new Findings.Filter(null, null, "osv", null, Severity.HIGH, null)))
                .containsExactly("org.acme:a");
        assertThat(coordinates(new Findings.Filter(null, Finding.Kind.VULNERABILITY, null, "advisory", null, null)))
                .as("kind + category both match both findings")
                .containsExactly("org.acme:a", "org.acme:b");
    }

    @Test
    void a_category_filter_matches_case_insensitively_through_the_index() throws IOException {
        record("org.acme:a", "osv", Finding.Kind.VULNERABILITY, "Advisory", Severity.HIGH);
        findings.reindex();

        // The filter matches category case-insensitively; the index buckets it lower-cased and the seek lower-cases the
        // query, so a differently-cased query still resolves.
        assertThat(coordinates(new Findings.Filter(null, null, null, "ADVISORY", null, null)))
                .containsExactly("org.acme:a");
    }

    @Test
    void the_index_read_pages_the_window_and_reports_more() throws IOException {
        for (int index = 0; index < 5; index++) {
            record("org.acme:c" + index, "osv", Finding.Kind.VULNERABILITY, "advisory", Severity.CRITICAL);
        }
        findings.reindex();
        Findings.Filter critical = new Findings.Filter(null, null, null, null, Severity.CRITICAL, null);

        Findings.Page first = findings.all(critical, 0, 2);
        assertThat(first.located()).hasSize(2);
        assertThat(first.more()).as("more remain past the first window").isTrue();

        Findings.Page last = findings.all(critical, 4, 2);
        assertThat(last.located()).hasSize(1);
        assertThat(last.more()).as("the last row is the end of the bucket").isFalse();
    }

    @Test
    void an_eviction_rebuilds_the_index_even_when_the_scan_stamp_has_not_moved() throws IOException {
        record("org.acme:a", "osv", Finding.Kind.VULNERABILITY, "advisory", Severity.CRITICAL);
        record("org.acme:b", "osv", Finding.Kind.VULNERABILITY, "advisory", Severity.CRITICAL);
        findings.reindex();
        assertThat(coordinates(new Findings.Filter(null, null, null, null, Severity.CRITICAL, null)))
                .containsExactly("org.acme:a", "org.acme:b");

        // Evict b's findings (delete its consolidated metadata document, the way the inventory's evict does) and bump the
        // eviction epoch; the scan stamp never moved. The composite build stamp moves on the epoch alone, so the next pass
        // rebuilds and drops the evicted coordinate rather than lingering a scan.
        store.delete(MetadataKey.version("Maven", "org.acme:b", "1.0"));
        Findings.evictions(store).bump();
        findings.reindex();

        assertThat(coordinates(new Findings.Filter(null, null, null, null, Severity.CRITICAL, null)))
                .as("the evicted coordinate drops on the next index pass").containsExactly("org.acme:a");
    }

    @Test
    void a_rebuild_reclaims_an_orphan_generation() throws IOException {
        record("org.acme:a", "osv", Finding.Kind.VULNERABILITY, "advisory", Severity.CRITICAL);
        findings.reindex();

        // Seed a crashed-rebuild orphan generation (a half-built g7 no marker points at), then rebuild at a moved stamp:
        // the reclaim at the rebuild's start clears every generation but the live one, so the orphan is gone.
        store.write("findingsfilter/g7/sev/CRITICAL/000000000",
                new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)));
        record("org.acme:b", "osv", Finding.Kind.VULNERABILITY, "advisory", Severity.CRITICAL);
        Findings.evictions(store).bump();
        findings.reindex();

        assertThat(store.list("findingsfilter")).as("the crashed-rebuild orphan generation is reclaimed")
                .doesNotContain("g7");
    }
}
