package build.jenesis.repository.ui;

import module java.base;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/**
 * The chain rule behind {@link ConsoleAccess}: a request is allowed when a real principal is signed in and holds
 * something in this console.
 *
 * <p>It lives here, once, rather than as a lambda in each chain, for the reason the authorization matrix is also
 * declared once: a security rule copied into a second chain is a rule that will drift, and it drifts towards
 * whichever copy somebody forgets. Every chain that serves a console - production, development, and both editions'
 * - asks this.
 *
 * <p>An anonymous token is explicitly not a principal. Spring's anonymous authentication reports itself as
 * authenticated, so a check that asked only {@code isAuthenticated()} would hand the console to an unauthenticated
 * visitor whenever anonymous authentication is enabled - and {@code getName()} would then be {@code anonymousUser},
 * an id that names no subject and would be asked about as if it were one.
 */
public final class ConsoleAccessRule {

    private ConsoleAccessRule() {
    }

    /**
     * The authorities that <em>are</em> holding something, whoever the principal turns out to be.
     *
     * <p>A mechanism that signs somebody in as an administrator has established that by authenticating them, and
     * the matrix already trusts these two for the screens they gate - so asking the grant store a second time
     * would only be able to disagree, and disagreeing means locking out the administrator. Two of the four ways
     * they are conferred have no principal row to find at all: the bootstrap key signs in as {@code admin}, a name
     * that is a deployment credential rather than a person, and the development profile invents its users
     * outright.
     *
     * <p>{@code ROLE_USER} is deliberately absent. It is what everybody who signs in gets, so accepting it here
     * would be {@code authenticated()} again, and the floor would be no floor.
     */
    private static final Set<String> ADMINISTERS = Set.of("ROLE_SUPERADMIN", "ROLE_ADMIN");

    /** Allow a request only from a signed-in principal that {@code access} says holds something here - or that the
     *  mechanism which signed them in has already established as an administrator. */
    public static AuthorizationManager<RequestAuthorizationContext> holdsSomething(ConsoleAccess access) {
        return (authentication, context) -> new AuthorizationDecision(holds(access, authentication.get()));
    }

    /**
     * The predicate itself, so the chain's floor and the refusal handler cannot answer it differently.
     *
     * <p>They did, briefly, and the result was the wrong screen for the wrong person: an administrator refused one
     * screen above their grade was told they hold nothing at all, because the handler asked the grant store while
     * the floor had already let them past on their authority. Whatever "holds something" means, it has to mean the
     * same thing to whoever asks.
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
