package build.jenesis.repository.ui.admin.security;

import module java.base;
import build.jenesis.repository.ui.identity.UserDirectory;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.cache.storage.Names;
import build.jenesis.repository.ui.store.TenantService;
import org.springframework.stereotype.Component;

/**
 * Reads tenant membership across the whole store: which tenants a console user belongs to, and the
 * role they hold in a given one. Each tenant keeps its members as grants to their principal subject, so this
 * resolves a user's access at login (allow if a member of any tenant, or a super-admin) and per request (the role
 * that the current tenant grants) with a point read per tenant asked about. Writing membership is the per-tenant
 * {@link UserDirectory}'s job; this is the cross-tenant read side.
 *
 * <p>{@link #accessibleTo} reads the tenants {@link Authorization#tenantsOf} names for the user - one point read,
 * kept by every write that grants or takes away a role, directly or through a group - and confirms each against the
 * role that tenant grants (see {@link #granted}), so the answer costs a read per tenant the user belongs to and
 * never a probe of every tenant.
 */
@Component
public class Memberships {

    private final Authorization authorization;
    private final TenantService tenants;
    private final MembershipCache cache;

    public Memberships(Authorization authorization, TenantService tenants, MembershipCache cache) {
        this.authorization = authorization;
        this.tenants = tenants;
        this.cache = cache;
    }

    public Optional<UserDirectory.Role> roleIn(String tenant, String id) {
        if (!Names.isTenant(tenant)) {
            return Optional.empty();
        }
        // Memoised for the life of the request: the shell, nav and every authorization decision read the same
        // role, and each read is a point read of that member's own grants.
        return cache.role(tenant, id, () -> UserDirectory.roleIn(authorization, tenant, id));
    }

    public List<String> accessibleTo(String id, boolean superadmin) {
        return cache.accessible(id, superadmin, () -> {
            if (superadmin) {
                // A super-admin is a member everywhere regardless of any tenant's grants.
                return tenants.all();
            }
            try {
                return granted(id, authorization.tenantsOf(id));
            } catch (IOException e) {
                throw new UncheckedIOException("Could not read the tenants of " + id, e);
            } catch (IllegalArgumentException _) {
                return List.of();   // an id that cannot name a subject is a member of nothing
            }
        });
    }

    /**
     * The tenants among {@code candidates} whose membership grants {@code id} a role. The index names candidates
     * rather than answers: a tenant purged whole removes its grants through the store and leaves the principal's
     * index naming it, and a grant at a single repository names a tenant where the console confers no role. Asking
     * each tenant for the role drops both, at a point read per candidate, each memoised for the request by
     * {@link MembershipCache}.
     */
    private List<String> granted(String id, List<String> candidates) {
        List<String> mine = new ArrayList<>();
        for (String tenant : candidates) {
            if (roleIn(tenant, id).isPresent()) {
                mine.add(tenant);
            }
        }
        return mine;
    }
}
