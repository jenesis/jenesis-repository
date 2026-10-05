package build.jenesis.repository.compliance.openssf.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.openssf.OpenSsfMaliciousSource;
import build.jenesis.repository.compliance.osv.OsvAdvisorySource;
import build.jenesis.repository.compliance.osv.OsvQuery;
import build.jenesis.repository.feed.FeedRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * The vulnerability feed and the malicious-package feed ask OSV the same question about a copy one screen judges, and
 * share the one answer: whichever asks first reaches OSV, the other maps the same records its own way - the
 * vulnerability feed every record, the malicious-package feed its {@code MAL-} ones - and a failure is never shared.
 */
class SharedOsvAnswerTest {

    private static final String ANSWER = """
            {"vulns":[{"id":"MAL-2026-0001","summary":"a malicious release"},
                      {"id":"GHSA-aaaa-bbbb-cccc","summary":"a vulnerability"}]}""";

    @Test
    void two_feeds_asking_the_same_copy_cost_osv_one_query() {
        List<String> asked = new ArrayList<>();
        OsvQuery.Shared shared = OsvQuery.Shared.between();
        AdvisorySource osv = OsvAdvisorySource.exchanging(request -> answer(asked, request), shared);
        AdvisorySource openssf = OpenSsfMaliciousSource.exchanging(request -> answer(asked, request), shared);

        assertThat(osv.advisories("npm", "evil-package", "1.0.0")).extracting(AdvisorySource.Advisory::id)
                .contains("MAL-2026-0001", "GHSA-aaaa-bbbb-cccc");
        assertThat(openssf.advisories("npm", "evil-package", "1.0.0")).extracting(AdvisorySource.Advisory::id)
                .as("the malicious-package feed's reading of the same answer").containsExactly("MAL-2026-0001");
        assertThat(asked).as("one query for the two feeds").containsExactly("/v1/query");

        openssf.advisories("npm", "other-package", "2.0.0");
        assertThat(asked).as("another question is asked").hasSize(2);
    }

    @Test
    void sources_sharing_nothing_each_ask_and_a_failure_is_not_shared() {
        List<String> asked = new ArrayList<>();
        AdvisorySource osv = OsvAdvisorySource.exchanging(request -> answer(asked, request), OsvQuery.Shared.none());
        AdvisorySource openssf = OpenSsfMaliciousSource.exchanging(request -> answer(asked, request),
                OsvQuery.Shared.none());
        osv.advisories("npm", "evil-package", "1.0.0");
        openssf.advisories("npm", "evil-package", "1.0.0");
        assertThat(asked).hasSize(2);

        OsvQuery.Shared shared = OsvQuery.Shared.between();
        AdvisorySource failing = OpenSsfMaliciousSource.exchanging(_ -> {
            throw new IOException("the feed is down");
        }, shared);
        assertThatExceptionOfType(UncheckedIOException.class)
                .isThrownBy(() -> failing.advisories("npm", "evil-package", "1.0.0"));
        List<String> after = new ArrayList<>();
        AdvisorySource recovered = OsvAdvisorySource.exchanging(request -> answer(after, request), shared);
        assertThat(recovered.advisories("npm", "evil-package", "1.0.0")).isNotEmpty();
        assertThat(after).as("the failure left nothing to share, so the next ask reaches the feed").hasSize(1);
    }

    private static String answer(List<String> asked, FeedRequest request) {
        String path = request.uri().getPath();
        if (path.equals("/v1/query")) {
            asked.add(path);
            return ANSWER;
        }
        return "{}";
    }
}
