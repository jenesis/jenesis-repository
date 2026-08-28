package build.jenesis.repository.ui;

import org.springframework.security.core.GrantedAuthority;

import module java.base;

/**
 * What a signed-in user may do: the console's authorization policy, asked once per sign-in.
 *
 * <p>Every login mechanism ends at the same question - a provider has just told us who this is, so what authorities
 * does that principal carry, and may they in at all? The mechanisms differ in how they establish identity; they do
 * not differ in what happens next, so that step lives behind one seam and the mechanisms share it.
 *
 * <p>An implementation may also <em>refuse</em>, by throwing, and that is a real part of the contract rather than an
 * escape hatch: a deployment whose membership lives outside the console will have principals a provider will happily
 * authenticate and this console must not admit. Refusing here stops the sign-in with the provider's own error
 * handling rather than admitting the user and relying on every later check to deny them.
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
