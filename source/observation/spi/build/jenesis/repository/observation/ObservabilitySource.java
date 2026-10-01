package build.jenesis.repository.observation;

import module java.base;

/**
 * The seam a plugin reports its signals through: {@link #healthChecks()}, {@link #metrics()} and
 * {@link #taskStatuses()}, each empty by default. Discovered with {@link ServiceLoader}; a disabled or absent plugin
 * contributes nothing. The signals are self-describing and registry-free: the distribution bridges the
 * {@link ObservabilityReport} onto Actuator and the console, so a plugin never touches Micrometer.
 *
 * <h2>Contract</h2>
 * <ol>
 *   <li><b>Thread-safety.</b> All three may be called concurrently and repeatedly - a scrape, a render and a docs run
 *       can overlap - so an implementation is an effectively immutable view over computed state.</li>
 *   <li><b>Absence sentinel.</b> An empty list, never {@code null} or an exception, when the plugin reports nothing or
 *       is off.</li>
 *   <li><b>Selection failure.</b> Nothing to select: every discovered source is collected. A source has no
 *       {@code name()}, so the {@code Providers} packaging guards do not apply and a module registered twice
 *       contributes its signals twice; that case is open until an additive SPI has a naming rule. The one discovery
 *       site is {@link ObservabilityReport#discover()}; a consumer controlling the set uses
 *       {@link ObservabilityReport#from}.</li>
 *   <li><b>Tenant scoping.</b> The report is deployment-global and served to an operator, so no signal carries tenant
 *       content or a per-tenant identifier; a per-tenant plugin reports a roll-up here.</li>
 *   <li><b>Read purity.</b> Read-path methods: no external fetch, scan, store write or blocking I/O. A health check
 *       reports what the last refresh recorded, so the overview stands when its source is down.</li>
 *   <li><b>Staleness.</b> A refreshed signal carries its freshness: a {@link TaskStatus} its {@code lastRun} (null when
 *       never) and {@code outcome}, a {@link HealthCheck} its last refresh in {@code detail}.</li>
 *   <li><b>Error visibility.</b> A throw is contained to this source: {@link ObservabilityReport#from} collects through
 *       {@code Contributions}, so it becomes one {@link Health#UNKNOWN} check named
 *       {@code jenrepo.observation.unavailable.<source>} with only the class and exception type, logged once, and drops
 *       {@link ObservabilityReport#overall} to {@code UNKNOWN}. An implementation that cannot determine a signal
 *       reports it itself as {@link Health#UNKNOWN} or {@link TaskStatus.State#UNKNOWN} with a detail, since only it
 *       knows which signal and why. Detail text never carries a secret or tenant content. An {@link Error} is not
 *       contained.</li>
 *   <li><b>Lifecycle / ownership.</b> A discovered source is loaded and read, never closed, so it owns no thread,
 *       client or scheduler. An owned source is a component its context built and closes.</li>
 *   <li><b>Where the state comes from.</b> A discovered source is stateless and reads what this node recorded, such as
 *       the store's operation counts. An owned source is reported from the context that built it
 *       ({@link ObservabilityReport#of}). Nothing hands an instance to a discovered source through a static: two
 *       contexts in one JVM would report each other's.</li>
 *   <li><b>Ordering / concurrency.</b> The report sorts by signal name, so it is independent of discovery order. Names
 *       are unique across plugins ({@code jenrepo.<feature>.<signal>}); a duplicate is a collision, not a merge.</li>
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
