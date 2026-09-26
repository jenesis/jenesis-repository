package build.jenesis.repository.gateway;

import module java.base;
import module org.slf4j;
import build.jenesis.repository.definitions.RepositoryDefinition;
import build.jenesis.repository.server.PullThroughCache;
import build.jenesis.repository.server.PullThroughHooks;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.GatePolicyProvider;
import build.jenesis.repository.compliance.Verdict;
import build.jenesis.repository.format.ArtifactLayout;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.inventory.OriginSection;
import build.jenesis.repository.metadata.MetadataProvider;
import build.jenesis.repository.metadata.MetadataStore;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;

/**
 * Resolves a named repository to its backings and serves a request across them, so one deployment can offer
 * writable stores, read-through proxies and grouped views rather than a single deployment-wide mode. A
 * {@link RepositoryDefinition} is the generalized {@code (writable, ordered fallbacks)} model: {@code writable} =
 * accepts uploads into its own store, and each {@link RepositoryDefinition.Fallback} is an external upstream URL or another
 * repository consulted, first-hit-wins, on a local miss - with a per-upstream copy ({@code store}) and screening
 * policy. The three historical shapes are points in this space: hosted = writable with no fallbacks; proxy =
 * non-writable with one upstream fallback; group = non-writable with repository-name fallbacks.
 *
 * <p>{@link #resolve} walks it with a <b>typed {@link Outcome} channel</b>:
 * local-first over the repository's own store, then the fallbacks in
 * order. A {@link Outcome#MISS} falls through to the next fallback; a {@link Outcome#HIT} streams and stops; a
 * {@link Outcome#REFUSED} - a screening verdict or a locally-withheld artifact - <b>ends the walk</b> with a
 * non-disclosive {@code 404} so no weaker fallback is ever consulted (the structural closure of the 
 * weakest-member bypass); a {@link Outcome#ERROR} propagates a structural refusal already on the wire. Reads
 * compose by recursion (a repository fallback may itself be writable, proxied or grouped); each leg is served
 * through a buffering {@link Deferred} that streams a hit and swallows a {@code 404}. The router reuses the free
 * {@link PullThroughCache} for each upstream leg and each format's own {@code handle} for the local leg, so it adds
 * only the routing. A publish targets a repository's own store iff it is {@code writable} (a non-writable repo
 * answers {@code 405}); write-delegation ({@code push=}) is gone.
 */
public final class RepositoryRouter {

    private static final Logger LOGGER = LoggerFactory.getLogger(RepositoryRouter.class);

    /**
     * The typed outcome of resolving one repository (or one fallback leg) - the channel that replaces the boolean
     * 404-sniffing the old walk used. The distinction the old
     * {@code 404 ⇒ miss, else ⇒ commit} sniff could not make is {@link #REFUSED} vs {@link #MISS}: both are a plain
     * {@code 404} on the wire, but a refusal <b>ends the walk</b> so no weaker fallback is consulted - the structural
     * closure of the weakest-member bypass.
     */
    public enum Outcome {
        /** Bytes were committed to the client (a {@code 2xx}/{@code 3xx} streamed through the exchange). */
        HIT,
        /** A genuine not-found - a local {@code 404} with no withhold, or an upstream {@code 404}/negative-cache. The
         *  walk falls through to the next fallback. */
        MISS,
        /** A screening verdict (quarantine/reject or a cannot-screen refusal) <b>or</b> a locally withheld artifact:
         *  content the gate retracted, not absence. The wire stays a non-disclosive {@code 404}, but the walk
         *  <b>ends</b> - no weaker fallback is consulted. */
        REFUSED,
        /** A structural refusal already committed to the wire (spool-budget {@code 503}, depth {@code 508}, a
         *  cannot-serve {@code 5xx}); the walk ends exactly as today. */
        ERROR
    }

    /** The read-side quarantine guard consumed (never modified) from the publication-interceptor chain
     *  ({@link build.jenesis.repository.store.PublishInterceptor#withheld}): whether the artifact at {@code path} in a
     *  repository's own {@code store} is currently withheld from serving (a gate retracted it after the fact, or it is
     *  staged). A locally-withheld artifact is a {@link Outcome#REFUSED}, not a {@link Outcome#MISS} - so it ends the
     *  walk rather than falling through to a weaker fallback. The default is {@link #NONE} (nothing withheld); the
     *  server wires it to the discovered interceptor chain. */
    @FunctionalInterface
    public interface WithheldGuard {
        boolean withheld(String path, ArtifactStore store) throws IOException;

        /** No local withhold guard - every local {@code 404} is a genuine miss (the single-tenant default, and
         *  the gateway default until a deployment wires the discovered chain). */
        WithheldGuard NONE = (path, store) -> false;
    }

    /**
     * The serve-policy seam the router carries and the {@code redirect-directory} module fills:
     * for a {@link RepositoryDefinition.Serve#REDIRECT} upstream fallback the walk delegates the leg here instead of
     * fetch-screen-serving it, so the router owns the routing decision (which leg, chosen once at fallback level for
     * sibling coherence) while the module owns the behavior (the byte-free policy floor / withheld probe, the SSRF and
     * credential guards, the {@code 307} emission and its accounting). The handler emits its answer
     * through {@code exchange} and returns the walk {@link Outcome}: {@link Outcome#HIT} when a {@code 307} (or a
     * terminal status) was committed, {@link Outcome#MISS} to fall through to the next fallback (a screened redirect the
     * floor or withheld probe vetoed - the walk continues exactly as an upstream miss would), or
     * {@link Outcome#REFUSED} to end the walk. A {@code redirect} definition is refused at parse when no module is
     * installed, so a production walk never reaches a REDIRECT leg without a handler; the default {@link #REDIRECT_ABSENT}
     * sentinel is a belt-and-braces fail-loud for a directly-constructed one.
     *
     * <p><b>DNS-directory legs.</b> A {@link RepositoryDefinition.Source.DnsDirectory} leg delegates here through the
     * same seam, but with {@code upstream == null}: the {@code redirect-dns}-provided handler resolves the target per
     * request by the DNS walk ({@code DnsDirectory.locate}) from the coordinate the {@code format} derives, rather than
     * from a clause-literal upstream. A handler that serves a DNS leg must therefore read the routing from {@code
     * fallback.source()} and the {@code exchange} path, not assume a non-null {@code upstream}.
     */
    @FunctionalInterface
    public interface RedirectHandler {
        /** Serve one REDIRECT leg. {@code upstream} is the clause-literal target for an {@link RepositoryDefinition.Source.Upstream}
         *  fallback, or {@code null} for a {@link RepositoryDefinition.Source.DnsDirectory} fallback whose target is resolved per
         *  request by the DNS walk. */
        Outcome redirect(String tenant, String repository, RepositoryDefinition.Fallback fallback, URI upstream,
                         RepositoryFormat format, FormatExchange exchange) throws IOException;
    }

    /** The default handler for a router with none injected: a directly-constructed {@code redirect} fallback that
     *  reaches the walk without the module is a fail-loud {@link IllegalStateException} rather than a silent proxy
     *  (the parse refusal should already have caught it). */
    private static final RedirectHandler REDIRECT_ABSENT = (tenant, repository, fallback, upstream, format, exchange) -> {
        throw new IllegalStateException("A 'redirect' fallback for repository '" + repository + "' was resolved but no "
                + "redirect handler is installed (the 'redirect-directory' module); this definition should have been "
                + "refused at parse. Install the 'redirect-directory' module, or drop 'redirect' from the definition.");
    };

    private final Function<String, RepositoryDefinition> definitions;
    private final BiFunction<String, String, ArtifactStore> stores;
    private final ProxyFormat.Fetcher fetcher;
    private final BiFunction<String, GatePolicyProvider.Path, ComplianceGate> gate;
    private final IntSupplier holdDays;
    private final Supplier<ArtifactStore> passThrough;
    private final HardenedScreen.Bounds hardeningBounds;
    private final WithheldGuard withheld;

    /** Resolves the consolidated metadata store bound to a repository's scoped store - where the digest-pinned
     *  {@code verdict} record and the {@code origin} acquisition rows land. Defaults to the discovered
     *  persistence module ({@link #INSTALLED_METADATA}); a test injects one through {@link #tracking}. */
    private final Function<ArtifactStore, MetadataStore> metadataOver;

    /** The serve-policy collaborator a {@link RepositoryDefinition.Serve#REDIRECT} upstream leg delegates to (the
     *  {@code redirect-directory} module's injected handler); {@link #REDIRECT_ABSENT} until a deployment injects one
     *  through {@link #redirecting(RedirectHandler)} - and a {@code redirect} definition cannot parse without the
     *  module, so the default is only ever reached by a directly-constructed one (fail-loud). */
    private final RedirectHandler redirect;

    /** The origin-refresh coalescing gate: the day each {@code (tenant|repo|path|sha256)} key last had its
     *  {@code origin} row's {@code lastServed}/{@code serves} durably refreshed, so a hot no-copy pass-through refreshes
     *  a key at most once per day rather than CAS-storming one doc key on every serve (the {@code BatchingDownloadTracker}
     *  day-granular discipline, §7). The first acquisition of a key - and every digest change (a new key) - is never in
     *  this map, so it is always written synchronously (§9). Pruned to a single day's keys on each pass, so it is bounded
     *  by the distinct coordinates served in a day, not everything ever served. */
    private final Map<String, LocalDate> originRefreshed = new ConcurrentHashMap<>();

    public RepositoryRouter(Function<String, RepositoryDefinition> definitions,
                            BiFunction<String, String, ArtifactStore> stores,
                            ProxyFormat.Fetcher fetcher) {
        // The default pass-through scratch is a budgeted SpoolStore on the standard budget, so an out-of-the-box
        // nocache leg already spools an untrusted upstream body to bounded temp files and refuses (503) rather than
        // growing unbounded; a deployment sizes the budget by passing a configured store to passingThrough(...). The
        // hardened leg's untrusted-upstream fetch bounds default to HardenedScreen.Bounds.standard() until a deployment
        // sizes them from config via hardening(...). The withheld read guard defaults to NONE until a deployment wires
        // the discovered interceptor chain via withholding(...). The consolidated metadata store (verdict + origin
        // records) is resolved from the discovered persistence module (MetadataProvider.installed()) until a test
        // injects one via tracking(...).
        this(definitions, stores, fetcher, (_, _) -> null, () -> 0,
                new SpoolStore(SpoolStore.Budget.standard())::acquire, HardenedScreen.Bounds.standard(),
                WithheldGuard.NONE, INSTALLED_METADATA, REDIRECT_ABSENT);
    }

    /** Resolve the consolidated metadata store bound to a repository's scoped store from the discovered persistence
     *  module, or {@code null} when none is installed (the leg then records/reuses nothing). */
    private static final Function<ArtifactStore, MetadataStore> INSTALLED_METADATA =
            store -> MetadataProvider.installed().map(provider -> provider.over(store)).orElse(null);

    private RepositoryRouter(Function<String, RepositoryDefinition> definitions,
                             BiFunction<String, String, ArtifactStore> stores,
                             ProxyFormat.Fetcher fetcher,
                             BiFunction<String, GatePolicyProvider.Path, ComplianceGate> gate,
                             IntSupplier holdDays,
                             Supplier<ArtifactStore> passThrough, HardenedScreen.Bounds hardeningBounds,
                             WithheldGuard withheld, Function<ArtifactStore, MetadataStore> metadataOver,
                             RedirectHandler redirect) {
        this.definitions = definitions;
        this.stores = stores;
        this.fetcher = fetcher;
        this.gate = gate;
        this.holdDays = holdDays;
        this.passThrough = passThrough;
        this.hardeningBounds = hardeningBounds;
        this.withheld = withheld;
        this.metadataOver = metadataOver;
        this.redirect = redirect;
    }

    /** Screen every proxied artifact through the gate before caching or serving it, so a router-configured proxy
     *  applies the same fetch firewall as the deployment-wide proxy; a version the upstream published within the hold
     *  window is quarantined. The gate is resolved per request from the serving tenant, so a tenant's own policy screens
     *  its pull-through fetches (a null gate for the tenant disables screening).
     *
     *  <p>It is a lookup of <em>both</em> gate flavours rather than one gate: a fetch off an untrusted upstream
     *  is screened through {@link GatePolicyProvider.Path#PROXY} as it always was, while a <em>stored</em> artifact
     *  re-screened on a hardened cache hit takes the flavour it was reached by ({@link RescreenFlavor}), which for the
     *  hybrid {@code writable} + hardened-fallback shape is not always the proxy one. */
    public RepositoryRouter gating(BiFunction<String, GatePolicyProvider.Path, ComplianceGate> gate,
                                   IntSupplier holdDays) {
        return new RepositoryRouter(definitions, stores, fetcher, gate, holdDays, passThrough, hardeningBounds,
                withheld, metadataOver, redirect);
    }

    /** A tenant's proxy-flavour gate - what every leg that screens a body <em>arriving from an upstream</em> uses, and
     *  the null-check every "is this tenant gated at all?" test reads. */
    private ComplianceGate proxyGate(String tenant) {
        return gate == null ? null : gate.apply(tenant, GatePolicyProvider.Path.PROXY);
    }

    /** A tenant's flavour lookup, as the re-screen legs take it: they decide the flavour per stored artifact through
     *  {@link RescreenFlavor} rather than being handed a gate the router chose for them. */
    private Function<GatePolicyProvider.Path, ComplianceGate> gates(String tenant) {
        return gate == null ? null : path -> gate.apply(tenant, path);
    }

    /** Inject the consolidated metadata-store factory the verdict and origin records are written through,
     *  bound per call to a repository's scoped store. Production leaves the default ({@link MetadataProvider#installed()});
     *  a test passes an in-test store so the router's records round-trip without installing the persistence module for
     *  every gateway test. */
    public RepositoryRouter tracking(Function<ArtifactStore, MetadataStore> metadataOver) {
        return new RepositoryRouter(definitions, stores, fetcher, gate, holdDays, passThrough, hardeningBounds,
                withheld, metadataOver, redirect);
    }

    /** Supply the scratch {@link ArtifactStore} the {@code nocache} pass-through leg fetches through, so a test can
     *  observe the streaming without spooling to a temporary file, or a deployment can inject a {@link SpoolStore}
     *  sized to its own resource budget; production uses the default {@link SpoolStore} that spools each blob to a
     *  bounded, owner-only temp file (bounded heap) and reclaims it once served, refusing with 503 when its budget is
     *  exhausted. A returned store implementing {@link AutoCloseable} is closed after the request, the hook the default
     *  uses to reclaim its scratch. */
    public RepositoryRouter passingThrough(Supplier<ArtifactStore> passThrough) {
        return new RepositoryRouter(definitions, stores, fetcher, gate, holdDays, passThrough, hardeningBounds,
                withheld, metadataOver, redirect);
    }

    /** Set the untrusted-upstream fetch {@link HardenedScreen.Bounds} the hardened leg enforces (the per-artifact
     *  size ceiling, the fetch duration ceiling and the minimum throughput floor). A deployment sizes them from config;
     *  the default is {@link HardenedScreen.Bounds#standard()}. */
    public RepositoryRouter hardening(HardenedScreen.Bounds hardeningBounds) {
        return new RepositoryRouter(definitions, stores, fetcher, gate, holdDays, passThrough, hardeningBounds,
                withheld, metadataOver, redirect);
    }

    /** Wire the read-side {@link WithheldGuard} (the discovered publication-interceptor {@code withheld} chain), so a
     *  local {@code 404} over an artifact the gate has retracted is fed the {@link Outcome#REFUSED} channel and ends
     *  the walk rather than falling through to a weaker fallback (the weakest-member closure, for locally-withheld
     *  content). */
    public RepositoryRouter withholding(WithheldGuard withheld) {
        return new RepositoryRouter(definitions, stores, fetcher, gate, holdDays, passThrough, hardeningBounds,
                withheld, metadataOver, redirect);
    }

    /** Inject the {@link RedirectHandler} a {@link RepositoryDefinition.Serve#REDIRECT} upstream leg delegates to (the
     *  {@code redirect-directory} module's handler - the router carries the seam, the module carries the behavior).
     *  Production wires it once at boot; a test injects a stub that records the leg. This only sets the
     *  instance collaborator - {@link RepositoryDefinition#redirectHandlerInstalled(boolean)} controls whether the {@code redirect} token
     *  parses at all. */
    public RepositoryRouter redirecting(RedirectHandler redirect) {
        return new RepositoryRouter(definitions, stores, fetcher, gate, holdDays, passThrough, hardeningBounds,
                withheld, metadataOver, redirect);
    }

    /** The explicit definition of a repository, or {@code null} when it is not configured (a plain hosted repo). */
    public RepositoryDefinition definition(String repository) {
        return definitions.apply(repository);
    }

    /** The repository a write to {@code repository} lands in - <b>itself</b> when it is {@code writable}, else
     *  {@code null} (it is read-only and a publish answers {@code 405}). An unconfigured name is a plain writable repo:
     *  writability is a repository's own property now - a group/proxy is read-only, and write-delegation
     *  ({@code push=}) is gone. */
    public String writeTarget(String repository) {
        RepositoryDefinition definition = definitions.apply(repository);
        if (definition == null) {
            return repository;
        }
        return definition.writable() ? repository : null;
    }

    /** Serve a {@code GET}/{@code HEAD} across the repository's backings, writing the response through the exchange.
     *  Returns the typed {@link Outcome} (bytes streamed on {@link Outcome#HIT}; a non-disclosive {@code 404} committed
     *  here on {@link Outcome#MISS}/{@link Outcome#REFUSED}; a structural status already committed on
     *  {@link Outcome#ERROR}). */
    public Outcome resolve(String tenant, String repository, RepositoryFormat format, FormatExchange exchange)
            throws IOException {
        Outcome outcome = resolve(tenant, repository, format, exchange, 0);
        if (outcome == Outcome.MISS || outcome == Outcome.REFUSED) {
            // Nothing was committed to the real exchange (every miss/refusal 404 was swallowed by a per-leg Deferred),
            // so the client is shown a single plain 404 here. A REFUSED is byte-identical on the wire to a MISS (no
            // existence leak), but the walk already stopped so no weaker fallback ran.
            exchange.respond(404);
        }
        return outcome;
    }

    /** The read side kept as {@code void} for the {@code RoutedServing} seam. */
    public void serve(String tenant, String repository, RepositoryFormat format, FormatExchange exchange)
            throws IOException {
        resolve(tenant, repository, format, exchange);
    }

    /**
     * The typed-outcome resolution walk: local-first over the repository's own store, then the ordered
     * {@link RepositoryDefinition.Fallback fallbacks}, first-hit-wins. A {@link Outcome#MISS} falls through to the next fallback;
     * a {@link Outcome#HIT}/{@link Outcome#REFUSED}/{@link Outcome#ERROR} ends the walk. Each leg is served through a
     * {@link Deferred} that streams a hit straight to the client but swallows a {@code 404}, so the walk can try the
     * next fallback without a leaked existence probe; the final {@code 404} is committed once, by {@link #resolve}.
     *
     * <p><b>§1 streaming.</b> No artifact body is materialised here: the local leg runs the format's own {@code handle}
     * (streaming), each upstream leg runs the shared {@link PullThroughCache} streaming pull-through, and the
     * screen/spool legs keep their bounded-prefix/bounded-spool discipline unchanged.
     */
    private Outcome resolve(String tenant, String repository, RepositoryFormat format, FormatExchange exchange,
                            int depth) throws IOException {
        if (depth > 16) {
            exchange.respond(508);   // a fallback cycle: a structural refusal committed to the wire (ERROR), never a miss
            return Outcome.ERROR;
        }
        RepositoryDefinition definition = definitions.apply(repository);
        if (definition == null) {
            definition = RepositoryDefinition.hosted();
        }
        // 1. LOCAL-FIRST: the repository's OWN store (uploads + previously cached fallback fetches), exactly the
        // local-first discipline PullThroughCache drives - a local upload shadows a fallback's same coordinate, and a
        // cached fallback fetch is a local hit on every subsequent request (steady state never re-walks). The store is
        // consulted only when the repository actually HAS one - it is writable (uploads) or has a caching upstream
        // fallback (cached fetches); a pure pass-through / group view has no own store, so it is never resolved (the
        // "a pass-through never touches the repository store" contract, and its scratch local-first is driven by the
        // fallback's own PullThroughCache below).
        if (hasOwnStore(definition)) {
            ArtifactStore local = stores.apply(tenant, repository);
            // #79 cache-HIT close: for a hardened serving posture, verify the cached hit against the CURRENT
            // gate BEFORE it serves - this local-first path does NOT run through PullThroughCache, so the seam is
            // consulted here directly. A DEFAULT/other posture (or an ungated tenant) serve-throughs, so today's
            // local-first below is byte-for-byte unchanged and the withheld-pointer retraction still applies.
            if (MigrationRescreenTask.hardenedProxy(definition) && proxyGate(tenant) != null) {
                PullThroughHooks.HitDecision decision = new HardenedHitVerify(gates(tenant), holdDays.getAsInt(),
                        hardeningBounds, passThrough, metadataOver).verifyHit(format, exchange.path(), local);
                if (decision instanceof PullThroughHooks.HitDecision.Withhold) {
                    // A now-retracted/refused hardened hit: 404 without serving, evicted, and no upstream re-fetch. It
                    // ends the walk (REFUSED) - a locally-refused hardened artifact never falls to a weaker fallback
                    // (the weakest-member bypass); a subsequent request misses the evicted pointer and re-fetches
                    // through the leg.
                    return Outcome.REFUSED;
                }
                if (decision instanceof PullThroughHooks.HitDecision.ServeLocal serveLocal) {
                    // The edition re-screens the LOCAL bytes fail-closed and serves the verified body, or 404s+evicts.
                    Deferred verifiedLeg = new Deferred(exchange);
                    serveLocal.serve().serve(format, verifiedLeg, local);
                    if (verifiedLeg.committed()) {
                        return verifiedLeg.status() < 400 ? Outcome.HIT : Outcome.ERROR;
                    }
                    return Outcome.REFUSED;   // serveVerified answered 404 (non-ALLOW, evicted): fail-closed, ends walk
                }
                // serveThrough (a valid recorded ALLOW, or nothing durably local): fall through to today's local-first.
            }
            Deferred localLeg = new Deferred(exchange);
            format.handle(localLeg, local);
            if (localLeg.committed()) {
                return localLeg.status() < 400 ? Outcome.HIT : Outcome.ERROR;
            }
            if (withheld.withheld(exchange.path(), local)) {
                // A locally WITHHELD artifact (the gate retracted a previously-linked path, or it is staged): content
                // that exists but the gate withdrew, NOT absence. It never falls through to a weaker fallback (the
                // weakest-member bypass, for local content); the wire stays a plain 404, committed by resolve().
                return Outcome.REFUSED;
            }
        }
        // 2. FALLBACKS IN ORDER, FIRST HIT WINS.
        int fallbackIndex = -1;
        for (RepositoryDefinition.Fallback fallback : definition.fallbacks()) {
            fallbackIndex++;
            // A `match=` coordinate predicate filters the walk MISS-composably - a request this fallback's
            // predicate does not admit is skipped, falling through to the next fallback exactly as an upstream 404
            // would, so a coordinate-partitioned upstream set composes without touching the refusal-stops-the-walk
            // semantics below. The leg decision is made ONCE here (never per-URL inside the leg), so a Maven `.sha1`
            // sibling the format fetches within the chosen leg follows the same upstream (sibling-coherence).
            if (!applies(fallback, format, exchange.path())) {
                continue;
            }
            Outcome outcome = switch (fallback.source()) {
                case RepositoryDefinition.Source.Repository inner ->
                        resolve(tenant, inner.name(), format, new Deferred(exchange), depth + 1);   // recursion into a member
                // A REDIRECT upstream leg delegates to the injected redirect handler (emit a 307 to the
                // upstream) instead of fetch-screen-serving it; PROXY is today's pull-through walk, byte-for-byte.
                case RepositoryDefinition.Source.Upstream upstream -> fallback.serve() == RepositoryDefinition.Serve.REDIRECT
                        ? redirect.redirect(tenant, repository, fallback, upstream.url(), format, exchange)
                        : fetchScreenServe(tenant, repository, fallbackIndex, fallback, upstream.url(), format, exchange);
                // A DNS-directory leg delegates to the SAME redirect handler, but with no
                // clause-literal upstream - the redirect-dns-provided handler resolves the target per request by the DNS
                // walk (DnsDirectory.locate) and emits the 307 (or MISSes to fall through / REFUSEs to end the walk).
                // The `dns` source parses only as `redirect` (validated at parse), so the handler is always consulted.
                case RepositoryDefinition.Source.DnsDirectory dns ->
                        redirect.redirect(tenant, repository, fallback, null, format, exchange);
            };
            if (outcome == Outcome.MISS) {
                continue;                 // genuine 404 from this fallback - try the next
            }
            return outcome;               // HIT / REFUSED / ERROR ends the walk (a refusal stops the walk)
        }
        return Outcome.MISS;              // exhausted - resolve() commits the single 404
    }

    /** The content-addressed hash a served fallback fetch landed under in {@code store} - the {@code blobs/<hash>}
     *  publish pointer the format wrote for {@code path}, or {@code null} when nothing is published there (an index
     *  served through the buffered {@code fetch} path, or a leg that cached nothing readable). A tiny pointer read, never
     *  a blob-body read (§1): the store computed the hash on write, so origin reuses it. Best-effort - a read failure
     *  yields {@code null} (no origin row), never a failed serve. */
    private static String located(ArtifactStore store, String path) {
        try {
            return new Publication(store).located(path).map(key -> key.substring("blobs/".length())).orElse(null);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Whether the repository has a store of its OWN to serve local-first from: it accepts uploads ({@code writable}),
     *  or it has at least one caching upstream fallback whose fetched bytes land in that store. A pure pass-through
     *  ({@code nocache} upstream) or a group view over other repositories owns nothing, so its store is never resolved
     *  - preserving the "a pass-through never touches the repository store" guarantee and matching today's routing,
     *  where a nocache leg's local-first ran over the scratch (driven by the fallback's own PullThroughCache), not the
     *  durable store, and a group never touched a store for itself. */
    private static boolean hasOwnStore(RepositoryDefinition definition) {
        return definition.writable() || definition.fallbacks().stream()
                .anyMatch(fallback -> fallback.source() instanceof RepositoryDefinition.Source.Upstream && fallback.store());
    }

    /** Whether a fallback's {@code match=} coordinate predicate admits this request, derived from the path
     *  alone with no I/O. A fallback with no predicate always applies. A predicate is evaluated against the
     *  format-derived coordinate: a coordinate that does not match skips the fallback (the MISS-composable walk filter),
     *  while a request the format has no coordinate for - a coordinate-less checksum sibling, a metadata/index path, or
     *  a format without the {@link ArtifactLayout} capability - is left to the configured order and never partitioned
     *  away, so a sibling fetch follows the same leg its artifact took (sibling-coherence: the leg decision is made once
     *  at fallback level, never per-URL inside a leg). */
    private static boolean applies(RepositoryDefinition.Fallback fallback, RepositoryFormat format, String path) {
        if (fallback.match() == null) {
            return true;
        }
        if (!(format instanceof ArtifactLayout layout)) {
            return true;                  // no coordinate knowledge: configured order (never filtered out)
        }
        Optional<ArtifactDescriptor> described = layout.describe(path);
        if (described.isEmpty() || described.get().coordinate() == null) {
            return true;                  // empty-coordinate (checksum / metadata): configured order
        }
        return fallback.matches(described.get());
    }

    /**
     * Serve one {@link RepositoryDefinition.Source.Upstream} fallback: fetch-screen-serve honouring the fallback's own
     * {@code store} (cache-or-not) and {@code screening} (DEFAULT tenant-gate / HARDEN full-body / UNSCREENED opt-out)
     * policy, through a {@link Deferred} so a hit streams to the client while a miss/refusal stays swallowed. The
     * upstream's real status is observed below the screen by an {@link UpstreamProbe}, so a {@code 404}-on-the-wire is
     * classified as a {@link Outcome#REFUSED} (the upstream served {@code 200} but the screen withheld it) rather than
     * a {@link Outcome#MISS} (the upstream itself had nothing) - the distinction the old 404-sniff could not make.
     */
    private Outcome fetchScreenServe(String tenant, String repository, int fallbackIndex, RepositoryDefinition.Fallback fallback,
                                     URI upstream, RepositoryFormat format, FormatExchange exchange) throws IOException {
        Deferred leg = new Deferred(exchange);
        UpstreamProbe probe = new UpstreamProbe(fetcher);
        boolean harden = fallback.screening() == RepositoryDefinition.Screening.HARDEN;
        boolean store = fallback.store();
        // The durable per-repository store the origin record (and the screen's own quarantine records) land in, resolved
        // for a gated tenant even on a no-store leg (durable records beside transient bytes). For an ungated
        // pass-through it stays the throwaway scratch, preserving the "an ungated pass-through never touches the
        // repository store" guarantee - origin then lands wherever the durable records do.
        ArtifactStore records = null;
        // The content-addressed hash of the served bytes - the sha256 the origin row is keyed on. It is read
        // from the store the bytes actually land in (the durable cache, or the transient scratch/spool BEFORE it is
        // reclaimed) as the tiny publish pointer, never by re-reading the blob body (§1): the store computed the hash on
        // write, so origin reuses it. Captured inside each branch before the scratch is closed.
        String digest = null;
        if (harden) {
            // An untrusted-upstream hardening proxy: the fetched body is spooled in full, screened, and only a verified
            // stream is released (spool -> screen -> release-verified-stream, HardenedScreen). The pre-verdict spool is
            // a budgeted SpoolStore scratch, reclaimed once served; a budget exhaustion refuses the fetch with 503
            // rather than spooling unbounded. A hardened leg with no screening gate installed fails loud in screening()
            // rather than proxying unscreened (§9). Store-on-pass vs transient: a plain harden (store=true)
            // durably caches the verified copy; a harden-nocache (store=false) screens every fetch and caches nothing.
            // Either way the gate's DURABLE records (QuarantineLog row, /quarantine pointer, digest-pinned verdict) go
            // to the real per-repository store, never the throwaway scratch.
            ArtifactStore durable = stores.apply(tenant, repository);
            records = durable;
            ArtifactStore spool = passThrough.get();
            ArtifactStore body = store ? durable : passThrough.get();
            try {
                pullThrough(tenant, format, fallback, upstream, leg, body, durable, spool, probe);
                digest = located(body, exchange.path());   // the verified copy the leg cached (durable) or spooled
            } catch (SpoolStore.BudgetExhausted exhausted) {
                LOGGER.warn("Spool budget exhausted screening hardened "
                        + repository + exchange.path() + ": " + exhausted.getMessage());
                leg.respond(503);
            } finally {
                close(spool);
                if (body != durable) {
                    close(body);   // reclaim the transient nocache scratch; the durable store is not closed
                }
            }
        } else if (store) {
            ArtifactStore durable = stores.apply(tenant, repository);
            records = durable;
            pullThrough(tenant, format, fallback, upstream, leg, durable, durable, null, probe);
            digest = located(durable, exchange.path());
        } else {
            // A pass-through: the fetched bytes are served once and discarded, so they never touch the repository's
            // store. The scratch streams the artifact from upstream to the response spooled to a temp file rather than
            // held whole in heap, and is closed - deleting the scratch - once served. But the gate's DURABLE records
            // (the /quarantine pointer and the QuarantineLog row for a withheld artifact) are written to the real
            // per-repository store, not the throwaway scratch, so a gated nocache leg still leaves a review entry and
            // audit record. That real store is resolved lazily and only for a gated tenant, so an ungated pass-through
            // never touches the repository store.
            ArtifactStore scratch = passThrough.get();
            records = proxyGate(tenant) == null ? scratch : stores.apply(tenant, repository);
            try {
                pullThrough(tenant, format, fallback, upstream, leg, scratch, records, null, probe);
                digest = located(scratch, exchange.path());   // the transient scratch copy, read before it is reclaimed
            } catch (SpoolStore.BudgetExhausted exhausted) {
                LOGGER.warn("Spool budget exhausted serving " + repository
                        + exchange.path() + ": " + exhausted.getMessage());
                leg.respond(503);
            } finally {
                close(scratch);
            }
        }
        if (leg.committed()) {
            if (leg.status() < 400 && probe.served() && digest != null) {
                // Origin follows the bytes. The upstream actually served THESE bytes (probe.served()) and they
                // streamed to the client, so record where they came from - for BOTH a store and a no-store fallback.
                // The sha256 is the content-addressed hash the store computed on write (read as the tiny publish
                // pointer, never a blob re-read, §1); for a hardened leg it equals the spool digest the sibling verdict
                // is pinned to (the schemas align).
                String target = probe.url() != null ? probe.url() : upstream.toString();
                recordFallbackOrigin(tenant, repository, fallbackIndex, fallback, target, exchange.path(),
                        records, digest);
            }
            return leg.status() < 400 ? Outcome.HIT : Outcome.ERROR;   // a body streamed, or a 503/5xx committed
        }
        // The leg answered a 404. The upstream itself either had nothing (a genuine MISS) or served 200 that the screen
        // withheld (a REFUSED that must NOT fall through to a weaker fallback).
        return probe.served() ? Outcome.REFUSED : Outcome.MISS;
    }

    /**
     * Record the {@code origin} acquisition row for a served fallback fetch into the coordinate's consolidated metadata
     * document, the sibling of the hardened leg's {@code verdict} record. Written to {@code records} - the
     * durable per-repository store even on a no-store leg (the "durable records beside transient bytes" pattern), so a
     * no-store fallback's origin row survives though its blob does not; for an ungated pass-through {@code records} is
     * the throwaway scratch, so origin lands wherever the durable records do (the store is never touched by an ungated
     * pass-through).
     *
     * <p><b>First acquisition synchronous, refreshes coalesced (§9/§7).</b> The first serve of a given
     * {@code (path, sha256)} - and every digest change (a new sha256 appends a new row, the visible drift trail) - is
     * written synchronously here, never swallowed. A repeated no-copy serve of the <em>same</em> bytes only refreshes
     * the row's {@code lastServed}/{@code serves}, and is coalesced to at most once per key per day (the
     * {@code BatchingDownloadTracker} day-granular discipline) so a hot pass-through never CAS-storms one doc key: a
     * same-day repeat returns early without reading or writing the document. The refresh is best-effort - a failure is
     * logged (never silent, §9) and never fails the already-served response.
     */
    private void recordFallbackOrigin(String tenant, String repository, int fallbackIndex, RepositoryDefinition.Fallback fallback,
                                      String target, String path, ArtifactStore records, String sha256) {
        if (records == null) {
            return;
        }
        MetadataStore metadata = metadataOver.apply(records);
        if (metadata == null) {
            return;                       // no metadata persistence module - origin has nowhere durable to live
        }
        String key = tenant + '|' + repository + '|' + path + '|' + sha256;
        LocalDate today = LocalDate.ofInstant(Instant.now(), ZoneOffset.UTC);
        // Coalesce same-day refreshes of the SAME (key) so a hot pass-through does not CAS-storm one doc key (§7): a key
        // already refreshed today is skipped without touching the document. A never-seen key (a first acquisition or a
        // digest change - both a new (key)) is always written synchronously below (§9).
        originRefreshed.entrySet().removeIf(entry -> !today.equals(entry.getValue()));   // bound to a single day's keys
        if (today.equals(originRefreshed.putIfAbsent(key, today))) {
            return;
        }
        HardenedScreen.Coordinate coordinate = HardenedScreen.originCoordinate(records, path);
        String screening = switch (fallback.screening()) {
            case DEFAULT -> "default";
            case HARDEN -> "harden";
            case UNSCREENED -> "unscreened";
        };
        try {
            metadata.mutate(coordinate.ecosystem(), coordinate.coordinate(), coordinate.version(),
                    OriginSection.TAG, OriginSection.recordFallback(repository, fallbackIndex, target,
                            sha256, fallback.store(), screening, Instant.now()));
        } catch (IOException | RuntimeException e) {
            // Best-effort refresh/first-write: the bytes are already served, so a record failure is logged (never
            // silent, §9) rather than failing the serve - it only costs a re-record on the next non-coalesced serve.
            originRefreshed.remove(key);   // let the next serve retry rather than treating a failed write as done
            LOGGER.warn("Could not record the fallback origin row for " + path + " from " + target, e);
        }
    }

    /** The upstream fetcher for one fallback, wrapped per the fallback's {@code screening} policy so a fetched artifact
     *  is screened before it is cached or served: a non-{@code ALLOW} verdict becomes an empty result, so the
     *  pull-through treats it as a miss and the artifact never reaches the build (and the {@link UpstreamProbe} below
     *  the screen records that the upstream nonetheless served it, so the walk classifies the swallowed 404 as a
     *  REFUSED, not a MISS). DEFAULT screens through the serving tenant's gate (unwrapped if the tenant is ungated);
     *  HARDEN is full-body fail-closed; UNSCREENED is the explicit, loudly-warned no-screen opt-out. The
     *  screen's durable records (the {@code /quarantine} pointer and the {@code QuarantineLog} row) are written to
     *  {@code records} - the real per-repository store even on the {@code nocache} leg, never the throwaway scratch. */
    private ProxyFormat.Fetcher screening(String tenant, String path, ArtifactStore records, RepositoryDefinition.Fallback fallback,
                                          ArtifactStore spool, ProxyFormat.Fetcher raw) {
        ComplianceGate active = proxyGate(tenant);
        if (fallback.screening() == RepositoryDefinition.Screening.HARDEN) {
            // Selected-but-unsatisfiable stays loud (§9, the store=s3-without-module precedent): a hardened fallback is
            // an explicit opt-in to full screening, so a missing screening gate must throw at resolution naming what
            // is absent - never a silent fallback to unscreened proxying of an untrusted upstream.
            if (active == null) {
                throw new IllegalStateException("Repository proxy is declared 'harden' (untrusted-upstream full "
                        + "screening enforced) but no compliance screening is installed for tenant '" + tenant
                        + "' - a hardened proxy must never silently fall back to serving an untrusted upstream "
                        + "unscreened. Enable the compliance screening gate for this tenant, or drop 'harden' from the "
                        + "proxy definition.");
            }
            // The digest-pinned verdict is recorded in the consolidated metadata document over the same durable
            // per-repository store the QuarantineLog lives in, and - on a store-on-pass leg - reused from it. A
            // deployment without the metadata persistence module has no provider, so the leg records/reuses nothing and
            // screens every fetch.
            //
            // A transient `harden nocache` leg (store=false) still records the verdict for audit and keeps drift
            // detection, but does NOT reuse a recorded ALLOW to skip screening: nothing durable is cached, so every
            // fetch re-screens the full body rather than reuse-serving. Verdict reuse is enabled only on the
            // store-on-pass leg, where a durably cached copy is the trusted backing the reuse dedups against.
            MetadataStore metadata = metadataOver.apply(records);
            return new HardenedScreen(active, records, holdDays.getAsInt(), spool, metadata, hardeningBounds,
                    fallback.store()).wrap(raw, path);
        }
        if (fallback.screening() == RepositoryDefinition.Screening.UNSCREENED || active == null) {
            // UNSCREENED is the explicit, loudly-warned (at parse) opt-out; an ungated tenant likewise has no screen.
            // Either way the fetched body is served without a compliance screen - the honest name for what an ungated
            // proxy already did by omission.
            return raw;
        }
        return new ProxyScreen(active, records, holdDays.getAsInt()).wrap(raw, path);
    }

    /** Run one upstream fallback's pull-through: the fetched body passes through {@code body} (the durable cache on a
     *  caching fallback, the throwaway scratch on a {@code nocache} pass-through), while the gate's durable records go
     *  to {@code records} (always the real per-repository store). The raw fetcher is wrapped first by the
     *  {@link UpstreamProbe} (to observe the real upstream status below the screen) and then by the fallback's
     *  {@link #screening} policy. A proxy-capable format runs the shared streaming {@link PullThroughCache}; any other
     *  format handles the request against the body store. */
    private void pullThrough(String tenant, RepositoryFormat format, RepositoryDefinition.Fallback fallback, URI upstream,
                             FormatExchange exchange, ArtifactStore body, ArtifactStore records, ArtifactStore spool,
                             UpstreamProbe probe) throws IOException {
        if (format instanceof ProxyFormat proxy) {
            // Unify both #79 legs through the pull-through seam: the raw probe is handed to the cache, and
            // the fallback's screening()-composed fetcher is injected on the MISS leg via HardenedHitVerify.screenFetch
            // (one screening decorator, applied once - the eager screening() call still fails loud for a hardened leg
            // with no gate). On the HIT leg the same hooks verify a hardened cache hit fail-closed before it serves; a
            // non-hardened fallback serves through (today's withheld-pointer retraction). This body store is usually the
            // same durable store resolve()'s step-1 local-first already hit-verified, so this leg's verify is reached
            // only when step-1 missed - idempotent, never a double serve.
            // Composed eagerly for the requested path, so an unsatisfiable hardened leg fails at resolution; a format
            // that keeps its answer under another path (ProxyFormat.keptAs) is screened under that one.
            ProxyFormat.Fetcher screened = screening(tenant, exchange.path(), records, fallback, spool, probe);
            Function<String, ProxyFormat.Fetcher> screen = path -> path.equals(exchange.path()) ? screened
                    : screening(tenant, path, records, fallback, spool, probe);
            HardenedHitVerify hooks = new HardenedHitVerify(fallback.screening() == RepositoryDefinition.Screening.HARDEN,
                    gates(tenant), holdDays.getAsInt(), hardeningBounds, passThrough, metadataOver, screen);
            new PullThroughCache(probe, hooks).serve(format, proxy, upstream, exchange, body);
        } else {
            format.handle(exchange, body);
        }
    }

    /** Reclaim a pass-through scratch store once its request is served; a cleanup failure never masks the served
     *  result. A store that is not {@link AutoCloseable} (an injected test scratch) needs no reclamation. */
    private static void close(ArtifactStore store) {
        if (store instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception _) {
                // best-effort scratch reclamation
            }
        }
    }

    /** The raw upstream fetcher wrapped to observe whether the upstream actually served the artifact ({@code 200}),
     *  sitting <b>below</b> the screen so it sees the real upstream status the screen may then withhold. It streams
     *  unchanged - being a decorator it is never a {@link ProxyFormat.Fetcher.Buffered}, so all three legs
     *  ({@code fetch}, {@code download} and {@code head}) delegate to the real fetcher's own implementations rather
     *  than to a derivation that would buffer an artifact or open a body to answer a metadata question (§1), recording
     *  only the observed status. A {@code head} that saw {@code 200} counts as served: the upstream <em>has</em> the
     *  artifact, and a screen above may still withhold that answer, which is exactly the REFUSED-not-MISS distinction
     *  this probe exists to draw - a screen-withheld metadata answer must not fall through to a weaker fallback. It
     *  does not record the {@link #url()} though: that names where the served <em>bytes</em> came from for the origin
     *  row, and a {@code HEAD} downloads none (origin follows the bytes). The walk reads
     *  {@link #served()} to tell a genuine upstream miss (never {@code 200}) from a refusal (the upstream served
     *  {@code 200} but the wire ended a {@code 404} because the screen withheld it) - the discriminator the old
     *  boolean 404-sniff lacked. Request-scoped: a fresh probe wraps the fetcher for each upstream fallback leg. */
    private static final class UpstreamProbe implements ProxyFormat.Fetcher {

        private final ProxyFormat.Fetcher delegate;
        private boolean served;
        private String url;

        private UpstreamProbe(ProxyFormat.Fetcher delegate) {
            this.delegate = delegate;
        }

        @Override
        public Optional<ProxyFormat.Fetched> fetch(URI url, Map<String, String> requestHeaders) throws IOException {
            Optional<ProxyFormat.Fetched> fetched = delegate.fetch(url, requestHeaders);
            if (fetched.isPresent() && fetched.get().status() == 200) {
                served = true;
            }
            return fetched;
        }

        @Override
        public Optional<ProxyFormat.Head> head(URI url, Map<String, String> requestHeaders) throws IOException {
            Optional<ProxyFormat.Head> head = delegate.head(url, requestHeaders);
            if (head.isPresent() && head.get().status() == 200) {
                // The upstream HAS the artifact: a screen above may withhold this metadata answer, and the walk must
                // read that as a REFUSED rather than a MISS so it does not fall through to a weaker fallback. The
                // origin URL is deliberately NOT recorded here - it names where the served bytes were downloaded from,
                // and a HEAD downloads none.
                served = true;
            }
            return head;
        }

        @Override
        public Optional<ProxyFormat.Download> download(URI url, Map<String, String> requestHeaders) throws IOException {
            Optional<ProxyFormat.Download> opened = delegate.download(url, requestHeaders);
            if (opened.isPresent() && opened.get().status() == 200) {
                served = true;
                this.url = url.toString();   // the FULL upstream artifact URL - the origin row's target, aligned with
                                             // the verdict's source (both name the same fetched URL)
            }
            return opened;
        }

        private boolean served() {
            return served;
        }

        /** The full upstream artifact URL the served body was downloaded from, or {@code null} when the leg served
         *  nothing through {@code download} - the origin row's {@code target}, reconciled with the verdict's {@code source}. */
        private String url() {
            return url;
        }
    }

    /** A {@link FormatExchange} that defers committing to the real exchange until it sees a leg's status, so a leg's
     *  hit streams straight through with nothing buffered, while a leg's {@code 404} is swallowed (its tiny body
     *  discarded) and reported through {@link #missed()} so the walk moves on to the next fallback. Any other status is
     *  a {@link #committed()} terminal (a served body, or a structural {@code 5xx}/{@code 508}) that streams through
     *  and ends the walk. Response headers are held until the commit; reads delegate to the real exchange unchanged. */
    private static final class Deferred implements FormatExchange {

        private final FormatExchange delegate;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private boolean missed;
        private boolean committed;
        private int status = -1;

        private Deferred(FormatExchange delegate) {
            this.delegate = delegate;
        }

        @Override
        public String method() {
            return delegate.method();
        }

        @Override
        public String path() {
            return delegate.path();
        }

        @Override
        public String requestUri() {
            return delegate.requestUri();
        }

        @Override
        public String external(String formatPath) {
            return delegate.external(formatPath);
        }

        @Override
        public String scheme() {
            return delegate.scheme();
        }

        @Override
        public String queryParameter(String name) {
            return delegate.queryParameter(name);
        }

        @Override
        public String requestHeader(String name) {
            return delegate.requestHeader(name);
        }

        @Override
        public String setting(String key) {
            // Delegate deployment toggles straight through: a format served through a fallback-walk leg (a group member
            // recursion, a screened/spool leg) must read the operator's configured value, not the FormatExchange default
            // null. Without this a walked leg silently sees the shipped default for every setting the direct-serve leg
            // reads from the servlet environment (maven-metadata-compute, npm rewrite toggles, ...).
            return delegate.setting(key);
        }

        @Override
        public InputStream requestStream() throws IOException {
            return delegate.requestStream();
        }

        @Override
        public void setResponseHeader(String name, String value) {
            // Pre-commit: buffer, so headers set before a leg's status is known are replayed onto the real exchange on a
            // hit (respond) and dropped with the swallowed body on a 404. Post-commit: respond already ran and flushed the
            // buffered map, so a late header must go straight to the delegate - buffering it here would silently drop it.
            if (committed) {
                delegate.setResponseHeader(name, value);
            } else {
                headers.put(name, value);
            }
        }

        @Override
        public OutputStream respond(int status, long contentLength) throws IOException {
            this.status = status;
            if (status == 404) {
                missed = true;
                return OutputStream.nullOutputStream();
            }
            committed = true;
            headers.forEach(delegate::setResponseHeader);
            return delegate.respond(status, contentLength);
        }

        private boolean missed() {
            return missed;
        }

        /** Whether this leg committed a real (non-{@code 404}) response that streamed through to the client. */
        private boolean committed() {
            return committed;
        }

        /** The status this leg answered ({@code -1} if it never responded). */
        private int status() {
            return status;
        }
    }
}
