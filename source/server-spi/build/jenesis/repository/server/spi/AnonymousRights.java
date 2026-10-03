package build.jenesis.repository.server.spi;

import module java.base;
import build.jenesis.repository.scope.AnonymousGrants;

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

    /** Parse an {@code anonymous-rights} value through its one grammar, {@link AnonymousGrants}. No new right
     *  vocabulary is introduced - a token that names nothing simply confers nothing at match time, exactly as an
     *  unknown token on a minted credential does. */
    static AnonymousRights parse(String rights) {
        Map<String, List<String>> grants = AnonymousGrants.parse(rights);
        return grants.isEmpty() ? NONE : new AnonymousRights(Map.copyOf(grants));
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
}
