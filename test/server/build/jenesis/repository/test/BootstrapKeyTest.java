package build.jenesis.repository.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.server.RepositoryAutoConfiguration;
import build.jenesis.repository.server.RepositoryProperties;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.StandardEnvironment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The bootstrap key: how an operator gets their FIRST credential on an enforcing deployment.
 *
 * <p>Before it, a fresh install was unusable as configured. {@code jenreg.auth} is on by default, a keyless caller
 * is rejected, and every route that could mint a key requires one already - so the only advice a new deployment
 * could be given was to switch authentication off, which is not a bootstrap but a different deployment.
 */
class BootstrapKeyTest {

    @TempDir
    Path root;

    private ArtifactStore store() {
        return ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
    }

    private Authorization authorization(String bootstrapKey) {
        RepositoryProperties properties = new RepositoryProperties();
        properties.setBootstrapKey(bootstrapKey);
        return new RepositoryAutoConfiguration(new StandardEnvironment())
                .authorization(properties, store());
    }

    @Test
    void an_enforcing_deployment_with_no_bootstrap_key_authorizes_nothing() throws IOException {
        // The premise, and the defect this closes: enforcing plus no key is a deployment nobody can use.
        Authorization authorization = authorization("");
        String key = Authorization.mint("default");

        assertThat(authorization.enforced()).as("auth is on by default").isTrue();
        assertThat(authorization.authorize(key, "releases", Authorization.REPOSITORY_WRITE))
                .as("an unprovisioned key authorizes nothing, and nothing here could provision one")
                .isNotEqualTo(Authorization.Decision.ALLOWED);
    }

    @Test
    void the_bootstrap_key_is_provisioned_and_carries_every_right() throws IOException {
        String key = Authorization.mint("default");
        Authorization authorization = authorization(key);

        assertThat(authorization.authorize(key, "releases", Authorization.REPOSITORY_WRITE))
                .as("the bootstrap key may publish").isEqualTo(Authorization.Decision.ALLOWED);
        assertThat(authorization.authorize(key, "*", Authorization.MANAGE_WRITE))
                .as("and may administer, which is what lets it issue the credentials that replace it")
                .isEqualTo(Authorization.Decision.ALLOWED);
    }

    @Test
    void provisioning_the_same_bootstrap_key_twice_converges() throws IOException {
        // It is re-provisioned on every boot for as long as it is set, so it has to converge rather than
        // accumulate - the key's own hash is its identity, which is what makes that true.
        String key = Authorization.mint("default");
        authorization(key);
        Authorization second = authorization(key);

        assertThat(second.credentials("default")).as("one credential, not one per boot").hasSize(1);
        assertThat(second.authorize(key, "releases", Authorization.REPOSITORY_WRITE))
                .isEqualTo(Authorization.Decision.ALLOWED);
    }

    @Test
    void a_malformed_bootstrap_key_is_refused_at_boot_rather_than_ignored() {
        // An operator who set this expects a working key; silently dropping a typo leaves them locked out with no
        // line saying why.
        assertThatThrownBy(() -> authorization("not-a-key"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("well-formed key");
    }
}
