package build.jenesis.repository.gateway;

import module java.base;
import module org.slf4j;
import build.jenesis.repository.definitions.RepositoryDefinition;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.GatePolicyProvider;
import build.jenesis.repository.compliance.QualityInspector;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.Checksums;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.maintenance.MaintenanceTask;
import build.jenesis.repository.maintenance.RepositoryContext;
import build.jenesis.repository.metadata.MetadataProvider;
import build.jenesis.repository.metadata.MetadataStore;
import build.jenesis.repository.settings.SettingsScopes;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ServableNames;
import build.jenesis.repository.store.Publication;

/**
 * The late-enablement migration re-screen sweep for a hardening proxy (§5 self-healing).
 *
 * <p><b>Amortizer, not sole defense.</b> The request-time fail-closed <em>guarantee</em> now lives at
 * the serve boundary: {@link HardenedHitVerify} verifies every hardened cache hit against the current gate BEFORE it
 * serves (the {@link build.jenesis.repository.server.PullThroughHooks} seam #79 closes), so an unverdicted or
 * stale-verdicted hit is never served unverified. This sweep is the §5 bulk <em>complement</em> - it pre-records the
 * digest-pinned verdicts so a steady-state hit-verify is one cheap metadata read rather than a full local re-screen, and
 * it proactively evicts bad cached artifacts including ones never requested again (healing cold corners the on-read
 * verify never reaches). The two share the identical {@link HardenedScreen#serveVerified} local re-screen; neither
 * re-fetches the untrusted upstream to validate already-cached bytes.
 *
 * <p><b>The problem it heals.</b> When a proxy repository is switched to {@code harden} <em>late</em>, its store may
 * already hold artifacts that were cached and served through BEFORE hardening was enabled - bytes the plain caching
 * proxy never full-body-screened, so they carry no {@link VerdictSection digest-pinned verdict}. Adopting {@code harden}
 * must be a config flip, not a manual re-import: this Lease-guarded, idempotent sweep <em>back-fills</em> by re-screening
 * the already-cached artifacts in place - they are local, so nothing is re-fetched from the untrusted upstream - and
 * removes the ones that are actually bad so a subsequent request misses and re-fetches through the hardened leg (or is
 * withheld). Until the sweep reaches a cold artifact, the on-read {@link HardenedHitVerify} still verifies it fail-closed.
 *
 * <p><b>What it does per hardened repository.</b>
 * <ol>
 *   <li><b>Enumerate the needs-screening set (§7).</b> The already-cached artifacts are enumerated by prefix listing
 *       through the store - never a full scan - as {@link Cached}{@code (path, digest, content, eviction)} tuples (the
 *       default {@link #inventoryCache()} source). For each, the {@link VerdictSection verdict
 *       lookup} decides "needs screening": an artifact whose recorded verdict is <em>absent</em>, or pinned to a
 *       <em>different</em> digest than the cached bytes, is re-screened; one already carrying a current
 *       digest-pinned {@code ALLOW} is skipped (the durable-verdict short-circuit that makes a completed sweep cheap to
 *       re-run).</li>
 *   <li><b>Re-screen from local bytes.</b> Each needs-screening artifact is re-screened from its <em>local cached
 *       bytes</em> through {@link HardenedScreen#serveVerified} - the same full-body screen path the live hardened leg
 *       uses, over a {@link QualityInspector.Content} handle on the local blob - which records the digest-pinned verdict
 *. No upstream fetch: these bytes are already local.</li>
 *   <li><b>Evict/quarantine a bad cached artifact.</b> On a non-{@code ALLOW} verdict (the pre-harden serve let through
 *       a secret or a policy-hit) the sweep does not keep serving it: {@code serveVerified} already recorded the
 *       refusal in the durable {@code QuarantineLog} (and copied a held body under {@code /quarantine}) like the live
 *       path, and the sweep then <em>evicts the cached bytes</em> so a subsequent request misses and re-fetches through
 *       the hardened leg. This proactively removes a bad cached artifact even where no request would trigger the
 *       serve-boundary {@link HardenedHitVerify}, so it is evicted, not merely withheld on the next read.</li>
 * </ol>
 *
 * <p><b>Idempotent, self-healing, single-writer (§4/§5).</b> The sweep holds no state - it converges from the store on
 * every pass. It is {@link #exclusion() lease-owned}, so the neutral scheduler runs it under the single-writer
 * {@code locks/migration-rescreen} lease and two replicas never sweep the same store concurrently. A completed sweep is
 * cheap to re-run: every already-verdicted artifact is skipped by the digest-pinned check, so a second pass re-screens
 * nothing. A crash mid-sweep re-converges: the artifacts screened before the crash keep their recorded verdicts and are
 * skipped on the next pass, which screens only the rest. It runs on startup/activation (the scheduler resolves it when
 * {@code harden-rescreen} is enabled) and can be re-triggered on demand. With no consolidated metadata module installed
 * the leg records no verdict and reuses none, so the sweep degrades to re-screening every cached artifact each pass
 * (always safe, never a silently-incomplete view) - exactly the /graceful-absence behaviour.
 *
 * <p><b>Gated to {@code harden} repos.</b> A repository whose definition carries no {@code harden} upstream fallback is
 * skipped: a repository with no fallbacks, a grouped view, a plain caching proxy and a {@code nocache} pass-through all
 * have nothing to back-fill.
 *
 * <p><b>Each artifact is re-screened through the gate flavour it was reached by.</b> The pass used to run the
 * PROXY gate over everything the store held, which is right for a repository that accepts no upload but wrong for the
 * hybrid {@code writable} + hardened-fallback shape {@link #hardenedProxy} deliberately covers: an upload re-screened
 * through the proxy flavour meets PROXY_ONLY dimensions that have nothing to say about it (and is <em>evicted</em> on
 * their verdict) and softened ones the publish gate would not have softened. {@link RescreenFlavor} makes that one
 * decision, off the durable {@code origin} acquisition trail, for this sweep and for {@link HardenedHitVerify} alike.
 */
public final class MigrationRescreenTask implements MaintenanceTask {

    private static final Logger LOGGER = LoggerFactory.getLogger(MigrationRescreenTask.class);

    /** The task name - also the {@code locks/migration-rescreen} object the exclusive pass locks on. */
    public static final String NAME = "migration-rescreen";

    private final Duration interval;
    private final Function<GatePolicyProvider.Path, ComplianceGate> gates;
    private final int holdDays;
    private final HardenedScreen.Bounds bounds;
    private final CachedArtifactSource source;
    private final Function<ArtifactStore, MetadataStore> metadata;

    /** A hardened artifact already cached in a repository's store, as the sweep needs to re-screen it: the request
     *  {@code path} the live leg keys its verdict off, the content {@code digest} of the cached bytes, a re-openable
     *  {@code content} handle on the local blob (never a heap {@code byte[]}, §1), and an {@code eviction} that removes
     *  the cached pointer so a subsequent request misses and re-fetches through the hardened leg. */
    public record Cached(String path, String digest, QualityInspector.Content content, Eviction eviction) {
    }

    /** Remove a bad cached artifact's serving pointer(s) so a subsequent request misses. */
    @FunctionalInterface
    public interface Eviction {
        void evict() throws IOException;
    }

    /** Enumerates a repository store's already-cached artifacts as {@link Cached} tuples - the seam the sweep drives, so
     *  the store-layout enumeration is isolated from the screen-record-evict logic (and injectable in a test). Streamed
     *  through a {@link CachedVisitor} rather than returned as a list, so a hardened proxy with a very large pre-harden
     *  cache does not hold every {@link Cached} handle in heap at once - the sweep screens and releases each in turn. */
    @FunctionalInterface
    public interface CachedArtifactSource {
        void cached(ArtifactStore store, CachedVisitor visitor) throws IOException;
    }

    /** A visitor over a store's streamed cached artifacts, allowed the same checked {@link IOException} the
     *  enumeration and the per-artifact screen it drives may throw. */
    @FunctionalInterface
    public interface CachedVisitor {
        void accept(Cached cached) throws IOException;
    }

    /** A sweep on the default cached-artifact enumeration and the discovered consolidated metadata
     *  store (production). {@code gates} answers the gate for each {@link GatePolicyProvider.Path} flavour, because a
     *  stored artifact is re-screened through the flavour it was reached by ({@link RescreenFlavor}). */
    public MigrationRescreenTask(Duration interval, Function<GatePolicyProvider.Path, ComplianceGate> gates,
                                 int holdDays, HardenedScreen.Bounds bounds) {
        this(interval, gates, holdDays, bounds, inventoryCache());
    }

    /** As {@link #MigrationRescreenTask(Duration, Function, int, HardenedScreen.Bounds)}, with an explicit
     *  cached-artifact {@code source} - the seam a test drives synthetic cached artifacts through. */
    public MigrationRescreenTask(Duration interval, Function<GatePolicyProvider.Path, ComplianceGate> gates,
                                 int holdDays, HardenedScreen.Bounds bounds, CachedArtifactSource source) {
        this(interval, gates, holdDays, bounds, source, discoveredMetadata());
    }

    /** As {@link #MigrationRescreenTask(Duration, Function, int, HardenedScreen.Bounds, CachedArtifactSource)},
     *  with an explicit per-repository {@code metadata} resolver (a repository's store to its digest-pinned verdict
     *  store, {@code null}-yielding when no persistence module is installed) - the seam a test injects an in-test
     *  {@code MetadataStore} through, since putting the persistence module on the gateway test path would flip the
     *  sibling tests off their sidecar path. */
    public MigrationRescreenTask(Duration interval, Function<GatePolicyProvider.Path, ComplianceGate> gates,
                                 int holdDays, HardenedScreen.Bounds bounds, CachedArtifactSource source,
                                 Function<ArtifactStore, MetadataStore> metadata) {
        this.interval = interval;
        this.gates = Objects.requireNonNull(gates, "gates");
        this.holdDays = holdDays;
        this.bounds = Objects.requireNonNull(bounds, "bounds");
        this.source = Objects.requireNonNull(source, "source");
        this.metadata = Objects.requireNonNull(metadata, "metadata");
    }

    /** The production per-repository metadata resolver: the discovered consolidated metadata store over the
     *  repository's own store, or {@code null} when no {@link MetadataProvider} is installed (the leg then records no
     *  verdict and reuses none - the graceful-absence path). */
    public static Function<ArtifactStore, MetadataStore> discoveredMetadata() {
        return store -> MetadataProvider.installed().map(provider -> provider.over(store)).orElse(null);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Duration interval() {
        return interval;
    }

    /** Exclusive: the pass evicts cached pointers and records verdicts, so the scheduler runs it under the
     *  single-writer {@code locks/migration-rescreen} lease and a replicated fleet never double-sweeps a store. */
    @Override
    public Exclusion exclusion() {
        return Exclusion.LEASE;
    }

    @Override
    public void repository(RepositoryContext context) throws IOException {
        UnaryOperator<String> config = context.config();
        if (config == null || !hardened(config, context.repository())) {
            return;   // only a repository serving a hardened upstream leg has pre-harden cached bytes to back-fill
        }
        ArtifactStore store = context.store();
        MetadataStore metadata = this.metadata.apply(store);
        // The spool is unused on the local re-screen path (serveVerified screens the local Content directly and
        // releases via its own handle, never touching the spool), but the screen requires one; a small budgeted
        // scratch satisfies the constructor and is reclaimed after the pass.
        SpoolStore spoolStore = new SpoolStore(SpoolStore.Budget.standard());
        ArtifactStore spool = spoolStore.acquire();
        // Hoisted out of the per-artifact loop: constructing an inventory runs a ServiceLoader scan, and the flavour
        // question below is asked once per cached artifact.
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(store);
        long[] screened = {0};
        long[] evicted = {0};
        try {
            // Stream the cached artifacts rather than buffering the whole cache: each is screened and its handle released
            // before the next is enumerated, so a hardened proxy with a very large pre-harden cache stays heap-bounded.
            source.cached(store, cached -> {
                if (currentlyVerdicted(metadata, cached)) {
                    return;   // a current digest-pinned ALLOW already pins these exact bytes - needs no screening
                }
                screened[0]++;
                // The flavour is the artifact's, not the sweep's: a hand upload sitting in a writable repo with
                // a hardened fallback is re-screened through the PUBLISH gate it was published under, everything else
                // through PROXY. The screen is rebuilt per artifact - it is a handful of field assignments - because the
                // gate it wraps is the per-artifact decision.
                HardenedScreen screen = new HardenedScreen(
                        RescreenFlavor.gate(gates, inventory, metadata, cached.path(), cached.digest()),
                        store, holdDays, spool, metadata, bounds);
                if (rescreen(screen, cached)) {
                    // Non-ALLOW: serveVerified recorded the refusal (QuarantineLog + /quarantine) like the live path;
                    // evict the cached bytes so a subsequent request misses and re-fetches through the hardened leg.
                    cached.eviction().evict();
                    evicted[0]++;
                }
            });
        } finally {
            close(spool);
        }
        context.gauge("jenreg.gateway.hardened.rescreen.screened",
                "Pre-harden cached artifacts re-screened from local bytes this migration pass",
                Map.of("tenant", context.tenant(), "repository", context.repository()), screened[0]);
        context.gauge("jenreg.gateway.hardened.rescreen.evicted",
                "Pre-harden cached artifacts evicted this migration pass because they re-screened non-ALLOW",
                Map.of("tenant", context.tenant(), "repository", context.repository()), evicted[0]);
    }

    /** Whether {@code repository} is a hardened proxy with pre-harden cached bytes to back-fill. The definition string
     *  is read from the live effective config under {@code repositories.<name>} (the same key {@code LiveConfig.definition}
     *  resolves); the key is assembled in a local rather than passed as a literal, so it is a runtime-resolved
     *  definition, not a stranded static config key. A malformed definition is treated as not-hardened rather than
     *  throwing the pass. */
    private static boolean hardened(UnaryOperator<String> config, String repository) {
        String definitionKey = SettingsScopes.repositoryKey(repository);
        String specification = config.apply(definitionKey);
        if (specification == null || specification.isBlank()) {
            return false;
        }
        try {
            return hardenedProxy(RepositoryDefinition.parse(specification));
        } catch (RuntimeException malformed) {
            LOGGER.warn("Skipping migration re-screen of " + repository + ": its definition '" + specification
                    + "' did not parse", malformed);
            return false;
        }
    }

    /** Whether a repository serves any untrusted-upstream hardening leg whose cached bytes need the per-hit re-screen -
     *  {@link RepositoryDefinition#harden()}, the single source of truth. It is not limited to a definition whose only
     *  clause is {@code fallback <url> harden}: a {@code writable} repository with a hardened upstream fallback, or a
     *  list of several fallbacks carrying one, is equally a hardening proxy, and keying on anything narrower would let
     *  it slip the cache-hit re-verify (and the redirect exclusion) and serve retroactively-refused cached bytes. A
     *  {@code harden nocache} repository has nothing durably cached, so the sweep enumerates nothing for it. */
    static boolean hardenedProxy(RepositoryDefinition definition) {
        return definition.harden();
    }

    /** Whether a current digest-pinned {@code ALLOW} verdict already pins exactly the cached bytes - the lookup
     *  that decides an artifact needs no screening. A missing metadata store, an absent verdict, a verdict over
     *  different bytes, or a withholding/refusal all read as "needs screening" (fail-closed), so the sweep re-screens
     *  rather than trusting an unverified or stale cache. A read failure re-screens too. */
    private static boolean currentlyVerdicted(MetadataStore metadata, Cached cached) {
        if (metadata == null) {
            return false;
        }
        HardenedScreen.Coordinate coordinate = HardenedScreen.coordinate(cached.path());
        try {
            // The completeness test applies here too, and this is the surface an operator reaches for after
            // raising the ceiling: a re-screen sweep that skipped every artifact whose verdict the OLD ceiling cut
            // short would skip precisely the artifacts the raise was meant to inspect.
            return VerdictSection.allows(metadata.section(coordinate.ecosystem(), coordinate.coordinate(),
                    coordinate.version(), VerdictSection.TAG), cached.digest(),
                    QualityInspector.fullBodyInspectionLimit());
        } catch (IOException read) {
            LOGGER.warn("Could not read the verdict record for " + cached.path() + "; re-screening", read);
            return false;
        }
    }

    /** Re-screen a cached artifact from its local bytes through the hardened leg's full-body screen, recording the
     *  digest-pinned verdict. Returns {@code true} when the artifact re-screened non-{@code ALLOW} and must be
     *  evicted - {@link HardenedScreen#serveVerified} releases a stream on {@code ALLOW} and an empty result otherwise,
     *  having already recorded the refusal for a withheld/refused body. */
    private static boolean rescreen(HardenedScreen screen, Cached cached) throws IOException {
        Optional<ProxyFormat.Download> served = screen.serveVerified(cached.path(), Optional.of(cached.content()));
        if (served.isPresent()) {
            served.get().close();   // ALLOW: the verdict is recorded and the bytes stay cached, nothing to release here
            return false;
        }
        return true;   // non-ALLOW: recorded (QuarantineLog) by serveVerified; the caller evicts the cached bytes
    }

    /** The default cached-artifact enumeration: walking the store's {@code publish/} namespace in bounded scan pages
     *  (skipping {@code publish/quarantine}) and resolving each pointer to its stored blob. A blobs-namespace release
     *  (npm, PyPI, NuGet, ...) is enumerated off the inventory and resolves through its {@code blobs/} content hashes;
     *  a publish-namespace release (Maven, raw) resolves through its {@code publish/} pointers. Evicting a bad
     *  artifact removes the serving pointer(s) so a subsequent request misses. */
    public static CachedArtifactSource inventoryCache() {
        return (store, visitor) -> {
            StoreRepositoryInventory inventory = new StoreRepositoryInventory(store);
            Publication publication = new Publication(store);
            // The publish/ namespace is walked through the STORE, not through the inventory records: the artifacts
            // this sweep exists for were cached by a PLAIN proxy, which links a publish/ pointer but records no
            // inventory row - an inventory walk would enumerate everything except the pre-harden cache it is
            // meant to back-fill. The quarantine subtree is skipped (those pointers hold withheld bodies, not
            // served cache), and every pointer that resolves to a stored blob is a candidate; the digest-pinned
            // verdict short-circuit above this makes re-visiting a recorded artifact cheap.
            String quarantine = Publication.QUARANTINE_ROOT + "/";
            String resume = "";
            while (resume != null) {
                List<ArtifactStore.Listed> page = new ArrayList<>();
                ArtifactStore.Scan scan = store.scan("publish", resume, SCAN_PAGE, page::add);
                resume = scan.cursor().orElse(null);
                for (ArtifactStore.Listed listed : page) {
                    String key = listed.key();
                    if (key.startsWith(quarantine)) {
                        continue;
                    }
                    String path = key.substring("publish".length());
                    Optional<String> hash = publishHash(store, path);
                    if (hash.isPresent()) {
                        visitor.accept(new Cached(path, hash.get(), blob(store, hash.get()),
                                () -> publication.unpublish(path)));
                    }
                }
            }
            // The blobs-namespace formats serve by content hash rather than through a publish/ pointer, so their
            // versions are enumerated off the inventory - the recording their deploy choreography always writes.
            inventory.coordinates(coordinate -> {
                String ecosystem = coordinate.ecosystem();
                String name = coordinate.coordinate();
                String version = coordinate.version();
                if (!inventory.servesFromBlobs(ecosystem)) {
                    return;   // already enumerated above, off its publish/ pointer
                }
                List<String> paths = inventory.paths(ecosystem, name, version);
                List<String> hashes = inventory.blobHashes(ecosystem, name, version);
                for (int index = 0; index < hashes.size(); index++) {
                    String path = paths.isEmpty()
                            ? inventory.locate(ecosystem, name, version)
                            : paths.get(Math.min(index, paths.size() - 1));
                    if (path == null || path.isBlank()) {
                        continue;
                    }
                    visitor.accept(new Cached(path, hashes.get(index), blob(store, hashes.get(index)),
                            () -> inventory.discardBlobs(ecosystem, name, version)));
                }
            });
        };
    }

    /** The scan page the publish-namespace walk resumes over, so a very large pre-harden cache is streamed in
     *  bounded pages rather than held as one listing. */
    private static final int SCAN_PAGE = 512;

    /** The content hash the {@code publish/} pointer at a served path resolves to, if it is a stored blob pointer -
     *  the publish-namespace (Maven/raw) content resolution, reading only the tiny pointer, never the blob. */
    private static Optional<String> publishHash(ArtifactStore store, String path) throws IOException {
        return store.readVersioned("publish" + path)
                .map(versioned -> ServableNames.hash(versioned.content()))
                .filter(Checksums::isSha256Hex);
    }

    /** A re-openable {@link QualityInspector.Content} over a stored {@code blobs/<hash>} blob - the whole body
     *  streamable from byte zero as many times as an inspector (or the digest re-hash) needs, never a heap {@code byte[]}. */
    private static QualityInspector.Content blob(ArtifactStore store, String hash) {
        String key = "blobs/" + hash;
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
                // best-effort scratch reclamation - a cleanup failure never masks the completed pass
            }
        }
    }
}
