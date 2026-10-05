package build.jenesis.repository.compliance.spi.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.Ecosystems;
import build.jenesis.repository.compliance.Freshness;
import build.jenesis.repository.compliance.ScreenedThrough;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the advisory sources the contract itself builds declare they cover: no active feed covers nothing, a fixed map
 * every ecosystem, and feeds merged together the union of what each covers - so an ecosystem none of them covers
 * reads as unscreened rather than clean.
 */
class AdvisoryCoverageTest {

    private static AdvisorySource covering(String... ecosystems) {
        return new AdvisorySource() {
            @Override
            public List<Advisory> advisories(String ecosystem, String coordinate, String version) {
                return List.of();
            }

            @Override
            public Set<String> ecosystems() {
                return Set.of(ecosystems);
            }

            @Override
            public Freshness freshness() {
                return Freshness.FIXED;
            }
        };
    }

    @Test
    void no_active_feed_covers_nothing() {
        assertThat(AdvisorySource.none().ecosystems()).isEmpty();
    }

    @Test
    void a_fixed_source_covers_every_ecosystem() {
        assertThat(AdvisorySource.of(Map.of()).ecosystems()).isEqualTo(Ecosystems.canonical());
    }

    @Test
    void merged_feeds_cover_what_any_of_them_covers() {
        assertThat(AdvisorySource.combined(covering("Maven", "npm"), covering("npm", "PyPI")).ecosystems())
                .containsExactlyInAnyOrder("Maven", "npm", "PyPI");
        assertThat(AdvisorySource.resolve(List.of(covering("Maven"), covering("Go"))).ecosystems())
                .doesNotContain("conda");
    }

    @Test
    void a_cached_copy_is_screened_through_the_enabled_feeds_covering_its_ecosystem_or_reads_unscreened() {
        SequencedMap<String, AdvisorySource> enabled = new LinkedHashMap<>();
        enabled.put("osv", covering("Maven", "npm"));
        enabled.put("github", covering("npm"));

        assertThat(ScreenedThrough.cached("npm", enabled))
                .isEqualTo(new ScreenedThrough(ScreenedThrough.Basis.FEEDS, List.of("osv", "github")));
        assertThat(ScreenedThrough.cached("maven", enabled).feeds()).as("the label matched as the feeds match it")
                .containsExactly("osv");
        assertThat(ScreenedThrough.cached("conda", enabled)).satisfies(screened -> {
            assertThat(screened.basis()).isEqualTo(ScreenedThrough.Basis.UNCOVERED);
            assertThat(screened.unscreened()).as("no finding from no feed is not clean").isTrue();
        });
        assertThat(ScreenedThrough.cached("npm", new LinkedHashMap<>()).unscreened()).isTrue();
    }

    @Test
    void a_published_version_is_screened_through_its_closure_or_by_nothing() {
        assertThat(ScreenedThrough.published(true).basis()).isEqualTo(ScreenedThrough.Basis.CLOSURE);
        assertThat(ScreenedThrough.published(true).unscreened()).isFalse();
        assertThat(ScreenedThrough.published(false).unscreened()).isTrue();
    }
}
