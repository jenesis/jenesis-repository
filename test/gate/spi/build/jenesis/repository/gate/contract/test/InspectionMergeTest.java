package build.jenesis.repository.gate.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.gate.InspectionMerge;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The subject ordering both screens run before the gate assesses an upload: content-scan subjects (an embedded-secret
 * detection or an inbound attestation an inspector derived from an artifact's bytes) sort <em>after</em> the package
 * subjects a format inspector contributes, and the order within each group is preserved - a stable partition. This is
 * load-bearing: the screens read "the first subject is the artifact itself" (the licenses recorded on an accepted
 * publish, the coordinate a quarantine log line names), so a content finding must never displace the package as the
 * head of the list. Pure over the compliance SPI's {@code Subject}; no store and no gate boot.
 */
class InspectionMergeTest {

    private static ComplianceGate.Subject pkg(String coordinate) {
        return new ComplianceGate.Subject("maven", coordinate, "1.0",
                List.of(new ComplianceGate.DeclaredLicense("Apache-2.0", null)));
    }

    private static ComplianceGate.Subject contentScan(String rule) {
        // Empty licenses + a detected secret => Subject.contentScan() is true (no licensable coordinate identity).
        return new ComplianceGate.Subject("scan", "", "", List.of())
                .withSecrets(List.of(new ComplianceGate.DetectedSecret(rule, "detected", "****", "path")));
    }

    @Test
    void content_scan_subjects_sort_after_the_package_subjects_keeping_each_groups_order() {
        ComplianceGate.Subject a = pkg("org:a");
        ComplianceGate.Subject b = pkg("org:b");
        ComplianceGate.Subject x = contentScan("aws-key");
        ComplianceGate.Subject y = contentScan("gh-token");

        // Interleaved input; the partition hoists both packages ahead of both content scans, each group in order.
        assertThat(InspectionMerge.order(List.of(x, a, y, b))).containsExactly(a, b, x, y);
    }

    @Test
    void a_list_that_is_all_package_subjects_is_returned_in_its_original_order() {
        ComplianceGate.Subject a = pkg("org:a");
        ComplianceGate.Subject b = pkg("org:b");
        assertThat(InspectionMerge.order(List.of(a, b))).containsExactly(a, b);
    }

    @Test
    void a_content_scan_subject_never_displaces_the_package_as_the_first_subject() {
        // Even when the content scanner ran first, the package stays subject[0] - the invariant the screens' "first
        // subject is the artifact" reads depend on, so licenses/coordinate are keyed off the package, not a finding.
        ComplianceGate.Subject pkg = pkg("org:a");
        ComplianceGate.Subject scan = contentScan("aws-key");

        List<ComplianceGate.Subject> ordered = InspectionMerge.order(List.of(scan, pkg));
        assertThat(ordered).first().isSameAs(pkg);
        assertThat(ordered.getLast().contentScan()).isTrue();
    }
}
