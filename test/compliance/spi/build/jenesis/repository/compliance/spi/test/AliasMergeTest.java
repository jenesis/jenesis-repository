package build.jenesis.repository.compliance.spi.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.AdvisorySource.Advisory;
import build.jenesis.repository.compliance.Severity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two feeds' records of one flaw merge on any name they share, not on a CVE alone: a {@code PYSEC-} or
 * {@code RUSTSEC-} record naming a {@code GHSA-} id, and GitHub's record under that id, are one advisory however
 * neither carries a CVE - counted once, scored at the stronger band, every name kept.
 */
class AliasMergeTest {

    @Test
    void records_of_one_flaw_sharing_no_cve_merge_on_a_shared_alias() {
        Advisory osv = new Advisory("PYSEC-2026-1", Severity.UNKNOWN, false, "2.0", List.of(), "",
                List.of("GHSA-aaaa-bbbb-cccc"));
        Advisory github = new Advisory("GHSA-aaaa-bbbb-cccc", Severity.HIGH, false, null, List.of(),
                "a flaw described at length");

        assertThat(AdvisorySource.merged(List.of(osv, github))).singleElement().satisfies(advisory -> {
            assertThat(advisory.id()).as("the first feed's id stays").isEqualTo("PYSEC-2026-1");
            assertThat(advisory.severity()).isEqualTo(Severity.HIGH);
            assertThat(advisory.fixed()).isEqualTo("2.0");
            assertThat(advisory.aliases()).containsExactly("GHSA-aaaa-bbbb-cccc");
        });
    }

    @Test
    void a_later_record_naming_the_first_by_its_id_merges_and_lends_its_own_id_as_an_alias() {
        Advisory github = new Advisory("GHSA-aaaa-bbbb-cccc", Severity.HIGH, false, null, List.of(), "");
        Advisory rustsec = new Advisory("RUSTSEC-2026-0001", Severity.HIGH, false, "1.1", List.of(), "",
                List.of("GHSA-aaaa-bbbb-cccc"));

        assertThat(AdvisorySource.merged(List.of(github, rustsec))).singleElement()
                .satisfies(advisory -> assertThat(advisory.aliases()).containsExactly("RUSTSEC-2026-0001"));
    }

    @Test
    void records_sharing_no_name_stay_apart() {
        Advisory one = new Advisory("PYSEC-2026-1", Severity.HIGH, false, null, List.of(), "",
                List.of("GHSA-aaaa-bbbb-cccc"));
        Advisory other = new Advisory("GHSA-dddd-eeee-ffff", Severity.HIGH, false, null, List.of(), "");

        assertThat(AdvisorySource.merged(List.of(one, other))).hasSize(2);
    }
}
