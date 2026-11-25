package build.jenesis.repository.server;

import module java.base;

import jakarta.servlet.http.HttpServletRequest;

/**
 * A part of this deployment's URL space whose URLs name the tenant they are served for - the repositories under
 * {@code /repository/<tenant>/...}, the build cache under {@code /build/<tenant>/...}. The token endpoint asks every
 * one a deployment carries which tenant a {@code resource} of a token-exchange request (RFC 8693 section 2.1)
 * addresses, so a client that knows only the URL it will use a key against is issued a key of that URL's tenant.
 * A module that serves such URLs contributes one as a bean; the URL spaces of two are disjoint.
 *
 * <h2>Contract</h2>
 * <ol>
 * <li><b>Thread-safety.</b> One shared instance is asked by concurrent requests.</li>
 * <li><b>Absence sentinel.</b> An empty answer says the path is not of this URL space, or names no tenant a
 *     request may address there; {@code null} is never returned and nothing is thrown for either.</li>
 * <li><b>Tenant scoping.</b> The answer is the tenant the deployment would serve the path for - under a routing
 *     that answers one tenant, that tenant and no other - decided from the names alone, so it is the same for a
 *     tenant that exists and one that does not.</li>
 * <li><b>Read purity.</b> The path is read as a name: nothing is written or authorized and no artifact is opened.
 *     An implementation over the routing reads what the routing reads to route any request.</li>
 * </ol>
 */
@FunctionalInterface
public interface TenantResource {

    /**
     * The tenant {@code path} names.
     *
     * @param request the token request, which carries no key of its own.
     * @param path    the decoded path of a URL of this deployment, starting with {@code /}.
     */
    Optional<String> tenant(HttpServletRequest request, String path);
}
