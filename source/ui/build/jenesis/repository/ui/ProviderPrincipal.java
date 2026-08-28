package build.jenesis.repository.ui;

import module java.base;

/**
 * Provider-agnostic derivation of a console principal from an OAuth2/OIDC user.
 *
 * <p>The stable id is the provider's user-name attribute - the numeric {@code id} for GitHub, the {@code sub} claim
 * for OIDC - qualified by the registration it came from, so principals live under {@code <provider>/<id>} whichever
 * provider signed them in. Qualifying matters: two providers can hand out the same raw id, and an unqualified one
 * would let a principal from one become a principal from the other.
 *
 * <p>A display name is picked from the first of a few common attributes that is present. It is for member lists and
 * error messages and never for identity, because a provider lets a user change it.
 */
public final class ProviderPrincipal {

    private static final List<String> DISPLAY_ATTRIBUTES = List.of("login", "preferred_username", "email", "name");

    private ProviderPrincipal() {
    }

    public static String qualifiedId(String registrationId, String rawId) {
        return registrationId + "/" + rawId;
    }

    public static String displayName(Map<String, Object> attributes) {
        for (String attribute : DISPLAY_ATTRIBUTES) {
            Object value = attributes.get(attribute);
            if (value != null && !value.toString().isBlank()) {
                return value.toString();
            }
        }
        return "";
    }
}
