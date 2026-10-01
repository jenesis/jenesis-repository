package build.jenesis.repository.health;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Providers;

/**
 * Discovers the durable health ledger and binds it to a repository's scoped store, so writers (the sweep, the
 * publish-time persistence, a rescan) and readers (the gate, the API, the console) reach it without depending on the
 * persistence module ({@code build.jenesis.repository.health.store}). Without one {@link #installed()} is empty and
 * everything degrades: the sweep records nothing, {@code /api/health} answers that the store is absent, and the gate
 * falls back to the live source. Stateless: one instance serves every tenant and repository.
 *
 * <h2>Contract</h2>
 * <ol>
 *   <li><b>Thread-safety.</b> {@link #over} is called per request and per sweep, concurrently, so the provider and the
 *       {@link HealthLedger} it returns are thread-safe.</li>
 *   <li><b>Idempotency / replay.</b> Re-recording converges on one record per {@code (ecosystem, coordinate)}.</li>
 *   <li><b>Absence sentinel.</b> {@link #installed()} answers an empty {@link Optional} with no persistence module
 *       installed. {@code null} is never returned.</li>
 *   <li><b>Selection failure.</b> No key selects a ledger, so the one failure is ambiguity: two installed providers
 *       make {@link #installed()} throw naming both, through the shared {@link Providers#singleton}.</li>
 *   <li><b>Tenant scoping.</b> The caller hands in an already-scoped store; the ledger touches nothing outside it.</li>
 *   <li><b>Read purity.</b> Reading renders stored verdicts only, never probing the live source.</li>
 *   <li><b>Staleness.</b> The {@link HealthLedger#scanned health stamp} records the last refresh, and every
 *       {@link HealthLedger#worstFirst} answer carries its as-of instant in both states.</li>
 *   <li><b>Lifecycle / ownership.</b> The caller resolves the provider once and calls {@link #over} per request;
 *       {@link #installed()} caches and closes nothing.</li>
 *   <li><b>Ordering / determinism.</b> Which provider answers depends on what is installed, never on discovery
 *       order.</li>
 *   <li><b>Bounded work / cancellation.</b> A returned ledger streams {@link HealthLedger#all} to a
 *       {@code LedgerVisitor} without materialising the scored set, and serves {@link HealthLedger#worstFirst} from a
 *       committed ranking. The inherited stream default refuses past {@link ArtifactStore#MAX_INHERITED_CHILDREN}
 *       records; {@code worstFirst} has no fallback - with no committed ranking it reports {@code Ranking.NotBuilt},
 *       never a bounded sort whose "worst" is that of an arbitrary prefix.</li>
 * </ol>
 */
public interface HealthLedgerProvider {

    /** Bind the durable health ledger to one repository's scoped store. */
    HealthLedger over(ArtifactStore store);

    /** The installed provider, through the shared {@link Providers#singleton}: empty with no persistence module, and a
     *  second installed provider throws rather than letting module-path order decide which ledger the gate reads. */
    static Optional<HealthLedgerProvider> installed() {
        return Providers.singleton("health-ledger", ServiceLoader.load(HealthLedgerProvider.class));
    }
}
