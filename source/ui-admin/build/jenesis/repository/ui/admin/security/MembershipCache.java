package build.jenesis.repository.ui.admin.security;

import build.jenesis.repository.ui.identity.UserDirectory;
import module java.base;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

/**
 * A per-request memo over {@link Memberships}' reads, which one render asks many times (the shell, the nav, every
 * {@code TenantAuthorization} decision). Request-scoped, so a revoked grant counts on the next request and nothing
 * needs evicting; the scoped proxy lets the singleton {@link Memberships} reach the current request's instance.
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
