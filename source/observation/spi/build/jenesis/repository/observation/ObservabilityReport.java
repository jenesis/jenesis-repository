package build.jenesis.repository.observation;

import module java.base;

/**
 * The single collected view every consumer reads - the console overview page, the Actuator health/metrics contributors
 * and the reference docs all render <em>this</em>, so a signal is named and described in exactly one place. {@link #from}
 * merges the signals of a set of {@link ObservabilitySource}s (name-sorted for a stable ordering); {@link #discover} does
 * the same over the {@link ServiceLoader}-installed sources; {@link #overall} collapses the health checks into one
 * verdict. A source that reports nothing (a disabled plugin) simply adds nothing - the report degrades gracefully to
 * whatever is actually running.
 *
 * <p>A source that <em>fails</em> degrades too, but visibly: collection runs through {@link Contributions}, so a source
 * that throws is contained to its own rows and replaced by a {@link Health#UNKNOWN} health check named
 * {@code jenesis.observation.unavailable.<source>} - the report stands, every other source is collected, and the
 * failure reaches both the reader and the log instead of degrading the whole overview. {@link #overall} therefore drops
 * to {@code UNKNOWN}: "a source could not determine its state" is exactly the truth about a report one of whose sources
 * threw, and it must never collapse back to {@code UP}.
 */
public record ObservabilityReport(List<HealthCheck> healthChecks, List<Metric> metrics, List<TaskStatus> tasks) {

    public ObservabilityReport {
        healthChecks = List.copyOf(healthChecks);
        metrics = List.copyOf(metrics);
        tasks = List.copyOf(tasks);
    }

    /** Collect and name-sort the signals of {@code sources}; a source that throws contributes {@link #unavailable}
     *  instead of taking the report down with it. */
    public static ObservabilityReport from(Iterable<? extends ObservabilitySource> sources) {
        List<HealthCheck> health = new ArrayList<>();
        List<Metric> metrics = new ArrayList<>();
        List<TaskStatus> tasks = new ArrayList<>();
        // One contained collection per source: its three signal lists are read together, so a source that throws from
        // any of them (or answers null, which List.copyOf turns into a throw here) is one degraded row rather than a
        // half-collected source silently contributing its metrics but not its health.
        for (ObservabilityReport contributed : Contributions.collect("observability source", sources,
                source -> new ObservabilityReport(source.healthChecks(), source.metrics(), source.taskStatuses()),
                ObservabilityReport::unavailable)) {
            health.addAll(contributed.healthChecks());
            metrics.addAll(contributed.metrics());
            tasks.addAll(contributed.tasks());
        }
        health.sort(Comparator.comparing(HealthCheck::name));
        metrics.sort(Comparator.comparing(Metric::name));
        tasks.sort(Comparator.comparing(TaskStatus::name));
        return new ObservabilityReport(health, metrics, tasks);
    }

    /** Collect the signals of every {@link ServiceLoader}-discovered {@link ObservabilitySource}. */
    public static ObservabilityReport discover() {
        return from(ServiceLoader.load(ObservabilitySource.class).stream()
                .map(ServiceLoader.Provider::get)
                .toList());
    }

    /**
     * The rows a source that threw is reported as: one {@link Health#UNKNOWN} check named for the failing source, and
     * no metrics or tasks - the plugin reported none, and inventing values it never produced would be worse than
     * saying so. The check names the source's implementation class and the <em>kind</em> of failure; the full
     * exception is in the log ({@link Contributions#reason}), because a detail is operator-facing text that must carry
     * no secret. A failed source is never simply dropped: on this surface an absent signal reads as "this plugin
     * reports nothing", which is precisely what an operator must not conclude here.
     */
    private static ObservabilityReport unavailable(ObservabilitySource source, Exception failure) {
        return new ObservabilityReport(List.of(HealthCheck.of(
                Signals.name("observation", "unavailable", Contributions.segment(source)),
                "Whether the " + source.getClass().getName() + " observability source is healthy is unknown:"
                        + " it failed when this report collected its signals",
                Health.UNKNOWN,
                "The source threw " + Contributions.reason(failure) + " instead of reporting, so its health checks,"
                        + " metrics and task statuses are missing from this report - their absence is not an all-clear."
                        + " The server log carries the failure; every other source was collected.")),
                List.of(), List.of());
    }

    /** The worst health across every check - {@link Health#UP} when nothing reports trouble. */
    public Health overall() {
        Health overall = Health.UP;
        for (HealthCheck check : healthChecks) {
            overall = overall.worst(check.status());
        }
        return overall;
    }
}
