package build.jenesis.repository.ui;

import module java.base;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/**
 * The chain rule behind {@link ConsoleAccess}, shared by every console chain, production and development: a request is
 * allowed when a real principal is signed in and holds something in this console. An anonymous token is not a
 * principal, although Spring reports it authenticated.
 */
public final class ConsoleAccessRule {

    private ConsoleAccessRule() {
    }

    /**
     * The authorities that hold something whoever the principal is: a mechanism that signed somebody in as an
     * administrator established it, and some such principals (the bootstrap key's {@code admin}, the development
     * profile's users) have no grant row to find. {@code ROLE_USER} is absent, since everyone signed in holds it.
     */
    private static final Set<String> ADMINISTERS = Set.of("ROLE_SUPERADMIN", "ROLE_ADMIN");

    /** Allow a request only from a signed-in principal that {@code access} says holds something here - or that the
     *  mechanism which signed them in has already established as an administrator. */
    public static AuthorizationManager<RequestAuthorizationContext> holdsSomething(ConsoleAccess access) {
        return (authentication, context) -> new AuthorizationDecision(holds(access, authentication.get()));
    }

    /**
     * The predicate itself, shared by the chain's floor and the refusal handler so the two always agree.
     */
    public static boolean holds(ConsoleAccess access, Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return false;
        }
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if (ADMINISTERS.contains(authority.getAuthority())) {
                return true;
            }
        }
        return access.holdsAnything(authentication.getName());
    }
}
