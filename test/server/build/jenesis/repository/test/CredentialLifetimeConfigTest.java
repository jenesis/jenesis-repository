package build.jenesis.repository.test;

import build.jenesis.repository.server.RepositoryAutoConfiguration;
import build.jenesis.repository.server.RepositoryProperties;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import org.springframework.core.env.StandardEnvironment;

import module org.junit.jupiter.api;
import module java.base;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The credential-lifetime properties reach the {@link Authorization} the deployment actually runs on.
 *
 * <p>{@link Authorization#withLifetimes} being correct is a separate claim, asserted beside the other lifetime legs.
 * This one is about the wiring, and it is the half that was missing: the withers were public and honoured all along,
 * and the defect was that no configuration path called them. A test of the method alone would leave exactly that gap
 * one level up - the dial reachable in principle, and nothing proving the bean reaches it.
 *
 * <p>It calls the bean method directly rather than booting a context. The method is the wiring, the properties bean
 * is a plain setter object, and a Spring context would add a container start to a claim that does not involve one.
 */
class CredentialLifetimeConfigTest {

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeEach
    void setUp() {
        store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
    }

    private Authorization authorization(String defaultLifetime, String maxLifetime) {
        RepositoryProperties properties = new RepositoryProperties();
        properties.setAuth(true);
        properties.setCredentialDefaultLifetime(defaultLifetime);
        properties.setCredentialMaxLifetime(maxLifetime);
        return new RepositoryAutoConfiguration(new StandardEnvironment()).authorization(properties, store);
    }

    @Test
    void the_configured_lifetimes_reach_the_authorization_the_deployment_runs_on() {
        Authorization configured = authorization("P30D", "P60D");

        assertThat(configured.defaultLifetime()).isEqualTo(Duration.ofDays(30));
        assertThat(configured.maxLifetime()).isEqualTo(Duration.ofDays(60));
    }

    @Test
    void an_unconfigured_deployment_keeps_the_ninety_day_default_and_no_ceiling() {
        Authorization shipped = authorization("", "");

        assertThat(shipped.defaultLifetime())
                .as("the shipped posture is unchanged for a deployment that sets neither")
                .isEqualTo(Duration.ofDays(90));
        assertThat(shipped.maxLifetime())
                .as("and no ceiling appears on upgrade, which would shorten every tenant's credentials at once")
                .isNull();
    }
}
