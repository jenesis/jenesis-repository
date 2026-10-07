package build.jenesis.repository.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.server.RepositoryAutoConfiguration;
import build.jenesis.repository.server.RepositoryProperties;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.server.spi.KeyUsageTracker;
import build.jenesis.repository.usage.BatchingKeyUsageTracker;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The deployment's {@code jenrepo.track-key-usage} reaches the tracker the server composes: a provider reads its
 * settings by bare name, so a composition that handed it the environment as it is would leave the setting unread
 * and the tracker recording whatever the deployment said.
 */
class KeyUsageSettingReachesTrackerTest {

    @Test
    void a_deployment_that_switches_key_usage_off_gets_a_tracker_that_records_nothing() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("deployment",
                Map.of("jenrepo.track-key-usage", "false")));

        KeyUsageTracker tracker = new RepositoryAutoConfiguration(environment)
                .keyUsageTracker(new RepositoryProperties(), Authorization.anonymous(), environment);

        assertThat(tracker).isInstanceOfSatisfying(BatchingKeyUsageTracker.class,
                batching -> assertThat(batching.enabled()).as("switched off by the deployment's setting").isFalse());
    }
}
