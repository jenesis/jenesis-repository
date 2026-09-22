package build.jenesis.repository.server.kernel.contract.test;

import module org.junit.jupiter.api;
import build.jenesis.repository.server.kernel.RepositoryProperties;
import build.jenesis.repository.settings.ImportHostGuard;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code block-private-import-hosts} SSRF-guard decision, resolved fail-closed through the single
 * {@link ImportHostGuard} both import legs share: the stored runtime setting wins, else this deployment's env-field
 * ({@link RepositoryProperties#getBlockPrivateImportHosts}), else <em>block for every edition</em>. The old
 * tenancy-derived off-for-{@code fixed} default is gone - a forgotten or misunderstood config now blocks rather than
 * opening an SSRF, and a single-tenant operator migrating from an internal Nexus opts out <em>explicitly</em> (the
 * env-field or the stored setting set to {@code false}).
 */
class RepositoryPropertiesTest {

    @Test
    void per_credential_authorization_is_on_by_default_and_anonymous_is_an_explicit_opt_out() {
        assertThat(new RepositoryProperties().isAuth())
                .as("the secure default: a fresh deployment enforces per-credential authorization").isTrue();

        RepositoryProperties open = new RepositoryProperties();
        open.setAuth(false);
        assertThat(open.isAuth())
                .as("anonymous/open is honoured only as an explicit opt-out (jenreg.auth=false)").isFalse();
    }

    @Test
    void the_guard_defaults_on_for_every_edition_when_nothing_is_set() {
        RepositoryProperties multi = new RepositoryProperties();
        multi.setTenancy("multi");
        assertThat(multi.getBlockPrivateImportHosts()).as("no explicit env-field value is set").isNull();
        assertThat(multi.importHostsGuarded(null)).as("multi-tenant fails closed when unset").isTrue();

        RepositoryProperties fixed = new RepositoryProperties();
        fixed.setTenancy("fixed");
        assertThat(fixed.importHostsGuarded(null))
                .as("the single-tenant edition now fails closed too - the closed SSRF hole").isTrue();
    }

    @Test
    void an_explicit_env_field_overrides_the_default_either_way() {
        RepositoryProperties fixedOptOut = new RepositoryProperties();
        fixedOptOut.setTenancy("fixed");
        fixedOptOut.setBlockPrivateImportHosts(false);
        assertThat(fixedOptOut.importHostsGuarded(null))
                .as("a single-tenant operator opts out explicitly to migrate from an internal host").isFalse();

        RepositoryProperties multiOptIn = new RepositoryProperties();
        multiOptIn.setTenancy("multi");
        multiOptIn.setBlockPrivateImportHosts(true);
        assertThat(multiOptIn.importHostsGuarded(null)).as("an explicit block still forces on").isTrue();
    }

    @Test
    void the_stored_setting_wins_over_the_env_field_and_the_default() {
        RepositoryProperties envOff = new RepositoryProperties();
        envOff.setBlockPrivateImportHosts(false);
        assertThat(envOff.importHostsGuarded(true))
                .as("a stored true re-blocks even though the env-field opted out").isTrue();
        assertThat(envOff.importHostsGuarded(false)).as("a stored false agrees with the env opt-out").isFalse();

        RepositoryProperties unset = new RepositoryProperties();
        assertThat(unset.importHostsGuarded(false))
                .as("a stored false opts out even with no env-field set").isFalse();
        assertThat(unset.importHostsGuarded(true)).as("a stored true blocks").isTrue();
    }

    @Test
    void the_shared_resolver_fails_closed_for_the_console_leg_which_cannot_see_the_env_field() {
        // The console passes null for the env-field (it has no RepositoryProperties); the stored-setting-else-block
        // path still yields the secure default, so the two legs cannot drift to an insecure default.
        assertThat(ImportHostGuard.blockPrivateHosts(null, null)).as("unset everywhere blocks").isTrue();
        assertThat(ImportHostGuard.blockPrivateHosts(false, null)).as("a stored opt-out is honoured").isFalse();
        assertThat(ImportHostGuard.blockPrivateHosts(true, null)).as("a stored block is honoured").isTrue();
        assertThat(ImportHostGuard.stored(null)).as("an unset stored setting is null").isNull();
        assertThat(ImportHostGuard.stored("  ")).as("a blank stored setting is null").isNull();
        assertThat(ImportHostGuard.stored("false")).isFalse();
        assertThat(ImportHostGuard.stored("true")).isTrue();
    }
}
