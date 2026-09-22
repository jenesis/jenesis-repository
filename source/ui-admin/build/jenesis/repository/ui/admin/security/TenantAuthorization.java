package build.jenesis.repository.ui.admin.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;
import build.jenesis.repository.ui.identity.UserDirectory;

/**
 * Authorization for tenant-scoped routes: a request is allowed when the user is an env super-admin
 * (admin of every tenant) or holds at least the required role in the tenant the session has selected.
 * Roles live in that tenant's member objects, so the decision is made per request against the
 * current tenant rather than from a fixed authority. Used by both security chains to gate mutating
 * routes (editor) and the per-tenant admin area (admin).
 */
@Component
public class TenantAuthorization {

    private final Memberships memberships;

    public TenantAuthorization(Memberships memberships) {
        this.memberships = memberships;
    }

    public AuthorizationManager<RequestAuthorizationContext> require(UserDirectory.Role min) {
        return (authentication, context) -> {
            Authentication auth = authentication.get();
            if (auth == null || !auth.isAuthenticated()) {
                return new AuthorizationDecision(false);
            }
            for (GrantedAuthority authority : auth.getAuthorities()) {
                if (authority.getAuthority().equals("ROLE_SUPERADMIN")) {
                    return new AuthorizationDecision(true);
                }
            }
            HttpServletRequest request = context.getRequest();
            HttpSession session = request.getSession(false);
            Object tenant = session == null ? null : session.getAttribute(SessionCurrentTenant.ATTRIBUTE);
            if (tenant == null) {
                return new AuthorizationDecision(false);
            }
            boolean granted = memberships.roleIn(tenant.toString(), auth.getName())
                    .map(role -> role.atLeast(min))
                    .orElse(false);
            return new AuthorizationDecision(granted);
        };
    }
}
