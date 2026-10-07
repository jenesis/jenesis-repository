package build.jenesis.repository.findings;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Providers;

/**
 * Binds the findings ledger to a repository's scoped store, discovered with {@code ServiceLoader} so writers and
 * readers need no compile-time dependency on the persistence module. Without one installed, {@link #installed()} is
 * empty: writers skip persistence, {@code /api/findings} answers that the store is absent, and the vulnerability views
 * fall back to the live feeds.
 *
 * <h2>Contract</h2>
 * <ol>
 * <li><b>Thread-safety.</b> One provider serves the deployment and {@link #over} is called per request, concurrently,
 *     so the provider and its {@link Findings} are thread-safe.</li>
 * <li><b>Idempotency / replay.</b> Recording a finding twice converges on one row; a superseded finding is marked,
 *     not erased.</li>
 * <li><b>Absence sentinel.</b> {@link #installed()} is empty without a persistence module; {@code null} is never
 *     legal.</li>
 * <li><b>Selection failure.</b> Nothing names a ledger, so the only failure is ambiguity: two installed providers
 *     make {@link #installed()} throw naming both, through {@link Providers#singleton}.</li>
 * <li><b>Tenant scoping.</b> The caller hands in a scoped store, and the ledger reads and writes nothing outside
 *     it.</li>
 * <li><b>Staleness.</b> Every row carries its first- and last-seen instants.</li>
 * <li><b>Lifecycle / ownership.</b> {@link #installed()} answers the provider discovered on first use and held for
 *     the process; a caller calls {@link #over} per request. The provider owns whatever the ledger needs closed.</li>
 * <li><b>Bounded work / cancellation.</b> A ledger bounds its own repository-wide reads:
 *     {@link Findings#all(Findings.Filter, int, int)} collects no more than its window and
 *     {@link Findings#all(Findings.Filter, Findings.Visitor)} materialises nothing. The inherited defaults refuse past
 *     {@link ArtifactStore#MAX_INHERITED_CHILDREN} matched rows rather than buffer the ledger.</li>
 * </ol>
 */
public interface FindingsProvider {

    /** Bind the findings ledger to one repository's scoped store. */
    Findings over(ArtifactStore store);

    /** The installed provider, empty without a persistence module; a second installed provider throws. */
    static Optional<FindingsProvider> installed() {
        return Providers.singleton("findings", InstalledFindings.DISCOVERED);
    }
}
