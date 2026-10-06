package build.jenesis.repository.closure.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.closure.ClosureSettingsContributor;
import build.jenesis.repository.closure.ClosureTask;
import build.jenesis.repository.inventory.IncrementalPasses;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.Wizard;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Closure resolution is a repository's setting, on by default and asked when a repository is created, and its pass is
 * full on a dial of its own: what the catalogue declares, what the repository wizard asks, and what the pass does with
 * nothing set.
 */
class ClosureSettingDefaultTest {

    private static Setting declared() {
        return new ClosureSettingsContributor().settings().stream()
                .filter(setting -> ClosureTask.SETTING.equals(setting.key()))
                .findFirst().orElseThrow();
    }

    @Test
    void the_declared_setting_is_a_repositorys_on_by_default_and_asked_by_its_wizard() {
        Setting setting = declared();
        assertThat(setting.defaultValue()).isEqualTo("true");
        assertThat(setting.kind()).isEqualTo(Setting.Kind.BOOLEAN);
        assertThat(setting.scope()).isEqualTo(Setting.Scope.REPOSITORY);
        assertThat(setting.tier()).isEqualTo(Setting.Tier.ESSENTIAL);
        assertThat(Wizard.REPOSITORY.asks(setting)).as("the repository wizard asks it").isTrue();
    }

    @Test
    void the_closure_pass_is_full_once_a_week_at_the_hourly_cadence_unless_its_own_dial_says_otherwise() {
        Setting setting = new ClosureSettingsContributor().settings().stream()
                .filter(declared -> ClosureTask.FULL_EVERY.equals(declared.key())).findFirst().orElseThrow();
        assertThat(setting.defaultValue()).isEqualTo("168");

        assertThat(IncrementalPasses.fullEvery(_ -> null, ClosureTask.EVERY)).isEqualTo(168);
        assertThat(IncrementalPasses.fullEvery(key -> IncrementalPasses.FULL_EVERY.equals(key) ? "2" : null,
                ClosureTask.EVERY)).as("the scans' dial is not the closure pass's").isEqualTo(168);
        assertThat(IncrementalPasses.fullEvery(key -> ClosureTask.FULL_EVERY.equals(key) ? "2" : null,
                ClosureTask.EVERY)).isEqualTo(2);
    }

    @Test
    void a_repository_that_sets_nothing_resolves_closures() {
        assertThat(ClosureTask.enabled(_ -> null)).isTrue();
        assertThat(ClosureTask.enabled(key -> ClosureTask.SETTING.equals(key) ? "false" : null)).isFalse();
    }
}
