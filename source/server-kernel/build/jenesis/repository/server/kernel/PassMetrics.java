package build.jenesis.repository.server.kernel;

import module java.base;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;

/**
 * The maintenance scheduler's <em>meter sink</em>, split out of {@link MaintenanceScheduler} so the one Micrometer-aware
 * piece of the maintenance kernel is a single object (the maintenance-kernel spike's R9/R10).
 *
 * <p>This is the boundary the spike measured and deliberately left in place: <strong>no maintenance task imports
 * Micrometer</strong> - a pass reports through the framework-free
 * {@code RepositoryContext.gauge/counter(String name, String description, Map<String,String> tags, double value)} seam,
 * and this class is the only place those rows become {@link MultiGauge} rows and {@link Counter} increments. Splitting
 * it out keeps that boundary explicit rather than diffuse, and keeps the rest of the split ({@link TaskSchedule},
 * {@link LeaseGuard}) free of the meter dependency.
 *
 * <h2>Contract</h2>
 * <ol>
 *   <li><b>Absence sentinel.</b> A {@code null} registry is legal and inert - a deployment (or a test) with no meter
 *       registry wired records nothing here and still runs every pass. Failure <em>visibility</em> does not depend on
 *       it: {@link TaskSchedule#failed(String)} carries the per-task status independently, so a registry-less
 *       deployment still reports a failed pass through the observability seam.</li>
 *   <li><b>Thread-safety.</b> A {@link Sink} is written concurrently by the fanned-out units of one pass and is
 *       synchronised accordingly; {@link #flush(Sink)} is synchronised on the owning {@code PassMetrics} because the
 *       {@link MultiGauge} registry is shared across passes.</li>
 *   <li><b>Ordering.</b> A gauge family is replaced <em>wholesale</em> per pass, so an evicted repository drops off the
 *       dashboard instead of serving its last value forever; a counter accumulates and is incremented straight onto the
 *       registry rather than collected and flushed.</li>
 *   <li><b>Bounded work.</b> Meter names and tag keys come from the tasks, whose names are a small fixed set; the
 *       {@code task} tag on the failure counter is therefore bounded, as &sect;3 requires of a {@code jenreg.*} meter.</li>
 * </ol>
 */
public final class PassMetrics {

    private final MeterRegistry registry;
    private final Map<String, MultiGauge> gauges = new HashMap<>();

    PassMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** Count one failed pass or unit, so a sweep that keeps failing is visible on the dashboard and not only in the
     *  log. The task names are a small, fixed set, so the tag is bounded. */
    void failure(String task) {
        if (registry == null) {
            return;
        }
        Counter.builder("jenreg.maintenance.failures")
                .description("Maintenance passes or (tenant, repository) units that failed and were skipped")
                .tag("task", task)
                .register(registry)
                .increment();
    }

    /** A fresh collector for one pass's gauge rows; the pass's units write into it concurrently and the scheduler
     *  {@link #flush(Sink) flushes} it once the pass has settled. */
    Sink sink() {
        return new Sink();
    }

    /** Publish a pass's collected gauge rows, replacing each named family's previous rows wholesale so evicted
     *  repositories drop off the dashboard rather than serving stale values. */
    synchronized void flush(Sink sink) {
        if (registry == null) {
            return;
        }
        Map<String, List<MultiGauge.Row<?>>> byName = new LinkedHashMap<>();
        Map<String, String> descriptions = new HashMap<>();
        for (GaugeRow row : sink.rows()) {
            byName.computeIfAbsent(row.name(), _ -> new ArrayList<>()).add(row.row());
            descriptions.putIfAbsent(row.name(), row.description());
        }
        byName.forEach((name, gaugeRows) -> gauges.computeIfAbsent(name, missing ->
                        MultiGauge.builder(missing).description(descriptions.get(missing)).register(registry))
                .register(gaugeRows, true));
    }

    private record GaugeRow(String name, String description, MultiGauge.Row<?> row) {
    }

    /** One pass's meter sink, handed to every {@code RepositoryContext} the pass builds. Gauges are collected and
     *  flushed wholesale when the pass settles; counters accumulate, so they go straight onto the registry. */
    final class Sink {

        private final List<GaugeRow> rows = Collections.synchronizedList(new ArrayList<>());

        private Sink() {
        }

        private List<GaugeRow> rows() {
            synchronized (rows) {
                return List.copyOf(rows);
            }
        }

        void gauge(String name, String description, Map<String, String> tags, double value) {
            rows.add(new GaugeRow(name, description, MultiGauge.Row.of(tags(tags), value)));
        }

        void counter(String name, String description, Map<String, String> tags, double amount) {
            if (registry == null) {
                return;
            }
            Counter.builder(name).description(description).tags(tags(tags)).register(registry).increment(amount);
        }

        private static Tags tags(Map<String, String> tags) {
            Tags converted = Tags.empty();
            for (Map.Entry<String, String> tag : tags.entrySet()) {
                converted = converted.and(tag.getKey(), tag.getValue());
            }
            return converted;
        }
    }
}
