package build.jenesis.repository.ui.admin.test;

import module java.base;
import module org.junit.jupiter.api;

import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.cache.storage.testkit.CacheStorages;
import build.jenesis.repository.cleanup.StoredReport;
import build.jenesis.repository.scope.Scopes;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Documents;
import build.jenesis.repository.ui.store.TenantPurge;
import build.jenesis.repository.ui.store.TenantService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A tenant is deleted in the background: starting it answers at once, and its state - running, then done with the
 * objects it removed - is read back from the deployment's product space, which outlives the tenant it describes.
 */
class TenantDeletionTest {

    @TempDir
    Path root;

    @Test
    void a_started_deletion_removes_the_tenant_and_reports_what_it_removed() throws Exception {
        Documents documents = CacheStorages.documents(root);
        ArtifactStore store = documents.store();
        TenantService tenants = new TenantService(documents);
        tenants.create("acme");
        store.write("acme/releases/publish/lib-1.0.jar", new ByteArrayInputStream(new byte[]{1}));
        store.write(Scopes.space(Scopes.AUTH) + "/acme/credentials", new ByteArrayInputStream(new byte[]{2}));
        store.write(Scopes.space(Scopes.AUDIT) + "/acme/2026-10-03/event", new ByteArrayInputStream(new byte[]{3}));
        TenantPurge purge = new TenantPurge(tenants, store, Authorization.enforcing(store), AuditTrail.NONE,
                () -> "operator-admin", "operator");
        assertThat(purge.deletion("acme")).as("a tenant never deleted has no deletion to report").isEmpty();

        assertThat(purge.start("acme")).as("the deletion starts").isTrue();

        // Settled as the report's own run settles - stored, and its lease released - so nothing still writes under
        // the temporary directory when it goes.
        StoredReport.awaitSettled(store.scope(Scopes.SYSTEM), "tenant-deletion-acme", Duration.ofSeconds(30))
                .orElseThrow(() -> new AssertionError("the deletion did not finish"));
        StoredReport.Report finished = purge.deletion("acme").orElseThrow();
        assertThat(finished.failure()).isNull();
        assertThat(finished.count()).as("every object the tenant held, counted").isGreaterThanOrEqualTo(3);
        assertThat(store.exists("acme/releases/publish/lib-1.0.jar")).isFalse();
        assertThat(store.exists(Scopes.space(Scopes.AUTH) + "/acme/credentials")).isFalse();
        assertThat(store.exists(Scopes.space(Scopes.AUDIT) + "/acme/2026-10-03/event")).isFalse();
        assertThat(tenants.exists("acme")).isFalse();
    }
}
