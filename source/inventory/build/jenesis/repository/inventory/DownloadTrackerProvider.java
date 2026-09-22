package build.jenesis.repository.inventory;

import module java.base;
import build.jenesis.repository.store.Providers;

/**
 * A named factory for a {@link DownloadTracker}, discovered at runtime with {@link ServiceLoader} - so download
 * tracking is a drop-in module and the composition names no implementation. Each provider reads its own
 * configuration through the {@code config} lookup (a property/setting accessor returning {@code null} when unset)
 * and writes through the {@link Inventories} it is given, staying free of any framework dependency. With no module
 * installed, {@link #resolve} answers {@link DownloadTracker#NONE}: nothing records and the worker reports as off.
 *
 * <h2>Contract</h2>
 * <ol>
 * <li><b>Thread-safety.</b> {@link #name()} is a pure declaration; {@link #create} runs once, on the boot thread.
 *     The {@link DownloadTracker} it returns is called from every serving thread on every download, so <em>that</em>
 *     object must be thread-safe and must not block the response.</li>
 * <li><b>Idempotency / replay.</b> Counting is monotone and additive: a batch flushed twice after a crash may
 *     over-count, which is the documented direction of error, but a lost batch may never make a served artifact
 *     look unserved.</li>
 * <li><b>Absence sentinel.</b> {@link DownloadTracker#NONE} is the sentinel: with no module installed, or with the
 *     installed provider declining, nothing records and the worker reports as off. {@link #create} declares "I
 *     decline" with an empty {@link Optional}; {@code null} is never a legal return from it or from
 *     {@link #name()}. An <em>installed but switched-off</em> tracker is a different state from the sentinel - it
 *     still stands, so a health surface can tell "installed but off" from a dead worker.</li>
 * <li><b>Selection failure (&sect;9).</b> This SPI has <em>no</em> selection key - {@code track-downloads} is the
 *     installed tracker's own on/off dial, not a provider name - so there is no explicitly-selected miss to fail
 *     on. The one resolution failure is ambiguity: two installed providers would make module-path order decide
 *     which tracker counts, so {@link #resolve} <em>throws</em> naming both rather than picking a discovery-order
 *     winner. Resolution runs through the shared {@link Providers#optionalUnique} primitive, never a hand-rolled
 *     loop.</li>
 * <li><b>Tenant scoping (&sect;6).</b> The tracker never resolves a tenant itself: every marker is written through
 *     the {@link Inventories} lookup keyed by the caller's {@code (tenant, repository)} pair.</li>
 * <li><b>Error visibility (&sect;9).</b> Recording is best-effort and its blast radius is bounded to counting: a
 *     lost increment may only under-count a download statistic, and it may never hide a served artifact, change a
 *     hold, or fail the download it observed.</li>
 * <li><b>Durability / delivery.</b> Counts are batched, so the commit point is the batch flush, not the download.
 *     A crash between them loses the un-flushed window - the documented best-effort seam - and the durable source
 *     of truth for what is served is the store, never the counter.</li>
 * <li><b>Lifecycle / ownership.</b> The composition resolves the tracker once and owns it; a tracker may own the
 *     batching worker thread it flushes on and closes it through its own lifecycle. {@link #resolve} builds at most
 *     one instance per call, caches nothing and closes nothing.</li>
 * <li><b>Ordering / determinism.</b> The resolved tracker is a function of what is installed and configured, never
 *     of discovery order.</li>
 * </ol>
 */
public interface DownloadTrackerProvider {

    /** Where markers land: the caller's tenant/repository-scoped inventory lookup. */
    @FunctionalInterface
    interface Inventories {

        StoreRepositoryInventory inventory(String tenant, String repository);
    }

    /** The tracker name this provider answers to, e.g. {@code batching}. */
    String name();

    /** Build the tracker writing into {@code inventories}, reading settings through {@code config}; empty when
     *  off. */
    Optional<DownloadTracker> create(Inventories inventories, UnaryOperator<String> config);

    /** The single installed tracker, resolved through the shared {@link Providers#optionalUnique} policy, or
     *  {@link DownloadTracker#NONE} when no module is installed or the installed one declines. A <em>second</em>
     *  installed provider throws rather than letting module-path order decide which tracker counts. */
    static DownloadTracker resolve(Inventories inventories, UnaryOperator<String> config) {
        return Providers.optionalUnique("download-tracker",
                        ServiceLoader.load(DownloadTrackerProvider.class),
                        DownloadTrackerProvider::name,
                        _ -> true,
                        provider -> provider.create(inventories, config))
                .orElse(DownloadTracker.NONE);
    }
}
