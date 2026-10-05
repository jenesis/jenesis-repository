package build.jenesis.repository.compliance.spi.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.Ecosystems;
import build.jenesis.repository.compliance.Freshness;

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
}
