package build.jenesis.repository.server;

import module java.base;

import build.jenesis.repository.server.spi.Authorization;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Where a request carries its repository key. The server's own header is {@code Jenesis-Repository-Key}, but most
 * clients can only speak the standard one: the Jenesis build tool sends its {@code jenesis.*.token} verbatim as
 * {@code Authorization}, Maven and Gradle are configured with a bearer token far more easily than with a custom
 * header, and {@code docker login} can send nothing but {@code Authorization: Basic}. So a key is accepted from any
 * of these, in this order:
 *
 * <ol>
 * <li>{@code Jenesis-Repository-Key: jenk_…} - the native header, which always wins when present;</li>
 * <li>{@code Authorization: Bearer jenk_…} or {@code Authorization: jenk_…} - the key as a bearer token, with or
 *     without the scheme, which is what a Jenesis build sends for {@code -Djenesis.module.token=jenk_…};</li>
 * <li>{@code Authorization: Basic base64(<user>:jenk_…)} - the key as the password of a Basic credential, which is
 *     how {@code docker login -u anything -p jenk_…} presents it.</li>
 * </ol>

 * <p>The user name of a Basic credential is not part of a key and never authenticates anything, so
 * {@link #from} ignores it. It is readable separately through {@link #user} because a caller may have given
 * that slot a meaning of its own: Basic is structurally two values, and a client that can be configured with
 * nothing but a user and a password has no other way to send a second one.
 *
 * <p>Only a well-formed key ({@code jenk_} prefix, tenant, secret and a matching checksum) is ever lifted out of
 * {@code Authorization}; anything else in that header - a Basic credential for some other scheme, an unrelated
 * bearer token - is treated as no key at all, so nothing here turns a foreign credential into a principal. The
 * native header is returned as presented even when malformed, so a typo in it is still rejected downstream as a
 * bad key rather than silently becoming an anonymous request.
 */
public final class PresentedKey {

    public static final String HEADER = "Jenesis-Repository-Key";

    private PresentedKey() {
    }

    /** The key a request presents, or {@code null} when it presents none; see the class comment for where it may be. */
    public static String from(HttpServletRequest request) {
        return from(request.getHeader(HEADER), request.getHeader("Authorization"));
    }

    public static String from(String header, String authorization) {
        if (header != null && !header.isBlank()) {
            return header;
        }
        if (authorization == null || authorization.isBlank()) {
            return null;
        }
        String value = authorization.strip();
        if (value.regionMatches(true, 0, "Bearer ", 0, 7)) {
            value = value.substring(7).strip();
        } else if (value.regionMatches(true, 0, "Basic ", 0, 6)) {
            value = password(authorization);
        }
        return value != null && Authorization.wellFormed(value) ? value : null;
    }

    /**
     * The user name of a Basic credential, or {@code null} when the request presents no readable Basic
     * credential. It is deliberately returned as sent and unvalidated: nothing here decides what it means, and a
     * caller that gives the slot a meaning is the one that knows what a valid value looks like.
     *
     * <p>Unlike {@link #from}, this does not require the password to be a well-formed key - a caller reading the
     * user name may be about to reject the request for the credential, and needs to know which name it arrived
     * under to say so.
     */
    public static String user(HttpServletRequest request) {
        return user(request.getHeader("Authorization"));
    }

    public static String user(String authorization) {
        String credential = basic(authorization);
        if (credential == null) {
            return null;
        }
        int colon = credential.indexOf(':');
        return colon < 0 ? null : credential.substring(0, colon);
    }

    private static String basic(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            return null;
        }
        String value = authorization.strip();
        if (!value.regionMatches(true, 0, "Basic ", 0, 6)) {
            return null;
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(value.substring(6).strip());
        } catch (IllegalArgumentException malformed) {
            return null;
        }
        return new String(decoded, StandardCharsets.UTF_8);
    }

    private static String password(String authorization) {
        String credential = basic(authorization);
        if (credential == null) {
            return null;
        }
        int colon = credential.indexOf(':');
        return colon < 0 ? null : credential.substring(colon + 1);
    }
}
