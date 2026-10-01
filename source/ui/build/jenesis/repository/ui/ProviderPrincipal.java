package build.jenesis.repository.ui;

import module java.base;

/**
 * Derives a console principal from an OAuth2/OIDC user. The stable id is the provider's user-name attribute (GitHub's
 * numeric {@code id}, OIDC's {@code sub}) qualified by its registration as {@code <provider>/<id>}, since two providers
 * can issue the same raw id. The display name, from the first common attribute present, is never identity, since a
 * user can change it.
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
