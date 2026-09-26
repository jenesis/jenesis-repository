package build.jenesis.repository.findings.store.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.Severity;
import build.jenesis.repository.findings.Finding;
import build.jenesis.repository.findings.Findings;
import build.jenesis.repository.findings.FindingsProvider;
import build.jenesis.repository.findings.store.StoreFindings;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The store-backed findings ledger: durable, structured, per-coordinate, categorize-never-discard - a finding
 * round-trips with every field, two feeds coexist attributed on one coordinate, a re-record refreshes without
 * losing history, supersession marks rather than deletes, labels attach and refresh per source, and the
 * repository-wide walk filters on every documented axis.
 */
class StoreFindingsTest {

    private static final Instant FIRST = Instant.parse("2026-07-01T00:00:00Z");
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

    @Test
    void a_finding_round_trips_with_every_field() throws IOException {
        Finding recorded = new Finding("GHSA-1", "osv", Finding.Kind.VULNERABILITY, "advisory", Severity.CRITICAL,
                0.9, "Remote code execution", List.of("CVE-2021-44228", "https://osv.dev/GHSA-1"), "scan-sweep",
                Map.of("fixed", "2.17.1"), FIRST, FIRST, null,
                List.of(new Finding.Label("ai", "applicability", "applies", 0.7, FIRST)));
        findings.record("Maven", "org.apache.logging.log4j:log4j-core", "2.14.1", recorded);

        assertThat(findings.of("Maven", "org.apache.logging.log4j:log4j-core", "2.14.1"))
                .singleElement().isEqualTo(recorded);
    }

    @Test
    void two_sources_reporting_one_coordinate_coexist_with_attribution() throws IOException {
        findings.record("Maven", "org.acme:lib", "1.0", Finding.of(
                "CVE-2026-1", "osv", Finding.Kind.VULNERABILITY, "advisory", Severity.HIGH, "as OSV sees it", FIRST));
        findings.record("Maven", "org.acme:lib", "1.0", Finding.of(
                "GHSA-x", "github", Finding.Kind.VULNERABILITY, "advisory", Severity.CRITICAL,
                "as GitHub sees it", FIRST));

        List<Finding> stored = findings.of("Maven", "org.acme:lib", "1.0");
        assertThat(stored).hasSize(2);
        assertThat(stored).extracting(Finding::source).containsExactly("osv", "github");
        assertThat(stored).extracting(Finding::description).containsExactly("as OSV sees it", "as GitHub sees it");
    }

    @Test
    void a_re_record_refreshes_facts_and_last_seen_but_keeps_first_seen_labels_and_marks() throws IOException {
        findings.record("Maven", "org.acme:lib", "1.0", Finding.of(
                "GHSA-x", "osv", Finding.Kind.VULNERABILITY, "advisory", Severity.MEDIUM, "first words", FIRST));
        findings.label("Maven", "org.acme:lib", "1.0", "osv", "GHSA-x",
                new Finding.Label("static", "reachability", "UNKNOWN", 1.0, FIRST));
        findings.supersede("Maven", "org.acme:lib", "1.0", "osv", "GHSA-x", "GHSA-better");

        findings.record("Maven", "org.acme:lib", "1.0", Finding.of(
                "GHSA-x", "osv", Finding.Kind.VULNERABILITY, "advisory", Severity.HIGH, "rescored words", LATER));

        assertThat(findings.of("Maven", "org.acme:lib", "1.0")).singleElement().satisfies(finding -> {
            assertThat(finding.severity()).as("mutable facts refresh").isEqualTo(Severity.HIGH);
            assertThat(finding.description()).isEqualTo("rescored words");
            assertThat(finding.firstSeen()).as("the first sighting is history, kept").isEqualTo(FIRST);
            assertThat(finding.lastSeen()).isEqualTo(LATER);
            assertThat(finding.labels()).as("labels other modules attached survive a re-scan").hasSize(1);
            assertThat(finding.supersededBy()).as("a supersession mark survives a re-scan").isEqualTo("GHSA-better");
        });
    }

    @Test
    void a_superseded_finding_is_marked_not_deleted() throws IOException {
        findings.record("Maven", "org.acme:lib", "1.0", Finding.of(
                "CVE-1", "osv", Finding.Kind.VULNERABILITY, "advisory", Severity.LOW, "old", FIRST));
        findings.supersede("Maven", "org.acme:lib", "1.0", "osv", "CVE-1", "withdrawn upstream");

        assertThat(findings.of("Maven", "org.acme:lib", "1.0")).singleElement().satisfies(finding -> {
            assertThat(finding.active()).isFalse();
            assertThat(finding.supersededBy()).isEqualTo("withdrawn upstream");
            assertThat(finding.description()).as("nothing else on the row changed").isEqualTo("old");
        });
        assertThatThrownBy(() ->
                findings.supersede("Maven", "org.acme:lib", "1.0", "osv", "CVE-absent", "nothing"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_label_attaches_to_its_finding_and_a_sources_rerun_refreshes_its_own() throws IOException {
        findings.record("Maven", "org.acme:lib", "1.0", Finding.of(
                "CVE-1", "osv", Finding.Kind.VULNERABILITY, "advisory", Severity.HIGH, "words", FIRST));
        findings.label("Maven", "org.acme:lib", "1.0", "osv", "CVE-1",
                new Finding.Label("static", "reachability", "UNKNOWN", 1.0, FIRST));
        findings.label("Maven", "org.acme:lib", "1.0", "osv", "CVE-1",
                new Finding.Label("ai", "reachability", "likely-reachable", 0.6, FIRST));
        // The AI classifier re-runs: its own opinion refreshes, the static one is untouched.
        findings.label("Maven", "org.acme:lib", "1.0", "osv", "CVE-1",
                new Finding.Label("ai", "reachability", "not-reachable", 0.8, LATER));

        assertThat(findings.of("Maven", "org.acme:lib", "1.0")).singleElement().satisfies(finding -> {
            assertThat(finding.labels()).hasSize(2);
            assertThat(finding.labels()).extracting(Finding.Label::source).containsExactly("static", "ai");
            assertThat(finding.labels().getLast().value()).isEqualTo("not-reachable");
        });
        assertThatThrownBy(() -> findings.label("Maven", "org.acme:lib", "1.0", "osv", "CVE-absent",
                new Finding.Label("ai", "reachability", "x", 1.0, FIRST)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void the_repository_wide_walk_filters_by_every_axis() throws IOException {
        findings.record("Maven", "org.acme:lib", "1.0", Finding.of(
                "CVE-1", "osv", Finding.Kind.VULNERABILITY, "advisory", Severity.CRITICAL, "vuln", FIRST));
        findings.record("Maven", "org.acme:lib", "2.0", Finding.of(
                "gate-1", "gate", Finding.Kind.GATE, "QUARANTINE", Severity.NONE, "no license", FIRST));
        findings.record("npm", "left-pad", "1.0.0", Finding.of(
                "MAL-1", "openssf", Finding.Kind.MALWARE, "advisory", Severity.HIGH, "malicious", FIRST));

        assertThat(findings.all(Findings.Filter.none())).hasSize(3);
        assertThat(findings.all(new Findings.Filter(null, Finding.Kind.GATE, null, null, null, null)))
                .singleElement().satisfies(located -> assertThat(located.finding().id()).isEqualTo("gate-1"));
        assertThat(findings.all(new Findings.Filter(null, null, "openssf", null, null, null)))
                .singleElement().satisfies(located -> assertThat(located.ecosystem()).isEqualTo("npm"));
        assertThat(findings.all(new Findings.Filter(null, null, null, "QUARANTINE", null, null)))
                .singleElement().satisfies(located -> assertThat(located.version()).isEqualTo("2.0"));
        assertThat(findings.all(new Findings.Filter(null, null, null, null, Severity.CRITICAL, null)))
                .singleElement().satisfies(located -> assertThat(located.finding().id()).isEqualTo("CVE-1"));
        assertThat(findings.all(new Findings.Filter("org.acme:lib", null, null, null, null, null)))
                .as("a bare coordinate matches every version").hasSize(2);
        assertThat(findings.all(new Findings.Filter("org.acme:lib:2.0", null, null, null, null, null)))
                .as("coordinate:version is the per-artifact view").singleElement()
                .satisfies(located -> assertThat(located.version()).isEqualTo("2.0"));
    }

    @Test
    void the_ecosystem_filter_disambiguates_a_same_named_coordinate_across_ecosystems() throws IOException {
        findings.record("npm", "cross", "1.0.0", Finding.of(
                "NPM-1", "osv", Finding.Kind.VULNERABILITY, "advisory", Severity.HIGH, "npm one", FIRST));
        findings.record("PyPI", "cross", "1.0.0", Finding.of(
                "PY-1", "osv", Finding.Kind.VULNERABILITY, "advisory", Severity.HIGH, "pypi one", FIRST));

        // Without an ecosystem the same-named coordinate conflates both ecosystems (the old five-field filter).
        assertThat(findings.all(new Findings.Filter("cross", null, null, null, null, null))).hasSize(2);
        // Scoped to one ecosystem (case-insensitively), only that ecosystem's row is returned.
        assertThat(findings.all(new Findings.Filter("cross", null, null, null, null, "pypi")))
                .singleElement().satisfies(located -> assertThat(located.finding().id()).isEqualTo("PY-1"));
        assertThat(findings.all(new Findings.Filter("cross", null, null, null, null, "npm")))
                .singleElement().satisfies(located -> assertThat(located.finding().id()).isEqualTo("NPM-1"));
    }

    @Test
    void a_coordinate_query_resolves_by_prefix_amid_many_other_coordinates() throws IOException {
        findings.record("Maven", "org.acme:lib", "1.0", Finding.of(
                "A", "osv", Finding.Kind.VULNERABILITY, "advisory", Severity.HIGH, "a", FIRST));
        for (int i = 0; i < 20; i++) {
            findings.record("Maven", "org.acme:noise" + i, "1.0", Finding.of(
                    "N" + i, "osv", Finding.Kind.VULNERABILITY, "advisory", Severity.LOW, "n", FIRST));
        }
        // The coordinate push-down returns exactly the queried coordinate's rows, never a neighbour's, even with a
        // crowded ecosystem subtree beside it.
        assertThat(findings.all(new Findings.Filter("org.acme:lib", null, null, null, null, null)))
                .singleElement().satisfies(located -> assertThat(located.finding().id()).isEqualTo("A"));
    }

    @Test
    void the_repository_wide_walk_pages_a_bounded_slice() throws IOException {
        for (int i = 0; i < 5; i++) {
            findings.record("Maven", "org.acme:lib", "1." + i, Finding.of(
                    "CVE-" + i, "osv", Finding.Kind.VULNERABILITY, "advisory", Severity.HIGH, "v" + i, FIRST));
        }

        Findings.Page first = findings.all(Findings.Filter.none(), 0, 2);
        assertThat(first.located()).hasSize(2);
        assertThat(first.more()).as("more remain past the first page").isTrue();

        Findings.Page last = findings.all(Findings.Filter.none(), 4, 2);
        assertThat(last.located()).hasSize(1);
        assertThat(last.more()).as("the final page reports no more").isFalse();

        Findings.Page past = findings.all(Findings.Filter.none(), 10, 2);
        assertThat(past.located()).as("an offset past the end is an empty page").isEmpty();
        assertThat(past.more()).isFalse();
    }

    @Test
    void the_streaming_walk_visits_exactly_the_same_matched_set_as_the_buffered_walk() throws IOException {
        // The visitor overload is what the console facet fold and background reclassification use to stay bounded in
        // heap; it must deliver exactly what all(Filter) collects - same rows, same order, no drop or duplicate - across
        // several coordinates and versions so a caller can fold facets or rows without buffering the whole matched set.
        for (int i = 0; i < 6; i++) {
            findings.record("Maven", "org.acme:lib" + (i % 3), "1." + i, Finding.of(
                    "CVE-" + i, i % 2 == 0 ? "osv" : "github", Finding.Kind.VULNERABILITY, "advisory",
                    Severity.HIGH, "v" + i, FIRST));
        }

        List<Findings.Located> streamed = new ArrayList<>();
        findings.all(Findings.Filter.none(), streamed::add);

        assertThat(streamed).isEqualTo(findings.all(Findings.Filter.none()));

        // The filter is honoured identically on the streaming path.
        Findings.Filter osv = new Findings.Filter(null, null, "osv", null, null, null);
        List<Findings.Located> streamedOsv = new ArrayList<>();
        findings.all(osv, streamedOsv::add);
        assertThat(streamedOsv).isEqualTo(findings.all(osv));
        assertThat(streamedOsv).allMatch(located -> located.finding().source().equals("osv"));
    }

    @Test
    void the_provider_is_discovered_and_a_path_like_coordinate_round_trips() throws IOException {
        assertThat(FindingsProvider.installed()).as("ServiceLoader discovers the store module").isPresent();
        // A coordinate with path-like characters (a Go module path) encodes into one traversal-free segment.
        findings.record("Go", "github.com/acme/lib", "v1.0.0", Finding.of(
                "GO-1", "osv", Finding.Kind.VULNERABILITY, "advisory", Severity.LOW, "words", FIRST));
        assertThat(FindingsProvider.installed().orElseThrow().over(store)
                .of("Go", "github.com/acme/lib", "v1.0.0")).hasSize(1);
        assertThat(findings.all(Findings.Filter.none())).singleElement()
                .satisfies(located -> assertThat(located.coordinate()).isEqualTo("github.com/acme/lib"));
    }

    @Test
    void a_traversal_laced_ecosystem_or_version_is_rejected() {
        // An ecosystem that would escape into a sibling key-space, or a version that would move within it, is refused
        // by the document's key codec before anything is read or written.
        assertThatThrownBy(() -> findings.of("../auth", "org.acme:lib", "1.0"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> findings.of("Maven", "org.acme:lib", ".."))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> findings.of("Maven/..", "org.acme:lib", "1.0"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> findings.of("Maven", "org.acme:lib", "1.0/.."))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
