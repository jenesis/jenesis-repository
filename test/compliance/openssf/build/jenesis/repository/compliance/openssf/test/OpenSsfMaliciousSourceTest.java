package build.jenesis.repository.compliance.openssf.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.Severity;
import build.jenesis.repository.compliance.VulnerabilityRecord;
import build.jenesis.repository.compliance.openssf.OpenSsfMaliciousSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * The curated OpenSSF malicious-packages source's response parsing, against a fixed endpoint - only the dataset's
 * {@code MAL-} records are kept and each is flagged malicious, the query is well-formed, the reviewer's severity
 * word maps to a band (absent stays NONE - the gate acts on the flag), CVE aliases and a range-ending fixed version
 * carry through, an empty result is empty, and a failed query fails closed.
 */
class OpenSsfMaliciousSourceTest {

    @Test
    void only_mal_records_are_kept_and_flagged_malicious() {
        String response = """
                {"vulns":[
                  {"id":"MAL-2024-1234"},
                  {"id":"GHSA-aaaa","database_specific":{"severity":"CRITICAL"}},
                  {"id":"CVE-2024-0001"}
                ]}""";
        OpenSsfMaliciousSource source = new OpenSsfMaliciousSource(_ -> response);
        assertThat(source.advisories("npm", "evil-package", "1.0.0")).singleElement()
                .satisfies(advisory -> {
                    assertThat(advisory.id()).isEqualTo("MAL-2024-1234");
                    assertThat(advisory.malicious()).as("a curated record is malicious by definition").isTrue();
                    assertThat(advisory.severity()).as("no severity word stays NONE; the flag gates").isEqualTo(Severity.NONE);
                    assertThat(advisory.detail().source()).as("attributed to the dataset, read through OSV")
                            .isEqualTo(new VulnerabilityRecord.Source("OpenSSF Malicious Packages",
                                    "https://osv.dev/vulnerability/MAL-2024-1234"));
                });
    }

    @Test
    void queries_the_package_at_the_requested_version() {
        StringBuilder seen = new StringBuilder();
        OpenSsfMaliciousSource source = new OpenSsfMaliciousSource(body -> {
            seen.append(body);
            return "{\"vulns\":[]}";
        });
        source.advisories("PyPI", "requessts", "1.0.0");
        assertThat(seen.toString())
                .contains("\"ecosystem\":\"PyPI\"")
                .contains("\"name\":\"requessts\"")
                .contains("\"version\":\"1.0.0\"");
    }

    /** OSV keys ecosystems by its own schema names and answers nothing to another spelling, so a Conan package is
     *  asked for under OSV's {@code ConanCenter} - asked under the product's name, every Conan query came back empty,
     *  which reads as "not malicious". */
    @Test
    void a_conan_package_is_asked_for_under_the_name_osv_knows_it_by() {
        StringBuilder seen = new StringBuilder();
        OpenSsfMaliciousSource source = new OpenSsfMaliciousSource(body -> {
            seen.append(body);
            return "{\"vulns\":[]}";
        });
        source.advisories("Conan", "zlib", "1.3");
        assertThat(seen.toString()).contains("\"ecosystem\":\"ConanCenter\"");
    }

    /** OSV refuses a query naming an ecosystem outside its schema with a {@code 400}, which this source, failing
     *  closed, would raise for every coordinate of it. */
    @Test
    void an_ecosystem_osv_does_not_publish_is_never_asked() {
        OpenSsfMaliciousSource source = new OpenSsfMaliciousSource(body -> {
            throw new AssertionError("asked " + body);
        });
        assertThat(source.advisories("conda", "numpy", "1.0")).isEmpty();
        assertThat(source.advisories("Jenesis", "org.acme.widget", "1.0")).isEmpty();
    }

    @Test
    void the_reviewers_severity_word_maps_to_a_band() {
        String response = """
                {"vulns":[
                  {"id":"MAL-2024-1","database_specific":{"severity":"CRITICAL"}},
                  {"id":"MAL-2024-2","database_specific":{"severity":"MODERATE"}}
                ]}""";
        OpenSsfMaliciousSource source = new OpenSsfMaliciousSource(_ -> response);
        assertThat(source.advisories("npm", "evil-package", "1.0.0"))
                .extracting(AdvisorySource.Advisory::severity)
                .containsExactly(Severity.CRITICAL, Severity.MEDIUM);
    }

    @Test
    void reads_the_cve_aliases_filtering_non_cve_ones() {
        String response = """
                {"vulns":[{"id":"MAL-2024-1234","aliases":["CVE-2024-9999","GHSA-dupe"]}]}""";
        OpenSsfMaliciousSource source = new OpenSsfMaliciousSource(_ -> response);
        assertThat(source.advisories("npm", "evil-package", "1.0.0").get(0).cves())
                .as("only CVE aliases, not other ids").containsExactly("CVE-2024-9999");
    }

    @Test
    void reads_the_fixed_version_ending_a_compromised_range() {
        String response = """
                {"vulns":[{"id":"MAL-2024-1234","affected":[
                  {"package":{"ecosystem":"npm","name":"compromised-lib"},
                   "ranges":[{"type":"SEMVER","events":[{"introduced":"2.1.0"},{"fixed":"2.1.3"}]}]},
                  {"package":{"ecosystem":"npm","name":"other-lib"},
                   "ranges":[{"type":"SEMVER","events":[{"introduced":"0"},{"fixed":"9.9.9"}]}]}]}]}""";
        OpenSsfMaliciousSource source = new OpenSsfMaliciousSource(_ -> response);
        assertThat(source.advisories("npm", "compromised-lib", "2.1.1")).singleElement()
                .extracting(AdvisorySource.Advisory::fixed)
                .as("only the queried package's range end, not the other affected package")
                .isEqualTo("2.1.3");
    }

    @Test
    void a_record_affecting_every_version_carries_no_fixed_version() {
        String response = """
                {"vulns":[{"id":"MAL-2024-1234","affected":[
                  {"package":{"ecosystem":"npm","name":"evil-package"},
                   "ranges":[{"type":"SEMVER","events":[{"introduced":"0"}]}]}]}]}""";
        OpenSsfMaliciousSource source = new OpenSsfMaliciousSource(_ -> response);
        assertThat(source.advisories("npm", "evil-package", "1.0.0")).singleElement()
                .extracting(AdvisorySource.Advisory::fixed).isNull();
    }

    @Test
    void a_clean_package_has_no_advisories() {
        OpenSsfMaliciousSource source = new OpenSsfMaliciousSource(_ -> "{\"vulns\":[]}");
        assertThat(source.advisories("npm", "left-pad", "1.3.0")).isEmpty();
    }

    @Test
    void a_failed_query_fails_closed() {
        OpenSsfMaliciousSource source = new OpenSsfMaliciousSource(_ -> {
            throw new java.io.IOException("network down");
        });
        assertThatExceptionOfType(UncheckedIOException.class)
                .isThrownBy(() -> source.advisories("npm", "evil-package", "1.0.0"));
    }

    @Test
    void follows_the_next_page_token_so_a_page_two_record_is_surfaced() {
        List<String> sent = new ArrayList<>();
        OpenSsfMaliciousSource source = new OpenSsfMaliciousSource(body -> {
            sent.add(body);
            return body.contains("\"page_token\"")
                    ? "{\"vulns\":[{\"id\":\"MAL-2024-2\"}]}"
                    : "{\"vulns\":[{\"id\":\"MAL-2024-1\"}],\"next_page_token\":\"tok-2\"}";
        });
        assertThat(source.advisories("npm", "evil-package", "1.0.0"))
                .extracting(AdvisorySource.Advisory::id)
                .as("both pages are drawn, so a page-two curated record is not invisible to the gate")
                .containsExactly("MAL-2024-1", "MAL-2024-2");
        assertThat(sent).hasSize(2);
        assertThat(sent.get(1)).as("the follow-up request echoes the token back as page_token")
                .contains("\"page_token\":\"tok-2\"");
    }

    @Test
    void a_feed_that_never_stops_paging_fails_closed_at_the_cap() {
        OpenSsfMaliciousSource source = new OpenSsfMaliciousSource(_ ->
                "{\"vulns\":[{\"id\":\"MAL-2024-9\"}],\"next_page_token\":\"never-ends\"}");
        assertThatExceptionOfType(UncheckedIOException.class)
                .as("a feed that keeps handing back a token fails closed at the page cap, never spins unbounded")
                .isThrownBy(() -> source.advisories("npm", "evil-package", "1.0.0"));
    }

    @Test
    void a_batch_fetches_only_the_malicious_records_it_names() {
        List<String> exchanged = new ArrayList<>();
        OpenSsfMaliciousSource batched = OpenSsfMaliciousSource.exchanging(request -> {
            exchanged.add(request.uri().getPath());
            return switch (request.uri().getPath()) {
                case "/v1/querybatch" -> """
                        {"results":[
                          {"vulns":[{"id":"MAL-2024-1234","modified":"m"},{"id":"GHSA-aaaa","modified":"m"}]},
                          {},
                          {"vulns":[{"id":"MAL-2024-1234","modified":"m"}]}
                        ]}""";
                case "/v1/vulns/MAL-2024-1234" -> "{\"id\":\"MAL-2024-1234\",\"summary\":\"steals tokens\"}";
                default -> throw new AssertionError("asked " + request.uri());
            };
        });

        List<List<AdvisorySource.Advisory>> answers = batched.advisories(List.of(
                new AdvisorySource.Query("npm", "evil-package", "1.0.0"),
                new AdvisorySource.Query("npm", "left-pad", "1.3.0"),
                new AdvisorySource.Query("npm", "evil-package", "1.0.1")));

        assertThat(answers.get(0)).singleElement().satisfies(advisory -> {
            assertThat(advisory.id()).isEqualTo("MAL-2024-1234");
            assertThat(advisory.malicious()).isTrue();
        });
        assertThat(answers.get(1)).isEmpty();
        assertThat(answers.get(2)).extracting(AdvisorySource.Advisory::id).containsExactly("MAL-2024-1234");
        assertThat(exchanged).as("a record the dataset does not curate is never fetched, and a curated one once")
                .containsExactly("/v1/querybatch", "/v1/vulns/MAL-2024-1234");
    }
}
