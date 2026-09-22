package build.jenesis.repository.health;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Providers;

/**
 * Discovers the durable health ledger and binds it to a repository's scoped store, so writers (the health sweep, the
 * publish-time persistence, an explicit rescan) and readers (the compliance gate, the API, the console) reach the
 * persisted health through {@code ServiceLoader} rather than a compile-time dependency on the persistence module. A
 * deployment that carries {@code build.jenesis.repository.health.store} provides one; a deployment without it has none,
 * so {@link #installed()} is empty and everything degrades - the sweep records nothing, the {@code /api/health} surface
 * answers that the store is absent, and the compliance gate falls back to the live health source. The provider is
 * stateless: it takes the per-request scoped store on each call, so a single instance serves every tenant and
 * repository.
 *
 * <h2>Contract</h2>
 * <ol>
 * <li><b>Thread-safety.</b> One provider instance serves the whole deployment: {@link #over} is called per request
 *     and per sweep, concurrently, so the provider and the {@link HealthLedger} it returns must both be
 *     thread-safe.</li>
 * <li><b>Idempotency / replay.</b> Re-recording a project's health converges on one record per
 *     {@code (ecosystem, coordinate)} pair rather than accumulating rows - the scheduled sweep, the publish-time
 *     persistence and an explicit rescan all write the same key.</li>
 * <li><b>Absence sentinel.</b> {@link #installed()} answers an empty {@link Optional} when no persistence module is
 *     on the module path; the sweep then records nothing, the endpoint answers that the store is absent, and the
 *     gate falls back to the live health source. {@code null} is never a legal return.</li>
 * <li><b>Selection failure (&sect;9).</b> This SPI has <em>no</em> selection key - nothing names a ledger by name -
 *     so there is no explicitly-selected miss to fail on. The one resolution failure is ambiguity: two installed
 *     providers would make module-path order decide which ledger the gate reads, so {@link #installed()}
 *     <em>throws</em> naming both rather than picking a discovery-order winner. Resolution runs through the shared
 *     {@link Providers#optionalUnique} primitive, never a hand-rolled loop.</li>
 * <li><b>Tenant scoping (&sect;6).</b> The provider never resolves a tenant: the caller hands in an already-scoped
 *     store and the ledger reads and writes nothing outside it.</li>
 * <li><b>Read purity (&sect;10).</b> Reading the ledger renders stored verdicts only - it never probes the live
 *     health source. Refreshing is the sweep's or an explicit rescan's job.</li>
 * <li><b>Staleness.</b> A {@link HealthLedger#scanned health stamp} records the last refresh instant, so an empty panel is never
 *     ambiguous between "healthy" and "never scored". Every {@link HealthLedger#worstFirst} answer carries its own
 *     as-of instant in both of its states ({@code Ranking.scannedAt()}) - the instant the ranking was <em>built</em>
 *     at for a ranked page, so an eventually-consistent ranking is never shown fresher than it is, and the ledger's
 *     last sweep for the not-built state, so "swept but not yet ranked" reads differently from "nothing has ever run
 *     here".</li>
 * <li><b>Lifecycle / ownership.</b> The caller resolves the provider once and calls {@link #over} per request;
 *     {@link #installed()} caches nothing and closes nothing.</li>
 * <li><b>Ordering / determinism.</b> Which provider {@link #installed()} answers is a function of what is
 *     installed, never of discovery order.</li>
 * <li><b>Bounded work / cancellation.</b> A ledger this provider returns must stream {@link HealthLedger#all} to a
 *     {@code LedgerVisitor} without materialising the scored set - the obligation that lets a rank-index rebuild
 *     fold over millions of coordinates - and serve {@link HealthLedger#worstFirst} from a ranking a pass committed.
 *     The inherited stream default is a small-ledger fallback whose bound is <em>visible</em>: it refuses past
 *     {@link ArtifactStore#MAX_INHERITED_CHILDREN} records with an {@link IllegalStateException} naming the class
 *     and the override, rather than buffering the whole ledger behind a streaming promise. {@code worstFirst} has no
 *     such fallback at all: with no committed ranking it reports {@code Ranking.NotBuilt} rather than deriving one on
 *     the request thread, so the ranked read is bounded by construction and never by a ceiling on a whole-ledger
 *     sort (a bounded sample would be the worst of an arbitrary prefix wearing a "worst overall" label - &sect;9's
 *     silent fallback, and what a ceiling here would have preserved).</li>
 * </ol>
 */
public interface HealthLedgerProvider {

    /** Bind the durable health ledger to one repository's scoped store. */
    HealthLedger over(ArtifactStore store);

    /** The installed provider discovered via {@link ServiceLoader}, resolved through the shared
     *  {@link Providers#optionalUnique} policy: empty when no persistence module is present, and a <em>second</em>
     *  installed provider throws rather than letting module-path order decide which ledger the gate reads. */
    static Optional<HealthLedgerProvider> installed() {
        return Providers.singleton("health-ledger", ServiceLoader.load(HealthLedgerProvider.class));
    }
}
