package build.jenesis.repository.ui.store;

import module java.base;
import build.jenesis.repository.store.Documents;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.scope.Scopes;

/**
 * Tenant lifecycle on the root storage: a tenant is a top-level container, created and deleted by super-admins, and
 * existing on every backend through its marker object. Its members are grants in the authorization store, not here.
 * What is a tenant is decided by the one {@link Scopes} rule in every method, so the reserved spaces beside the tenants
 * can be neither created nor listed.
 */
public class TenantService {

    /** The object whose presence is the tenant, written create-if-absent by {@link #create}, so a second create loses
     *  the compare-and-set. It carries no membership. */
    public static final String TENANT_FILE = ".users/tenant.properties";

    private final Documents rootStorage;
    private final AuditTrail audit;
    private final ConsoleActor actor;

    /** A view that records no audit events, for callers that only list or check existence; a mutating caller uses the
     *  audited constructor. */
    public TenantService(Documents rootStorage) {
        this(rootStorage, AuditTrail.none(), () -> "console");
    }

    public TenantService(Documents rootStorage, AuditTrail audit, ConsoleActor actor) {
        this.rootStorage = rootStorage;
        this.audit = audit;
        this.actor = actor;
    }

    /**
     * Every tenant under the storage root, sorted: each top-level name {@link Scopes#valid} accepts. The paged listing is
     * followed to exhaustion, since tenants are provisioned by super-admins and every caller needs all of them.
     */
    public List<String> all() {
        List<String> result = new ArrayList<>();
        String cursor = null;
        while (true) {
            Documents.Page page = rootStorage.containers("", cursor, Documents.PAGE, name -> {
                if (Scopes.valid(name)) {
                    result.add(name);
                }
            });
            if (page.exhausted()) {
                break;
            }
            cursor = page.next().orElseThrow();
        }
        result.sort(Comparator.naturalOrder());
        return result;
    }

    public boolean exists(String tenant) {
        if (!Scopes.valid(tenant)) {
            return false;
        }
        // A version probe of the marker; a scope without one (holding only artifacts) is asked for a single child.
        Documents scope = rootStorage.scope(tenant);
        if (scope.version(TENANT_FILE) != null) {
            return true;
        }
        boolean[] holds = new boolean[1];
        scope.containers("", null, 1, _ -> holds[0] = true);
        return holds[0];
    }

    public void create(String tenant) throws IOException {
        String name = validName(tenant);
        if (exists(name)) {
            throw new IllegalArgumentException("Tenant already exists: " + tenant);
        }
        // Create-if-absent, so of two racing creates the loser is told the tenant exists.
        if (!rootStorage.scope(name).writeVersioned(TENANT_FILE, new Properties(), null)) {
            throw new IllegalArgumentException("Tenant already exists: " + tenant);
        }
        // Audited in the new tenant's own scope, best-effort.
        audit.record(name, actor.name(), "tenant.create", name);
    }

    /** Removes the tenant's cache-side container only; the complete removal is {@link TenantPurge#delete(String)}. */
    public void delete(String tenant) throws IOException {
        rootStorage.deleteAll(validName(tenant));
    }

    /** {@code tenant} validated as a tenant name by the {@link Scopes} rule {@link #all} lists by, trimmed. */
    static String validName(String tenant) {
        return Scopes.require("tenant", tenant);
    }
}
