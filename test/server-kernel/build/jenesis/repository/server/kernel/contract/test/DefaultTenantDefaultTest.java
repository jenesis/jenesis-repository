package build.jenesis.repository.server.kernel.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.server.RepositoryProperties;
import build.jenesis.repository.settings.CoreSettingsContributor;
import build.jenesis.repository.settings.Setting;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tenant a deployment serves when it names none is {@code releases}, and it is the first segment of every URL
 * such a deployment answers - {@code /repository/releases/<repository>/} - so it is written out here as a literal
 * rather than read back from its definition.
 *
 * <p>It was {@code default}, which read as a fallback rather than a place in every client's configuration, and it
 * was spelled out in eight places: the properties the server binds, the build cache's own properties, the console
 * twice, the key-login module, the key mint, the credential context and the command line. Those now reference one
 * definition, so the declaration legs below are close to tautologies and are kept to fail if a literal comes back.
 * What the product <em>does</em> with nothing set is asked of the running pieces elsewhere: the key mint in
 * {@code MintKeyTest} and the live configuration in {@code SettingsRefreshTest}, while the suites that boot a
 * server name the tenant they address and so assert nothing about this value.
 */
class DefaultTenantDefaultTest {

    private static final String KEY = "default-tenant";

    @Test
    void the_properties_the_server_binds_default_to_releases() {
        assertThat(new RepositoryProperties().getDefaultTenant())
                .as("the tenant a shipped composition serves when " + KEY + " is not set")
                .isEqualTo("releases");
    }

    @Test
    void the_declared_setting_defaults_to_releases() {
        Setting declared = new CoreSettingsContributor().settings().stream()
                .filter(setting -> KEY.equals(setting.key()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(KEY + " is not in the core setting catalogue"));
        assertThat(declared.defaultValue())
                .as("the default the settings screen and the generated reference show for " + KEY)
                .isEqualTo("releases");
    }
}
