package build.jenesis.repository.metadata.store;

import module java.base;
import module org.slf4j;
import build.jenesis.repository.observation.Metric;
import build.jenesis.repository.observation.ObservabilitySource;

/**
 * The consolidated metadata store's observability signals, accumulated registry-free and reported through the
 * discovered {@link ObservabilitySource} seam (the distribution bridges them onto Actuator and the console; this
 * module never touches Micrometer). One process-wide {@link #SHARED} instance carries the counters every
 * {@link StoreMetadata} bumps; a test injects its own instance for a deterministic assertion.
 *
 * <ul>
 *   <li><strong>{@code jenreg.metadata.document.bytes}</strong> - the largest document observed on a write,
 *       measured against a soft ceiling ({@link #SOFT_SIZE_LIMIT}) past which a mutate logs a WARNING. The document
 *       rewrites whole on every CAS, so growth costs latency and CPU, never billed request-bytes - a
 *       doc-growth guard, a signal not an enforced limit.</li>
 *   <li><strong>{@code jenreg.metadata.cas.retries}</strong> - section-scoped CAS commits re-read and retried
 *       after a concurrent writer won the token: contention on the shared per-version document, converged by
 *       re-applying the section transform.</li>
 *   <li><strong>{@code jenreg.metadata.cas.exhausted}</strong> - mutations that lost the write race the full
 *       retry bound and failed to their caller.</li>
 * </ul>
 */
public final class MetadataMetrics implements ObservabilitySource {

    /** The process-wide instance {@link StoreMetadata} reports into by default. */
    public static final MetadataMetrics SHARED = new MetadataMetrics();

    /** The soft ceiling (bytes) past which a mutate logs a WARNING - a guard signal, never enforced. */
    public static final int SOFT_SIZE_LIMIT = 64 * 1024;

    private static final Logger LOGGER = LoggerFactory.getLogger(MetadataMetrics.class);

    private final LongAdder mutations = new LongAdder();
    private final LongAdder retries = new LongAdder();
    private final LongAdder exhausted = new LongAdder();
    private final AtomicLong largestBytes = new AtomicLong();

    public MetadataMetrics() {
    }

    void recordMutation() {
        mutations.increment();
    }

    void recordRetry() {
        retries.increment();
    }

    void recordExhausted() {
        exhausted.increment();
    }

    void observeDocument(String key, int bytes) {
        largestBytes.accumulateAndGet(bytes, Math::max);
        if (bytes > SOFT_SIZE_LIMIT) {
            LOGGER.warn("Metadata document " + key + " is " + bytes + " bytes, past the " + SOFT_SIZE_LIMIT
                    + "-byte soft guard - every mutate rewrites it whole, so review whether a section is "
                    + "accumulating unbounded rows");
        }
    }

    /** Committed section mutations (a successful CAS write). */
    public long mutations() {
        return mutations.sum();
    }

    /** CAS commits re-read and retried after a token conflict. */
    public long retries() {
        return retries.sum();
    }

    /** Mutations that exhausted the retry bound and failed. */
    public long exhausted() {
        return exhausted.sum();
    }

    /** The largest document (bytes) observed on a write. */
    public long largestBytes() {
        return largestBytes.get();
    }

    @Override
    public List<Metric> metrics() {
        return List.of(
                Metric.bounded("jenreg.metadata.document.bytes",
                        "The largest consolidated metadata document observed on a write, against the soft size "
                                + "guard past which a mutate logs a warning (the document rewrites whole on every "
                                + "CAS, so growth is latency and CPU, never billed request-bytes).",
                        largestBytes.get(), SOFT_SIZE_LIMIT, "bytes"),
                Metric.counter("jenreg.metadata.cas.retries",
                        "Section-scoped CAS commits re-read and retried after a concurrent writer won the token - "
                                + "contention on the shared per-version document, converged by re-applying the "
                                + "section transform.",
                        retries.sum(), ""),
                Metric.counter("jenreg.metadata.cas.exhausted",
                        "Metadata mutations that lost the write race the full retry bound and failed to their "
                                + "caller - a sustained-contention signal, not a benign single conflict.",
                        exhausted.sum(), ""));
    }
}
