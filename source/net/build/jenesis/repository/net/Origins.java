package build.jenesis.repository.net;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/**
 * The web origin of a URL, as RFC 6454 defines it: its scheme, its host and its port. A credential, a redirect or
 * an upstream's own link is trusted within one origin and no further, so every place that asks "is this the same
 * server" asks here and gets the one answer.
 */
public final class Origins {

    private Origins() {
    }

    /**
     * Whether {@code first} and {@code other} share an origin: the same scheme and host, compared without case, and
     * the same port, a port left out read as the scheme's default ({@code 80} for {@code http}, {@code 443} for
     * {@code https}). A URL without a host has no origin and shares one with nothing.
     */
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
