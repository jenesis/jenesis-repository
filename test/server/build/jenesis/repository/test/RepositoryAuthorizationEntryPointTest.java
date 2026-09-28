package build.jenesis.repository.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.server.AuthFailures;
import build.jenesis.repository.server.RepositoryAuthorizationEntryPoint;
import build.jenesis.repository.server.spi.AccessDenial;
import build.jenesis.repository.server.spi.Authorization;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * How the entry point answers a denial. A caller with no usable credential gets a {@code 401}, and on an artifact path
 * the challenge its client needs to send one: Maven on a read and a Distribution client on everything present their
 * credentials only in answer to a {@code WWW-Authenticate} challenge, and cargo only to one naming its own scheme. The
 * API paths answer the bare status, so a browser is never shown a Basic dialog. A caller whose credential does not
 * reach the target is refused as the deployment's {@link AccessDenial} says, without a challenge - it already holds a
 * credential, which does not suffice.
 *
 * <p>Nothing here may depend on the repository a request names: the challenge is the installed formats', so it is
 * the same for a repository that exists and one that does not, and the refusal is the same for both by construction.
 */
class RepositoryAuthorizationEntryPointTest {

    private final AuthFailures failures = new AuthFailures();

    @Test
    void a_keyless_registry_request_is_challenged_with_basic() {
        HttpServletRequest request = request("/v2/", null);
        HttpServletResponse response = mock(HttpServletResponse.class);

        entryPoint(List.of(), AccessDenial.NOT_FOUND).commence(request, response,
                new InsufficientAuthenticationException("no key"));

        verify(response).setStatus(401);
        verify(response).setHeader("WWW-Authenticate", "Basic realm=\"Jenesis Repository\"");
        assertThat(failures.count("key", 401)).isEqualTo(1);
    }

    @Test
    void a_keyless_artifact_read_is_challenged_with_basic() {
        HttpServletRequest request = request("/repository/default/maven/org/x/y/1/y-1.jar", null);
        HttpServletResponse response = mock(HttpServletResponse.class);

        entryPoint(List.of(), AccessDenial.NOT_FOUND).commence(request, response,
                new InsufficientAuthenticationException("no key"));

        verify(response).setStatus(401);
        verify(response).setHeader("WWW-Authenticate", "Basic realm=\"Jenesis Repository\"");
    }

    @Test
    void every_keyless_artifact_request_names_the_installed_formats_schemes_whatever_it_addresses() {
        // A Cargo scheme on a Maven path, and on a repository that need not exist: the schemes are the installed
        // formats', so the challenge cannot say what a repository holds or whether there is one.
        for (String uri : List.of("/repository/default/crates/config.json", "/repository/default/absent/x.pom",
                "/v2/default/images/app/manifests/1.0", "/staging/default/releases/1/x.jar")) {
            HttpServletRequest request = request(uri, null);
            HttpServletResponse response = mock(HttpServletResponse.class);

            entryPoint(List.of("Cargo"), AccessDenial.NOT_FOUND).commence(request, response,
                    new InsufficientAuthenticationException("no key"));

            verify(response).setStatus(401);
            verify(response).setHeader("WWW-Authenticate", "Basic realm=\"Jenesis Repository\"");
            verify(response).addHeader("WWW-Authenticate", "Cargo");
        }
    }

    @Test
    void a_keyless_api_request_answers_a_bare_401() {
        HttpServletRequest request = request("/api/credentials", null);
        HttpServletResponse response = mock(HttpServletResponse.class);

        entryPoint(List.of("Cargo"), AccessDenial.NOT_FOUND).commence(request, response,
                new InsufficientAuthenticationException("no key"));

        verify(response).setStatus(401);
        verify(response, never()).setHeader(anyString(), anyString());
        verify(response, never()).addHeader(anyString(), anyString());
    }

    @Test
    void a_refusal_answers_404_by_default_without_a_challenge_on_either_failure_path() {
        for (String uri : List.of("/v2/library/app/blobs/uploads/", "/repository/default/releases/a.pom",
                "/api/settings", "/actuator/prometheus")) {
            HttpServletResponse commenced = mock(HttpServletResponse.class);
            entryPoint(List.of("Cargo"), AccessDenial.NOT_FOUND).commence(
                    request(uri, Authorization.Decision.FORBIDDEN), commenced,
                    new InsufficientAuthenticationException("read-only key"));
            HttpServletResponse handled = mock(HttpServletResponse.class);
            entryPoint(List.of("Cargo"), AccessDenial.NOT_FOUND).handle(
                    request(uri, Authorization.Decision.FORBIDDEN), handled, new AccessDeniedException("no right"));

            for (HttpServletResponse response : List.of(commenced, handled)) {
                verify(response).setStatus(404);
                verify(response, never()).setHeader(anyString(), anyString());
                verify(response, never()).addHeader(anyString(), anyString());
            }
        }
        assertThat(failures.count("key", 403)).as("counted as the refusal it is, whatever status answered it")
                .isEqualTo(8);
        assertThat(failures.count("key", 404)).isZero();
    }

    @Test
    void a_refusal_answers_403_when_the_deployment_says_so() {
        HttpServletRequest request = request("/v2/library/app/blobs/uploads/", Authorization.Decision.FORBIDDEN);
        HttpServletResponse response = mock(HttpServletResponse.class);

        entryPoint(List.of("Cargo"), AccessDenial.FORBIDDEN).commence(request, response,
                new InsufficientAuthenticationException("read-only key"));

        verify(response).setStatus(403);
        verify(response, never()).setHeader(anyString(), anyString());
        assertThat(failures.count("key", 403)).isEqualTo(1);
    }

    @Test
    void the_setting_is_asked_on_each_refusal() {
        // Live: a value written in the console applies to the next refusal, with no restart.
        AtomicReference<AccessDenial> setting = new AtomicReference<>(AccessDenial.NOT_FOUND);
        RepositoryAuthorizationEntryPoint entryPoint = new RepositoryAuthorizationEntryPoint(failures, List::of,
                setting::get);
        HttpServletResponse before = mock(HttpServletResponse.class);
        entryPoint.handle(request("/api/settings", Authorization.Decision.FORBIDDEN), before,
                new AccessDeniedException("no right"));
        setting.set(AccessDenial.FORBIDDEN);
        HttpServletResponse after = mock(HttpServletResponse.class);
        entryPoint.handle(request("/api/settings", Authorization.Decision.FORBIDDEN), after,
                new AccessDeniedException("no right"));

        verify(before).setStatus(404);
        verify(after).setStatus(403);
    }

    private RepositoryAuthorizationEntryPoint entryPoint(List<String> challenges, AccessDenial denial) {
        return new RepositoryAuthorizationEntryPoint(failures, () -> challenges, () -> denial);
    }

    private static HttpServletRequest request(String uri, Authorization.Decision decision) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn(uri);
        when(request.getAttribute("jenrepo.decision")).thenReturn(decision);
        return request;
    }
}
