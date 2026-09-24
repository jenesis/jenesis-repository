package build.jenesis.repository.findings.store.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.Severity;
import build.jenesis.repository.findings.AiReachabilityLabels;
import build.jenesis.repository.findings.Finding;
import build.jenesis.repository.findings.ReachabilityLabels;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The conservative AI-reachability merge and combined-verdict contract, pinned directly: {@link AiReachabilityLabels}
 * is the seam every reachability badge and the {@code ai:}/{@code agreed:} facet key on, and its "an aggregate never
 * under-reports" invariant is security-relevant - a regression that flipped the {@link AiReachabilityLabels#strongest}
 * ladder, mishandled a {@code null} side, or let an AI opinion downgrade a deterministic {@code reachable} verdict
 * would silently present a reachable vulnerability as not-reachable. The classifier legitimately emits several
 * REACHABILITY rows per advisory (multiple analyzed call paths), so the collide-and-collapse merge is a production
 * path; these assert the ladder, the conflicting-opinion collapse across a coordinate's rows (and CVE aliases), and
 * the two-engine agreement, none of which the single-opinion task tests exercise.
 */
class AiReachabilityLabelsTest {

    @Test
    void strongest_takes_the_more_reachable_opinion_and_a_null_side_yields_the_other() {
        // likely-reachable outranks unknown outranks likely-not-reachable, symmetrically (order must not matter).
        assertThat(AiReachabilityLabels.strongest(
                AiReachabilityLabels.LIKELY_NOT_REACHABLE, AiReachabilityLabels.LIKELY_REACHABLE))
                .isEqualTo(AiReachabilityLabels.LIKELY_REACHABLE);
        assertThat(AiReachabilityLabels.strongest(
                AiReachabilityLabels.LIKELY_REACHABLE, AiReachabilityLabels.LIKELY_NOT_REACHABLE))
                .isEqualTo(AiReachabilityLabels.LIKELY_REACHABLE);
        assertThat(AiReachabilityLabels.strongest(AiReachabilityLabels.UNKNOWN, AiReachabilityLabels.LIKELY_REACHABLE))
                .isEqualTo(AiReachabilityLabels.LIKELY_REACHABLE);
        assertThat(AiReachabilityLabels.strongest(
                AiReachabilityLabels.LIKELY_NOT_REACHABLE, AiReachabilityLabels.UNKNOWN))
                .isEqualTo(AiReachabilityLabels.UNKNOWN);
        assertThat(AiReachabilityLabels.strongest(
                AiReachabilityLabels.LIKELY_REACHABLE, AiReachabilityLabels.LIKELY_REACHABLE))
                .isEqualTo(AiReachabilityLabels.LIKELY_REACHABLE);
        // A null side (a coordinate row that carried no opinion) yields the other side, never null.
        assertThat(AiReachabilityLabels.strongest(null, AiReachabilityLabels.LIKELY_REACHABLE))
                .isEqualTo(AiReachabilityLabels.LIKELY_REACHABLE);
        assertThat(AiReachabilityLabels.strongest(AiReachabilityLabels.LIKELY_NOT_REACHABLE, null))
                .isEqualTo(AiReachabilityLabels.LIKELY_NOT_REACHABLE);
    }

    @Test
    void verdicts_collapse_conflicting_rows_to_the_strongest_opinion_by_advisory_id_and_cve_alias() {
        // Two active rows for the same advisory carry opposing opinions; the aggregate must keep the reachable one.
        Finding notReachable = advisory("GHSA-xxxx", List.of("CVE-2021-1"), AiReachabilityLabels.LIKELY_NOT_REACHABLE);
        Finding reachable = advisory("GHSA-xxxx", List.of("CVE-2021-1"), AiReachabilityLabels.LIKELY_REACHABLE);
        Finding unopined = advisory("GHSA-yyyy", List.of(), null);   // no opinion - contributes no key
        Map<String, String> verdicts = AiReachabilityLabels.verdicts(List.of(notReachable, reachable, unopined));
        assertThat(verdicts).containsEntry("GHSA-xxxx", AiReachabilityLabels.LIKELY_REACHABLE);
        assertThat(verdicts).containsEntry("CVE-2021-1", AiReachabilityLabels.LIKELY_REACHABLE);   // the CVE alias too
        assertThat(verdicts).doesNotContainKey("GHSA-yyyy");
    }

    @Test
    void agreed_lets_a_decisive_static_verdict_win_and_never_downgrades_a_deterministic_reachable() {
        // An AI opinion can never downgrade a decided static verdict; only an unknown/absent static verdict defers to it.
        assertThat(AiReachabilityLabels.agreed(ReachabilityLabels.REACHABLE, AiReachabilityLabels.LIKELY_NOT_REACHABLE))
                .isEqualTo(ReachabilityLabels.REACHABLE);
        assertThat(AiReachabilityLabels.agreed(ReachabilityLabels.NOT_REACHABLE, AiReachabilityLabels.LIKELY_REACHABLE))
                .isEqualTo(ReachabilityLabels.NOT_REACHABLE);
        assertThat(AiReachabilityLabels.agreed(ReachabilityLabels.UNKNOWN, AiReachabilityLabels.LIKELY_REACHABLE))
                .isEqualTo(ReachabilityLabels.REACHABLE);
        assertThat(AiReachabilityLabels.agreed(ReachabilityLabels.UNKNOWN, AiReachabilityLabels.LIKELY_NOT_REACHABLE))
                .isEqualTo(ReachabilityLabels.NOT_REACHABLE);
        assertThat(AiReachabilityLabels.agreed(ReachabilityLabels.UNKNOWN, null))
                .isEqualTo(ReachabilityLabels.UNKNOWN);
    }

    @Test
    void matches_keys_the_ai_and_agreed_facets_on_the_right_label() {
        // The ai: facet keys on the AI opinion (absence == unknown); agreed: on the combined verdict; a bare filter on
        // the static label - the three ways the vulnerability view narrows by reachability.
        assertThat(AiReachabilityLabels.matches(
                ReachabilityLabels.UNKNOWN, AiReachabilityLabels.LIKELY_REACHABLE, "ai:likely-reachable")).isTrue();
        assertThat(AiReachabilityLabels.matches(ReachabilityLabels.UNKNOWN, null, "ai:unknown")).isTrue();
        assertThat(AiReachabilityLabels.matches(
                ReachabilityLabels.UNKNOWN, AiReachabilityLabels.LIKELY_REACHABLE, "agreed:reachable")).isTrue();
        assertThat(AiReachabilityLabels.matches(
                ReachabilityLabels.REACHABLE, AiReachabilityLabels.LIKELY_NOT_REACHABLE, "agreed:reachable")).isTrue();
        assertThat(AiReachabilityLabels.matches(ReachabilityLabels.UNKNOWN, null, "")).isTrue();   // no filter matches all
    }

    /** An advisory finding carrying (or not) an AI reachability opinion label, the shape {@code verdicts} folds. */
    private static Finding advisory(String id, List<String> references, String aiVerdict) {
        List<Finding.Label> labels = aiVerdict == null ? List.of() : List.of(new Finding.Label(
                AiReachabilityLabels.SOURCE, AiReachabilityLabels.NAME, aiVerdict, 1.0, Instant.EPOCH));
        return new Finding(id, "test", Finding.Kind.VULNERABILITY, "", Severity.HIGH, 1.0, "", references, "",
                Map.of(), Instant.EPOCH, Instant.EPOCH, null, labels);
    }
}
