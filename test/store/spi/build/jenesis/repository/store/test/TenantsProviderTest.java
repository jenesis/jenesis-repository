package build.jenesis.repository.store.test;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Features;
import build.jenesis.repository.store.Tenants;
import build.jenesis.repository.store.TenantsProvider;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The tenant directory seam in its free-edition shape: no tenants module ships, so {@code installed()} answers
 * {@code false} (the capability signal a console gates tenant management on) and {@code resolve} falls back to the
 * fixed single-tenant directory over the configured tenant - the specialization the shared
 * {@code <tenant>/<repository>/...} layout rests on. A store-backed directory is a provider module's part (the
 * multi-tenant edition's), exercised there; like the publish-interceptor chain, the core's discovery is
 * empty by design.
 *
 * <p>Since T-101b the seam resolves through the shared {@code Providers.optionalUnique} primitive, which fixes the
 * §9 defect the T-002 inventory pass found here: an <em>explicitly selected</em>
 * {@code jenreg.tenants=<name>} that no provider answers to used to degrade silently to the fixed single
 * tenant, collapsing a multi-tenant deployment onto one tenant and hiding every other tenant's artifacts behind a
 * 404 that looks like an empty repository. It now throws, exactly as the store backend already did. Only
 * <em>unselected</em> absence still degrades.
 */
class TenantsProviderTest {

    @TempDir
    Path root;

    @AfterEach
    void restore() {
        Features.reset();
    }

    private ArtifactStore store() {
        return ArtifactStoreProvider.resolve(
                "filesystem", key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
    }

    @Test
    void the_free_edition_installs_no_tenants_module() {
        assertThat(TenantsProvider.installed()).isFalse();
    }

    @Test
    void resolve_falls_back_to_the_fixed_directory_over_the_configured_tenant() throws IOException {
        Tenants tenants = TenantsProvider.resolve(store(), key -> null, "acme");
        assertThat(tenants.list()).containsExactly("acme");
        assertThat(tenants.exists("acme")).isTrue();
        assertThat(tenants.exists("other")).isFalse();
    }

    @Test
    void an_explicitly_selected_directory_no_provider_answers_to_fails_loudly() {
        // The §9 fix (T-101b): before, this silently answered the fixed single-tenant directory, so a deployment
        // that configured a tenants module it had not installed - or misspelled its name - came up looking like a
        // healthy single-tenant server while every other tenant's artifacts 404'd.
        Features.configure(key -> "jenreg.tenants".equals(key) ? "store-tenants" : null);
        ArtifactStore store = store();
        assertThatThrownBy(() -> TenantsProvider.resolve(store, key -> null, "acme"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'store-tenants'")
                .hasMessageContaining("jenreg.tenants=store-tenants")
                .hasMessageContaining("no installed provider answers to it")
                .hasMessageContaining("refusing to degrade silently");
    }

    @Test
    void an_unselected_deployment_still_degrades_to_the_fixed_directory() throws IOException {
        // The other half of the contract: absence with nothing selected is not an error, so a free single-tenant
        // deployment boots on the fixed directory exactly as before.
        Features.configure(key -> null);
        assertThat(TenantsProvider.resolve(store(), key -> null, "acme").list()).containsExactly("acme");
        assertThat(TenantsProvider.installed()).isFalse();
    }

    @Test
    void the_fixed_directory_refuses_to_grow() {
        assertThatThrownBy(() -> Tenants.fixed("default").create("another"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("install a tenants module");
    }

    @Test
    void the_fixed_directory_requires_its_tenant() {
        assertThatNullPointerException().isThrownBy(() -> Tenants.fixed(null));
    }
}
