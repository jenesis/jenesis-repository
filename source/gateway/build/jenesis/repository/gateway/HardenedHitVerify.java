package build.jenesis.repository.gateway;

import module java.base;
import build.jenesis.repository.definitions.RepositoryDefinition;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.GatePolicyProvider;
import build.jenesis.repository.compliance.QualityInspector;
import build.jenesis.repository.compliance.Verdict;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.metadata.MetadataStore;
import build.jenesis.repository.server.PullThroughHooks;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;

/**
 * The {@link PullThroughHooks} that closes the #79 cache-HIT bypass for a HARDEN serving posture: a locally
 * cached hardened artifact is decided against the current gate <em>before</em> any hit byte is served,
 * the request-time fail-closed complement of the {@link MigrationRescreenTask} bulk amortizer. It is the hit-verify twin
 * of the miss-leg {@link HardenedScreen} - both funnel the identical screening mechanism, never a second one.
 *
 * <p><b>{@link #verifyHit} - the HIT leg (fail-closed local serve).</b> A hardened cache hit is decided from LOCAL state
 * alone, <em>never</em> by re-fetching the upstream to re-validate cached bytes (review caveat 1):
 * <ul>
 *   <li><b>Nothing durably local</b> ({@link Publication#located} empty) - {@link HitDecision#serveThrough()}: there is
 *       no cached blob to verify, so the local-first serve runs as today and a genuine local miss flows on to the
 *       (screened) miss leg.</li>
 *   <li><b>A valid recorded {@code ALLOW} verdict pinning exactly these cached bytes</b> (digest-pinned verdict
 *       reuse) - {@link HitDecision#serveThrough()}: the amortized steady state, one metadata read and no re-screen. The
 *       cached blob key <em>is</em> the content digest (the store is content-addressed), so the digest match is read
 *       from the pointer, never by re-streaming the blob.</li>
 *   <li><b>A recorded non-{@code ALLOW} verdict pinning these bytes</b> (a retro re-verdict or a policy flip the sweep
 *       already recorded) - {@link HitDecision#withhold()}: {@code 404} without serving, and the cached pointer is
 *       evicted so a subsequent request misses and re-fetches through the hardened miss leg. No upstream re-fetch here -
 *       the withhold is final for this request.</li>
 *   <li><b>Unverdicted, or a verdict pinning different bytes</b> - {@link HitDecision#serveLocal(LocalServe)}: the
 *       edition re-screens the LOCAL bytes fail-closed through {@link HardenedScreen#serveVerified} (never the upstream)
 *       and streams the verified bytes, or answers {@code 404} and evicts when the current gate refuses them.</li>
 * </ul>
 *
 * <p><b>Caveat 2 - no retroactive 2 GiB eviction.</b> The local re-screen runs through {@link HardenedScreen#serveVerified},
 * which screens the local {@link QualityInspector.Content} directly and <em>does not</em> apply the untrusted-upstream
 * fetch {@link HardenedScreen.Bounds#maxArtifactBytes() size ceiling} - that ceiling guards only the live spool of an
 * untrusted upstream body inside {@link HardenedScreen} {@code download}, and is not an eviction rule for content already
 * admitted to the store. So an already-admitted {@code > 2 GiB} cached blob is re-screened and served on hit-verify
 * without the fetch ceiling retroactively 404-ing it (the default: exempt already-admitted content, never regress
 * availability). The {@code bounds} are carried only so the leg is constructed identically to the miss leg.
 *
 * <p><b>{@link #screenFetch} - the MISS leg.</b> Returns the router's own {@code screening(...)}-composed fetcher (the
 * upstream-probe / spool / records placement), so a local miss screens through the SAME decorator the routed miss leg
 * always used - one screening mechanism unified through the seam, never double-screened (the fetcher handed to the
 * {@link build.jenesis.repository.server.PullThroughCache} constructor is the <em>raw</em> probe; the screening
 * decoration happens here, once). On the verify-only wiring (the {@code resolve()} step-1 local-first path, which does
 * not run a miss leg through this seam) it is the identity default.
 *
 * <p><b>DEFAULT / non-hardened legs.</b> Constructed with {@code harden == false} (or simply not used), {@link #verifyHit}
 * is the {@link HitDecision#serveThrough()} default: a DEFAULT proxy keeps today's withheld-pointer retraction (the retro
 * KEV/license sweeps write the marker; a per-hit full gate re-assessment on every DEFAULT read would be a §7 regression
 * with no verdict record to dedup against), so no per-hit store read is added there.
 *
 * <p><b>The flavour is the artifact's.</b> This leg is reached for every hit in a repository whose
 * {@link RepositoryDefinition#harden()} is set, and that includes the hybrid {@code writable} + hardened-fallback
 * shape, whose store holds uploads beside cached fetches. So the gate is not "the proxy gate" but the flavour
 * {@link RescreenFlavor} reads off the artifact's own durable {@code origin} trail - the same one decision
 * {@link MigrationRescreenTask} makes, so the verdict the sweep pre-records and the verdict this leg would reach cannot
 * disagree.
 */
public final class HardenedHitVerify implements PullThroughHooks {

    private static final String BLOB_PREFIX = "blobs/";

    private final boolean harden;
    private final Function<GatePolicyProvider.Path, ComplianceGate> gates;
    private final int holdDays;
    private final HardenedScreen.Bounds bounds;
    private final Supplier<ArtifactStore> spool;
    private final Function<ArtifactStore, MetadataStore> metadataOver;
    private final Function<String, ProxyFormat.Fetcher> screenedMissFetcher;

    /** The verify-only form wired into {@code resolve()} step-1 local-first: {@link #verifyHit} does the hardened
     *  hit-verify, {@link #screenFetch} is the identity default (this path runs no miss leg through the seam). Used only
     *  for a HARDEN serving posture, so {@code harden} is implicitly {@code true}. */
    public HardenedHitVerify(Function<GatePolicyProvider.Path, ComplianceGate> gates, int holdDays,
                             HardenedScreen.Bounds bounds, Supplier<ArtifactStore> spool,
                             Function<ArtifactStore, MetadataStore> metadataOver) {
        this(true, gates, holdDays, bounds, spool, metadataOver, null);
    }

    /** The full form wired into the router's {@link build.jenesis.repository.server.PullThroughCache} leg: {@code harden}
     *  gates whether {@link #verifyHit} runs the hardened hit-verify (a non-hardened fallback serves through), and
     *  {@code screenedMissFetcher} composes the router's {@code screening(...)} miss-leg fetcher for a path, which
     *  this seam returns from {@link #screenFetch} (so the miss leg screens through the one shared decorator) - for
     *  the path the cache screens under, which is the kept one where a format keeps its answer under another name
     *  than the one requested. */
    public HardenedHitVerify(boolean harden, Function<GatePolicyProvider.Path, ComplianceGate> gates, int holdDays,
                             HardenedScreen.Bounds bounds, Supplier<ArtifactStore> spool,
                             Function<ArtifactStore, MetadataStore> metadataOver,
                             Function<String, ProxyFormat.Fetcher> screenedMissFetcher) {
        this.harden = harden;
        this.gates = gates;
        this.holdDays = holdDays;
        this.bounds = Objects.requireNonNull(bounds, "bounds");
        this.spool = Objects.requireNonNull(spool, "spool");
        this.metadataOver = Objects.requireNonNull(metadataOver, "metadataOver");
        this.screenedMissFetcher = screenedMissFetcher;
    }

    @Override
    public HitDecision verifyHit(RepositoryFormat format, String path, ArtifactStore store) throws IOException {
        if (!harden || gates == null) {
            // A non-hardened serving posture (or an ungated tenant a hardened miss leg would already fail loud over)
            // keeps today's local-first serve: the withheld-pointer retraction below the format's own serve still holds,
            // no per-hit store read is added.
            return HitDecision.serveThrough();
        }
        // Resolve the local cached pointer. The blob key IS the content digest (the store is content-addressed), so the
        // digest is read from the tiny pointer, never by re-streaming the blob (§1). Nothing durably local -> serve
        // through, so a genuine local miss flows on to the (screened) miss leg exactly as today.
        Optional<String> key = new Publication(store).located(path);
        if (key.isEmpty()) {
            return HitDecision.serveThrough();
        }
        String digest = key.get().substring(BLOB_PREFIX.length());
        Optional<VerdictSection.Recorded> prior = recorded(store, path);
        // A valid recorded ALLOW pinning EXACTLY these cached bytes: the amortized cheap path (one metadata read, no
        // re-screen, no re-fetch) - the MigrationRescreenTask sweep pre-writes this verdict so a steady-state hit is
        // this branch. Serve through so the format's own handle streams the hit with its real headers.
        if (prior.isPresent() && prior.get().allows(digest)) {
            return HitDecision.serveThrough();
        }
        // A recorded non-ALLOW verdict pinning these exact bytes (a retro re-verdict, or a policy flip already recorded):
        // the current gate refuses them. Withhold (404) and evict so a subsequent request misses and re-fetches through
        // the hardened miss leg - decided from local state, never an upstream re-fetch (caveat 1).
        if (prior.isPresent() && prior.get().pins(digest) && prior.get().verdict() != Verdict.ALLOW) {
            evict(store, path);
            return HitDecision.withhold();
        }
        // Unverdicted (never screened before hardening, or the verdict was lost / pins other bytes): re-screen the LOCAL
        // bytes fail-closed through HardenedScreen.serveVerified - never the upstream - and serve verified or 404+evict.
        String blobKey = key.get();
        return HitDecision.serveLocal((fmt, exchange, serveStore) -> serveLocalVerified(path, blobKey, serveStore, exchange));
    }

    @Override
    public ProxyFormat.Fetcher screenFetch(String path, ProxyFormat.Fetcher upstream, ArtifactStore store) {
        // Unify the miss-leg screening through the seam: return the router's own screening()-composed fetcher (built for
        // this exact request path) rather than re-wrapping here, so the one shared ProxyScreen/HardenedScreen decorator
        // screens the miss leg and nothing is double-screened. Identity on the verify-only wiring (no miss leg).
        return screenedMissFetcher != null ? screenedMissFetcher.apply(path) : upstream;
    }

    /** Re-screen a cached hit's LOCAL bytes fail-closed and either stream the verified body or answer a non-disclosive
     *  {@code 404} + evict when the current gate refuses them - the {@link HitDecision#serveLocal(LocalServe)} callback.
     *  Runs {@link HardenedScreen#serveVerified} over a re-openable local {@link QualityInspector.Content}: it never
     *  applies the untrusted-upstream fetch {@link HardenedScreen.Bounds} ceiling to the local bytes (caveat 2), never
     *  re-fetches the upstream (caveat 1), and records any refusal (QuarantineLog + {@code /quarantine}) itself. */
    private void serveLocalVerified(String path, String blobKey, ArtifactStore store, FormatExchange exchange)
            throws IOException {
        ArtifactStore scratch = spool.get();
        try {
            MetadataStore metadata = metadataOver.apply(store);
            // The flavour is the artifact's, not this leg's: the blob key IS the content digest, so the origin
            // trail is consulted for exactly the bytes about to be re-screened.
            HardenedScreen screen = new HardenedScreen(
                    RescreenFlavor.gate(gates, new StoreRepositoryInventory(store), metadata, path,
                            blobKey.substring(BLOB_PREFIX.length())),
                    store, holdDays, scratch, metadata, bounds);
            Optional<ProxyFormat.Download> verified = screen.serveVerified(path, Optional.of(blob(store, blobKey)));
            if (verified.isEmpty()) {
                // Non-ALLOW: serveVerified recorded the refusal like the live path. Evict so a subsequent request misses
                // and re-fetches through the hardened miss leg; this request is a non-disclosive 404 (fail-closed).
                evict(store, path);
                exchange.respond(404);
                return;
            }
            writeThrough(verified.get(), store, blobKey, exchange);
        } finally {
            close(scratch);
        }
    }

    /** Stream a verified local {@link ProxyFormat.Download} to the exchange, with the Content-Length read from the
     *  cached blob's size (never by buffering the body, §1). */
    private static void writeThrough(ProxyFormat.Download download, ArtifactStore store, String blobKey,
                                     FormatExchange exchange) throws IOException {
        try (download) {
            download.headers().forEach(exchange::setResponseHeader);
            try (OutputStream out = exchange.respond(download.status(), store.size(blobKey))) {
                download.body().transferTo(out);
            }
        }
    }

    /** The digest-pinned verdict recorded for this path's coordinate, if any - read only. */
    private Optional<VerdictSection.Recorded> recorded(ArtifactStore store, String path) throws IOException {
        MetadataStore metadata = metadataOver.apply(store);
        HardenedScreen.Coordinate coordinate = HardenedScreen.coordinate(path);
        return VerdictSection.recorded(metadata.section(coordinate.ecosystem(), coordinate.coordinate(),
                coordinate.version(), VerdictSection.TAG));
    }

    /** Evict a bad cached artifact's serving pointer so a subsequent request misses and re-fetches through the hardened
     *  miss leg - the {@link MigrationRescreenTask.Eviction} idiom for a single served path. */
    private static void evict(ArtifactStore store, String path) throws IOException {
        new Publication(store).unpublish(path);
    }

    /** A re-openable {@link QualityInspector.Content} over a stored {@code blobs/<hash>} blob - streamable from byte
     *  zero as many times as an inspector (or the digest re-hash) needs, never a heap {@code byte[]} (§1). Mirrors the
     *  {@link MigrationRescreenTask} blob idiom. */
    private static QualityInspector.Content blob(ArtifactStore store, String key) {
        return new QualityInspector.Content() {
            @Override
            public long size() throws IOException {
                return store.size(key);
            }

            @Override
            public InputStream open() throws IOException {
                return store.open(key);
            }
        };
    }

    private static void close(ArtifactStore store) {
        if (store instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // best-effort scratch reclamation - a cleanup failure never masks the served result
            }
        }
    }
}
