package build.jenesis.repository.observation;

import module java.base;

/**
 * The seam a plugin reports its observability signals through: {@link #healthChecks()}, {@link #metrics()} and
 * {@link #taskStatuses()}, each defaulting to empty so a provider adopts only what it has - the same optional
 * default-method pattern its provider SPI already uses for {@code requiredConfig()}. A plugin (or its provider) implements
 * this and is discovered with {@link ServiceLoader}; a <em>disabled or absent</em> plugin contributes an empty source, or
 * none at all, so the overview never lists a signal for something that is not running.
 *
 * <p>The signals are self-describing (name + description) and registry-free: the distribution collects the sources into an
 * {@link ObservabilityReport} and bridges them onto Actuator and the console, so the plugin never touches Micrometer.
 *
 * <h2>Contract</h2>
 * <ol>
 *   <li><b>Thread-safety.</b> All three methods may be called concurrently and repeatedly - an Actuator scrape, a
 *       console overview render and a docs generation can overlap - so an implementation reads shared state without a
 *       lock or guards it itself. Implementations are effectively immutable views over already-computed state.</li>
 *   <li><b>Absence sentinel.</b> Every method returns an empty list, never {@code null} and never an exception, when
 *       the plugin reports nothing or is switched off. A disabled plugin contributes an empty source (or none at all)
 *       rather than a signal that reads as healthy; the report then simply does not list it.</li>
 *   <li><b>Read purity.</b> These are read-path methods (PRINCIPLES §10): they render state the plugin has already
 *       computed and must perform no external fetch, no scan, no store write and no blocking I/O. A health check
 *       reports what the last refresh recorded, so the overview still stands when the source it describes is down.</li>
 *   <li><b>Staleness.</b> A signal derived from a periodic refresh carries its own freshness rather than leaving an
 *       empty panel ambiguous between "clean" and "never scanned": a {@link TaskStatus} states {@code lastRun} (null
 *       when it has never run) and {@code outcome}, and a {@link HealthCheck} covering a refreshed source puts the
 *       last-refresh instant in its {@code detail}.</li>
 *   <li><b>Error visibility.</b> A throw propagates to the caller and degrades the whole collected report, so an
 *       implementation that cannot determine a signal reports it as {@link Health#UNKNOWN} (or
 *       {@link TaskStatus.State#UNKNOWN}) with a plain-text detail instead of throwing. Detail text is operator-facing
 *       and never carries a secret, a credential or a tenant's artifact content.</li>
 *   <li><b>Lifecycle / ownership.</b> The distribution owns the lifecycle: {@link ObservabilityReport#discover()}
 *       loads the sources through {@link ServiceLoader} each time, so instances are created, read and discarded -
 *       never cached, never closed. A source must not own a thread, a client or a scheduler; it observes something
 *       else's.</li>
 *   <li><b>Ordering / concurrency.</b> Results must be deterministic and independent of discovery order:
 *       {@link ObservabilityReport} concatenates the sources and sorts by signal name, so two deployments with the
 *       same plugins render the same report whatever order the module path yields. Signal names are stable and unique
 *       across plugins ({@code jenesis.<feature>.<signal>}, validated at construction) - a duplicate name is a
 *       collision between plugins, not a merge.</li>
 * </ol>
 */
public interface ObservabilitySource {

    /** The health checks this plugin reports; empty (the default) when it has none. */
    default List<HealthCheck> healthChecks() {
        return List.of();
    }

    /** The metrics this plugin reports; empty (the default) when it has none. */
    default List<Metric> metrics() {
        return List.of();
    }

    /** The background tasks this plugin reports the status of; empty (the default) when it runs none. */
    default List<TaskStatus> taskStatuses() {
        return List.of();
    }
}
