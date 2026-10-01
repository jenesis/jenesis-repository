package build.jenesis.repository.ui;

import module java.base;

import org.springframework.security.core.GrantedAuthority;

/**
 * What a signed-in user may do: the console's authorization policy, asked once per sign-in by every login mechanism.
 * An implementation may refuse by throwing, for a principal the provider authenticates but the console must not
 * admit; the sign-in then stops with the provider's own error handling.
 */
@FunctionalInterface
public interface LoginAuthorities {

    /**
     * The authorities this principal carries, or a thrown refusal if it may not sign in at all.
     *
     * @param qualifiedId the provider-qualified identity, {@code <registration>/<id>} (see
     *                    {@link ProviderPrincipal}), which is the form every policy and every stored grant is
     *                    keyed by
     * @param displayName a human-readable name for messages and member lists, possibly empty - never identity
     * @return the granted authorities; never {@code null}
     */
    Collection<GrantedAuthority> authorities(String qualifiedId, String displayName);
}
