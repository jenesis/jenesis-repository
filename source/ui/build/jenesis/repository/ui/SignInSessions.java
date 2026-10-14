package build.jenesis.repository.ui;

import module java.base;
import org.springframework.security.web.authentication.AbstractAuthenticationProcessingFilter;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

/**
 * What a sign-in filter a {@link LoginContributor} builds itself carries so that it signs a person into a session the
 * way the chain's own login configurers do. Spring Security sets both on a filter its DSL creates and on no other, and
 * leaving either out fails nothing visible: without the session context repository a sign-in is forgotten on the next
 * request, and without the session strategy the person is signed into the session the request arrived with - so a
 * session id an attacker planted in the browser before the sign-in is, afterwards, a signed-in session the attacker
 * holds.
 */
public final class SignInSessions {

    private SignInSessions() {
    }

    /**
     * {@code filter}, saving a sign-in to the session under a new session id and a new CSRF token. The token is the
     * console's own session-held one, the repository the chain's CSRF filter uses when none is configured.
     */
    public static <F extends AbstractAuthenticationProcessingFilter> F into(F filter) {
        filter.setSecurityContextRepository(new HttpSessionSecurityContextRepository());
        filter.setSessionAuthenticationStrategy(new CompositeSessionAuthenticationStrategy(List.of(
                new ChangeSessionIdAuthenticationStrategy(),
                new CsrfAuthenticationStrategy(new HttpSessionCsrfTokenRepository()))));
        return filter;
    }
}
