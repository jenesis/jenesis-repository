package build.jenesis.repository.auth.keylogin.test;

import module java.base;
import module org.junit.jupiter.api;

import build.jenesis.repository.scope.Scopes;
import build.jenesis.repository.auth.keylogin.KeyLoginMechanism;
import build.jenesis.repository.auth.keylogin.KeyLoginSettingsContributor;
import build.jenesis.repository.auth.keylogin.KeyLoginConfig;
import build.jenesis.repository.auth.keylogin.KeyLoginStorageNamespace;
import build.jenesis.repository.maintenance.StorageNamespace;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;
import build.jenesis.repository.ui.ConsoleModuleProvider;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The module joins the console the same way OIDC and LDAP do: discovered by {@link ServiceLoader} as a
 * {@link ConsoleModuleProvider} named {@code keylogin} whose configuration is {@link KeyLoginConfig}, and it lists its
 * one enable gate ({@code key-login}) as a {@link SettingsContributor} so the modules screen can pair it with a toggle.
 * It is also a first-class storage owner: its issued-key index registers as a {@link StorageNamespace} attributed to
 * the module, so the deployment's namespace registry accounts for the {@code auth/}-rooted key-space beside every
 * other store-writing module rather than leaving it an unregistered, purge-invisible space.
 */
public class KeyLoginModuleTest {

    @Test
    void isDiscoveredAsAConsoleModuleNamedKeyLogin() {
        // The provider name is the module's jenreg.<name> toggle, so it is the same spelling as the
        // enablement gate this module catalogues and its own condition reads: one key, not two.
        ConsoleModuleProvider keyLogin = ConsoleModuleProvider.installed().stream()
                .filter(provider -> provider.name().equals("key-login"))
                .findFirst()
                .orElseThrow();
        assertThat(keyLogin.configuration()).isEqualTo(KeyLoginConfig.class);
        assertThat(keyLogin.navEntries()).isEmpty();
    }

    @Test
    void contributesTheKeyLoginEnableGateAsBoolean() {
        List<Setting> contributed = ServiceLoader.load(SettingsContributor.class).stream()
                .map(ServiceLoader.Provider::get)
                .flatMap(contributor -> contributor.settings().stream())
                .filter(setting -> setting.key().equals("key-login"))
                .toList();
        assertThat(contributed).singleElement().satisfies(setting -> {
            assertThat(setting.kind()).isEqualTo(Setting.Kind.BOOLEAN);
            assertThat(setting.enablement()).isTrue();
            assertThat(setting.defaultValue()).isEqualTo("true");
            assertThat(setting.description()).isNotBlank();
        });
    }

    @Test
    void registersTheIssuedKeysAndTheFirstRunKeysAsAStorageNamespaceUnderTheSharedAuthRoot() {
        StorageNamespace namespace = ServiceLoader.load(StorageNamespace.class).stream()
                .map(ServiceLoader.Provider::get)
                .filter(KeyLoginStorageNamespace.class::isInstance)
                .findFirst()
                .orElseThrow();
        assertThat(namespace.sharedPrefixes())
                .as("the key index and the first-run keys are deployment-global spaces under the sanctioned shared "
                        + "auth/ root")
                .containsExactlyInAnyOrder(Scopes.space(Scopes.AUTH) + "/keylogin/keys.properties",
                        Scopes.space(Scopes.AUTH) + "/keylogin/first-run");
        assertThat(namespace.repositoryPrefixes()).as("nothing per-repository").isEmpty();
        assertThat(namespace.tenantPrefixes()).as("nothing per-tenant").isEmpty();
    }

    @Test
    void theKeyIndexNamespaceIsAttributedToTheKeyLoginModuleInTheManifest() {
        assertThat(StorageNamespace.declared())
                .as("the key index registers as a module-attributed manifest entry, not an unowned space")
                .anySatisfy(entry -> {
                    assertThat(entry.module()).isEqualTo("build.jenesis.repository.auth.keylogin");
                    assertThat(entry.sharedPrefixes()).containsExactlyInAnyOrder(
                            Scopes.space(Scopes.AUTH) + "/keylogin/keys.properties",
                            Scopes.space(Scopes.AUTH) + "/keylogin/first-run");
                });
    }

    /**
     * The module's unset-key posture must be the posture its catalogue entry publishes - and that posture is on.
     *
     * <p>Key-based sign-in used to ship off, and its catalogue entry once said "disabled by default" while the code
     * answered ENABLED; the module declares its own default for that reason. It ships on now, because a deployment
     * that has configured nothing is signed in to with the one-time key its start prints, and with the mechanism off
     * nobody could use it. This asserts the shipped default itself, from both places that state it, so reverting it
     * is a red test rather than a deployment nobody can enter.
     */
    @Test
    void an_unset_key_leaves_key_login_on_exactly_as_its_catalogue_entry_says() {
        Setting gate = new KeyLoginSettingsContributor().settings().stream()
                .filter(Setting::enablement)
                .findFirst()
                .orElseThrow(() -> new AssertionError("key-login publishes no enablement gate"));

        assertThat(gate.key()).isEqualTo(KeyLoginMechanism.NAME);
        assertThat(gate.defaultValue())
                .as("the catalogue publishes this gate as on by default")
                .isEqualTo("true");
        assertThat(new KeyLoginMechanism().enabledByDefault())
                .as("and the module declares the same, or the code and the console disagree about whether a "
                        + "sign-in method is available on a deployment that has configured nothing")
                .isTrue();
    }
}
