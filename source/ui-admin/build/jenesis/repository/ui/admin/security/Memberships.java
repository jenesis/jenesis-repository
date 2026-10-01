package build.jenesis.repository.ui.admin.security;

import module java.base;
import build.jenesis.repository.ui.identity.UserDirectory;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.cache.storage.Names;
import build.jenesis.repository.ui.store.TenantService;
import org.springframework.stereotype.Component;

/**
 * Reads tenant membership across the store, the cross-tenant read side of {@link UserDirectory}: which tenants a
 * console user belongs to and the role each grants, one point read per tenant asked about. {@link #accessibleTo}
 * reads the tenants {@link Authorization#tenantsOf} names for the user and confirms each ({@link #granted}), so it
 * costs a read per tenant the user belongs to, never a probe of every tenant.
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
        // Memoised for the request, which reads the same role many times.
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
     * The tenants among {@code candidates} that grant {@code id} a role. The index only names candidates: it can name a
     * purged tenant, or one where a single-repository grant confers no console role. One memoised read per candidate
     * ({@link MembershipCache}).
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
