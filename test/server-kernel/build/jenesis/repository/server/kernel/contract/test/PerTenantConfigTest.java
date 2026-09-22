package build.jenesis.repository.server.kernel.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.server.kernel.LiveConfig;
import build.jenesis.repository.server.kernel.RepositoryProperties;
import build.jenesis.repository.server.kernel.Settings;
import build.jenesis.repository.settings.SettingsDocuments;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the global-and-per-tenant configuration model at the store-backed {@link Settings} and the effective
 * {@link LiveConfig} it feeds - without booting a server, so the layering, isolation, write guards and per-tenant gate
 * resolution are exercised directly. A tenant's own documents live under its store scope and layer over the global
 * documents along the chain <em>tenant &gt; global &gt; default</em>; a deployment-wide (global-only) key is refused in
 * a tenant document; and a tenant's own deny list bites on the gate the publish/proxy paths read for that tenant while
 * the deployment default still admits the artifact.
 */
class PerTenantConfigTest {

    @TempDir
    private Path root;

    private Settings settings() throws IOException {
        ArtifactStore store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        return new Settings(store);
    }

    @Test
    void a_tenant_override_layers_over_global_and_is_isolated_from_other_tenants() throws IOException {
        Settings settings = settings();
        settings.set("deny-list", "global:coord");
        settings.set("acme", "deny-list", "acme:coord");
        settings.set("beta", "deny-list", "beta:coord");

        assertThat(settings.getOrDefault("acme", "deny-list", ""))
                .as("a tenant sees its own override").isEqualTo("acme:coord");
        assertThat(settings.getOrDefault("beta", "deny-list", ""))
                .as("another tenant sees its own, not acme's").isEqualTo("beta:coord");
        assertThat(settings.getOrDefault("deny-list", ""))
                .as("the deployment-wide value is untouched by the tenant writes").isEqualTo("global:coord");
        assertThat(settings.getOrDefault("gamma", "deny-list", ""))
                .as("a tenant that set nothing falls back to the deployment-wide value").isEqualTo("global:coord");

        assertThat(settings.overrides()).as("the global overrides carry only the deployment-wide value")
                .containsEntry("deny-list", "global:coord");
        assertThat(settings.overrides("acme")).as("the tenant overrides carry only the tenant's own value")
                .containsEntry("deny-list", "acme:coord");
        assertThat(settings.tenantConfigured("acme")).isTrue();
        assertThat(settings.tenantConfigured("gamma")).isFalse();
    }

    @Test
    void a_global_only_key_is_refused_in_a_tenant_document() throws IOException {
        Settings settings = settings();
        assertThatThrownBy(() -> settings.set("acme", "default-tenant", "x"))
                .as("a deployment-wide key cannot be set per tenant")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings.importTenant("acme", Map.of("core", Map.of("default-tenant", "x"))))
                .as("nor smuggled in through a tenant slice import")
                .isInstanceOf(IllegalArgumentException.class);
        // A tenant-overridable key writes fine, so the guard is scope-specific, not a blanket refusal.
        settings.set("acme", "deny-list", "ok:coord");
        assertThat(settings.getOrDefault("acme", "deny-list", "")).isEqualTo("ok:coord");
        assertThat(settings.getOrDefault("default-tenant", "default"))
                .as("the global-only key never landed anywhere").isEqualTo("default");
    }

    @Test
    void the_export_bundle_carries_global_and_tenants_and_round_trips_byte_identically() throws IOException {
        Settings settings = settings();
        settings.set("deny-list", "global:coord");
        settings.set("acme", "deny-list", "acme:coord");
        settings.set("beta", "vulnerability-threshold", "HIGH");

        SortedMap<String, SortedMap<String, String>> bundle = settings.exportBundle();
        byte[] exported = SettingsDocuments.serializeBundle(bundle);
        assertThat(new String(exported, StandardCharsets.UTF_8))
                .as("the bundle carries the global document and both tenants' slices")
                .contains("tenant:acme:").contains("tenant:beta:").contains("acme:coord").contains("HIGH");

        settings.importBundle(Map.of());   // wipe: an empty bundle clears the global docs and every tenant slice
        assertThat(settings.exportBundle()).as("the wipe cleared everything").isEmpty();

        settings.importBundle(bundle);      // restore from the captured bundle
        assertThat(SettingsDocuments.serializeBundle(settings.exportBundle()))
                .as("a re-export after an unchanged import is byte-identical").isEqualTo(exported);
        assertThat(settings.getOrDefault("acme", "deny-list", ""))
                .as("the tenant slice is back in force").isEqualTo("acme:coord");
        assertThat(settings.getOrDefault("beta", "vulnerability-threshold", "NONE")).isEqualTo("HIGH");
    }

    @Test
    void a_tenant_gate_rejects_a_coordinate_the_deployment_default_admits() throws IOException {
        Settings settings = settings();
        // The deployment leaves its deny-list empty; only acme forbids the coordinate.
        settings.set("acme", "deny-list", "com.evil:pkg");
        LiveConfig live = new LiveConfig(settings, new RepositoryProperties(), AdvisorySource.NONE, _ -> null);

        // A declared license sidesteps the license dimension's default unknown-license quarantine, so the only
        // difference under test is the deny list.
        ComplianceGate.Subject subject = new ComplianceGate.Subject("Maven", "com.evil:pkg", "1.0",
                List.of(new ComplianceGate.DeclaredLicense("Apache-2.0", null)));

        assertThat(live.publishGate().assess(subject).allowed())
                .as("the deployment-wide gate admits the coordinate").isTrue();
        assertThat(live.publishGate("acme").assess(subject).allowed())
                .as("acme's own deny list rejects it on the publish path").isFalse();
        assertThat(live.proxyGate("acme").assess(subject).allowed())
                .as("and on acme's proxy fetch path too").isFalse();
        assertThat(live.publishGate("beta").assess(subject).allowed())
                .as("a tenant that overrode nothing uses the deployment default").isTrue();
    }
}
