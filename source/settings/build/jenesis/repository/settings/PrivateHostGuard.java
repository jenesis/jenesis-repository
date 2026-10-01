package build.jenesis.repository.settings;

import module java.base;

import build.jenesis.repository.net.PrivateHosts;

/**
 * The screen for an outbound target the deployment fetches or sends to server-side, often with a credential attached:
 * an unscreened one turns the deployment into a request against cloud metadata or an internal service (an SSRF), and a
 * cleartext one hands what it sends to any observer. It lives in this {@code java.base}-only module so every leg that
 * screens an outbound target shares one rule and one wording. The pieces are named so a leg can take what it needs:
 * <ul>
 *   <li>{@link #refusalReason(URI, boolean)} - the composed screen, transport then host.</li>
 *   <li>{@link #refusalReason(URI, boolean, Predicate)} - the same with the host half injected, for a leg that
 *       substitutes the resolver or admits an unresolvable host; the format legs reach it through
 *       {@code build.jenesis.repository.blobs.OutboundTargets}.</li>
 *   <li>{@link #cleartextRefusal(URI)} - the transport half alone, with no I/O, for a leg with a different host policy
 *       ({@link ImportHostGuard}) and for a read surface that must not fetch.</li>
 *   <li>{@link #unfetchableRefusal(URI)} - the capability floor under the screen, which {@code allowInternal} does not
 *       lift.</li>
 * </ul>
 */
public final class PrivateHostGuard {

    private PrivateHostGuard() {
    }

    /**
     * The reason an outbound target must be refused, or {@code null} when it is admissible: it must be {@code https},
     * and its host must not resolve to an internal or non-public address ({@link #internal(URI)}).
     *
     * <p>{@code allowInternal} is the one opt-out for both halves, deployment-global so that no per-tenant dial can put
     * the deployment's traffic on the wire in cleartext. The scheme is checked first, so a refused plaintext URL costs no
     * DNS resolution; a caller re-runs this just before it connects, which closes the DNS-rebinding window.
     */
    public static String refusalReason(URI url, boolean allowInternal) {
        return refusalReason(url, allowInternal, PrivateHostGuard::internal);
    }

    /**
     * The same screen with the host half supplied, for a leg that substitutes the resolver (a test reproducing a DNS
     * rebind) or has its own host policy. {@link #refusalReason(URI, boolean)} binds it to {@link #internal(URI)}.
     */
    public static String refusalReason(URI url, boolean allowInternal, Predicate<URI> internal) {
        if (allowInternal) {
            return null;
        }
        String cleartext = cleartextRefusal(url);
        if (cleartext != null) {
            return cleartext;
        }
        if (internal.test(url)) {
            return "target resolves to an internal or non-public address";
        }
        return null;
    }

    /**
     * The transport half alone: a refusal unless {@code url} is {@code https}. The scheme is stated by the URL, so this
     * resolves and fetches nothing. Cleartext is a hazard about who else sees the request rather than where it goes,
     * so it applies to a public host too: a credential sent over it reaches any observer on the path.
     */
    public static String cleartextRefusal(URI url) {
        String scheme = url.getScheme();
        return scheme == null || !scheme.equalsIgnoreCase("https")
                ? "target is not https (scheme '" + scheme + "')"
                : null;
    }

    /**
     * The reason no request can be issued to {@code url} at all, or {@code null} for an {@code http} or {@code https}
     * URL naming a host. A capability statement, not a policy: {@code HttpRequest.newBuilder} throws for anything else
     * ({@code gopher:}, {@code file:}, {@code https:///path}), which would surface as a {@code 500} where a proxy read
     * should answer its local {@code 404}.
     *
     * <p>Not part of the dialled screen and not lifted by {@code allowInternal}, since no setting makes a transport
     * exist. A leg runs this I/O-free floor first, then the dialled screen.
     */
    public static String unfetchableRefusal(URI url) {
        String scheme = url.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))) {
            return "target is not an http(s) URL (scheme '" + scheme + "'), so no request can be issued to it";
        }
        String host = url.getHost();
        return host == null || host.isBlank()
                ? "target names no host ('" + url + "'), so no request can be issued to it"
                : null;
    }

    /**
     * Whether the host of {@code url} resolves now to an address a credentialed server-side request must not reach (see
     * {@link PrivateHosts}, including the {@code 169.254.169.254} metadata address), or cannot be resolved at all. A
     * host this admits is held to its public addresses when the product's HTTP client connects
     * ({@link PrivateHosts#connectable}), closing the DNS-rebinding window.
     */
    public static boolean internal(URI url) {
        String host = url.getHost();
        if (host == null) {
            return true;
        }
        try {
            InetAddress[] addresses = PrivateHosts.addresses(host);
            if (addresses.length == 0) {
                return true;
            }
            for (InetAddress address : addresses) {
                if (blocked(address)) {
                    return true;
                }
            }
            return false;
        } catch (UnknownHostException _) {
            return true;   // cannot confirm the host is external
        }
    }

    /**
     * Whether an address is in a range a credentialed server-side request must not reach: {@link PrivateHosts}' table,
     * shared by every leg even where their policy on an unresolvable host differs. Package-private for
     * {@link ImportHostGuard}.
     */
    static boolean blocked(InetAddress address) {
        return PrivateHosts.isPrivate(address);
    }
}
