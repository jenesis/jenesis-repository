package build.jenesis.repository.net;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/**
 * The web origin of a URL as RFC 6454 defines it: scheme, host and port. A credential, a redirect or an upstream's own
 * link is trusted within one origin and no further, so every "same server?" question is answered here.
 */
public final class Origins {

    private Origins() {
    }

    /** Whether {@code first} and {@code other} share an origin: the same scheme and host, compared without case, and
     *  the same port, an omitted port read as the scheme's default ({@code 80}, {@code 443}). A URL without a host
     *  shares an origin with nothing. */
    public static boolean same(URI first, URI other) {
        if (first == null || other == null || first.getHost() == null || other.getHost() == null
                || first.getScheme() == null || other.getScheme() == null) {
            return false;
        }
        return first.getScheme().equalsIgnoreCase(other.getScheme())
                && first.getHost().equalsIgnoreCase(other.getHost())
                && port(first) == port(other);
    }

    private static int port(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return switch (Objects.requireNonNull(uri.getScheme()).toLowerCase(Locale.ROOT)) {
            case "http" -> 80;
            case "https" -> 443;
            default -> -1;
        };
    }
}
