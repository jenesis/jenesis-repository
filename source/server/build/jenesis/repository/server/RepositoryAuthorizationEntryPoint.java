package build.jenesis.repository.server;

import module java.base;

import build.jenesis.repository.server.spi.AccessDenial;
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
 * {@link AccessDeniedHandler} (its path for a denial it deems authenticated), so either path answers the same way: a
 * {@code FORBIDDEN} decision (a key that lacks the right, or whose source address lies outside the credential's
 * allowlist) is refused with the deployment's {@link AccessDenial} - {@code 404} unless an operator chose {@code 403} -
 * and anything else (no key, a malformed or expired key) is a {@code 401}. Every denial it answers is recorded on the
 * {@link AuthFailures} accessor under the {@code key} mechanism, as a {@code 401} or a {@code 403} whatever status the
 * refusal was answered with, so a metrics layer can surface {@code jenrepo.auth.failures} without this component
 * depending on any registry.
 *
 * <p>A {@code 401} on an artifact path ({@code /repository/**}, {@code /v2/**}, a staged upload) carries a
 * {@code WWW-Authenticate: Basic} challenge, because several ecosystem clients present their credentials only in
 * answer to one - Maven on a read, a Distribution client on everything - and without it they report the repository as
 * unauthorized and never send the key they hold. It also names every scheme an installed format declares
 * ({@link build.jenesis.repository.format.RepositoryFormat#challenges}) - Cargo's own, because cargo asks for the
 * sparse index's {@code config.json} without a token and retries with one only when the 401 names that scheme. The
 * schemes are the installed formats', never the addressed repository's: a challenge naming the format a repository
 * holds would tell a caller with no credential that the repository exists and what it is, and looking it up would cost
 * a store read an absent name does not. The API and actuator paths stay bare, so a browser calling them is never shown
 * a Basic dialog.
 */
public final class RepositoryAuthorizationEntryPoint implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final AuthFailures failures;
    private final Supplier<List<String>> challenges;
    private final Supplier<AccessDenial> denial;

    /**
     * @param failures   where every denial is recorded.
     * @param challenges the schemes the installed formats declare, named on a {@code 401} on an artifact path beside
     *                   {@code Basic}.
     * @param denial     how a refusal is answered, asked on each refusal so a live setting is honoured.
     */
    public RepositoryAuthorizationEntryPoint(AuthFailures failures, Supplier<List<String>> challenges,
                                             Supplier<AccessDenial> denial) {
        this.failures = failures;
        this.challenges = challenges;
        this.denial = denial;
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
        if (request.getAttribute("jenrepo.decision") == Authorization.Decision.FORBIDDEN) {
            response.setStatus(denial.get().status());
            failures.record("key", 403);
            return;
        }
        response.setStatus(401);
        if (artifact(request.getRequestURI())) {
            response.setHeader("WWW-Authenticate", "Basic realm=\"Jenesis Repository\"");
            for (String scheme : challenges.get()) {
                response.addHeader("WWW-Authenticate", scheme);
            }
        }
        failures.record("key", 401);
    }
}
