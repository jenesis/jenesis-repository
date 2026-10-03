package build.jenesis.repository.blobs;

import module java.base;

import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The pull-through leg of a proxying format, with the front-door path screen already applied. Every proxying format
 * implements this rather than {@link ProxyFormat} directly, so the screen a proxy leg owes its request
 * path is a property of <em>the seam</em> instead of a line each leg must remember to write.
 *
 * <p>{@link #proxy} is {@code default} and {@code final} in spirit - it screens, then hands control to
 * {@link #pullThrough}, which is the only method a format writes, so a new format cannot omit the screen.
 *
 * <p>{@link Keys#unsafePath} is a pure delegation to {@code ArtifactStore.traversalFree}, which refuses a backslash
 * and a C0 control character alongside the {@code .}/{@code ..} segments. So this seam and the layouts' own request
 * screens refuse exactly the same shapes, through one predicate.
 *
 * <h2>Contract</h2>
 * This is a role sub-interface of {@link ProxyFormat}, which is itself a role of {@link RepositoryFormat}: every clause
 * of both contracts binds unchanged and is documented on those interfaces. Only what this seam <em>adds</em> is
 * stated here.
 * <ol>
 * <li><b>Thread-safety.</b> Unchanged: a format is a stateless singleton the server calls concurrently. This interface
 *     adds no state of its own - {@link #proxy} is a pure screen over its arguments and holds nothing between
 *     calls.</li>
 * <li><b>Absence sentinel.</b> A screened-out request path is answered {@code false} - "this leg served nothing, let
 *     the local {@code 404} stand", {@link ProxyFormat}'s own sentinel. It is deliberately not a thrown refusal and
 *     deliberately not a {@code 400}: a proxy leg runs <em>after</em> a local miss, so the truthful answer is that the
 *     path names nothing here, and a throw out of this seam would surface as an unmapped {@code 500} where a
 *     {@code 404} is the truth ({@link RepositoryFormat}'s traversal clause).</li>
 * <li><b>An enumeration is not an artifact, and its absence is an answer.</b> {@link ProxyFormat} clause 2
 *     makes one {@code false} carry an unproxyable path, an upstream miss, a transport failure <em>and</em> a refused
 *     body, so a leg that could not reach its upstream answers exactly as a leg whose upstream said "no such thing".
 *     On a version-pinned or content-addressed document that is right - the {@code 404} says "not cached here", the
 *     client re-pulls, and nothing about a build's resolution is decided by the absence. On an <em>enumeration</em> -
 *     a packument, a PEP 503 simple index, a {@code repodata} / {@code Packages} index, a versions endpoint - it is
 *     not: there the {@code 404} <b>is</b> the answer, an empty enumeration a build resolves against, so a network
 *     blip reaches the client as the fact that a package has no versions, indistinguishable from the truth.
 *     <p><b>So every relay names which of the two it is</b>, as
 *     {@link ProxyRelay.Document#ENUMERATION} or {@link ProxyRelay.Document#PINNED}, and the <em>rule</em> - upstream
 *     {@code 404}/{@code 410} is a real miss and the local {@code 404} stands; a transport failure or any other
 *     non-{@code 200} answers {@code 502} and is logged - lives once, in
 *     {@link ProxyRelay#unanswered}. The classification stays with the format: which of its paths is a version list
 *     is protocol knowledge only the format has, so unlike {@link #proxy}'s request-path screen this seam cannot
 *     discharge the obligation structurally. It makes the classification <em>unskippable</em> instead - no relay
 *     helper omits the {@link ProxyRelay.Document} - and every leg names its enumeration and pinned paths.</li>
 * <li><b>A digest we could not read is not a digest the upstream does not publish.</b> The same split, one
 *     layer down, on the <em>integrity</em> surface - where it is not a wrong answer but a silent fail-open.
 *     {@link ProxyFormat} clause 5 lets an ecosystem that advertises no digest proxy unverified rather than fabricate
 *     a check. That covers an upstream that <em>published nothing</em>, never a leg whose digest comes out of a
 *     <em>second</em> document it <em>could not read</em> - a packument fetch that timed out, a compact index behind a
 *     shared-egress {@code 429}, a registration leaf the outbound screen refuses. Treating those as "declares no
 *     checksum" would let anyone who can drop one sidecar fetch turn integrity off for that pull.
 *     <p><b>So every fill names which of the three it is</b> -
 *     {@link ProxyRelay.Declared#of a declared digest}, {@link ProxyRelay.Declared#NONE} ("the document answered and
 *     declares none", clause 5's fall-back, deliberately unchanged), or
 *     {@link ProxyRelay.Declared#unreadable unreadable} - and the <em>rule</em> lives once, in
 *     {@link ProxyRelay#fill}: an unreadable declaration <b>declines the fill</b> through
 *     {@link ProxyRelay#unverifiable} (a {@code WARN}, nothing cached, nothing served, the local {@code 404} standing
 *     so a later pull re-hits the upstream), a declared one is verified pointer-last, and only {@code NONE} caches
 *     unverified. As with the clause above, the <em>classification</em> stays with the format: which fetch declares a
 *     digest, and which of its outcomes is the upstream answering, is protocol knowledge only the format has, and
 *     every leg names its declaring document. Three legs (composer, cocoapods, pypi) have no split to make because
 *     their declaring document is the same one that resolves the download URL, so an unreadable one already declines
 *     the whole fill; one (huggingface) reads its digest off the artifact's own response headers, so there is no second
 *     fetch to drop; and one (debian) can reach no declaring document from a {@code pool/} request at all. Each of
 *     those is a classification stated in its row, not an exemption.</li>
 * <li><b>Selection failure.</b> None: this seam selects nothing. A format that does not claim the path
 *     ({@link RepositoryFormat#handles} answers {@code false}) is declined here rather than being allowed to compose a
 *     key from a prefix it never verified - the dispatcher already guarantees the claim, and the screen states it
 *     rather than trusting it.</li>
 * <li><b>Error visibility.</b> Nothing is swallowed. The screen decides before any store or network call and
 *     returns a value; it never catches an exception out of {@link #pullThrough}, so a transport or store failure
 *     surfaces unchanged.</li>
 * <li><b>Read purity.</b> The screen performs no store read, no fetch and no write, so a refusal costs a
 *     string scan and reaches neither the upstream nor the store. This is the property that makes the screen safe to
 *     run unconditionally on every proxied request.</li>
 * <li><b>Ordering / concurrency.</b> The screen runs exactly once per request, before {@link #pullThrough}, and its
 *     verdict is a pure function of the request path - so two concurrent requests for the same path are screened
 *     identically and discovery order changes nothing.</li>
 * <li><b>Bounded work / cancellation.</b> The screen is one linear pass over the request path, whose length the store's
 *     {@link ArtifactStore#MAX_KEY_BYTES} ceiling already bounds at the write seam and the servlet container bounds at
 *     the request line. There is no bound to breach and therefore no truncation to report.</li>
 * <li><b>Durability / delivery.</b> Unchanged: {@link #pullThrough} owns the cache fill and its pointer-last commit
 *     point. A screened-out request commits nothing at all, so it opens no crash window.</li>
 * <li><b>Outbound targets.</b> The screen above judges the <em>request</em> path. It says nothing about the
 *     <em>outbound</em> URL a leg then fetches, and that is a second, separate obligation this seam does not
 *     structurally discharge - so it is stated here and verified per leg by
 *     the proxy contract kit and by the principle checkup. A leg
 *     fetches URLs of exactly two provenances. The first is <b>composed</b> from the operator-configured
 *     {@code upstream} and the (already screened) request path; that target is the operator's own choice and is
 *     judged once, where the operator wrote it ({@code RepositoryDefinition.parse}), not per fetch.
 *     The second is <b>advertised</b> by an upstream document - a Composer {@code dist.url}, a PyPI simple
 *     index {@code href}, a NuGet {@code RegistrationsBaseUrl} or {@code catalogEntry}, a Cargo {@code config.json}
 *     {@code dl} template, an rpm {@code repomd} {@code location href}, a CocoaPods {@code source} - and is chosen by
 *     the far side, not by the operator. <b>Every advertised URL must be screened before it is fetched</b>, because
 *     following one unscreened turns a proxy read into a server-side request of the upstream's choosing, with this
 *     deployment's credentials and inside its network. A redirect <em>hop</em> is not a leg's to screen at all: the
 *     transport owns it ({@code FetcherProvider}'s redirect-policy and SSRF clauses). A leg that composes every target
 *     and follows nothing advertised satisfies this clause by construction and says so.
 *     <p><b>The screen is two halves and a floor</b>. The
 *     <em>transport</em> half ({@code https}) and the <em>host</em> half (not internal) are run in one call under one
 *     dial - {@link #ALLOW_INTERNAL}, read through {@link #allowInternalTargets(FormatExchange)}. Underneath both sits
 *     the capability floor the dial does not lift: an advertised URL naming no http(s) transport, or no host at all, is
 *     not a policy question but an {@code HttpRequest.newBuilder} {@code IllegalArgumentException}, i.e. a {@code 500}
 *     where clause 2 says {@code 404}. The blocked host ranges themselves stay the shared free
 *     {@code build.jenesis.repository.net.PrivateHosts} classifier - never a private copy.
 *     <p><b>The whole screen is {@link OutboundTargets}, and there is exactly one of it</b>: a target at the
 *     scheme-and-authority the operator configured is admitted, and everything cross-origin runs the full screen.
 *     The configured upstream itself is judged on its transport half alone, so its own origin is not held to a
 *     stricter rule on a second path ({@link OutboundTargets} states the reasoning, and
 *     {@code ImportScreen.refusalReason} applies the same rule). Both directions are ratcheted by the proxy contract
 *     kit: a leg that refuses its own upstream's origin fails, and so does one that follows a private target off
 *     it.</li>
 * <li><b>Rewrite fidelity.</b> A leg that serves an upstream <em>document</em> rather than an artifact either rewrites
 *     the download URLs inside it to point back through this repository (npm's packument {@code dist.tarball}) or
 *     leaves the document alone because it carries no absolute URLs (Conan's index reads, HuggingFace's tree API).
 *     Both are admissible; silently serving a document whose URLs point at the upstream is not, because the client
 *     then bypasses this repository's cache, gate and audit for the very artifact it was asked about. What must hold
 *     after a rewrite is <b>fidelity</b>: every integrity field the document publishes ({@code dist.integrity},
 *     {@code shasum}, a {@code packageHash}) must still describe the bytes the rewritten URL will serve, and the
 *     rewritten URL must resolve to the same artifact the original named. A leg states which of the two it does and
 *     why; the checkup verifies it per leg, because fidelity is defined by the ecosystem's own protocol document and
 *     no kit in this repository holds that reference.</li>
 * </ol>
 */
public interface ProxyLeg extends RepositoryFormat, ProxyFormat {

    /**
     * The deployment-global dial that lets a proxy leg follow an <em>internal or plaintext</em> advertised target, and
     * lets an operator configure an internal or plaintext upstream at all (screens the upstream under this same
     * key). Default off, like every peer opt-out in the product.
     *
     * <p><b>Why one deployment-global dial and not one per format.</b> The question it answers is about this
     * deployment's network position and the transport it will put a credential on - not about npm versus rpm. An
     * operator running a plaintext internal mirror has that mirror for every format they proxy from it, and a
     * per-format dial would be a chance to believe the guard is on while one format's traffic is in the clear. It is
     * the same reason {@code PrivateHostGuard} refuses to split its two halves into two settings, the same shape as
     * {@code forwarding-allow-internal}, {@code emulator-allow-internal} and {@code block-private-import-hosts}, and
     * the reason it is not tenant-overridable: one tenant must not be able to put the deployment's per-host upstream
     * credential on the wire in cleartext for everyone.
     */
    String ALLOW_INTERNAL = "proxy-allow-internal";

    /**
     * Whether {@link #ALLOW_INTERNAL} is set for this exchange - the one read of the dial, so the legs cannot
     * drift on the key's spelling or its default. Absent configuration reads as {@code false}: {@code FormatExchange}
     * answers {@code null} for any exchange that carries no configuration (a headless embed, an internal push
     * exchange, a test double), and the secure answer for "no configuration at all" is the guard on.
     */
    static boolean allowInternalTargets(FormatExchange exchange) {
        return exchange != null && Boolean.parseBoolean(exchange.setting(ALLOW_INTERNAL));
    }

    /**
     * Screen the client-supplied request path, then hand a surviving request to {@link #pullThrough}. Implemented here
     * and not overridden: a format states its upstream protocol in {@link #pullThrough} and inherits the screen.
     *
     * <p>A request is declined ({@code false}, so the local {@code 404} stands) when this format does not claim the
     * path at all, or when {@link Keys#unsafePath} judges the path hostile - a {@code .}/{@code ..} segment, a
     * {@code \}, or a control character.
     */
    @Override
    default boolean proxy(FormatExchange exchange, ArtifactStore store, URI upstream, ProxyFormat.Fetcher fetcher)
            throws IOException {
        String path = exchange.path();
        if (path == null || !handles(path) || Keys.unsafePath(path)) {
            return false;
        }
        return pullThrough(exchange, store, upstream, fetcher);
    }

    /** A leg relays what its upstream serves and merges nothing with what is held here. */
    @Override
    default boolean mergesUpstream(FormatExchange exchange) {
        return false;
    }

    /**
     * Serve a local miss from {@code upstream} - what {@link ProxyFormat#proxy} documents, over a request path this
     * seam has already screened and already confirmed this format claims. A format therefore starts from its own
     * protocol routing (which sub-prefix of its namespace this is, which document shape the upstream serves) rather
     * than from a guard, and needs no traversal check of its own.
     *
     * @return {@code true} when this leg served a response, {@code false} to let the local {@code 404} stand
     */
    boolean pullThrough(FormatExchange exchange, ArtifactStore store, URI upstream, ProxyFormat.Fetcher fetcher)
            throws IOException;
}
