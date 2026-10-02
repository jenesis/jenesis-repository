package build.jenesis.repository.compliance.firstrun.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.SignalSourceProvider;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;
import build.jenesis.repository.settings.Wizard;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every feed ships off, so the first boot's wizard is where a new deployment decides which to consult - and a feed the
 * wizard does not ask about is one a new deployment never hears of. The wizard asks what the catalogue declares
 * essential, so this holds each installed feed's setting to that tier rather than to a list written beside it.
 */
class FirstRunFeedsTest {

    private static List<String> asked() {
        return Wizard.SETUP.steps().stream().flatMap(step -> step.settings().stream()).map(Setting::key).toList();
    }

    @Test
    void the_first_boot_asks_about_every_installed_feed() {
        List<String> installed = SignalSourceProvider.contributors().stream().map(SignalSourceProvider::name).toList();

        assertThat(installed).as("the feeds this graph carries").contains("osv", "github", "openssf");
        assertThat(asked()).containsAll(installed);
    }

    @Test
    void every_feed_it_asks_about_is_off_where_nothing_was_set() {
        Map<String, Setting> catalogue = new HashMap<>();
        SettingsContributor.all().forEach(setting -> catalogue.put(setting.key(), setting));
        for (String feed : SignalSourceProvider.contributors().stream().map(SignalSourceProvider::name).toList()) {
            assertThat(catalogue).as("%s has a setting the wizard can ask", feed).containsKey(feed);
            assertThat(catalogue.get(feed).defaultValue()).as("%s ships off", feed).isEqualTo("false");
        }
    }

    @Test
    void it_asks_about_feeds_before_thresholds_and_leaves_webhooks_to_the_settings() {
        List<String> asked = asked();
        assertThat(asked).as("where events are sent is decided when there is a receiver, not on a new deployment")
                .doesNotContain("webhook", "webhook-endpoints");
        for (String feed : SignalSourceProvider.contributors().stream().map(SignalSourceProvider::name).toList()) {
            assertThat(asked.indexOf(feed)).as("a threshold means nothing until %s is on", feed)
                    .isLessThan(asked.indexOf("vulnerability-threshold"));
        }
    }
}
