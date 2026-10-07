package build.jenesis.repository.compliance.spi.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.Freshness;
import build.jenesis.repository.compliance.Severity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Which of the deployment's advisory feeds screen a repository: every one it switched on where the repository names
 * none, none where it names {@value AdvisorySource#NONE_SELECTED}, exactly those it names otherwise; and the merged
 * source the gate asks narrowed to them - while a source a caller built of its own, the none a published version's gate
 * holds among them, is never widened or narrowed, and a name that is not on makes every query an outage saying so. A
 * mirror screens only a repository that names it.
 */
class AdvisoryFeedSelectionTest {

    private static final AdvisorySource.Advisory FROM_OSV = new AdvisorySource.Advisory("CVE-2026-1", Severity.HIGH,
            false, null, List.of("CVE-2026-1"), "known to the first feed", List.of());

    private static final AdvisorySource.Advisory FROM_GITHUB = new AdvisorySource.Advisory("GHSA-2", Severity.LOW,
            false, null, List.of(), "known to the second feed", List.of());

    private final SequencedMap<String, AdvisorySource> enabled = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        enabled.put("github", AdvisorySource.of(Map.of("org.acme:lib", List.of(FROM_GITHUB))));
        enabled.put("osv", AdvisorySource.of(Map.of("org.acme:lib", List.of(FROM_OSV))));
    }

    @Test
    void a_repository_naming_no_feed_is_screened_by_every_one_switched_on() {
        assertThat(AdvisorySource.selected(enabled, _ -> null).sequencedKeySet())
                .as("every feed switched on, in the deployment's order").containsExactlyElementsOf(enabled.keySet());
        assertThat(AdvisorySource.selected(enabled, repository("")).sequencedKeySet())
                .containsExactlyElementsOf(enabled.keySet());
    }

    @Test
    void a_repository_names_its_feeds_and_none_names_none() {
        assertThat(AdvisorySource.selected(enabled, repository(" osv "))).containsOnlyKeys("osv");
        assertThat(AdvisorySource.selected(enabled, repository("osv,github")).sequencedKeySet())
                .as("in the order the setting names them").containsExactly("osv", "github");
        assertThat(AdvisorySource.selected(enabled, repository("github,osv,github")).sequencedKeySet())
                .as("a name given twice counted once").containsExactly("github", "osv");
        assertThat(AdvisorySource.selected(enabled, repository("NONE"))).isEmpty();
    }

    @Test
    void a_mirror_screens_only_a_repository_naming_it() {
        AdvisorySource.Advisory fromMirror = new AdvisorySource.Advisory("GHSA-3", Severity.HIGH, false, null,
                List.of(), "known to the mirror", List.of());
        enabled.put("osv-mirror", new Mirror(fromMirror));

        assertThat(AdvisorySource.selected(enabled, _ -> null).sequencedKeySet())
                .as("a blank selection takes every feed switched on but the mirror").containsExactly("github", "osv");
        assertThat(AdvisorySource.selected(enabled, repository("osv-mirror"))).containsOnlyKeys("osv-mirror");
        AdvisorySource merged = AdvisorySource.resolve(enabled);
        assertThat(merged.advisories("Maven", "org.acme:lib", "1.0"))
                .as("the deployment's merge asks no mirror").containsExactly(FROM_GITHUB, FROM_OSV);
        assertThat(AdvisorySource.forRepository(merged, repository("osv,osv-mirror"))
                .advisories("Maven", "org.acme:lib", "1.0")).containsExactly(FROM_OSV, fromMirror);
    }

    /** A mirror answering every lookup with one advisory, refreshing nothing. */
    private record Mirror(AdvisorySource.Advisory answer) implements AdvisorySource.Mirror {

        @Override
        public List<Advisory> advisories(String ecosystem, String coordinate, String version) {
            return List.of(answer);
        }

        @Override
        public Set<String> ecosystems() {
            return Set.of("Maven");
        }

        @Override
        public Freshness freshness() {
            return Freshness.FIXED;
        }

        @Override
        public void mirror(Set<String> ecosystems) {
        }

        @Override
        public List<Copy> copies() {
            return List.of();
        }

        @Override
        public Freshness refresh() {
            return Freshness.FIXED;
        }

        @Override
        public Optional<String> snapshot() {
            return Optional.empty();
        }
    }

    @Test
    void a_feed_that_is_not_on_fails_naming_it() {
        assertThatThrownBy(() -> AdvisorySource.selected(enabled, repository("osv,snyk")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("'snyk'")
                .hasMessageContaining(AdvisorySource.SELECTION);
    }

    @Test
    void the_merged_source_is_narrowed_to_what_the_repository_selects() {
        AdvisorySource merged = AdvisorySource.resolve(enabled);
        assertThat(merged.advisories("Maven", "org.acme:lib", "1.0")).containsExactly(FROM_GITHUB, FROM_OSV);

        assertThat(AdvisorySource.forRepository(merged, repository("osv")).advisories("Maven", "org.acme:lib", "1.0"))
                .containsExactly(FROM_OSV);
        assertThat(AdvisorySource.forRepository(merged, _ -> null)).as("nothing selected").isSameAs(merged);
        assertThat(AdvisorySource.forRepository(merged, repository("none")))
                .as("none selected").isSameAs(AdvisorySource.none());
    }

    @Test
    void a_source_a_caller_built_is_never_narrowed_or_widened() {
        assertThat(AdvisorySource.forRepository(AdvisorySource.none(), repository("osv")))
                .as("the gate of what is published here asks no feed whatever a repository selects")
                .isSameAs(AdvisorySource.none());
        AdvisorySource own = enabled.get("osv");
        assertThat(AdvisorySource.forRepository(own, repository("github"))).isSameAs(own);
    }

    @Test
    void a_selection_naming_a_feed_that_is_not_on_is_an_outage_of_every_query() {
        AdvisorySource misnamed = AdvisorySource.forRepository(AdvisorySource.resolve(enabled), repository("snyk"));

        assertThatThrownBy(() -> misnamed.advisories("Maven", "org.acme:lib", "1.0"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("'snyk'");
    }

    private static UnaryOperator<String> repository(String selection) {
        return key -> AdvisorySource.SELECTION.equals(key) ? selection : null;
    }
}
