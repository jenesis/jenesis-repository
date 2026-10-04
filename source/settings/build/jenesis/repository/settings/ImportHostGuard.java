package build.jenesis.repository.settings;

import module java.base;

import build.jenesis.repository.net.PrivateHosts;

/**
 * The fail-closed decision behind the {@code block-private-import-hosts} guard, shared by both import legs. A
 * migration URL is fetched server-side with the upstream credentials the operator supplied, so an unrestricted one is
 * an SSRF vector, and a plaintext one hands those credentials to anyone on the path.
 *
 * <p>Most specific wins, and nothing set means block:
 * <ol>
 *   <li>the stored {@code block-private-import-hosts} setting, if present;</li>
 *   <li>else {@code jenrepo.block-private-import-hosts}, if explicitly set;</li>
 *   <li>else {@code true}, whatever the tenancy.</li>
 * </ol>
 * An operator migrating from an internal manager at a private address opts out explicitly. The console leg has no
 * deployment property to consult and passes {@code null}, reaching the same default.
 */
public final class ImportHostGuard {

    private ImportHostGuard() {
    }

    /** A stored {@code block-private-import-hosts} value as a tri-state: {@code null} when unset or blank. */
    public static Boolean stored(String value) {
        return value == null || value.isBlank() ? null : Boolean.parseBoolean(value.trim());
    }

    /**
     * The block decision: {@code stored} when set, else {@code configured} when set, else {@code true}; {@code null}
     * means not set.
     *
     * <p>There is no pin layer because a pinned key cannot be stored: every write path, the settings import included,
     * refuses one with a 409. So {@code stored} is non-null only for an unpinned key, and the console leg need consult
     * no pin of its own.
     */
    public static boolean blockPrivateHosts(Boolean stored, Boolean configured) {
        if (stored != null) {
            return stored;
        }
        return configured != null ? configured : true;
    }

    /**
     * The reason an import URL must be refused, or {@code null} when the migration may proceed: the screen every leg
     * that submits one calls: the {@code /api/repository/import} controller, the console migration panel, the import
     * edge and an export. The transport must be
     * {@code https} ({@link PrivateHostGuard#cleartextRefusal(URI)}), since an import may carry the incumbent's
     * credentials, and the host must not resolve into a {@link PrivateHostGuard#blocked(InetAddress) blocked range}.
     * One dial governs both halves, so an operator cannot opt out of one while believing the other still holds.
     *
     * <p>An unresolvable host is admitted, unlike in {@link PrivateHostGuard#refusalReason(URI, boolean)}: it cannot be
     * reached, and the import source's own probe reports it better. This judges only the submitted URL; the URLs a
     * source hands back are screened at the fetch by {@code ImportScreen}
     * ({@code ImportSourceProvider.open(provider, request, fetcher)}).
     */
    public static String refusalReason(String url, boolean blockPrivateHosts) {
        if (!blockPrivateHosts) {
            return null;
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException _) {
            return "the URL is malformed";
        }
        String cleartext = PrivateHostGuard.cleartextRefusal(uri);
        if (cleartext != null) {
            // Checked first, so a plaintext URL costs no DNS resolution.
            return cleartext;
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return "the URL names no host";
        }
        InetAddress[] addresses;
        try {
            addresses = PrivateHosts.addresses(host);
        } catch (UnknownHostException _) {
            // Unreachable, so not an SSRF vector; the import source's probe answers its 400.
            return null;
        }
        for (InetAddress address : addresses) {
            if (PrivateHostGuard.blocked(address)) {
                return "the host resolves to a private, loopback, link-local or cloud-metadata address";
            }
        }
        return null;
    }
}
