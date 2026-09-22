package build.jenesis.repository.settings;

import module java.base;

import build.jenesis.repository.net.PrivateHosts;

/**
 * The shared "is this host an internal / non-public SSRF target" predicate, in a {@code java.base}-only home both the
 * import/proxy leg and the publish-forwarding leg depend on. A migration URL or a forward target is fetched
 * server-side with a credential attached, so an unscreened one turns the deployment into a server-side request against
 * cloud metadata or an internal service (an SSRF); this classifies whether a host must be blocked. It lives here rather
 * than in any one feature module so the legs that reuse it cannot drift to divergent block ranges - and, in particular,
 * so {@code forwarding} reaches it without a {@code requires} edge onto the {@code webhook} module.
 *
 * <p>{@link #refusalReason(URI, boolean)} is the whole outbound-target screen rather than only the host half: an
 * operator-supplied callback URL must be {@code https} <em>and</em> must not resolve internally. The two halves belong
 * together because they answer one question - "may this deployment send to this URL" - and splitting them is how the
 * webhook leg came to run the host half alone while its forwarding peer ran both (PRINCIPLES &sect;13: a guard one
 * feature applies to a shared concern is applied by every peer with that concern).
 *
 * <h2>Four shapes of the same screen, one statement of each rule</h2>
 * The legs that screen an outbound target do not all want the whole composed screen, so the pieces are named rather
 * than restated:
 * <ul>
 *   <li>{@link #refusalReason(URI, boolean)} - the composed screen, for a leg whose choke point may resolve DNS
 *       (webhook delivery, forward replay).</li>
 *   <li>{@link #refusalReason(URI, boolean, Predicate)} - the same screen with the host half injected, for a leg that
 *       has to substitute the resolver (the emulator's send-time re-screen, whose DNS-rebinding case cannot be
 *       reproduced against the real one; and every format proxy leg, whose host half deliberately admits an
 *       unresolvable host -). The format legs reach it through one wrapper,
 *       {@code build.jenesis.repository.blobs.OutboundTargets}, rather than ten copies of one.</li>
 *   <li>{@link #cleartextRefusal(URI)} - the transport half alone. It performs <b>no I/O</b>, which is what lets a
 *       leg with a different host policy reuse the rule verbatim ({@link ImportHostGuard}, whose unresolvable host is
 *       admissible where this class's is not) and lets a <em>read</em> surface state a standing refusal without an
 *       external fetch on a GET (PRINCIPLES &sect;10).</li>
 *   <li>{@link #unfetchableRefusal(URI)} - the capability floor <em>underneath</em> the screen, and the one piece the
 *       {@code allowInternal} dial does not lift.</li>
 * </ul>
 * Both the rule and its wording live in one place, so a leg cannot end up refusing cleartext in different words -
 * or, as found in two legs at once, not refusing it at all.
 */
public final class PrivateHostGuard {

    private PrivateHostGuard() {
    }

    /**
     * The reason an outbound target URI must be refused with the current settings, or {@code null} when it is
     * admissible: it must be {@code https} - a plaintext {@code http} callback puts everything the deployment sends,
     * and any credential the receiver keys off the URL, in front of any network observer, and lets an active
     * intermediary forge or rewrite what the receiver acts on - and its host must not resolve to an internal /
     * non-public address ({@link #internal(URI)}).
     *
     * <p>{@code allowInternal} is the single explicit opt-out for both halves, deployment-global by design: an
     * operator running a trusted internal receiver on a plain HTTP port says so once, and no per-tenant dial can put
     * that deployment's traffic on the wire in cleartext. It is the same posture the free core's
     * {@code S3ArtifactStoreProvider} takes over {@code JENREG_S3_ENDPOINT} (https unless
     * {@code JENREG_S3_ALLOW_INSECURE_ENDPOINT} says otherwise): a capability that cannot be honoured safely is
     * refused visibly rather than degraded silently (&sect;9).
     *
     * <p>The scheme is checked <em>first</em>, so a refused plaintext URL never pays a DNS resolution; a caller
     * re-runs this immediately before it connects, which is what closes the DNS-rebinding window on the host half.
     */
    public static String refusalReason(URI url, boolean allowInternal) {
        return refusalReason(url, allowInternal, PrivateHostGuard::internal);
    }

    /**
     * The same screen with the host half supplied, for a leg that must be able to substitute the resolver. The
     * emulator is the case: its send-time re-screen exists for a DNS rebind, which cannot be reproduced against the
     * real resolver, so a test drives the predicate instead. Production passes {@link #internal(URI)} - the two-argument
     * {@link #refusalReason(URI, boolean)} is exactly that binding, so a caller taking the seam still runs one screen
     * in one order under one dial rather than a private variant of it.
     */
    public static String refusalReason(URI url, boolean allowInternal, Predicate<URI> internal) {
        if (allowInternal) {
            return null;                                        // the trusted-internal opt-out bypasses both halves
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
     * The transport half alone: the reason {@code url} would put everything sent to it in front of any network
     * observer, or {@code null} when it is {@code https}. Nothing is resolved and nothing is fetched - the scheme is
     * <em>stated by the URL</em>, so this side can judge it from the configuration alone. That is the property the two
     * hazards separated turn on (a transport is judgeable and therefore refusable; "does this receiver verify
     * signatures" is not, and was only reported), and it is what lets this rule be applied by a leg whose host policy
     * differs and by a read surface that must not perform I/O.
     *
     * <p>Note the asymmetry with the host half. An internal-resolving host is a hazard about <em>where</em> the request
     * goes; cleartext is a hazard about <em>who else sees it</em> - so it bites hardest exactly where the host half is
     * silent, on a perfectly public host. A leg that carries a credential (the emulator's deployment key, an import's
     * upstream password, a webhook's signature-bearing body) hands that credential to any observer on the path, and an
     * active intermediary can rewrite what the far side then acts on.
     */
    public static String cleartextRefusal(URI url) {
        String scheme = url.getScheme();
        return scheme == null || !scheme.equalsIgnoreCase("https")
                ? "target is not https (scheme '" + scheme + "')"
                : null;
    }

    /**
     * The reason {@code url} is not a target this deployment can issue a request to <em>at all</em>, or {@code null}
     * when it is an {@code http}/{@code https} URL naming a host. This is a capability statement, not a policy
     * judgement: every outbound leg fetches through {@code java.net.http}, and {@code HttpRequest.newBuilder} throws
     * {@link IllegalArgumentException} for anything else - so a {@code gopher:}/{@code file:}/{@code jar:} URL an
     * upstream document advertised does not become a refusal, it becomes an unmapped {@code 500} out of a proxy read
     * where {@code ProxyLeg} clause 2 says the truthful answer is the local {@code 404}.
     *
     * <p><b>The host is part of the floor, not part of the host half</b>. {@code HttpRequest.newBuilder}
     * rejects {@code https:///path} for exactly the same reason it rejects {@code gopher://cdn.example/} - it cannot
     * address a connection - yet pinned only the scheme, so five legs whose host half answered "not private"
     * for a {@code null} host emitted such a URL and threw out of the fetch. Whether a URL <em>names</em> a host is a
     * capability fact this class can state once; whether the host it names is internal is the policy question the
     * legs differ on, and only that second one is a screen.
     *
     * <p><b>Deliberately not part of {@link #refusalReason(URI, boolean, Predicate)}, and deliberately not lifted by
     * {@code allowInternal}.</b> The dial says "this deployment trusts an internal or plaintext target"; it cannot say
     * "this deployment can speak gopher", because no setting makes a transport exist. Folding this into the dialled
     * screen would mean an operator who turns the dial on to reach an internal http mirror also re-opens the
     * {@code 500}. A leg therefore runs this floor first - it is I/O-free and cheaper than either half - and only then
     * the dialled screen.
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
     * Whether the host of {@code url} resolves (right now) to an internal / non-public target a credentialed
     * server-side request must not reach by default - a loopback, any-local, link-local (which covers the
     * {@code 169.254.169.254} cloud-metadata address), private/site-local, IPv6 unique-local, carrier-grade-NAT
     * ({@code 100.64.0.0/10}) or multicast address, or a host that cannot be resolved at all (so an unverifiable
     * target is refused rather than risked). Resolution is by name, so a DNS answer of an internal address is caught
     * too; a caller re-runs this immediately before it connects to close the DNS-rebinding / TOCTOU window.
     */
    public static boolean internal(URI url) {
        String host = url.getHost();
        if (host == null) {
            return true;
        }
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
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
            return true;   // cannot confirm the host is external - refuse rather than risk an internal request
        }
    }

    /**
     * Whether an address is in a range a credentialed server-side request must not reach.
     *
     * <p>The ranges themselves are {@link PrivateHosts}', not this class's. They used to be copied here, because
     * {@code PrivateHosts} sat in the format SPI and this module neither requires that SPI nor should have to for
     * a range table - so the two tables were maintained in parallel. That is the duplication whose previous
     * instance was a real SSRF gap: the inline copy that predated {@code PrivateHosts} omitted carrier-grade NAT
     * and multicast, and consolidating is what closed it. A second copy re-opens it the same way, because a range
     * added to one table and not the other is invisible until something reaches the wrong half.
     *
     * <p><b>What is shared is the table; what is not is the policy.</b> This guard and the format legs disagree
     * about an unresolvable host and a hostless URI - they admit, this refuses - and that difference is deliberate
     * rather than drift. It stays here, in the callers above. Only "is this address in a range nobody should be
     * steered into" moved, which is the half that must never disagree.
     *
     * <p>Package-private rather than private so {@link ImportHostGuard}, whose host policy differs again but whose
     * blocked ranges must not, classifies through this one call.
     */
    static boolean blocked(InetAddress address) {
        return PrivateHosts.isPrivate(address);
    }
}
