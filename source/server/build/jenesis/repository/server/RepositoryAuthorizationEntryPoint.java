package build.jenesis.repository.server;

import module java.base;

import build.jenesis.repository.server.spi.Authorization;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Maps a denied request to the status the {@link Authorization} credential model intends, rather than letting
 * Spring Security guess it from whether the request looks anonymous. The {@link RepositoryAuthorizationManager}
 * records the {@link Authorization.Decision} it computed on the request; this single component serves as both the
 * {@link AuthenticationEntryPoint} (Spring's path for a denial it deems unauthenticated) and the
 * {@link AccessDeniedHandler} (its path for a denial it deems authenticated), so either path answers {@code 403}
 * for a {@code FORBIDDEN} decision (a key that lacks the right) and {@code 401} otherwise (no key, a malformed or
 * expired key). The decision drives the status, so a present-but-unauthorized key is always a {@code 403}. Every
 * denial it answers is recorded on the {@link AuthFailures} accessor under the {@code key} mechanism, so a metrics
 * layer can surface {@code jenreg.auth.failures} without this component depending on any registry. A {@code 401}
 * on an artifact path ({@code /repository/**}, {@code /v2/**}) additionally carries a {@code WWW-Authenticate: Basic}
 * challenge, because several ecosystem clients present their credentials only in answer to one - Maven on a read,
 * a Distribution client on everything - and without it they report the repository as unauthorized and never send
 * the key they hold. It also names whatever schemes the addressed repository's format declares
 * ({@link build.jenesis.repository.format.RepositoryFormat#challenges}) - Cargo's own, because cargo asks for the
 * sparse index's {@code config.json} without a token and retries with one only when the 401 names that scheme.
 * The API and actuator paths stay bare, so a browser calling them is never shown a Basic dialog.
 */
public final class RepositoryAuthorizationEntryPoint implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final AuthFailures failures;
    private final Function<HttpServletRequest, List<String>> challenges;

    /**
     * @param failures   where every denial is recorded.
     * @param challenges the schemes the format of the repository a request addresses declares, asked only for a
     *                   {@code 401} on an artifact path; it answers none for a request it cannot resolve.
     */
    public RepositoryAuthorizationEntryPoint(AuthFailures failures,
                                             Function<HttpServletRequest, List<String>> challenges) {
        this.failures = failures;
        this.challenges = challenges;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception) {
        respond(request, response);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception) {
        respond(request, response);
    }

    private static boolean artifact(String uri) {
        return uri.startsWith("/repository/") || uri.startsWith(RepositoryRouting.STAGING) || uri.startsWith("/v2/");
    }

    private void respond(HttpServletRequest request, HttpServletResponse response) {
        Object decision = request.getAttribute("jenreg.decision");
        int status = decision == Authorization.Decision.FORBIDDEN ? 403 : 401;
        response.setStatus(status);
        if (status == 401 && artifact(request.getRequestURI())) {
            response.setHeader("WWW-Authenticate", "Basic realm=\"Jenesis Repository\"");
            for (String scheme : challenges.apply(request)) {
                response.addHeader("WWW-Authenticate", scheme);
            }
        }
        failures.record("key", status);
    }
}
