package build.jenesis.repository.ui;

import org.springframework.security.core.Authentication;

import module java.base;

/**
 * Resolves a signed-in principal's display name for the layout.
 *
 * <p>A mechanism module contributes one bean that understands its own principal type, so a console renders a
 * friendly name without importing any mechanism's classes, and falls back to {@link Authentication#getName()} when
 * no resolver answers. That fallback is an identity - a provider-qualified id - which is correct and unfriendly,
 * and it is what a console shows for a mechanism that offers nothing better.
 *
 * <p>It is here rather than in one console because the two rendered different things for the same signed-in user:
 * one greeted them by name and the other by their qualified id, which is an optical difference between two
 * consoles that are meant to be one product. It also decided a scope: a console that renders no name has no reason
 * to ask an OIDC provider for {@code profile} or {@code email}, so the two asked for different scopes, and the one
 * asking for less would have rendered less had it ever tried.
 */
@FunctionalInterface
public interface PrincipalNameResolver {

    Optional<String> displayName(Authentication authentication);

    /** The first name any resolver answers, or the principal's own identity when none does. */
    static String resolve(List<PrincipalNameResolver> resolvers, Authentication authentication) {
        if (authentication == null) {
            return null;
        }
        for (PrincipalNameResolver resolver : resolvers) {
            Optional<String> name = resolver.displayName(authentication);
            if (name.isPresent() && !name.get().isBlank()) {
                return name.get();
            }
        }
        return authentication.getName();
    }
}
