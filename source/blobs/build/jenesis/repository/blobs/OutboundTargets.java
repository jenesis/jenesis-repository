package build.jenesis.repository.blobs;

import module java.base;

import build.jenesis.repository.net.Origins;
import build.jenesis.repository.net.PrivateHosts;
import build.jenesis.repository.settings.PrivateHostGuard;

/**
 * The outbound-target screen a proxy leg owes the URL it is about to fetch, held once for the whole product.
 *
 * <p>The blocked address ranges come from {@link PrivateHosts}, and the transport half and the dial from
 * {@link PrivateHostGuard}. This class is the <b>wrapper</b> around them - the admitted scheme set, the answer for a
 * URL naming no host, and whether a target on the operator-configured upstream's own origin is exempt from the host
 * half. It is the one answer and every leg reaches it, so the legs cannot diverge on a shared concern.
 *
 * <h2>The two provenances, and why each gets a different screen</h2>
 * {@link ProxyLeg}'s contract clause 9 names them, and they are the two methods here:
 * <ul>
 *   <li>{@link #configuredRefusal(URI, boolean)} - an <b>operator-configured</b> root: the proxy upstream itself and
 *       the Go checksum database ({@code jenrepo.go.sumdb}). The operator chose the address, so the question is only
 *       what this deployment will put on the wire to reach it. <b>The transport half only</b>, because an
 *       internal, privately-addressed mirror is a legitimate and common deployment,
 *       and this value is also rendered on console GET paths where resolving a host would be an external lookup on a
 *       read path.</li>
 *   <li>{@link #advertisedRefusal(URI, URI, boolean)} - a URL an <b>upstream document</b> chose. The far side picked
 *       it, so it gets the whole screen: the capability floor, the transport half and the host half.</li>
 * </ul>
 *
 * <h2>A target on the configured upstream's own origin is exempt from the host half</h2>
 * <b>A target on the configured upstream's own origin is admitted; everything cross-origin runs the full shared
 * screen.</b> The operator-configured upstream is judged on its <b>transport half only</b>:
 * {@code https://mirror.internal/} is admitted with no dial, because the operator chose it and an internal mirror is
 * a normal deployment. Refusing {@code https://mirror.internal/registrations/hello/1.0.json} on the same request, over
 * the same connection, with the same credential, because the same server named it, would be no security boundary: a
 * same-origin target reaches no host, no port and no scheme the leg was not already reaching, so the exemption adds no
 * SSRF surface, and without it proxying an internal mirror would break on every format.
 *
 * <p>{@link ProxyLeg#ALLOW_INTERNAL} is not the answer for that case: the dial lifts <b>both halves for every target
 * on every leg</b>, so a deployment whose only unusual property is a private IP would have to accept cleartext
 * everywhere and let a hostile <em>public</em> upstream aim a {@code dl} template at {@code 169.254.169.254}. The
 * exemption is strictly narrower than the dial, and the dial keeps its one meaning.
 *
 * <p>The same rule holds for the importer - {@code ImportScreen.refusalReason(authorised, url)}, "exactly where the
 * operator pointed the importer, at the level they authorised" - and the {@code OciFormat} page guard applies it to
 * catalog/tags pagination. The proxy contract kit drives this class and that rule over one matrix and fails when they
 * part company.
 *
 * <p>The exemption is on the <b>origin</b> (scheme <em>and</em> authority), not on the bare host name. Comparing host
 * names only would let a compromised upstream pivot to any other port on its own box -
 * {@code https://mirror.internal:9200/} under an {@code https://mirror.internal/} upstream would be trusted, a
 * genuine, if narrow, SSRF.
 */
public final class OutboundTargets {

    private OutboundTargets() {
    }

    /**
     * The reason an <b>operator-configured</b> outbound root must be refused under the current dial, or {@code null}
     * when it may be reached: it must name a transport this deployment has
     * ({@link PrivateHostGuard#unfetchableRefusal}, the capability floor no dial lifts) and it must be {@code https}
     * unless {@code allowInternal} says this deployment accepts a plaintext or internal target.
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
     * <p><b>An unresolvable host stays admissible</b>, which is the reason
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
        if (Origins.same(advertised, upstream)) {
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
}
