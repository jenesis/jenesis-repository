package build.jenesis.repository.server.kernel;

import module java.base;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.util.UriUtils;

/**
 * Request helpers shared by the server's focused core controllers, kept in one place after the
 * {@code RepositoryController} monolith was split into the console shell, the deploy write path, admin import,
 * browse/search, dependents, the format icon and the deployment-info reads. These are the cross-cutting concerns a
 * few of those controllers share - resolving the request's tenant and guarding a query-supplied path against
 * traversal - so each controller stays a thin HTTP layer over the domain without copying the mechanism. Wrapping an
 * important repository event in an observation is the canonical {@code build.jenesis.repository.server.Observations}
 * helper, which the controllers call directly.
 */
public final class RepositoryRequests {

    private RepositoryRequests() {
    }

    /**
     * Validates the named repository and resolves the request's tenant from the {@code Jenesis-Repository-Key}
     * header, answering {@code 400} for a traversal-unsafe repository or tenant name and returning {@code null} so
     * the caller returns at once. Rights are enforced by Spring Security before the request reaches the controller,
     * so this makes no authorization decision.
     */
    public static String access(Repositories repositories, String repo, String key, HttpServletResponse response) {
        if (!Repositories.valid(repo)) {
            response.setStatus(400);
            return null;
        }
        String tenant = repositories.tenant(key);
        if (!Repositories.valid(tenant)) {
            response.setStatus(400);
            return null;
        }
        return tenant;
    }

    /**
     * Reject a parent-directory segment in a value the servlet container has <b>already decoded</b> - a
     * {@code @PathVariable}, a query parameter or a JSON body field - so it cannot escape the subtree the request is
     * scoped to once it becomes a store key. The container normalises the request <em>path</em> and not these, which
     * is why they are guarded here at all; but it has decoded them, so a percent-encoded traversal has already become
     * a literal {@code ..} by the time it arrives and this comparison sees it.
     *
     * <p>A {@code null} is rejected rather than passed over. Every caller guards a value that is about to be
     * concatenated into a store key, so an absent one is a malformed request, and the callers turn this exception
     * into a {@code 400} - which is the answer a missing required parameter deserves, and better than the
     * {@code NullPointerException} that reading it as a {@code 500} used to produce.
     *
     * <p>Use {@link #rejectRawTraversal} instead where the value is taken from the raw request URI; that is a
     * different question and a weaker check there is a real hole rather than a redundant one.
     */
    public static void rejectTraversal(String value) {
        if (value == null || value.contains("..")) {
            throw new IllegalArgumentException("A repository path must not contain '..'.");
        }
    }

    /**
     * Reject a traversal in a value taken from the <b>raw, undecoded</b> request URI, where {@link #rejectTraversal}
     * is genuinely not enough: nothing has decoded it, so {@code %2e%2e}, {@code %2E%2E} and {@code ..%2f} all carry a
     * parent-directory segment past a plain {@code contains("..")}, and a double encoding ({@code %252e%252e}) becomes
     * one only when something downstream decodes a second time.
     *
     * <p>So decode once - exactly as the enforcing {@code RepositoryAuthorizationManager} does before it routes - and
     * reject a {@code ..} in either form, any residual percent-escape (which is what a double encoding leaves behind),
     * and any un-normalised decoded segment ({@code //}, {@code /./}, {@code /../}). This matters most on a
     * non-enforcing (anonymous) deployment, where that path normaliser never runs at all.
     *
     * <p>The staging store carries a guard of its own that asks the same question of the keys it forms, and the two
     * are deliberately separate implementations rather than one shared helper: a second layer checking the same
     * property only adds anything while it can fail independently of the first.
     */
    public static void rejectRawTraversal(String value) {
        if (value == null) {
            throw new IllegalArgumentException("A repository path must not contain '..'.");
        }
        String decoded = UriUtils.decode(value, StandardCharsets.UTF_8);
        String rooted = decoded.startsWith("/") ? decoded : "/" + decoded;
        if (value.contains("..") || decoded.contains("..") || decoded.indexOf('%') >= 0
                || !normalized(rooted)) {
            throw new IllegalArgumentException(
                    "A repository path must not contain '..' or a percent-encoded segment.");
        }
    }

    /** Whether the (already percent-decoded) request path is normalized - carries no empty ({@code //}) or dot
     *  ({@code /.} , {@code /..}) segment. Spring routes on the normalized path, so an un-normalized URI would reach a
     *  controller while a prefix-based classification of it - the authorization manager's scope and operator gate,
     *  the traversal guard above - misreads it; a legitimate artifact, {@code /api} or {@code /actuator} route never
     *  carries such a segment, so a request that does is rejected rather than classified. A trailing single slash (an
     *  empty <em>terminal</em> segment) is left alone - it does not shift a prefix match - so directory-style listing
     *  paths are unaffected. It used to live on the enforcing manager and was read from here; it lives here because
     *  the manager is in the tenancy module and the kernel is what both read. */
    public static boolean normalized(String path) {
        return !path.contains("//")
                && !path.contains("/./") && !path.contains("/../")
                && !path.endsWith("/.") && !path.endsWith("/..");
    }

}
