package build.jenesis.repository.compliance.github.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.AdvisorySource.Advisory;
import build.jenesis.repository.compliance.github.GitHubAdvisorySource;
import build.jenesis.repository.compliance.Severity;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The GitHub Advisory Database source's parsing against a fixed endpoint, and its composition with another feed: an
 * advisory maps to its CVSS-scored severity, fixed version and CVE aliases; a {@code malware} advisory is flagged; an
 * ecosystem GitHub does not track is not queried; and {@link AdvisorySource#combined} reports a vulnerability both
 * feeds carry only once.
 */
class GitHubAdvisorySourceTest {

    private static final String LODASH = """
            [{
              "ghsa_id": "GHSA-jf85-cpcp-j695",
              "cve_id": "CVE-2019-10744",
              "type": "reviewed",
              "severity": "critical",
              "cvss": {"vector_string": "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H", "score": 9.1},
              "identifiers": [{"type": "GHSA", "value": "GHSA-jf85-cpcp-j695"}, {"type": "CVE", "value": "CVE-2019-10744"}],
              "vulnerabilities": [
                {"package": {"ecosystem": "npm", "name": "lodash"},
                 "vulnerable_version_range": "< 4.17.12", "first_patched_version": {"identifier": "4.17.12"}}]
            }]""";

    @Test
    void an_advisory_maps_to_its_severity_fixed_version_and_cves() {
        GitHubAdvisorySource source = new GitHubAdvisorySource((ecosystem, affects, next) -> new GitHubAdvisorySource.Endpoint.Page(LODASH, null));

        assertThat(source.advisories("npm", "lodash", "4.17.11")).singleElement().satisfies(advisory -> {
            assertThat(advisory.id()).isEqualTo("GHSA-jf85-cpcp-j695");
            assertThat(advisory.severity()).isEqualTo(Severity.CRITICAL);
            assertThat(advisory.malicious()).isFalse();
            assertThat(advisory.fixed()).isEqualTo("4.17.12");
            assertThat(advisory.cves()).containsExactly("CVE-2019-10744");
        });
    }

    @Test
    void pagination_to_a_cross_origin_private_host_fails_closed() {
        // GitHub's rel="next" is followed verbatim. A compromised/MITM'd feed that points the next page at a
        // cross-origin private/metadata host must be refused (fail closed), not fetched - otherwise the feed steers the
        // server-side pagination fetch into its own internal network (SSRF).
        GitHubAdvisorySource source = new GitHubAdvisorySource((ecosystem, affects, next) ->
                new GitHubAdvisorySource.Endpoint.Page("[]", "http://169.254.169.254/latest/meta-data/iam"));

        assertThatThrownBy(() -> source.advisories("npm", "lodash", "4.17.11"))
                .isInstanceOf(UncheckedIOException.class)
                .hasStackTraceContaining("cross-origin");
    }

    @Test
    void pagination_to_a_cross_origin_public_host_fails_closed_without_leaking_the_token() {
        // A rel="next" pointing at a PUBLIC attacker host (not private, so it passes an SSRF-only screen) is followed
        // verbatim with the feed's Bearer token attached - a credential exfiltration. GitHub pagination never leaves
        // the feed host, so any cross-origin next is refused before the fetch, so the token never reaches the endpoint.
        int[] calls = new int[1];
        GitHubAdvisorySource source = new GitHubAdvisorySource((ecosystem, affects, next) -> {
            calls[0]++;
            return new GitHubAdvisorySource.Endpoint.Page("[]", "https://evil.example/collect");
        });

        assertThatThrownBy(() -> source.advisories("npm", "lodash", "4.17.11"))
                .isInstanceOf(UncheckedIOException.class)
                .hasStackTraceContaining("cross-origin");
        assertThat(calls[0]).as("the cross-origin next was never fetched - the token was not sent there").isEqualTo(1);
    }

    @Test
    void pagination_on_the_operator_feed_host_is_followed() {
        // The control: a next page on the operator's own feed host (api.github.com, incl. a private self-hosted GHE) is
        // trusted and followed, so the SSRF screen refuses only the cross-origin vector and never a legitimate next.
        int[] calls = new int[1];
        GitHubAdvisorySource source = new GitHubAdvisorySource((ecosystem, affects, next) -> {
            calls[0]++;
            return next == null
                    ? new GitHubAdvisorySource.Endpoint.Page(LODASH, "https://api.github.com/advisories?after=cursor")
                    : new GitHubAdvisorySource.Endpoint.Page("[]", null);
        });

        assertThat(source.advisories("npm", "lodash", "4.17.11")).hasSize(1);
        assertThat(calls[0]).as("the same-host next page was followed").isEqualTo(2);
    }

    @Test
    void the_ecosystem_and_versioned_coordinate_are_sent() {
        String[] sent = new String[2];
        GitHubAdvisorySource source = new GitHubAdvisorySource((ecosystem, affects, next) -> {
            sent[0] = ecosystem;
            sent[1] = affects;
            return new GitHubAdvisorySource.Endpoint.Page("[]", null);
        });
        source.advisories("PyPI", "requests", "2.31.0");
        assertThat(sent[0]).as("PyPI maps to GitHub's pip").isEqualTo("pip");
        assertThat(sent[1]).isEqualTo("requests@2.31.0");
    }

    @Test
    void a_malware_advisory_is_flagged() {
        GitHubAdvisorySource source = new GitHubAdvisorySource((ecosystem, affects, next) ->
                new GitHubAdvisorySource.Endpoint.Page("""
                [{"ghsa_id": "GHSA-mal", "type": "malware", "severity": "critical",
                  "vulnerabilities": [{"package": {"name": "evil"}}]}]""", null));

        assertThat(source.advisories("npm", "evil", "1.0.0")).singleElement().satisfies(advisory -> {
            assertThat(advisory.id()).isEqualTo("GHSA-mal");
            assertThat(advisory.malicious()).isTrue();
        });
    }

    @Test
    void an_untracked_ecosystem_is_not_queried() {
        boolean[] queried = {false};
        GitHubAdvisorySource source = new GitHubAdvisorySource((ecosystem, affects, next) -> {
            queried[0] = true;
            return new GitHubAdvisorySource.Endpoint.Page("[]", null);
        });
        assertThat(source.advisories("Debian", "bash", "5.2")).isEmpty();
        assertThat(queried[0]).as("GitHub does not track Debian, so no request is made").isFalse();
    }

    @Test
    void combined_reports_a_shared_vulnerability_once() {
        AdvisorySource osv = AdvisorySource.of(Map.of("lodash", List.of(
                new Advisory("CVE-2019-10744", Severity.CRITICAL, false, "4.17.12", List.of("CVE-2019-10744")))));
        AdvisorySource github = new GitHubAdvisorySource((ecosystem, affects, next) -> new GitHubAdvisorySource.Endpoint.Page(LODASH, null));

        assertThat(AdvisorySource.combined(osv, github).advisories("npm", "lodash", "4.17.11"))
                .as("the same vulnerability - OSV's CVE and GitHub's GHSA - counts once")
                .singleElement().satisfies(advisory -> assertThat(advisory.id()).isEqualTo("CVE-2019-10744"));
    }

    @Test
    void the_severity_word_bands_when_no_cvss_score_is_present() {
        assertThat(source("[{\"ghsa_id\":\"GHSA-mod\",\"severity\":\"moderate\","
                + "\"vulnerabilities\":[{\"package\":{\"name\":\"lib\"}}]}]").advisories("npm", "lib", "1.0"))
                .singleElement().extracting(Advisory::severity)
                .as("'moderate' with no cvss score maps to MEDIUM").isEqualTo(Severity.MEDIUM);
        assertThat(source("[{\"ghsa_id\":\"GHSA-crit\",\"severity\":\"critical\","
                + "\"vulnerabilities\":[{\"package\":{\"name\":\"lib\"}}]}]").advisories("npm", "lib", "1.0"))
                .singleElement().extracting(Advisory::severity).isEqualTo(Severity.CRITICAL);
    }

    @Test
    void the_description_prefers_summary_then_falls_back_and_truncates() {
        assertThat(source("[{\"ghsa_id\":\"G1\",\"severity\":\"high\",\"summary\":\"a concise summary\","
                + "\"description\":\"the long form\"}]").advisories("npm", "lib", "1.0").getFirst().description())
                .as("the one-line summary is carried when present").isEqualTo("a concise summary");
        assertThat(source("[{\"ghsa_id\":\"G2\",\"severity\":\"high\",\"summary\":\"   \","
                + "\"description\":\"the long form detail\"}]").advisories("npm", "lib", "1.0").getFirst().description())
                .as("a blank summary falls back to the long-form description").isEqualTo("the long form detail");
        assertThat(source("[{\"ghsa_id\":\"G3\",\"severity\":\"high\",\"description\":\"" + "x".repeat(1500)
                + "\"}]").advisories("npm", "lib", "1.0").getFirst().description())
                .as("a >1000-char description is bounded to a 1000-char prefix").hasSize(1000);
    }

    @Test
    void the_fixed_version_reads_only_the_queried_packages_patch() {
        // Two vulnerabilities in one advisory: one names a different package, one names the queried coordinate. Only
        // the queried package's first_patched_version is read, never the co-listed package's.
        GitHubAdvisorySource source = source("""
                [{"ghsa_id":"GHSA-multi","severity":"high","vulnerabilities":[
                  {"package":{"name":"other-pkg"},"first_patched_version":{"identifier":"9.9.9"}},
                  {"package":{"name":"lodash"},"first_patched_version":{"identifier":"4.17.12"}}]}]""");
        assertThat(source.advisories("npm", "lodash", "4.17.11")).singleElement()
                .extracting(Advisory::fixed)
                .as("only the queried package's patched version is read").isEqualTo("4.17.12");
    }

    @Test
    void the_real_link_header_parser_follows_rel_next_across_multiple_relations() {
        WireMockServer server = new WireMockServer(
                WireMockConfiguration.options().bindAddress("localhost").dynamicPort());
        server.start();
        try {
            String host = "http://localhost:" + server.port();
            server.stubFor(get(urlPathEqualTo("/advisories")).willReturn(aResponse().withStatus(200)
                    .withHeader("Link", "<" + host + "/second-page>; rel=\"next\", "
                            + "<" + host + "/advisories?page=99>; rel=\"last\"")
                    .withBody("[{\"ghsa_id\":\"GHSA-page1\",\"severity\":\"high\"}]")));
            server.stubFor(get(urlPathEqualTo("/second-page")).willReturn(aResponse().withStatus(200)
                    .withBody("[{\"ghsa_id\":\"GHSA-page2\",\"severity\":\"critical\"}]")));

            assertThat(GitHubAdvisorySource.over(URI.create(host), null, Clock.systemUTC()).advisories("npm", "lodash", "4.17.11"))
                    .extracting(Advisory::id)
                    .as("the production RFC5988 parser extracts rel=next from a multi-relation header and follows it")
                    .containsExactly("GHSA-page1", "GHSA-page2");
        } finally {
            server.stop();
        }
    }

    @Test
    void the_real_link_header_parser_stops_when_no_rel_next_is_present() {
        WireMockServer server = new WireMockServer(
                WireMockConfiguration.options().bindAddress("localhost").dynamicPort());
        server.start();
        try {
            String host = "http://localhost:" + server.port();
            server.stubFor(get(urlPathEqualTo("/advisories")).willReturn(aResponse().withStatus(200)
                    .withHeader("Link", "<" + host + "/advisories?page=1>; rel=\"prev\", "
                            + "<" + host + "/advisories?page=9>; rel=\"last\"")
                    .withBody("[{\"ghsa_id\":\"GHSA-only\",\"severity\":\"moderate\"}]")));

            assertThat(GitHubAdvisorySource.over(URI.create(host), null, Clock.systemUTC()).advisories("npm", "lodash", "4.17.11"))
                    .extracting(Advisory::id)
                    .as("a header with only rel=prev/last names no next page, so the walk stops at one page")
                    .containsExactly("GHSA-only");
        } finally {
            server.stop();
        }
    }

    private static GitHubAdvisorySource source(String body) {
        return new GitHubAdvisorySource((ecosystem, affects, next) -> new GitHubAdvisorySource.Endpoint.Page(body, null));
    }

    @Test
    void follows_the_link_next_header_so_a_page_two_advisory_is_surfaced() {
        List<String> sentNext = new ArrayList<>();
        GitHubAdvisorySource source = new GitHubAdvisorySource((ecosystem, affects, next) -> {
            sentNext.add(next);
            return next == null
                    ? new GitHubAdvisorySource.Endpoint.Page(
                            "[{\"ghsa_id\":\"GHSA-page1\",\"severity\":\"high\"}]",
                            "https://api.github.com/advisories?per_page=100&after=cursor2")
                    : new GitHubAdvisorySource.Endpoint.Page(
                            "[{\"ghsa_id\":\"GHSA-page2\",\"severity\":\"critical\"}]", null);
        });
        assertThat(source.advisories("npm", "lodash", "4.17.11"))
                .extracting(Advisory::id)
                .as("both pages are drawn, so a page-two advisory is not invisible to the gate")
                .containsExactly("GHSA-page1", "GHSA-page2");
        assertThat(sentNext).as("the first request builds from the query, the second follows the Link URL verbatim")
                .containsExactly(null, "https://api.github.com/advisories?per_page=100&after=cursor2");
    }

    @Test
    void a_feed_that_never_stops_paging_fails_closed_at_the_cap() {
        GitHubAdvisorySource source = new GitHubAdvisorySource((ecosystem, affects, next) ->
                new GitHubAdvisorySource.Endpoint.Page("[]", "https://api.github.com/advisories?after=never-ends"));
        assertThatThrownBy(() -> source.advisories("npm", "lodash", "4.17.11"))
                .as("a feed that keeps advertising a next page fails closed at the cap, never spins unbounded")
                .isInstanceOf(UncheckedIOException.class);
    }
}
