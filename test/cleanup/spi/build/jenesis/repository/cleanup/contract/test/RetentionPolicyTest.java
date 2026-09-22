package build.jenesis.repository.cleanup.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.cleanup.CleanupPlan;
import build.jenesis.repository.cleanup.Release;
import build.jenesis.repository.cleanup.RetentionPolicy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a retention policy decides, against a fixed clock and a plain list of releases - no store, no sweep engine
 * and no scheduler. The keep-last cap, the max-age and prerelease-expiry rules and how they compose, the
 * not-downloaded-for rule, the pin that overrides all of them, the never-empty guarantee and per-coordinate and
 * per-ecosystem independence. Prerelease is a per-release flag a format supplies, so a test sets it explicitly
 * rather than relying on a version convention.
 */
class RetentionPolicyTest {

    private static final Instant NOW = Instant.parse("2026-06-27T00:00:00Z");

    @Test
    void keep_last_caps_the_number_of_versions_per_coordinate() {
        List<Release> releases = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            releases.add(new Release("org.example:lib", "1." + i, false, NOW.minus(Duration.ofDays(12 - i))));
        }
        CleanupPlan plan = new RetentionPolicy(10).plan(releases, NOW);
        assertThat(plan.evictions()).hasSize(2);
        assertThat(plan.evictions()).allSatisfy(e -> assertThat(e.reason()).isEqualTo("beyond keep-last=10"));
        assertThat(plan.evictions()).extracting(e -> e.release().version()).containsExactlyInAnyOrder("1.0", "1.1");
    }

    @Test
    void a_zero_or_negative_duration_dial_is_rejected_at_construction() {
        // A zero or negative duration would invert the rule - every past publish is "older" than PT0S or PT-1H -
        // so one mistyped dial would mass-delete everything but each coordinate's newest version on the next
        // sweep. A deletion policy fails loudly at construction, never at sweep time.
        assertThatThrownBy(() -> new RetentionPolicy(0).maxAge(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetentionPolicy(0).maxAge(Duration.ofHours(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetentionPolicy(0).prereleaseExpiry(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetentionPolicy(0).notDownloadedFor(Duration.parse("PT-1H")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RetentionPolicy.fromConfig(key -> key.equals("max-age") ? "PT-24H" : null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_bare_keep_last_zero_policy_with_no_duration_dial_evicts_nothing() {
        List<Release> releases = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            releases.add(new Release("org.example:lib", "1." + i, false, NOW.minus(Duration.ofDays(500 + i))));
        }
        assertThat(new RetentionPolicy(0).plan(releases, NOW).isEmpty())
                .as("no dial set means nothing is ever evicted, however old").isTrue();
    }

    @Test
    void max_age_evicts_old_versions_but_never_the_newest() {
        List<Release> releases = List.of(
                new Release("org.example:lib", "3.0", false, NOW.minus(Duration.ofDays(10))),
                new Release("org.example:lib", "2.0", false, NOW.minus(Duration.ofDays(120))),
                new Release("org.example:lib", "1.0", false, NOW.minus(Duration.ofDays(400))));
        CleanupPlan plan = new RetentionPolicy(0).maxAge(Duration.ofDays(90)).plan(releases, NOW);
        assertThat(plan.evictions()).extracting(e -> e.release().version()).containsExactlyInAnyOrder("2.0", "1.0");
    }

    @Test
    void a_single_old_version_is_kept_so_cleanup_never_empties_a_coordinate() {
        List<Release> releases = List.of(
                new Release("org.example:lib", "1.0", false, NOW.minus(Duration.ofDays(1000))));
        CleanupPlan plan = new RetentionPolicy(0).maxAge(Duration.ofDays(1)).plan(releases, NOW);
        assertThat(plan.isEmpty()).isTrue();
    }

    @Test
    void prerelease_expiry_applies_to_prereleases_only() {
        List<Release> releases = List.of(
                new Release("org.example:lib", "2.0", false, NOW.minus(Duration.ofDays(1))),
                new Release("org.example:lib", "2.0-SNAPSHOT", true, NOW.minus(Duration.ofDays(30))),
                new Release("org.example:lib", "1.0", false, NOW.minus(Duration.ofDays(30))));
        CleanupPlan plan = new RetentionPolicy(0).prereleaseExpiry(Duration.ofDays(14)).plan(releases, NOW);
        assertThat(plan.evictions()).singleElement()
                .satisfies(e -> assertThat(e.release().version()).isEqualTo("2.0-SNAPSHOT"));
    }

    @Test
    void the_age_and_prerelease_rules_compose_with_distinct_reasons() {
        List<Release> releases = List.of(
                new Release("org.example:lib", "4.0", false, NOW.minus(Duration.ofDays(1))),
                new Release("org.example:lib", "3.0", false, NOW.minus(Duration.ofDays(5))),
                new Release("org.example:lib", "2.0-SNAPSHOT", true, NOW.minus(Duration.ofDays(30))),
                new Release("org.example:lib", "1.0", false, NOW.minus(Duration.ofDays(200))));
        CleanupPlan plan = new RetentionPolicy(10)
                .maxAge(Duration.ofDays(90))
                .prereleaseExpiry(Duration.ofDays(14))
                .plan(releases, NOW);
        assertThat(plan.evictions()).extracting(CleanupPlan.Eviction::reason)
                .containsExactlyInAnyOrder("prerelease older than 14 days", "older than 90 days");
        assertThat(plan.evictions()).extracting(e -> e.release().version())
                .containsExactlyInAnyOrder("2.0-SNAPSHOT", "1.0");
    }

    @Test
    void not_downloaded_for_evicts_cold_versions_but_keeps_recently_downloaded_ones() {
        List<Release> releases = List.of(
                new Release("org.example:lib", "4.0", false, NOW.minus(Duration.ofDays(1)), NOW.minus(Duration.ofDays(1))),
                new Release("org.example:lib", "3.0", false, NOW.minus(Duration.ofDays(100)), NOW.minus(Duration.ofDays(1))),
                new Release("org.example:lib", "2.0", false, NOW.minus(Duration.ofDays(100)), NOW.minus(Duration.ofDays(60))),
                new Release("org.example:lib", "1.0", false, NOW.minus(Duration.ofDays(100)), NOW.minus(Duration.ofDays(90))));
        CleanupPlan plan = new RetentionPolicy(0).notDownloadedFor(Duration.ofDays(30)).plan(releases, NOW);
        assertThat(plan.evictions()).extracting(eviction -> eviction.release().version())
                .containsExactlyInAnyOrder("2.0", "1.0");
        assertThat(plan.evictions()).allSatisfy(e -> assertThat(e.reason()).isEqualTo("not downloaded for 30 days"));
    }

    @Test
    void a_pinned_version_is_never_evicted_whatever_the_rules_say() {
        List<Release> releases = List.of(
                new Release("org.example:lib", "3.0", false, NOW.minus(Duration.ofDays(1))),
                new Release("org.example:lib", "2.0", false, NOW.minus(Duration.ofDays(2)), NOW.minus(Duration.ofDays(2)), true),
                new Release("org.example:lib", "1.0", false, NOW.minus(Duration.ofDays(3))));
        CleanupPlan plan = new RetentionPolicy(1).plan(releases, NOW);
        assertThat(plan.evictions()).extracting(eviction -> eviction.release().version())
                .as("keep-last=1 would drop 2.0, but it is pinned").containsExactly("1.0");
    }

    @Test
    void coordinates_are_pruned_independently() {
        List<Release> releases = List.of(
                new Release("org.a:lib", "1.1", false, NOW.minus(Duration.ofDays(1))),
                new Release("org.a:lib", "1.0", false, NOW.minus(Duration.ofDays(2))),
                new Release("org.b:lib", "9.0", false, NOW.minus(Duration.ofDays(1))));
        CleanupPlan plan = new RetentionPolicy(1).plan(releases, NOW);
        assertThat(plan.evictions()).singleElement()
                .satisfies(e -> assertThat(e.release().coordinate()).isEqualTo("org.a:lib"));
    }

    @Test
    void the_same_coordinate_in_two_ecosystems_is_judged_per_ecosystem() {
        // An npm package and a crate may share a name: each ecosystem's version list is its own group, so each
        // ecosystem's newest is kept and keep-last counts per ecosystem - one ecosystem's releases can never
        // evict another's (grouped by coordinate alone, Cargo's only version would fall beyond npm's keep-last).
        List<Release> releases = List.of(
                new Release("npm", "foo", "2.0", NOW.minus(Duration.ofDays(1)), NOW.minus(Duration.ofDays(1)),
                        false, false),
                new Release("npm", "foo", "1.0", NOW.minus(Duration.ofDays(2)), NOW.minus(Duration.ofDays(2)),
                        false, false),
                new Release("Cargo", "foo", "9.0", NOW.minus(Duration.ofDays(30)), NOW.minus(Duration.ofDays(30)),
                        false, false));
        CleanupPlan plan = new RetentionPolicy(1).plan(releases, NOW);
        assertThat(plan.evictions()).singleElement().satisfies(e -> {
            assertThat(e.release().ecosystem()).isEqualTo("npm");
            assertThat(e.release().version()).isEqualTo("1.0");
        });
    }
}
