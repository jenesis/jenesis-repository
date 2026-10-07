package build.jenesis.repository.compliance.osv.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.Severity;
import build.jenesis.repository.compliance.VulnerabilityRecord;
import build.jenesis.repository.compliance.osv.OsvAdvisorySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What an OSV record says beyond its identifier and severity reaches the advisory structured: the database that
 * published it with the address of its record, each alias as a reference to where it is published, each CVSS vector as
 * a rating scored and named by its version, the CWE identifiers wherever the record states them, the advisories among
 * its references and its dates. A record from a database with no page of its own is attributed to OSV's page of it.
 */
class OsvAttributionTest {

    private static final String LOG4SHELL = """
            {"vulns":[{
              "id": "GHSA-jfh8-c2jp-5v3q",
              "summary": "Remote code injection in Log4j",
              "aliases": ["CVE-2021-44228"],
              "published": "2021-12-10T00:40:56Z",
              "modified": "2024-04-03T19:15:55Z",
              "severity": [
                {"type": "CVSS_V3", "score": "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:C/C:H/I:H/A:H"},
                {"type": "CVSS_V4", "score": "CVSS:4.0/AV:N/AC:L/AT:N/PR:N/UI:N/VC:H/VI:H/VA:H/SC:H/SI:H/SA:H"}
              ],
              "affected": [{"package": {"ecosystem": "Maven", "name": "org.apache.logging.log4j:log4j-core"},
                            "database_specific": {"cwe_ids": ["CWE-917"]}}],
              "references": [
                {"type": "ADVISORY", "url": "https://nvd.nist.gov/vuln/detail/CVE-2021-44228"},
                {"type": "WEB", "url": "https://logging.apache.org/log4j/2.x/security.html"}
              ],
              "database_specific": {"cwe_ids": ["CWE-20", "CWE-400", "CWE-502"], "severity": "CRITICAL"}
            }]}""";

    @Test
    void a_records_source_ratings_weaknesses_and_dates_reach_the_advisory_structured() {
        AdvisorySource.Advisory advisory = new OsvAdvisorySource(_ -> LOG4SHELL)
                .advisories("Maven", "org.apache.logging.log4j:log4j-core", "2.14.1").getFirst();

        VulnerabilityRecord detail = advisory.detail();
        assertThat(detail.source()).isEqualTo(new VulnerabilityRecord.Source("GitHub Advisory Database",
                "https://github.com/advisories/GHSA-jfh8-c2jp-5v3q"));
        assertThat(detail.references()).containsExactly(new VulnerabilityRecord.Reference("CVE-2021-44228",
                new VulnerabilityRecord.Source("NVD", "https://nvd.nist.gov/vuln/detail/CVE-2021-44228")));
        assertThat(detail.ratings()).extracting(VulnerabilityRecord.Rating::method)
                .containsExactly("CVSSv31", "CVSSv4");
        assertThat(detail.ratings().getFirst()).satisfies(rating -> {
            assertThat(rating.score()).isEqualTo(10.0);
            assertThat(rating.severity()).isEqualTo(Severity.CRITICAL);
            assertThat(rating.vector()).isEqualTo("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:C/C:H/I:H/A:H");
            assertThat(rating.source()).as("rated by the database that published it").isEqualTo(detail.source());
        });
        assertThat(detail.cwes()).containsExactly(20, 400, 502, 917);
        assertThat(detail.advisories()).extracting(VulnerabilityRecord.Link::url)
                .as("an advisory among its references, not every web page")
                .containsExactly("https://nvd.nist.gov/vuln/detail/CVE-2021-44228");
        assertThat(detail.published()).isEqualTo(Instant.parse("2021-12-10T00:40:56Z"));
        assertThat(detail.updated()).isEqualTo(Instant.parse("2024-04-03T19:15:55Z"));
    }

    @Test
    void a_record_with_a_severity_word_alone_is_rated_by_it_and_one_with_no_page_of_its_own_is_osvs() {
        AdvisorySource.Advisory advisory = new OsvAdvisorySource(_ -> """
                {"vulns":[{"id": "OSV-2026-1", "database_specific": {"severity": "HIGH"}}]}""")
                .advisories("Maven", "org.example:lib", "1.0").getFirst();

        assertThat(advisory.detail().source()).isEqualTo(new VulnerabilityRecord.Source("OSV",
                "https://osv.dev/vulnerability/OSV-2026-1"));
        assertThat(advisory.detail().ratings()).singleElement().satisfies(rating -> {
            assertThat(rating.severity()).isEqualTo(Severity.HIGH);
            assertThat(rating.score()).isNull();
            assertThat(rating.vector()).isNull();
        });
    }
}
