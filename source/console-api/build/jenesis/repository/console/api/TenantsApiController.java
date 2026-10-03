package build.jenesis.repository.console.api;

import module java.base;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.cleanup.StoredReport;
import build.jenesis.repository.scope.Scopes;
import build.jenesis.repository.server.PresentedKey;
import build.jenesis.repository.server.spi.AccessDenial;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Documents;
import build.jenesis.repository.ui.store.ConsoleActor;
import build.jenesis.repository.ui.store.TenantPurge;
import build.jenesis.repository.ui.store.TenantService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The deployment's tenants over the API - {@code GET /api/admin/tenants}, {@code PUT} and {@code DELETE} on
 * {@code /api/admin/tenants/{name}}, and {@code GET /api/admin/tenants/{name}/deletion}.
 *
 * <p><b>The screen's implementation.</b> Creation is {@link TenantService#create} and deletion
 * {@link TenantPurge#start} - artifacts, credentials, audit space and console members, in the screen's order, in the
 * background, its state read back as the screen reads it. Only the
 * actor differs: a request here carries a key, so the services are built per request attributing to the key's one-way
 * hash, never the key.
 *
 * <p>These are API routes answered with a key, so they sit with the console store's other API twins rather than under
 * the console's session chain.
 *
 * <p><b>Who may.</b> Tenants are deployment-wide: a key needs manage rights over every repository, which the
 * authorization manager requires of this path, and must belong to the operator tenant - the API's counterpart of the
 * console's super-admin. A tenant's own administrator may not.
 */
@RestController
public class TenantsApiController {

    private final Documents rootStorage;
    private final ArtifactStore repositoryStore;
    private final Authorization authorization;
    private final AuditTrail audit;
    private final String operatorTenant;

    public TenantsApiController(Documents rootStorage, ArtifactStore repositoryStore, Authorization authorization,
                                AuditTrail audit, String operatorTenant) {
        this.rootStorage = rootStorage;
        this.repositoryStore = repositoryStore;
        this.authorization = authorization;
        this.audit = audit;
        this.operatorTenant = operatorTenant;
    }

    /** Every tenant of the deployment, sorted. */
    @GetMapping("/api/admin/tenants")
    public ResponseEntity<?> list(HttpServletRequest request) {
        Optional<ResponseEntity<?>> refused = refused(request);
        if (refused.isPresent()) {
            return refused.get();
        }
        return ResponseEntity.ok(new Tenants(new TenantService(rootStorage).all()));
    }

    /** Create a tenant: {@code 201}, {@code 409} when it exists, {@code 400} for a name no tenant may have. */
    @PutMapping("/api/admin/tenants/{name}")
    public ResponseEntity<?> create(@PathVariable("name") String name, HttpServletRequest request)
            throws IOException {
        Optional<ResponseEntity<?>> refused = refused(request);
        if (refused.isPresent()) {
            return refused.get();
        }
        if (!Scopes.valid(name)) {
            return ResponseEntity.badRequest().body("'" + name + "' is not a tenant name: letters, digits, '-' and "
                    + "'_' only.");
        }
        TenantService tenants = services(request);
        if (tenants.exists(name)) {
            return ResponseEntity.status(409).body("Tenant '" + name + "' already exists.");
        }
        try {
            tenants.create(name);
        } catch (IllegalArgumentException lost) {
            // Another request created it between the probe and the create-if-absent write.
            return ResponseEntity.status(409).body(lost.getMessage());
        }
        return ResponseEntity.status(201).body(new Tenant(name));
    }

    /** Start deleting a tenant and everything it owned: {@code 202} with the deletion's state, whether this call
     *  started it or one was already running, or {@code 404} when there is no such tenant. */
    @DeleteMapping("/api/admin/tenants/{name}")
    public ResponseEntity<?> delete(@PathVariable("name") String name, HttpServletRequest request)
            throws IOException {
        Optional<ResponseEntity<?>> refused = refused(request);
        if (refused.isPresent()) {
            return refused.get();
        }
        TenantService tenants = services(request);
        if (!Scopes.valid(name) || !tenants.exists(name)) {
            return ResponseEntity.status(404).body("There is no tenant '" + name + "'.");
        }
        TenantPurge purge = purge(tenants, request);
        boolean started = purge.start(name);
        return ResponseEntity.status(202).body(Deletion.of(name, started, purge.deletion(name)));
    }

    /** Where a tenant's deletion stands: {@code running}, {@code done} with the objects it removed, {@code failed}
     *  with the reason, or {@code none} when the tenant was never deleted. */
    @GetMapping("/api/admin/tenants/{name}/deletion")
    public ResponseEntity<?> deletion(@PathVariable("name") String name, HttpServletRequest request)
            throws IOException {
        Optional<ResponseEntity<?>> refused = refused(request);
        if (refused.isPresent()) {
            return refused.get();
        }
        if (!Scopes.valid(name)) {
            return ResponseEntity.status(404).body("There is no tenant '" + name + "'.");
        }
        TenantPurge purge = purge(services(request), request);
        return ResponseEntity.ok(Deletion.of(name, false, purge.deletion(name)));
    }

    private TenantPurge purge(TenantService tenants, HttpServletRequest request) {
        return new TenantPurge(tenants, repositoryStore, authorization, audit, actor(request), operatorTenant);
    }

    /** The refusal a key outside the operator tenant gets, as the deployment's {@link AccessDenial} words it, before
     *  any tenant is looked up; nothing when authorization is off. */
    private Optional<ResponseEntity<?>> refused(HttpServletRequest request) {
        if (!authorization.enforced()) {
            return Optional.empty();
        }
        String key = PresentedKey.from(request);
        if (key == null || !operatorTenant.equals(Authorization.tenantOf(key))) {
            AccessDenial denial = AccessDenial.configured();
            return Optional.of(ResponseEntity.status(denial.status()).body(denial.explain("Not found.",
                    "Tenants are administered with a key of the operator tenant, '" + operatorTenant + "'.")));
        }
        return Optional.empty();
    }

    /** The tenant directory, attributing what it records to the caller's key. */
    private TenantService services(HttpServletRequest request) {
        return new TenantService(rootStorage, audit, actor(request));
    }

    /** The key's hash as the actor a service records, never the key. */
    private static ConsoleActor actor(HttpServletRequest request) {
        String key = PresentedKey.from(request);
        String name = key == null || key.isBlank() ? "anonymous" : Authorization.hash(key);
        return () -> name;
    }

    /** The tenants of the deployment. */
    public record Tenants(List<String> tenants) {
    }

    /** One tenant, as a creation names it. */
    public record Tenant(String tenant) {
    }

    /** A tenant's deletion: whether this call started it, its state ({@code running}, {@code done}, {@code failed} or
     *  {@code none}), when it started and finished, how many stored objects it removed, and why it failed. */
    public record Deletion(String tenant, boolean started, String state, Instant startedAt, Instant finishedAt,
                           int removed, String failure) {

        static Deletion of(String tenant, boolean started, Optional<StoredReport.Report> report) {
            if (report.isEmpty()) {
                return new Deletion(tenant, started, "none", null, null, 0, null);
            }
            StoredReport.Report stored = report.get();
            String state = stored.running() ? "running" : stored.failure() != null ? "failed" : "done";
            return new Deletion(tenant, started, state, stored.startedAt(), stored.finishedAt(), stored.count(),
                    stored.failure());
        }
    }
}
