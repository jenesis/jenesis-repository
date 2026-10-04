package build.jenesis.repository.compliance.osv.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.ComplianceSources;
import build.jenesis.repository.compliance.osv.OsvAdvisorySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The OSV feed follows its settings without a restart: switched on, it is the feed the gate screens with from the next
 * ask; a write to an unrelated setting keeps it, warm cache and all; re-pointed, it is built again.
 */
class ComplianceSourcesTest {

    private final Map<String, String> settings = new ConcurrentHashMap<>();

    @Test
    void the_osv_feed_switched_on_is_screened_with_at_the_next_ask() {
        try (ComplianceSources sources = new ComplianceSources(settings::get)) {
            assertThat(sources.advisories()).as("nothing switched on").isSameAs(AdvisorySource.none());

            settings.put("osv", "true");

            assertThat(sources.advisoryFeeds()).containsOnlyKeys("osv");
            assertThat(sources.advisoryFeeds().get("osv")).isInstanceOf(OsvAdvisorySource.class);
            assertThat(sources.advisories()).isNotSameAs(AdvisorySource.none());

            settings.put("osv", "false");
            assertThat(sources.advisories()).as("and switched off again").isSameAs(AdvisorySource.none());
        }
    }

    @Test
    void an_unrelated_write_keeps_the_feed_and_re_pointing_it_builds_it_again() {
        settings.put("osv", "true");
        try (ComplianceSources sources = new ComplianceSources(settings::get)) {
            AdvisorySource feed = sources.advisoryFeeds().get("osv");
            AdvisorySource merged = sources.advisories();

            settings.put("deny-list", "org.acme:widget");
            assertThat(sources.advisoryFeeds().get("osv")).as("a setting the feed never read").isSameAs(feed);
            assertThat(sources.advisories()).isSameAs(merged);

            settings.put("osv-endpoint", "https://osv.mirror.example");
            assertThat(sources.advisoryFeeds().get("osv")).as("re-pointed").isNotSameAs(feed);
        }
    }
}
