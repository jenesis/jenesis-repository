package build.jenesis.repository.ui.admin.security;

import module java.base;
import build.jenesis.repository.ui.identity.MembershipIndex;
import build.jenesis.repository.ui.identity.UserDirectory;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.store.Documents;
import build.jenesis.repository.cache.storage.Names;
import build.jenesis.repository.ui.store.TenantService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Reads tenant membership across the whole store: which tenants a console user belongs to, and the
 * role they hold in a given one. Each tenant keeps its members as one small object each under
 * a grant to their principal subject, so this resolves a user's access at login (allow if a member of any tenant, or a
 * super-admin) and per request (the role that the current tenant grants) with a point read per tenant asked about.
 * Writing membership is the per-tenant {@link UserDirectory}'s job; this is the cross-tenant read side.
 *
 * <p>{@link #accessibleTo} consults the reverse user&rarr;tenants {@link MembershipIndex}: a point read for the
 * candidate set, each candidate then confirmed against the forward grant it was derived from (see {@link #granted}),
 * so the answer is exactly what a walk of every tenant would give, at O(#user's tenants) point reads. The forward
 * per-member object stays the source of truth and the index is maintained on every membership write. A user whose
 * index is <em>absent</em> - a torn first write, or a user whose only role reaches them through a group - is answered
 * by the walk, which stamps the index so the next request is the point read.
 */
@Component
public class Memberships {

    private final Documents rootStorage;
    private final Authorization authorization;
    private final TenantService tenants;
    private final MembershipCache cache;
    private final MembershipIndex index;

    public Memberships(@Qualifier("rootStorage") Documents rootStorage, Authorization authorization,
                       TenantService tenants, MembershipCache cache) {
        this.rootStorage = rootStorage;
        this.authorization = authorization;
        this.tenants = tenants;
        this.cache = cache;
        this.index = new MembershipIndex(rootStorage);
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
                // A super-admin is a member everywhere regardless of any tenant's file, so there is nothing to index.
                return tenants.all();
            }
            Optional<List<String>> indexed = index.read(id);
            if (indexed.isPresent()) {
                // The fast path: a point read of the reverse user->tenants index names the candidates, and each is
                // confirmed against its forward member object - never the O(#tenants) walk (see granted).
                return granted(id, indexed.get());
            }
            // No reverse index for this user (a torn first write, or a role held only through a group). The
            // O(#tenants) walk answers so a real membership is never dropped, and stamps the index (best-effort) so
            // the next read is the point read above.
            List<String> walked = granted(id, tenants.all());
            index.stampIfAbsent(id, walked);
            return walked;
        });
    }

    /**
     * The tenants among {@code candidates} whose membership grants {@code id} a role - the walk's predicate,
     * applied to the full tenant list on the fallback path and to the indexed candidate set on the fast path. The
     * fast path must not trust a present index verbatim: the index is derived state and tenant deletion never
     * rewrites it ({@code TenantPurge} removes the tenant's own key spaces, not every member's {@code by-user}
     * entry), so a deleted tenant lingers there where a walk - which iterates live tenants only - would never return
     * it. Confirming each candidate against the forward source of truth (a deleted tenant's member object reads
     * absent, so it grants nothing) gives exactly the walk's answer at O(#user's tenants) point reads, each
     * memoised for the request by {@link MembershipCache}, instead of the walk's O(#deployment's tenants). The stale
     * entry is deliberately left in place rather than pruned: a remove-on-read would race a concurrent re-grant's
     * index add and could drop a real membership - the one hazard the index must never introduce - while an unpruned
     * entry costs only this filtered read.
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
