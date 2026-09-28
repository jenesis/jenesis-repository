package build.jenesis.repository.server.spi;

import module java.base;

/**
 * The strictly-opt-in anonymous role: the rights a keyless caller is granted, as {@code scope -> tokens}, exactly the
 * shape a minted credential's grants object has, and matched by the same {@link GrantMatching}.
 *
 * <p>Empty (the default) means no anonymous access: a keyless request is rejected exactly as an enforcing deployment
 * rejects one with no role configured. Immutable, parsed once from {@code jenrepo.anonymous-rights} when the
 * authorization is built.
 */
public final class AnonymousRights {

    static final AnonymousRights NONE = new AnonymousRights(Map.of());

    private final Map<String, List<String>> grants;

    private AnonymousRights(Map<String, List<String>> grants) {
        this.grants = grants;
    }

    /** Parse an {@code anonymous-rights} value, the shape a credential's grants object has. A bare token is granted
     *  on the {@code *} (every-repository) scope; a {@code <scope>=<token>} entry (the scope a repository name, or
     *  {@code <repository>:<prefix>} path scope) is granted on that scope. Blank/garbage entries are skipped. No new
     *  right vocabulary is introduced - a token that names nothing simply confers nothing at match time, exactly as
     *  an unknown token on a minted credential does. */
    static AnonymousRights parse(String rights) {
        if (rights == null || rights.isBlank()) {
            return NONE;
        }
        Map<String, List<String>> grants = new LinkedHashMap<>();
        for (String element : rights.split(",")) {
            String entry = element.strip();
            if (entry.isEmpty()) {
                continue;
            }
            int equals = entry.indexOf('=');
            String scope = equals < 0 ? "*" : entry.substring(0, equals).strip();
            String token = (equals < 0 ? entry : entry.substring(equals + 1)).strip();
            if (scope.isEmpty() || token.isEmpty()) {
                continue;
            }
            grants.computeIfAbsent(scope, unused -> new ArrayList<>()).add(token);
        }
        Map<String, List<String>> immutable = new LinkedHashMap<>();
        grants.forEach((scope, tokens) -> immutable.put(scope, List.copyOf(tokens)));
        return new AnonymousRights(Map.copyOf(immutable));
    }

    /** Whether any right is granted to a keyless caller. */
    boolean enabled() {
        return !grants.isEmpty();
    }

    /** The verdict for a keyless request under an enforcing deployment: {@code ALLOWED} iff a granted scope covers
     *  {@code scope} on {@code path} with a token conferring {@code required}; else {@code UNAUTHORIZED}, the
     *  {@code 401} a keyless request takes. Empty grants (the default) cover nothing. */
    Authorization.Decision decide(String scope, String path, String required) {
        if (grants.isEmpty()) {
            return Authorization.Decision.UNAUTHORIZED;
        }
        String repository = GrantMatching.repository(scope);
        for (Map.Entry<String, List<String>> grant : grants.entrySet()) {
            if (!GrantMatching.covers(grant.getKey(), repository, path)) {
                continue;
            }
            for (String token : grant.getValue()) {
                if (GrantMatching.grantedBy(token, required)) {
                    return Authorization.Decision.ALLOWED;
                }
            }
        }
        return Authorization.Decision.UNAUTHORIZED;
    }

    /** Whether an {@code anonymous-rights} value would let a keyless caller write or administer - it grants the
     *  all-privileges {@code *}, any {@code <surface>:write} (or a {@code <surface>:*} wildcard covering write), or any
     *  {@code manage:<verb>} admin right. The loud-warning escalation (the second guardrail): anonymous read is a WARN,
     *  anonymous write/admin a governance-level CRITICAL. Mirrored by the posture seeder (which cannot depend on this
     *  module). */
    public static boolean grantsWriteOrAdmin(String rights) {
        if (rights == null || rights.isBlank()) {
            return false;
        }
        for (String element : rights.split(",")) {
            String entry = element.strip();
            int equals = entry.indexOf('=');
            String token = (equals < 0 ? entry : entry.substring(equals + 1)).strip();
            if (token.equals("*")) {
                return true;
            }
            int colon = token.indexOf(':');
            String surface = colon < 0 ? token : token.substring(0, colon);
            String verb = colon < 0 ? "" : token.substring(colon + 1).strip();
            if (surface.equals("manage")) {
                return true;
            }
            if (verb.equals("write") || verb.equals("*")) {
                return true;
            }
        }
        return false;
    }
}
