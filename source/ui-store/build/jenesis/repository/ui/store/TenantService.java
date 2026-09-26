package build.jenesis.repository.ui.store;

import module java.base;
import build.jenesis.repository.store.Documents;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.scope.Scopes;

/**
 * Tenant lifecycle on top of the root storage: a tenant is a top-level container under the storage
 * root, holding its own projects. Env super-admins create and delete tenants; everyone else only ever works
 * inside the tenants they belong to. Creating a tenant writes its marker object so the tenant exists on every
 * backend, including object stores where a container is otherwise implicit.
 *
 * <p><b>Its members are not here.</b> A console member is a grant to a principal subject in the authorization
 * store, beside the grants a minted key holds - one rights vocabulary, several kinds of holder - so the tenant
 * container carries projects and the marker, and nothing about who may use them.
 *
 * <p>Every method here - {@link #all}, {@link #exists}, {@link #create}, {@link #delete} - decides what is a tenant
 * through the one shared {@link Scopes} rule. The reserved store namespaces the root shares with the artifact store
 * ({@code auth/}, {@code config/}, {@code audit/}, {@code locks/}) are therefore invisible to this service in both
 * directions: they cannot be created, and they are not listed. They used to be excluded only on creation, so
 * {@link #all} - the console's {@code /instances} screen and the super-admin's accessible-tenants answer - offered
 * {@code audit} as a tenant to open and delete.
 */
public class TenantService {

    /** The object whose presence <em>is</em> the tenant: written create-if-absent by {@link #create}, so a tenant
     *  exists on every backend (including object stores, where a container is otherwise implicit) and a second
     *  create loses the compare-and-set rather than clobbering anything. It is deliberately not a member document:
     *  splitting the marker from the membership is what let the membership become a key space. */
    public static final String TENANT_FILE = ".users/tenant.properties";

    private final Documents rootStorage;
    private final AuditTrail audit;
    private final ConsoleActor actor;

    /** A read-only / audit-free view: the audit seam stands in as the no-op trail and a neutral actor, for the browse
     *  and membership-resolution callers ({@code Memberships}, the login authorization) that only ever list or check
     *  existence and never create a tenant. A mutating caller (the console's tenants screen) uses the audited
     *  constructor below so {@link #create} records the creation. */
    public TenantService(Documents rootStorage) {
        this(rootStorage, AuditTrail.none(), () -> "console");
    }

    public TenantService(Documents rootStorage, AuditTrail audit, ConsoleActor actor) {
        this.rootStorage = rootStorage;
        this.audit = audit;
        this.actor = actor;
    }

    /**
     * Every tenant under the storage root, sorted. A top-level name is a tenant exactly when {@link Scopes#valid}
     * says so - the same rule {@link #create} enforces - so the reserved key spaces the artifact store keeps beside
     * the tenant scopes are never offered as tenants to open, scope into or delete.
     *
     * <p>The root listing is paged and its remainder is followed to exhaustion rather than reported: a tenant is
     * provisioned by an env super-admin, the set is not client-inflatable, and every caller of this method - the
     * tenants screen, the accessible-tenants answer, the membership walk - needs all of them or none. The bound
     * that matters is the one below it: {@link #exists} no longer lists a scope's children to find out whether any
     * exist.
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
        // A point read of the one scope, not a full listing of every tenant at the root then a linear scan: the
        // marker create() writes is a cheap version probe, and only a data-holding scope with no marker
        // (a scope that predates the console, or one holding only artifacts) falls back to a scoped listing. That
        // fallback is now a single-name page rather than the scope's whole child set: the question is "does this hold
        // anything at all", so one child answers it and every further one is work whose result is discarded. Mirrors
        // StoreTenants.exists and the SCIM tenant probe.
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
        // Create-if-absent, not a blind write: two creates of the same name race, and the compare-and-set with a
        // null expected writes only when the marker is absent, so the loser is told the tenant exists rather than
        // silently re-marking it. The marker carries no membership - that is a grant in the authorization store -
        // so there is nothing here a concurrent provision could clobber either.
        if (!rootStorage.scope(name).writeVersioned(TENANT_FILE, new Properties(), null)) {
            throw new IllegalArgumentException("Tenant already exists: " + tenant);
        }
        // A tenant creation is a privileged super-admin mutation, so it writes an audit event (§6) - in the
        // new tenant's own scope (audit/<name>/), the scope every other per-tenant mutation records in, attributed to
        // the acting member. Its counterpart, TenantPurge's delete, must instead record in the operator scope because
        // the purge deletes the tenant's own audit space; a create has no such constraint, so it records where it
        // naturally belongs. Best-effort by the trail's contract: a failed write never fails the creation.
        audit.record(name, actor.name(), "tenant.create", name);
    }

    /** Remove the tenant's cache-side container (its projects and console members) only. The complete cross-store
     *  tenant removal - which also purges the shared artifact store's {@code <tenant>/}, {@code auth/<tenant>/} and
     *  {@code audit/<tenant>/} so a recreated name inherits no live credentials, audit history or artifacts - runs
     *  through {@link TenantPurge#delete(String)}, the console's tenant-delete path. */
    public void delete(String tenant) throws IOException {
        rootStorage.deleteAll(validName(tenant));
    }

    /** Validate {@code tenant} as a creatable/removable tenant name and return its trimmed form: a traversal-free
     *  segment that is not a reserved store namespace. Shared by {@link #create}, {@link #delete} and
     *  {@link TenantPurge} so every console tenant-lifecycle path applies the one rule - and it is the same
     *  {@link Scopes} rule {@link #all} enumerates by, so what this refuses can never be listed. */
    static String validName(String tenant) {
        return Scopes.require("tenant", tenant);
    }
}
