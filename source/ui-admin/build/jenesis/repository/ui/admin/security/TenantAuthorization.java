package build.jenesis.repository.ui.admin.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;
import build.jenesis.repository.ui.SuperadminRole;
import build.jenesis.repository.ui.identity.UserDirectory;

/**
 * Authorization for tenant-scoped routes: allowed for a super-admin, or a user holding at least the required role in
 * the session's tenant, decided per request from that tenant's grants. Both security chains use it.
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
                if (authority.getAuthority().equals(SuperadminRole.AUTHORITY)) {
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
