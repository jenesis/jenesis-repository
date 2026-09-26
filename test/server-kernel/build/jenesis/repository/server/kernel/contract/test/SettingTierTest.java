package build.jenesis.repository.server.kernel.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every setting says whether an operator is expected to decide it or whether it tunes what was decided, and nothing
 * decides that for a contributor that forgot: a constructed setting is undecided until it is declared, and the
 * catalogue this module composes holds none undecided.
 *
 * <p>The census here covers the contributors this module's graph carries; the image's own census covers every
 * setting the shipped composition declares.
 */
class SettingTierTest {

    private static Setting plain() {
        return new Setting("example-interval", "Example", "Example interval", "How often the example runs.",
                Setting.Kind.DURATION, "PT1H", true);
    }

    @Test
    void a_constructed_setting_is_undecided_until_it_is_declared() {
        assertThat(plain().tier()).as("no constructor decides").isNull();
        assertThat(plain().essential().tier()).isEqualTo(Setting.Tier.ESSENTIAL);
        assertThat(plain().advanced().tier()).isEqualTo(Setting.Tier.ADVANCED);
    }

    @Test
    void a_gate_keeps_its_tier_and_a_tier_keeps_its_gate() {
        Setting gatedFirst = plain().gate().advanced();
        Setting tieredFirst = plain().advanced().gate();

        assertThat(gatedFirst.enablement()).isTrue();
        assertThat(gatedFirst.tier()).isEqualTo(Setting.Tier.ADVANCED);
        assertThat(tieredFirst.enablement()).isTrue();
        assertThat(tieredFirst.tier()).isEqualTo(Setting.Tier.ADVANCED);
    }

    @Test
    void the_census_names_an_undecided_setting() {
        assertThat(undecided(List.of(plain().essential(), plain()))).containsExactly("example-interval");
    }

    @Test
    void every_setting_this_graph_declares_is_decided() {
        List<Setting> catalogue = SettingsContributor.all();

        assertThat(catalogue).as("the core's own contributors are on this graph").isNotEmpty();
        assertThat(undecided(catalogue))
                .as("each of these arrived with neither essential() nor advanced(), so the settings screen cannot "
                        + "say whether to fold it away")
                .isEmpty();
    }

    /** The keys of the settings that declare no tier. */
    static List<String> undecided(List<Setting> settings) {
        return settings.stream().filter(setting -> setting.tier() == null).map(Setting::key).toList();
    }
}
