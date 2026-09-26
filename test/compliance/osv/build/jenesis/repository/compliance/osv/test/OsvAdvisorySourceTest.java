package build.jenesis.repository.compliance.osv.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.osv.OsvAdvisorySource;
import build.jenesis.repository.compliance.Severity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * The OSV source's response parsing and severity mapping, against a fixed endpoint - the query is well-formed, the
 * GitHub severity word becomes a band, an empty result is empty, and a failed query fails closed.
 */
class OsvAdvisorySourceTest {

    @Test
    void maps_each_vulnerability_to_its_severity_band() {
        String response = """
                {"vulns":[
                  {"id":"GHSA-aaaa","database_specific":{"severity":"CRITICAL"}},
                  {"id":"GHSA-bbbb","database_specific":{"severity":"MODERATE"}},
                  {"id":"GHSA-cccc","database_specific":{"severity":"HIGH"}}
                ]}""";
        OsvAdvisorySource source = new OsvAdvisorySource(_ -> response);
        List<AdvisorySource.Advisory> advisories = source.advisories("Maven", "org.example:lib", "1.0");
        assertThat(advisories).extracting(AdvisorySource.Advisory::id)
                .containsExactly("GHSA-aaaa", "GHSA-bbbb", "GHSA-cccc");
        assertThat(advisories).extracting(AdvisorySource.Advisory::severity)
                .containsExactly(Severity.CRITICAL, Severity.MEDIUM, Severity.HIGH);
    }

    @Test
    void queries_the_maven_package_at_the_requested_version() {
        StringBuilder seen = new StringBuilder();
        OsvAdvisorySource source = new OsvAdvisorySource(body -> {
            seen.append(body);
            return "{\"vulns\":[]}";
        });
        source.advisories("Maven", "org.apache.commons:commons-lang3", "3.14.0");
        assertThat(seen.toString())
                .contains("\"ecosystem\":\"Maven\"")
                .contains("\"name\":\"org.apache.commons:commons-lang3\"")
                .contains("\"version\":\"3.14.0\"");
    }

    @Test
    void a_conan_recipe_is_asked_for_in_the_ecosystem_osv_names_it() {
        // OSV answers only the ecosystems its schema defines, and it defines Conan's recipes as ConanCenter: asked as
        // "Conan", it answers nothing, and nothing reads as no advisory.
        StringBuilder seen = new StringBuilder();
        OsvAdvisorySource source = new OsvAdvisorySource(body -> {
            seen.append(body);
            return "{\"vulns\":[]}";
        });
        source.advisories("Conan", "zlib", "1.3.1");
        assertThat(seen.toString()).contains("\"ecosystem\":\"ConanCenter\"").contains("\"name\":\"zlib\"");
    }

    @Test
    void scores_severity_from_the_cvss_vector() {
        String response = """
                {"vulns":[
                  {"id":"OSV-1","severity":[{"type":"CVSS_V3","score":"CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H"}]},
                  {"id":"OSV-2","severity":[{"type":"CVSS_V3","score":"CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:L/I:N/A:N"}]}
                ]}""";
        OsvAdvisorySource source = new OsvAdvisorySource(_ -> response);
        assertThat(source.advisories("Maven", "org.example:lib", "1.0"))
                .extracting(AdvisorySource.Advisory::severity)
                .containsExactly(Severity.CRITICAL, Severity.MEDIUM);
    }

    @Test
    void scores_severity_from_a_cvss_v2_vector() {
        // The presence of an `Au:` metric selects the hand-rolled CVSS v2 base-score formula, distinct from the v3
        // path. Full network/low-complexity/complete-impact scores 10.0 -> CRITICAL; the classic partial-impact
        // vector scores 7.5 -> HIGH. Pinning the formula to these reference scores is what §8 requires.
        String response = """
                {"vulns":[
                  {"id":"CVE-v2-crit","severity":[{"type":"CVSS_V2","score":"AV:N/AC:L/Au:N/C:C/I:C/A:C"}]},
                  {"id":"CVE-v2-high","severity":[{"type":"CVSS_V2","score":"AV:N/AC:L/Au:N/C:P/I:P/A:P"}]}
                ]}""";
        OsvAdvisorySource source = new OsvAdvisorySource(_ -> response);
        assertThat(source.advisories("Maven", "org.example:lib", "1.0"))
                .extracting(AdvisorySource.Advisory::severity)
                .as("the hand-rolled CVSS v2 formula bands map onto the reference scores")
                .containsExactly(Severity.CRITICAL, Severity.HIGH);
    }

    @Test
    void scores_scope_changed_cvss3_vectors_at_their_reference_scores() {
        // The existing CVSS v3 test pins only S:U (scope-unchanged) vectors; the hand-rolled changed-scope branch (the
        // 1.08 multiplier, the changed PR weights, and the distinct changed-impact formula) went unexercised. Two
        // universally-published S:C reference vectors pin it to their exact scores:
        //   - the canonical reflected-XSS vector CVSS:3.1/AV:N/AC:L/PR:N/UI:R/S:C/C:L/I:L/A:N scores exactly 6.1 -> MEDIUM
        //   - Log4Shell's CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:C/C:H/I:H/A:H scores 10.72 pre-clamp, capped at 10.0 -> CRITICAL,
        //     which also pins the min(...,10) clamp that only the scope-changed 1.08 multiplier can drive past ten.
        String response = """
                {"vulns":[
                  {"id":"OSV-xss","severity":[{"type":"CVSS_V3","score":"CVSS:3.1/AV:N/AC:L/PR:N/UI:R/S:C/C:L/I:L/A:N"}]},
                  {"id":"OSV-log4shell","severity":[{"type":"CVSS_V3","score":"CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:C/C:H/I:H/A:H"}]}
                ]}""";
        OsvAdvisorySource source = new OsvAdvisorySource(_ -> response);
        assertThat(source.advisories("Maven", "org.example:lib", "1.0"))
                .extracting(AdvisorySource.Advisory::severity)
                .as("the exact reference scores 6.1 and 10.0 for these known S:C vectors band as MEDIUM and CRITICAL")
                .containsExactly(Severity.MEDIUM, Severity.CRITICAL);
    }

    @Test
    void the_scope_change_flag_alone_crosses_a_severity_boundary() {
        // The same metrics differ only in S:C vs S:U. The scope-changed formula (1.08 * (impact + exploitability))
        // scores 7.2 -> HIGH; the scope-unchanged formula over identical C/I/A/AV/AC/PR/UI scores 6.5 -> MEDIUM. Pinning
        // the pair at the 7.0 band boundary proves the hand-rolled scope-change arithmetic is live: were the 1.08
        // multiplier (or the changed-scope impact path) dropped, the S:C vector would fall back under 7.0 into MEDIUM
        // and this assertion would fail. A single mid-band check could not tell the two scopes apart.
        String changed = "{\"vulns\":[{\"id\":\"OSV-sc\",\"severity\":[{\"type\":\"CVSS_V3\","
                + "\"score\":\"CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:C/C:L/I:L/A:N\"}]}]}";
        String unchanged = "{\"vulns\":[{\"id\":\"OSV-su\",\"severity\":[{\"type\":\"CVSS_V3\","
                + "\"score\":\"CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:L/I:L/A:N\"}]}]}";
        assertThat(new OsvAdvisorySource(_ -> changed).advisories("Maven", "org.example:lib", "1.0"))
                .singleElement().extracting(AdvisorySource.Advisory::severity)
                .as("S:C scores 7.2 -> HIGH").isEqualTo(Severity.HIGH);
        assertThat(new OsvAdvisorySource(_ -> unchanged).advisories("Maven", "org.example:lib", "1.0"))
                .singleElement().extracting(AdvisorySource.Advisory::severity)
                .as("the identical metrics with S:U score 6.5 -> MEDIUM").isEqualTo(Severity.MEDIUM);
    }

    @Test
    void a_mal_prefixed_advisory_is_flagged_malicious() {
        // OSV carries the OpenSSF malicious-packages dataset too; a MAL- id is a deliberately harmful publication and
        // must set the malicious flag (the gate acts on the flag, not the severity), while an ordinary GHSA/CVE id does
        // not. GitHub and OpenSSF have this coverage; OSV did not.
        String response = """
                {"vulns":[
                  {"id":"MAL-2024-0001"},
                  {"id":"GHSA-aaaa","database_specific":{"severity":"HIGH"}}
                ]}""";
        OsvAdvisorySource source = new OsvAdvisorySource(_ -> response);
        List<AdvisorySource.Advisory> advisories = source.advisories("npm", "evil-package", "1.0.0");
        assertThat(advisories).extracting(AdvisorySource.Advisory::id).containsExactly("MAL-2024-0001", "GHSA-aaaa");
        assertThat(advisories.getFirst().severity())
                .as("a malicious-package record carries no score by design, and that is not an unreadable score: "
                        + "banded UNKNOWN it would outrank CRITICAL and a severity floor would reject what the "
                        + "gate's own rule quarantines")
                .isEqualTo(Severity.NONE);
        assertThat(advisories).extracting(AdvisorySource.Advisory::malicious)
                .as("only the MAL- record is malicious; the GHSA record is a flaw, not a malicious package")
                .containsExactly(true, false);
    }

    @Test
    void a_long_description_falls_back_to_a_bounded_prefix_of_the_details() {
        // The findings ledger persists what the advisory says; an unbounded details blob would bloat every record, so
        // the summary is preferred and, absent one, the details are truncated to a thousand characters.
        String longDetails = "x".repeat(1500);
        String withDetails = "{\"vulns\":[{\"id\":\"OSV-long\",\"database_specific\":{\"severity\":\"LOW\"},"
                + "\"details\":\"" + longDetails + "\"}]}";
        assertThat(new OsvAdvisorySource(_ -> withDetails).advisories("Maven", "org.example:lib", "1.0"))
                .singleElement().extracting(AdvisorySource.Advisory::description)
                .as("no summary: the details are truncated to a bounded 1000-character prefix")
                .isEqualTo("x".repeat(1000));

        String withSummary = "{\"vulns\":[{\"id\":\"OSV-sum\",\"database_specific\":{\"severity\":\"LOW\"},"
                + "\"summary\":\"a short summary\",\"details\":\"" + longDetails + "\"}]}";
        assertThat(new OsvAdvisorySource(_ -> withSummary).advisories("Maven", "org.example:lib", "1.0"))
                .singleElement().extracting(AdvisorySource.Advisory::description)
                .as("the one-line summary is preferred over the long details when present")
                .isEqualTo("a short summary");
    }

    @Test
    void the_cvss_vector_takes_precedence_over_the_github_word() {
        String response = """
                {"vulns":[{"id":"OSV-3",
                  "severity":[{"type":"CVSS_V3","score":"CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:L/I:N/A:N"}],
                  "database_specific":{"severity":"CRITICAL"}}]}""";
        OsvAdvisorySource source = new OsvAdvisorySource(_ -> response);
        assertThat(source.advisories("Maven", "org.example:lib", "1.0")).singleElement()
                .extracting(AdvisorySource.Advisory::severity).isEqualTo(Severity.MEDIUM);
    }

    @Test
    void reads_the_fixed_versions_from_the_matching_affected_ranges() {
        String response = """
                {"vulns":[{"id":"GHSA-jfh8","database_specific":{"severity":"CRITICAL"},"affected":[
                  {"package":{"ecosystem":"Maven","name":"org.apache.logging.log4j:log4j-core"},
                   "ranges":[{"type":"ECOSYSTEM","events":[
                     {"introduced":"2.0"},{"fixed":"2.3.1"},{"introduced":"2.4"},{"fixed":"2.12.2"}]}]},
                  {"package":{"ecosystem":"Maven","name":"org.other:unrelated"},
                   "ranges":[{"type":"ECOSYSTEM","events":[{"introduced":"0"},{"fixed":"9.9.9"}]}]}]}]}""";
        OsvAdvisorySource source = new OsvAdvisorySource(_ -> response);
        assertThat(source.advisories("Maven", "org.apache.logging.log4j:log4j-core", "2.14.1")).singleElement()
                .extracting(AdvisorySource.Advisory::fixed)
                .as("only the queried package's fixed versions, both branches, not the other affected package")
                .isEqualTo("2.3.1, 2.12.2");
    }

    @Test
    void an_advisory_with_no_fixed_version_carries_none() {
        String response = """
                {"vulns":[{"id":"GHSA-open","database_specific":{"severity":"HIGH"},"affected":[
                  {"package":{"ecosystem":"Maven","name":"org.example:lib"},
                   "ranges":[{"type":"ECOSYSTEM","events":[{"introduced":"0"}]}]}]}]}""";
        OsvAdvisorySource source = new OsvAdvisorySource(_ -> response);
        assertThat(source.advisories("Maven", "org.example:lib", "1.0")).singleElement()
                .extracting(AdvisorySource.Advisory::fixed).isNull();
    }

    @Test
    void reads_the_cve_aliases_filtering_non_cve_ones() {
        String response = """
                {"vulns":[{"id":"GHSA-jfh8","aliases":["CVE-2021-44228","GHSA-dupe"],
                  "database_specific":{"severity":"CRITICAL"}}]}""";
        OsvAdvisorySource source = new OsvAdvisorySource(_ -> response);
        assertThat(source.advisories("Maven", "org.example:lib", "1.0").get(0).cves())
                .as("only CVE aliases, not other GHSA ids").containsExactly("CVE-2021-44228");
    }

    @Test
    void a_clean_package_has_no_advisories() {
        OsvAdvisorySource source = new OsvAdvisorySource(_ -> "{\"vulns\":[]}");
        assertThat(source.advisories("Maven", "org.example:safe", "1.0")).isEmpty();
    }

    @Test
    void a_failed_query_fails_closed() {
        OsvAdvisorySource source = new OsvAdvisorySource(_ -> {
            throw new java.io.IOException("network down");
        });
        assertThatExceptionOfType(UncheckedIOException.class)
                .isThrownBy(() -> source.advisories("Maven", "org.example:lib", "1.0"));
    }

    @Test
    void follows_the_next_page_token_so_a_page_two_advisory_is_surfaced() {
        List<String> sent = new ArrayList<>();
        OsvAdvisorySource source = new OsvAdvisorySource(body -> {
            sent.add(body);
            return body.contains("\"page_token\"")
                    ? "{\"vulns\":[{\"id\":\"GHSA-page2\",\"database_specific\":{\"severity\":\"CRITICAL\"}}]}"
                    : "{\"vulns\":[{\"id\":\"GHSA-page1\",\"database_specific\":{\"severity\":\"HIGH\"}}],"
                            + "\"next_page_token\":\"tok-2\"}";
        });
        assertThat(source.advisories("Maven", "org.example:lib", "1.0"))
                .extracting(AdvisorySource.Advisory::id)
                .as("both pages are drawn, so a page-two advisory is not invisible to the gate")
                .containsExactly("GHSA-page1", "GHSA-page2");
        assertThat(sent).hasSize(2);
        assertThat(sent.get(1)).as("the follow-up request echoes the token back as page_token")
                .contains("\"page_token\":\"tok-2\"");
    }

    @Test
    void a_feed_that_never_stops_paging_fails_closed_at_the_cap() {
        OsvAdvisorySource source = new OsvAdvisorySource(_ ->
                "{\"vulns\":[{\"id\":\"GHSA-x\",\"database_specific\":{\"severity\":\"LOW\"}}],"
                        + "\"next_page_token\":\"never-ends\"}");
        assertThatExceptionOfType(UncheckedIOException.class)
                .as("a feed that keeps handing back a token fails closed at the page cap, never spins unbounded")
                .isThrownBy(() -> source.advisories("Maven", "org.example:lib", "1.0"));
    }

    @Test
    void a_coordinate_this_feed_could_not_screen_is_not_laundered_by_the_next_coordinate_that_answered() {
        // The §13 retrofit to the fail-closed half. The raise is what a CALLER acts on here - this feed never
        // hands back a degraded value - but the reading is what a CONSOLE renders, and it used to record successes
        // only: an OSV that had failed every lookup for three days still read "authoritative", because the last
        // instant it managed to stamp was still the last thing it had stamped. It is now derived per coordinate,
        // exactly as every other feed's is, so an unrelated coordinate answering does not clear it.
        OsvAdvisorySource source = new OsvAdvisorySource(body -> {
            if (body.contains("org.example:unreachable")) {
                throw new IOException("OSV unreachable for this coordinate");
            }
            return "{\"vulns\":[]}";
        });

        assertThat(source.advisories("Maven", "org.example:clean", "1.0")).isEmpty();
        assertThat(source.freshness().authoritative())
                .as("a feed answering every lookup it is given is authoritative").isTrue();

        assertThatExceptionOfType(UncheckedIOException.class)
                .isThrownBy(() -> source.advisories("Maven", "org.example:unreachable", "1.0"));
        assertThat(source.freshness().authoritative())
                .as("a coordinate this feed could not screen makes the reading non-authoritative").isFalse();

        assertThat(source.advisories("Maven", "org.example:clean", "1.0")).isEmpty();
        assertThat(source.freshness().authoritative())
                .as("and another coordinate screening cleanly says nothing about the one that did not")
                .isFalse();
        assertThat(source.freshness().refreshed())
                .as("the instant a rendered \"no advisories\" was last really screened is kept either way")
                .isPresent();
    }

    /**
     * A CVSS v4 vector is UNKNOWN, not NONE - the distinction a severity floor turns on.
     *
     * <p>The scorer reads v2 and v3 only, so a {@code CVSS:4.0} vector scores nothing. It used to fall through to
     * the GitHub severity word, which OSV entries from PyPA, RustSec and the Go database do not carry, and land on
     * {@code NONE}: {@code severityRank} 0, and an operator's {@code reject #severityRank >= 4} admitted a
     * critical advisory. v4 is in production use in OSV and GHSA today, so this was live rather than theoretical.
     *
     * <p>Scoring v4 was the alternative and was rejected - table-based, substantially more code, and it leaves
     * {@code NONE} meaning two things for the next vendor field the scorer cannot read. Saying UNKNOWN is the
     * answer {@code AdvisorySource} clause 4 already requires: a source must never answer clean when it means it
     * does not know.
     */
    @Test
    void an_unscorable_vector_is_unknown_rather_than_none() {
        String response = """
                {"vulns":[
                  {"id":"OSV-v4","severity":[{"type":"CVSS_V4",
                   "score":"CVSS:4.0/AV:N/AC:L/AT:N/PR:N/UI:N/VC:H/VI:H/VA:H/SC:N/SI:N/SA:N"}]}
                ]}""";
        assertThat(new OsvAdvisorySource(_ -> response).advisories("Maven", "org.example:lib", "1.0"))
                .extracting(AdvisorySource.Advisory::severity)
                .containsExactly(Severity.UNKNOWN);
    }

    /** An entry carrying no severity information at all is equally unknown, and a vendor word this does not read
     *  is too - all three used to be NONE, which is a positive claim none of them makes. */
    @Test
    void an_entry_with_no_readable_severity_is_unknown() {
        String response = """
                {"vulns":[
                  {"id":"OSV-bare"},
                  {"id":"OSV-word","database_specific":{"severity":"SEVERE-ISH"}}
                ]}""";
        assertThat(new OsvAdvisorySource(_ -> response).advisories("Maven", "org.example:lib", "1.0"))
                .extracting(AdvisorySource.Advisory::severity)
                .containsExactly(Severity.UNKNOWN, Severity.UNKNOWN);
    }

    /**
     * NONE stays reachable and keeps meaning what it says: a feed that scored the advisory, and scored it zero.
     * If the unknown band swallowed that too, the vocabulary would have traded one conflation for another.
     */
    @Test
    void a_scored_zero_is_still_none() {
        String response = """
                {"vulns":[
                  {"id":"OSV-zero","severity":[{"type":"CVSS_V3",
                   "score":"CVSS:3.1/AV:N/AC:H/PR:H/UI:R/S:U/C:N/I:N/A:N"}]}
                ]}""";
        assertThat(new OsvAdvisorySource(_ -> response).advisories("Maven", "org.example:lib", "1.0"))
                .extracting(AdvisorySource.Advisory::severity)
                .containsExactly(Severity.NONE);
    }
}
