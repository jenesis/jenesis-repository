package build.jenesis.repository.ui.admin.security;

import build.jenesis.repository.ui.identity.UserDirectory;
import module java.base;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

/**
 * A per-request memo over {@link Memberships}' cross-tenant reads. A single console page render asks for a user's
 * accessible tenants and their role in the selected tenant several times over (the shell's tenancy chrome, the nav
 * entries, every {@code TenantAuthorization} decision), and each {@link Memberships#accessibleTo} loops every tenant
 * reading the user's own grant under the tenant's authorization space - so an uncached render is O(#tenants) store round-trips repeated per
 * call. This holds those results for the life of the request only: request-scoped, so it never serves a stale role
 * across requests (a revoked grant is seen on the next request), and it needs no eviction - the scope ends with the
 * response. A scoped proxy lets the singleton {@link Memberships} hold a reference that resolves to the current
 * request's instance.
 */
@Component
@RequestScope(proxyMode = ScopedProxyMode.TARGET_CLASS)
public class MembershipCache {

    private final Map<String, Optional<UserDirectory.Role>> roles = new HashMap<>();
    private final Map<String, List<String>> accessible = new HashMap<>();

    /** The cached role for {@code id} in {@code tenant}, computing and storing it through {@code loader} on a miss. */
    Optional<UserDirectory.Role> role(String tenant, String id, Supplier<Optional<UserDirectory.Role>> loader) {
        return roles.computeIfAbsent(tenant + '\0' + id, _ -> loader.get());
    }

    /** The cached accessible-tenant list for {@code id}, computing and storing it through {@code loader} on a miss. */
    List<String> accessible(String id, boolean superadmin, Supplier<List<String>> loader) {
        return accessible.computeIfAbsent((superadmin ? "1" : "0") + id, _ -> loader.get());
    }
}
