package build.jenesis.repository.compliance.firstrun.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.SignalSourceProvider;
import build.jenesis.repository.settings.FirstRunSteps;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every feed ships off, so the first-run guide is where a new deployment decides which to consult - and a feed the
 * guide does not name is one a new deployment never hears of. This holds the guide to what is installed rather than
 * to a list written beside it.
 */
class FirstRunFeedsTest {

    private static List<String> keys(String step) {
        return FirstRunSteps.step(step).orElseThrow(() -> new AssertionError("no " + step + " step")).keys();
    }

    @Test
    void the_feeds_step_asks_about_every_installed_feed() {
        List<String> installed = SignalSourceProvider.contributors().stream().map(SignalSourceProvider::name).toList();

        assertThat(installed).as("the feeds this graph carries").contains("osv", "github", "openssf");
        assertThat(keys("feeds")).containsAll(installed);
    }

    @Test
    void every_feed_the_step_names_is_off_where_nothing_was_set() {
        Map<String, Setting> catalogue = new HashMap<>();
        ServiceLoader.load(SettingsContributor.class)
                .forEach(contributor -> contributor.settings().forEach(s -> catalogue.put(s.key(), s)));
        for (String feed : SignalSourceProvider.contributors().stream().map(SignalSourceProvider::name).toList()) {
            assertThat(catalogue).as("%s has a setting the guide can render", feed).containsKey(feed);
            assertThat(catalogue.get(feed).defaultValue()).as("%s ships off", feed).isEqualTo("false");
        }
    }

    @Test
    void the_guide_asks_where_to_be_told_and_asks_about_feeds_before_thresholds() {
        assertThat(keys("notifications")).contains("webhook", "webhook-endpoints");
        List<String> order = FirstRunSteps.ALL.stream().map(FirstRunSteps.Step::id).toList();
        assertThat(order.indexOf("feeds")).as("a threshold means nothing until a feed is on")
                .isLessThan(order.indexOf("vulnerabilities"));
    }
}
