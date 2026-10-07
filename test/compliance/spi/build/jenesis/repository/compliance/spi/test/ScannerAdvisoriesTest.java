package build.jenesis.repository.compliance.spi.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.ScannerAdvisories;
import build.jenesis.repository.compliance.Severity;
import build.jenesis.repository.compliance.VulnerabilityRecord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link ScannerAdvisories}: what every content scanner's rows come to, whatever its report looked like. */
class ScannerAdvisoriesTest {

    @Test
    void one_vulnerability_in_two_packages_is_one_advisory_naming_both_and_its_strongest_severity() throws IOException {
        List<AdvisorySource.Advisory> advisories = ScannerAdvisories.advisories(List.of(
                row("CVE-2026-1000", "openssl", "3.0.1", "3.0.9", "Medium", null, null),
                row("CVE-2026-1000", "libssl3", "3.0.1", "3.0.9", "High", null, null),
                row("GHSA-xxxx-yyyy", "lodash", "4.17.0", null, "Low", null, null)));

        assertThat(advisories).extracting(AdvisorySource.Advisory::id)
                .containsExactly("CVE-2026-1000", "GHSA-xxxx-yyyy");
        AdvisorySource.Advisory merged = advisories.getFirst();
        assertThat(merged.severity()).isEqualTo(Severity.HIGH);
        assertThat(merged.fixed()).isEqualTo("openssl 3.0.9, libssl3 3.0.9");
        assertThat(merged.description()).contains("openssl 3.0.1").contains("libssl3 3.0.1");
        assertThat(merged.cves()).containsExactly("CVE-2026-1000");
        assertThat(advisories.getLast().cves()).as("an identifier that is no CVE names none").isEmpty();
    }

    @Test
    void what_the_scanners_database_says_of_a_vulnerability_reaches_its_advisory_from_every_row() throws IOException {
        VulnerabilityRecord.Source alpine = new VulnerabilityRecord.Source("Alpine Secdb",
                "https://secdb.alpinelinux.org/");
        VulnerabilityRecord.Source nvd = new VulnerabilityRecord.Source("NVD", null);
        List<ScannerAdvisories.Row> rows = List.of(
                new ScannerAdvisories.Row("CVE-2019-14697", "musl", "1.1.22-r2", "1.1.22-r3", "Critical", null, 9.8,
                        null, new VulnerabilityRecord(alpine, List.of(), List.of(new VulnerabilityRecord.Rating(nvd,
                        9.8, Severity.CRITICAL, "CVSSv31", "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H")),
                        List.of(), List.of(), null, null)),
                new ScannerAdvisories.Row("CVE-2019-14697", "musl-utils", "1.1.22-r2", "1.1.22-r3", "Critical", null,
                        9.8, null, new VulnerabilityRecord(alpine, List.of(), List.of(), List.of(787), List.of(), null,
                        null)));

        VulnerabilityRecord detail = ScannerAdvisories.advisories(rows).getFirst().detail();

        assertThat(detail.source()).isEqualTo(alpine);
        assertThat(detail.ratings()).singleElement().satisfies(rating -> assertThat(rating.source()).isEqualTo(nvd));
        assertThat(detail.cwes()).as("from the row naming it").containsExactly(787);
    }

    @Test
    void the_scanners_word_comes_first_then_the_v3_score_then_the_v2_score_then_unknown() {
        assertThat(ScannerAdvisories.severity(row("a", null, null, null, "Negligible", 9.8, null)))
                .as("the word wins over a score, and negligible is the lowest band").isEqualTo(Severity.LOW);
        assertThat(ScannerAdvisories.severity(row("a", null, null, null, "Unknown", 9.8, 2.0)))
                .isEqualTo(Severity.CRITICAL);
        assertThat(ScannerAdvisories.severity(row("a", null, null, null, null, null, 5.0))).isEqualTo(Severity.MEDIUM);
        assertThat(ScannerAdvisories.severity(row("a", null, null, null, "", null, null))).isEqualTo(Severity.UNKNOWN);
    }

    @Test
    void a_scanners_word_reads_as_every_feeds_does() {
        assertThat(ScannerAdvisories.severity(row("a", null, null, null, "Moderate", 9.8, null)))
                .as("moderate is the medium band whoever says it, not a word to fall through to the score")
                .isEqualTo(Severity.MEDIUM);
        assertThat(Severity.ofWord("negligible", Severity.UNKNOWN)).as("one vocabulary for feeds and scanners")
                .isEqualTo(Severity.LOW);
    }

    @Test
    void an_empty_list_is_a_clean_image_and_a_missing_one_is_a_failed_scan() throws IOException {
        assertThat(ScannerAdvisories.advisories(List.of())).isEmpty();
        assertThatThrownBy(() -> ScannerAdvisories.advisories(null)).isInstanceOf(IOException.class)
                .hasMessageContaining("no vulnerabilities list");
    }

    @Test
    void a_row_naming_no_identifier_fails_the_scan_rather_than_dropping_the_row() {
        assertThatThrownBy(() -> ScannerAdvisories.advisories(List.of(row(" ", "openssl", "3.0.1", null, "High",
                null, null)))).isInstanceOf(IOException.class).hasMessageContaining("without an identifier");
    }

    private static ScannerAdvisories.Row row(String id, String pkg, String version, String fix, String severity,
                                             Double v3, Double v2) {
        return new ScannerAdvisories.Row(id, pkg, version, fix, severity, null, v3, v2);
    }
}
