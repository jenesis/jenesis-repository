package build.jenesis.repository.walk.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.store.Features;
import build.jenesis.repository.walk.WalkConsumer;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link WalkConsumer#discovered()} - the {@link java.util.ServiceLoader} enumeration the shared rebuild pass drives
 * from one place - lists every enabled consumer and skips a disabled one under the shared {@code
 * jenreg.<name>} feature convention: nothing set means enabled, only an explicit {@code false} disables.
 * The {@link DiscoverableWalkConsumer} registered by this test module stands in for a shipped consumer, and the
 * {@link FeatureGatedWalkConsumer} for one that repairs a feature which is off unless configured: its own toggle is
 * unset, so the old rule would have enumerated it, and its {@link WalkConsumer#enabled()} says the feature is off.
 */
class WalkConsumerDiscoveryTest {

    @AfterEach
    void restoreFeatures() {
        Features.reset();
    }

    @Test
    void a_registered_consumer_is_discovered_when_enabled() {
        Features.reset(); // default lookup: the feature is unset, so enabled
        assertThat(WalkConsumer.discovered())
                .as("a ServiceLoader-registered consumer is enumerated when its feature is unset")
                .anySatisfy(consumer -> assertThat(consumer).isInstanceOf(DiscoverableWalkConsumer.class))
                .extracting(WalkConsumer::name)
                .contains(DiscoverableWalkConsumer.NAME);
    }

    @Test
    void a_consumer_of_a_feature_that_is_off_reports_itself_absent() {
        Features.reset(); // the gated feature is unset, and its default is off
        assertThat(WalkConsumer.discovered())
                .as("a consumer whose enabled() says its feature is off is not enumerated, whatever its own toggle")
                .noneSatisfy(consumer -> assertThat(consumer).isInstanceOf(FeatureGatedWalkConsumer.class));
    }

    @Test
    void a_consumer_of_a_feature_that_is_on_is_discovered() {
        Features.configure(key -> Features.key(FeatureGatedWalkConsumer.FEATURE).equals(key) ? "true" : null);
        assertThat(WalkConsumer.discovered())
                .as("switching the parent feature on is enough; the consumer needs no key of its own")
                .anySatisfy(consumer -> assertThat(consumer).isInstanceOf(FeatureGatedWalkConsumer.class));
    }

    @Test
    void a_disabled_consumer_is_skipped_at_discovery() {
        Features.configure(key -> (Features.key(DiscoverableWalkConsumer.NAME)).equals(key) ? "false" : null);

        assertThat(WalkConsumer.discovered())
                .as("an explicit jenreg.<name>=false drops the consumer from the enumeration")
                .noneSatisfy(consumer -> assertThat(consumer).isInstanceOf(DiscoverableWalkConsumer.class));
    }
}
