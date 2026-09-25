package build.jenesis.repository.console.api;

import module java.base;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.scope.Scopes;
import build.jenesis.repository.server.PresentedKey;
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
 * The deployment's tenants over the API - {@code GET /api/admin/tenants}, and {@code PUT} and {@code DELETE} on
 * {@code /api/admin/tenants/{name}} - which the console's instances screen offered and nothing else did, so creating
 * a tenant was the one thing only possible by clicking.
 *
 * <p><b>The same implementation as the screen.</b> A creation is {@link TenantService#create} and a deletion is
 * {@link TenantPurge#delete}: the tenant's artifacts, credentials, audit space and console members, in the order the
 * screen removes them. Only the actor differs. The screen attributes an act to the signed-in member; a request here
 * carries a key, which the key filter makes the principal - so the console's own actor would write the key itself
 * into the audit trail. The services are therefore built per request over the same stores, attributing to the key's
 * one-way hash as every other API route does.
 *
 * <p><b>Why here and not beside the screen.</b> The console's module is the console's URL space - every route it
 * maps is served under the console's chain, with a session and a login redirect - and these are API routes, answered
 * with a key, so they sit with the console store's other API twins.
 *
 * <p><b>Who may.</b> Tenants are deployment-wide, so a key needs the manage rights over every repository - which the
 * authorization manager requires of this path - and must belong to the operator tenant, the API's counterpart of the
 * console's super-admin. A tenant's own administrator may not create or delete tenants, however wide its grant.
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

    /** Delete a tenant and everything it owned: {@code 200}, or {@code 404} when there is none. */
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
        new TenantPurge(tenants, repositoryStore, authorization, audit, actor(request), operatorTenant).delete(name);
        return ResponseEntity.ok(new Tenant(name));
    }

    /** The refusal a key outside the operator tenant gets; nothing when authorization is switched off. */
    private Optional<ResponseEntity<?>> refused(HttpServletRequest request) {
        if (!authorization.enforced()) {
            return Optional.empty();
        }
        String key = PresentedKey.from(request);
        if (key == null || !operatorTenant.equals(Authorization.tenantOf(key))) {
            return Optional.of(ResponseEntity.status(403).body("Tenants are administered with a key of the operator "
                    + "tenant, '" + operatorTenant + "'."));
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

    /** One tenant, as a creation or a deletion names it. */
    public record Tenant(String tenant) {
    }
}
