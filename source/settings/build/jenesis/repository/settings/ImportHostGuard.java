package build.jenesis.repository.settings;

import module java.base;

/**
 * The single, fail-closed decision behind the {@code block-private-import-hosts} guard, shared by both import
 * legs so they cannot drift to divergent defaults. A repository-migration URL is fetched server-side <em>with the
 * upstream credentials the operator typed into the migration form attached</em>, so an unrestricted one turns an
 * import into a server-side request against cloud metadata or an internal service (an SSRF) - and, over a plaintext
 * transport, hands those credentials to anyone on the path. {@link #refusalReason(String, boolean)} resolves both.
 *
 * <p>The layering is most-specific-wins and, crucially, <em>fail-closed</em> when nothing is set:
 * <ol>
 *   <li>the stored {@code block-private-import-hosts} setting, if present, wins;</li>
 *   <li>else the deployment's {@code jenreg.block-private-import-hosts} env-field, if explicitly set;</li>
 *   <li>else {@code true} (block) - for <em>every</em> edition.</li>
 * </ol>
 * The old tenancy-derived "off for the single-tenant {@code fixed} edition" convenience is deliberately gone: a
 * forgotten or misunderstood config must never be the insecure one. A {@code fixed}-edition operator migrating from an
 * internal Nexus/Artifactory at a private address opts out <em>explicitly</em> (the stored setting or the env-field set
 * to {@code false}).
 *
 * <p>The console leg has no {@code RepositoryProperties} env-field to consult, so it passes {@code null} for the
 * configured value; its stored-setting-else-block path still yields the same secure default, which is exactly why both
 * legs route through this one method rather than each re-deriving the decision.
 */
public final class ImportHostGuard {

    private ImportHostGuard() {
    }

    /** Parse a stored {@code block-private-import-hosts} value into a tri-state Boolean: {@code null} when the setting
     *  is unset or blank (so the next layer applies), otherwise its boolean. */
    public static Boolean stored(String value) {
        return value == null || value.isBlank() ? null : Boolean.parseBoolean(value.trim());
    }

    /**
     * The block decision: the stored setting when set, else the deployment env-field ({@code configured}) when set,
     * else fail-closed to {@code true} (block) for every edition. Both {@code stored} and {@code configured} are
     * tri-state - {@code null} means "not set, fall through".
     *
     * <p><b>Why there is no pin layer here, when every other read resolves pin over stored.</b> This looked like the
     * one dial in the product that inverts that rule, and it was carried as an argued exemption on the grounds that
     * retrofitting it changes what an SSRF screen enforces. It is not an inversion, and the reason is upstream: a
     * pinned key <em>cannot be stored</em>. Every write path refuses one - {@code PUT} and {@code DELETE
     * /api/settings/{key}} answer 409 naming what pins it, and the bundle restore {@code POST /api/settings/import}
     * now refuses the same way rather than persisting an inert value. So {@code stored} is non-null only for a key
     * nothing pins, and "stored wins" and "pin wins" cannot disagree: there is one rule, and this is it applied to
     * the only states that are reachable.
     *
     * <p>That is what makes it safe for the console's import leg to pass {@code null} for the deployment env-field
     * and consult no pin of its own. It could not see one without requiring the server kernel, which would drag the
     * kernel onto a screen-serving node (&sect;2); it does not need to. {@code SettingsImportGuardE2ETest} is the
     * leg that holds the upstream half in place, because this argument is only true while every write path refuses.
     */
    public static boolean blockPrivateHosts(Boolean stored, Boolean configured) {
        if (stored != null) {
            return stored;
        }
        return configured != null ? configured : true;
    }

    /**
     * The reason an import URL must be refused under the current dial, or {@code null} when the migration may proceed.
     * This is the ONE screen both import legs (the {@code /<repo>/admin/import} API controller and the
     * console migration panel) call, and it is <em>both</em> halves of "may this deployment fetch from this URL":
     *
     * <ol>
     *   <li><b>The transport must be {@code https}</b> - the shared {@link PrivateHostGuard#cleartextRefusal(URI)}
     *       rule, in the one home that also states it for the webhook, forwarding and emulator legs. An
     *       {@code ImportRequest} carries the incumbent manager's username and
     *       password when the operator supplies them, so a plaintext migration puts an <em>upstream credential</em>,
     *       not merely metadata, in front of every observer on the path - and lets an active intermediary substitute
     *       the artifacts the walk then writes into the hosted store. It bites hardest where the host half is silent,
     *       on a perfectly public host, which is why it was invisible until.</li>
     *   <li><b>The host must not resolve internally</b> - private/loopback/link-local/site-local/multicast/CGNAT/
     *       unique-local, through {@link PrivateHostGuard#blocked(InetAddress)} so the ranges are stated once.</li>
     * </ol>
     *
     * <p><b>One dial governs both halves.</b> {@code blockPrivateHosts} - resolved by
     * {@link #blockPrivateHosts(Boolean, Boolean)} from the stored setting over the deployment env-field, fail-closed -
     * is the whole opt-out, exactly as {@code forwarding-allow-internal} and {@code webhook-allow-internal} are for
     * their legs. No second setting was added for the transport half: an operator able to opt out of one and not the
     * other is an operator who can end up sending a credential in the clear while believing the guard is on.
     *
     * <p><b>An unresolvable host stays admissible</b>, which is where this screen deliberately parts from
     * {@link PrivateHostGuard#refusalReason(URI, boolean)}: it cannot be reached, so it is not an SSRF vector, and the
     * import source's own probe gives the operator a better message ("host that cannot answer") than a guard masking
     * it would. That divergence is about the <em>host</em> half only; the transport rule is shared verbatim.
     *
     * <p><b>Parity with the free core.</b> The free core's own import edge screens the same two halves under the same
     * dial ({@code ImportScreen.refusalReason}), so the divergence recorded here - this edition refusing
     * cleartext while the free {@code ImportEdgeController.isPublicImportUrl} still admitted it - is closed at the
     * rule level: blocked ranges, unresolvable-host allowance, fail-closed default and transport now agree in both
     * editions. The wording of each refusal is each edition's own; the decision is not.
     *
     * <p><b>What this guard does <em>not</em> cover, in either edition.</b> It judges the URL the operator submitted.
     * The URLs a migration <em>source</em> hands back - a Nexus listing's per-asset {@code downloadUrl}, an index's
     * enumerated coordinate URL - are a remote party's choice, and they are screened at the fetch instead, by the free
     * core's {@code ImportScreen} riding on the {@code ProxyFormat.Fetcher} a connector is handed. This
     * edition's {@code ImportController} picks that up when the free-core pin next moves: it builds its source with
     * {@code provider.create(request, fetcher)}, and the screened form is {@code ImportSourceProvider.open(provider,
     * request, fetcher)}.
     */
    public static String refusalReason(String url, boolean blockPrivateHosts) {
        if (!blockPrivateHosts) {
            return null;                            // the single explicit opt-out, and it bypasses both halves
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException _) {
            return "the URL is malformed";
        }
        String cleartext = PrivateHostGuard.cleartextRefusal(uri);
        if (cleartext != null) {
            // Checked first, so a refused plaintext URL never pays a DNS resolution - and so the operator is told
            // about the transport rather than about a host that was never the problem.
            return cleartext;
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return "the URL names no host";
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException _) {
            // A host that does not resolve cannot be reached, so it is not an SSRF vector; let the import source's own
            // probe reject it (the documented "host that cannot answer" 400) rather than masking that here.
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
