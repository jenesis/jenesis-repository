package build.jenesis.repository.test;

import build.jenesis.repository.server.AuthFailures;
import build.jenesis.repository.server.RepositoryAuthorizationEntryPoint;
import build.jenesis.repository.server.spi.Authorization;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import module org.junit.jupiter.api;
import org.springframework.security.authentication.InsufficientAuthenticationException;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The entry point's one protocol concession: a Distribution client presents its credentials only in answer to a
 * {@code WWW-Authenticate} challenge, so a {@code 401} under {@code /v2/} carries a {@code Basic} one. Every other
 * path answers the bare status the credential model intends, and a {@code 403} (a key that lacks the right) is
 * never a challenge - the client already holds a credential, it just does not suffice.
 */
class RepositoryAuthorizationEntryPointTest {

    private final AuthFailures failures = new AuthFailures();

    @Test
    void a_keyless_registry_request_is_challenged_with_basic() {
        HttpServletRequest request = request("/v2/", null);
        HttpServletResponse response = mock(HttpServletResponse.class);

        new RepositoryAuthorizationEntryPoint(failures).commence(request, response,
                new InsufficientAuthenticationException("no key"));

        verify(response).setStatus(401);
        verify(response).setHeader("WWW-Authenticate", "Basic realm=\"Jenesis Repository\"");
        assertThat(failures.count("key", 401)).isEqualTo(1);
    }

    @Test
    void a_keyless_artifact_request_outside_the_registry_answers_a_bare_401() {
        HttpServletRequest request = request("/repository/maven/org/x/y/1/y-1.jar", null);
        HttpServletResponse response = mock(HttpServletResponse.class);

        new RepositoryAuthorizationEntryPoint(failures).commence(request, response,
                new InsufficientAuthenticationException("no key"));

        verify(response).setStatus(401);
        verify(response, never()).setHeader(anyString(), anyString());
    }

    @Test
    void a_forbidden_registry_request_is_a_403_without_a_challenge() {
        HttpServletRequest request = request("/v2/library/app/blobs/uploads/", Authorization.Decision.FORBIDDEN);
        HttpServletResponse response = mock(HttpServletResponse.class);

        new RepositoryAuthorizationEntryPoint(failures).commence(request, response,
                new InsufficientAuthenticationException("read-only key"));

        verify(response).setStatus(403);
        verify(response, never()).setHeader(anyString(), anyString());
        assertThat(failures.count("key", 403)).isEqualTo(1);
    }

    private static HttpServletRequest request(String uri, Authorization.Decision decision) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn(uri);
        when(request.getAttribute("jenreg.decision")).thenReturn(decision);
        return request;
    }
}
