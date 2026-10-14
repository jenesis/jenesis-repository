package build.jenesis.repository.ui.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.ui.SignInSessions;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.web.authentication.AbstractAuthenticationProcessingFilter;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A sign-in filter a login mechanism builds itself signs a person into a session the way the chain's own login does:
 * under a new session id, so an id planted in the victim's browser before the sign-in does not become a signed-in
 * session, and with the sign-in saved to that session. A bare filter does neither, which is the second test: it is
 * what every hand-built sign-in filter is without {@link SignInSessions}.
 */
public class SignInSessionsTest {

    @Test
    public void a_sign_in_moves_the_session_it_arrived_with_to_a_new_id_and_saves_the_sign_in_there()
            throws Exception {
        HttpSession session = mock(HttpSession.class);
        HttpServletRequest request = arrivingWith(session);

        SignInSessions.into(new SigningIn())
                .doFilter(request, mock(HttpServletResponse.class), mock(FilterChain.class));

        verify(request).changeSessionId();
        verify(session).setAttribute(eq("SPRING_SECURITY_CONTEXT"), any());
    }

    @Test
    public void a_bare_sign_in_filter_keeps_the_planted_session_id_and_forgets_the_sign_in() throws Exception {
        HttpSession session = mock(HttpSession.class);
        HttpServletRequest request = arrivingWith(session);

        new SigningIn().doFilter(request, mock(HttpServletResponse.class), mock(FilterChain.class));

        verify(request, never()).changeSessionId();
        verify(session, never()).setAttribute(eq("SPRING_SECURITY_CONTEXT"), any());
    }

    /** A sign-in request carrying a session the browser already held - the one an attacker could have planted. */
    private static HttpServletRequest arrivingWith(HttpSession session) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(session.getId()).thenReturn("planted");
        when(request.getSession()).thenReturn(session);
        when(request.getSession(false)).thenReturn(session);
        when(request.getSession(true)).thenReturn(session);
        when(request.isRequestedSessionIdValid()).thenReturn(true);
        when(request.changeSessionId()).thenReturn("fresh");
        when(request.getMethod()).thenReturn("POST");
        return request;
    }

    /** A filter that signs every request in, and answers nothing of its own after it. */
    private static final class SigningIn extends AbstractAuthenticationProcessingFilter {

        private SigningIn() {
            super(request -> true);
            setAuthenticationSuccessHandler((request, response, authentication) -> {
            });
        }

        @Override
        public Authentication attemptAuthentication(HttpServletRequest request, HttpServletResponse response) {
            return UsernamePasswordAuthenticationToken.authenticated("someone", null,
                    AuthorityUtils.createAuthorityList("ROLE_USER"));
        }
    }
}
