package build.jenesis.repository.server.spi;

import module java.base;

/**
 * Whether a grant covers a request: the matching every holder is judged by - a presented key, a subject, and the
 * keyless caller's configured rights - so that one vocabulary means the same thing whoever holds it.
 *
 * <p>A grant is a scope and a comma-separated list of {@code <surface>:<verb>} tokens. The scope is a repository name
 * ({@code *} matching any), optionally narrowed to a path prefix as {@code <repository>:<prefix>}; a token confers a
 * required right when it is that right, its surface's wildcard {@code <surface>:*}, or the all-privileges {@code *}.
 * A scope may carry an expiry, kept beside it in the same grants document under {@link #EXPIRES}.
 */
final class GrantMatching {

    /**
     * The key a scope's expiry is kept under, in the grants document beside the grant itself.
     *
     * <p>Beside it rather than in a document of its own, because {@link Authorization#authorize} may not grow a
     * read: what a caller holds is three point reads and stays three. It cannot collide with a scope, and that is a
     * property of the grammar rather than a hope - a grant scope is {@code *} or a repository name optionally
     * narrowed by a path prefix, a repository name is {@code [A-Za-z0-9_-]+}, and none of those can begin with a
     * dot. A separator inside the name would not have been safe: a path prefix may legitimately carry an {@code @}
     * (an npm scope), so {@code expires@<scope>} would have been a guess about which characters a coordinate space
     * uses.
     */
    static final String EXPIRES = ".expires.";

    private GrantMatching() {
    }

    /** The repository a request's scope names for matching: a blank scope is the default, which only an
     *  every-repository grant covers. */
    static String repository(String scope) {
        return scope == null || scope.isBlank() ? "*" : scope;
    }

    /** Whether a grants object carries {@code required} for this repository and path, a scope's lapsed expiry
     *  withdrawing what it granted - the matching applied to whichever subject row the caller is asking about. */
    static boolean holds(Properties grants, String repository, String path, String required) {
        if (grants == null) {
            return false;
        }
        Instant now = Instant.now();
        for (String grantScope : grants.stringPropertyNames()) {
            if (grantScope.startsWith(EXPIRES)) {
                continue;   // a scope's expiry, not a scope
            }
            if (!covers(grantScope, repository, path) || expired(grants, grantScope, now)) {
                continue;
            }
            if (confers(grants.getProperty(grantScope), required)) {
                return true;
            }
        }
        return false;
    }

    /** Whether this scope's grant has lapsed. A malformed instant is treated as expired rather than ignored: the
     *  value exists only because somebody time-boxed the grant, and reading it as "no expiry" would answer the
     *  opposite of what they asked for. */
    static boolean expired(Properties grants, String scope, Instant now) {
        String value = grants.getProperty(EXPIRES + scope);
        if (value == null) {
            return false;
        }
        try {
            return !Instant.parse(value).isAfter(now);
        } catch (DateTimeParseException unreadable) {
            return true;
        }
    }

    /** Whether a grant scope - a repository ({@code *} matching any), optionally narrowed by a {@code :<prefix>} path
     *  prefix - covers a request for {@code repository} on {@code path}. A prefix grant matches only a non-null path
     *  at or under the prefix on a segment boundary, so {@code maven/com/acme} covers {@code maven/com/acme/x} but not
     *  {@code maven/com/acmexyz}; a bare repository grant covers any path. */
    static boolean covers(String grantScope, String repository, String path) {
        int colon = grantScope.indexOf(':');
        String repositoryPart = colon < 0 ? grantScope : grantScope.substring(0, colon);
        if (!repositoryPart.equals("*") && !repositoryPart.equals(repository)) {
            return false;
        }
        if (colon < 0) {
            return true;
        }
        if (path == null) {
            return false;
        }
        String prefix = trimPath(grantScope.substring(colon + 1));
        String target = trimPath(path);
        return target.equals(prefix) || target.startsWith(prefix + "/");
    }

    private static String trimPath(String path) {
        String value = path.strip();
        if (value.endsWith("/*")) {
            value = value.substring(0, value.length() - 2);
        }
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == '/') {
            start++;
        }
        while (end > start && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(start, end);
    }

    /** Whether any token of a comma-separated list confers {@code required}. */
    static boolean confers(String tokens, String required) {
        for (String token : tokens.split(",")) {
            if (grantedBy(token, required)) {
                return true;
            }
        }
        return false;
    }

    /** Whether a granted token confers a required {@code <surface>:<verb>}: the exact token, the per-surface
     *  wildcard {@code <surface>:*}, or the all-privileges {@code *}. An unknown token confers nothing. */
    static boolean grantedBy(String granted, String required) {
        String trimmed = granted.trim();
        if (trimmed.equals("*") || trimmed.equals(required)) {
            return true;
        }
        int colon = required.indexOf(':');
        return colon > 0 && trimmed.equals(required.substring(0, colon) + ":*");
    }
}
