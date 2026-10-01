package build.jenesis.repository.ui;

import module java.base;

import org.springframework.security.core.Authentication;

/**
 * Resolves a signed-in principal's display name for the layout. A mechanism module contributes one bean for its own
 * principal type, so every console greets a user the same way without importing mechanism classes; with no answer, the
 * provider-qualified id from {@link Authentication#getName()} is shown.
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
