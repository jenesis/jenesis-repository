package build.jenesis.repository.blobs;

import module java.base;

import build.jenesis.repository.net.PrivateHosts;
import build.jenesis.repository.settings.PrivateHostGuard;

/**
 * The outbound-target screen a proxy leg owes the URL it is about to fetch, held once for the whole product - the
 * wrapper that had been copied seven times, into <em>two contradictory policies</em>.
 *
 * <p>The dangerous half was never the copied part: the blocked address ranges have always come from
 * {@link PrivateHosts}, and the transport half and the dial from {@link PrivateHostGuard}. What each leg still
 * spelled out for itself was the <b>wrapper</b> around them - the admitted scheme set, the answer for a URL naming no
 * host, and whether a target on the operator-configured upstream's own origin is exempt from the host half. Three legs
 * said it was ({@code NuGetFormat.unsafeUpstreamUrl}, {@code CargoFormat.publicDownloadHost},
 * {@code RpmPackageDigests.sameOrigin}), three said it was not ({@code ComposerFormat}/{@code PyPiFormat}/
 * {@code CocoaPodsFormat.publicHost}), four enumeration walks said it was and checked the scheme too
 * ({@code emittable}), and none of the ten admitted that any of the others existed. &sect;13 calls an undocumented
 * divergence on a shared concern a bug; this class is the one answer, and every leg now reaches it.
 *
 * <h2>The two provenances, and why each gets a different screen</h2>
 * {@link ProxyLeg}'s contract clause 9 already names them, so they are the two methods here rather than two habits:
 * <ul>
 *   <li>{@link #configuredRefusal(URI, boolean)} - an <b>operator-configured</b> root: the proxy upstream itself and
 *       the Go checksum database ({@code jenreg.go.sumdb}). The operator chose the address, so the question is only
 *       what this deployment will put on the wire to reach it. <b>The transport half only</b>, for a reason
 *       that has not changed: an internal, privately-addressed mirror is a legitimate and common deployment,
 *       and this value is also rendered on console GET paths where resolving a host would be an external lookup on a
 *       read path (&sect;10).</li>
 *   <li>{@link #advertisedRefusal(URI, URI, boolean)} - a URL an <b>upstream document</b> chose. The far side picked
 *       it, so it gets the whole screen: the capability floor, the transport half and the host half.</li>
 * </ul>
 *
 * <h2>Which of the two host policies won, and why it is the exempting one</h2>
 * <b>A target on the configured upstream's own origin is admitted; everything cross-origin runs the full shared
 * screen.</b> The argument is not that the exemption is convenient - it is that absolute-refuse is <em>incoherent</em>
 * with the screen the product applies one layer up. The operator-configured upstream is judged on its
 * <b>transport half only</b>: {@code https://mirror.internal/} is deliberately admitted with no dial, because the
 * operator chose it and an internal mirror is a normal deployment. A leg that then absolute-refuses an advertised URL
 * applies a <em>stricter</em> rule to the upstream's second path than the product applies to the upstream itself - it
 * fetches {@code https://mirror.internal/v3/index.json} because the operator configured it, and refuses
 * {@code https://mirror.internal/registrations/hello/1.0.json} on the same request, over the same connection, with the
 * same credential, because the same server named it. That is not a security boundary. A same-origin target reaches no
 * host, no port and no scheme the leg was not already reaching; the marginal SSRF surface of the exemption is exactly
 * zero, while the cost of dropping it is that proxying an internal mirror breaks on every format.
 *
 * <p>The escape hatch once proposed instead - "let {@link ProxyLeg#ALLOW_INTERNAL} serve that case" - would have made
 * it worse rather than uniform. The dial lifts <b>both halves for every target on all fourteen legs</b>, so a
 * deployment whose only unusual property is a private IP would have to accept cleartext everywhere and let a hostile
 * <em>public</em> upstream aim a {@code dl} template at {@code 169.254.169.254}. The exemption is strictly narrower
 * than the dial and strictly safer than turning it on, so it stays and the dial keeps its one meaning.
 *
 * <p><b>And this class does not get to invent that rule.</b> It is already stated, argued, for the leg with the
 * same shape - {@code ImportScreen.refusalReason(authorised, url)}, "exactly where the operator pointed the
 * importer, at the level they authorised" - and the {@code OciFormat} page guard applies it to catalog/tags
 * pagination. Converging on absolute-refuse would have closed one divergence by opening a wider one, against the
 * rule the rest of the product already applies (&sect;13). The agreement is pinned rather than asserted: the proxy
 * contract kit drives this class and that rule over one matrix and fails when they part company.
 *
 * <p>The remaining residue of the old divergence is closed rather than carried: the exemption is on the <b>origin</b>
 * (scheme <em>and</em> authority), not on the bare host name. NuGet and Cargo compared host names only, so a
 * compromised upstream could pivot to any other port on its own box - {@code https://mirror.internal:9200/} under an
 * {@code https://mirror.internal/} upstream was trusted. That was a genuine, if narrow, SSRF and it is gone.
 */
public final class OutboundTargets {

    private OutboundTargets() {
    }

    /**
     * The reason an <b>operator-configured</b> outbound root must be refused under the current dial, or {@code null}
     * when it may be reached: it must name a transport this deployment has
     * ({@link PrivateHostGuard#unfetchableRefusal}, the capability floor no dial lifts) and it must be {@code https} unless {@code allowInternal} says this
     * deployment accepts a plaintext or internal target.
     *
     * <p>The host half is deliberately <em>not</em> applied - see the class note. The two callers are
     * {@code RepositoryDefinition.upstreamRefusal} (the proxy upstream) and
     * {@code GoChecksumDatabase.base} (the Go checksum database); they are the same question about the same kind
     * of value, so they are one rule with one wording rather than two.
     */
    public static String configuredRefusal(URI configured, boolean allowInternal) {
        if (configured == null) {
            return null;
        }
        String unfetchable = PrivateHostGuard.unfetchableRefusal(configured);
        if (unfetchable != null) {
            // Not the dial's to lift: a target naming no transport (or no host) is an IllegalArgumentException out of
            // HttpRequest.newBuilder, not a policy judgement, and no setting makes one fetchable.
            return unfetchable;
        }
        return allowInternal ? null : PrivateHostGuard.cleartextRefusal(configured);
    }

    /**
     * The reason a URL an <b>upstream document advertised</b> must not be fetched, or {@code null} when it may be. The
     * three rules, in the order that makes a refused target pay for as little as possible:
     * <ol>
     *   <li>the capability floor - a scheme this deployment cannot issue a request for, or no host at all, is refused
     *       before anything else and the dial does not lift it;</li>
     *   <li>a target on {@code upstream}'s own origin (scheme <em>and</em> authority) is admitted - it names nothing
     *       the leg was not already reaching;</li>
     *   <li>everything cross-origin runs the shared {@link PrivateHostGuard} screen under
     *       {@link ProxyLeg#ALLOW_INTERNAL}: it must be {@code https} and it must not resolve into this deployment's
     *       own network.</li>
     * </ol>
     *
     * <p><b>An unresolvable host stays admissible</b>, which is every leg's own long-standing host half and the reason
     * the three-argument {@link PrivateHostGuard#refusalReason(URI, boolean, Predicate)} form is used rather than the
     * composed one: a name that resolves nowhere is no internal-service target, and refusing it would take the offline
     * {@code .example} fixtures with it. A name this admits cannot be rebound onto a private address before the fetch
     * connects: the screen remembers the admission, and the product's HTTP client holds the connect to it.
     *
     * @param advertised    the URL the upstream document named
     * @param upstream      the operator-configured upstream this leg is proxying, whose origin is exempt
     * @param allowInternal the deployment's {@link ProxyLeg#ALLOW_INTERNAL} dial
     */
    public static String advertisedRefusal(URI advertised, URI upstream, boolean allowInternal) {
        String unfetchable = PrivateHostGuard.unfetchableRefusal(advertised);
        if (unfetchable != null) {
            return unfetchable;
        }
        if (sameOrigin(advertised, upstream)) {
            return null;
        }
        return PrivateHostGuard.refusalReason(advertised, allowInternal,
                target -> PrivateHosts.resolvesToPrivate(target.getHost()));
    }

    /** {@link #advertisedRefusal} as the predicate most legs want: whether the advertised URL may be fetched. A leg
     *  that has to <em>report</em> the refusal - because dropping it silently would fail open on an integrity surface,
     *  as rpm's primary index and NuGet's registration leaf do - takes the reason instead. */
    public static boolean mayFollow(URI advertised, URI upstream, boolean allowInternal) {
        return advertisedRefusal(advertised, upstream, allowInternal) == null;
    }

    /** Whether two URLs share a scheme <em>and</em> an authority. The scheme is part of the origin on purpose: a
     *  same-host {@code http://} target under an {@code https} upstream is a different origin, and it is precisely the
     *  downgrade the transport half exists to refuse. The authority is compared whole rather than by host, so a
     *  different port on the upstream's own box is cross-origin and is screened. Identical to
     *  {@code ImportScreen.sameOrigin} and {@code OciFormat.sameOrigin}. */
    private static boolean sameOrigin(URI target, URI upstream) {
        return upstream != null
                && Objects.equals(target.getScheme(), upstream.getScheme())
                && Objects.equals(target.getRawAuthority(), upstream.getRawAuthority());
    }
}
