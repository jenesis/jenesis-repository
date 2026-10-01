package build.jenesis.repository.observation;

import module java.base;

/**
 * The one collected view every consumer reads - the console overview, the Actuator contributors and the reference docs
 * - so a signal is named and described once. {@link #from} merges sources' signals name-sorted, {@link #discover} does
 * it over the installed sources, {@link #overall} collapses the health checks. A source reporting nothing adds nothing.
 *
 * <p>A failing source degrades visibly: collected through {@link Contributions}, it is replaced by a
 * {@link Health#UNKNOWN} check named {@code jenrepo.observation.unavailable.<source>}, every other source is collected,
 * and {@link #overall} drops to {@code UNKNOWN}, never back to {@code UP}.
 */
public record ObservabilityReport(List<HealthCheck> healthChecks, List<Metric> metrics, List<TaskStatus> tasks) {

    public ObservabilityReport {
        healthChecks = List.copyOf(healthChecks);
        metrics = List.copyOf(metrics);
        tasks = List.copyOf(tasks);
    }

    /** Collect and name-sort the signals of {@code sources}; a source that throws contributes {@link #unavailable}. */
    public static ObservabilityReport from(Iterable<? extends ObservabilitySource> sources) {
        List<HealthCheck> health = new ArrayList<>();
        List<Metric> metrics = new ArrayList<>();
        List<TaskStatus> tasks = new ArrayList<>();
        // One contained collection per source, its three lists read together, so a throw or a null from any is one
        // degraded row rather than a half-collected source.
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

    /** The report as every endpoint answers it, in one shape: the overall verdict, then the health checks, metrics and
     *  task statuses with their names and descriptions. {@code version} lets a client detect a shape change; a metric's
     *  {@code limit} and {@code usage} are {@code null} without a ceiling. */
    public View view() {
        return new View(1, overall().name(),
                healthChecks.stream().map(check -> new HealthView(check.name(), check.description(),
                        check.status().name(), check.detail())).toList(),
                metrics.stream().map(metric -> new MetricView(metric.name(), metric.description(),
                        metric.kind().name(), metric.value(), metric.unit(),
                        metric.limit().isPresent() ? metric.limit().getAsDouble() : null,
                        metric.usage().isPresent() ? metric.usage().getAsDouble() : null)).toList(),
                tasks.stream().map(task -> new TaskView(task.name(), task.description(), task.state().name(),
                        task.lastRun() == null ? null : task.lastRun().toString(),
                        task.lastDuration() == null ? null : task.lastDuration().toString(),
                        task.outcome())).toList());
    }

    /** The whole collected report, grouped by signal kind - see {@link #view()}. */
    public record View(int version, String overall, List<HealthView> health, List<MetricView> metrics,
                       List<TaskView> tasks) {
    }

    /** One self-describing health check: its name, registration description, verdict and detail. */
    public record HealthView(String name, String description, String status, String detail) {
    }

    /** One metric with its ceiling and usage fraction where it has one, {@code null} otherwise. */
    public record MetricView(String name, String description, String kind, double value, String unit, Double limit,
                             Double usage) {
    }

    /** One scheduled task's last run as its scheduler reported it. */
    public record TaskView(String name, String description, String state, String lastRun, String lastDuration,
                           String outcome) {
    }

    /** Collect the signals of the discovered sources and of the sources among {@code owned}, the objects a running
     *  context has built, so asking never makes a context build anything. A discovered source of the same class as an
     *  owned one gives way to it, so a context reports its own instance. */
    public static ObservabilityReport of(Collection<?> owned) {
        Set<Class<?>> ownedClasses = new HashSet<>();
        List<ObservabilitySource> sources = new ArrayList<>();
        for (Object candidate : owned) {
            if (candidate instanceof ObservabilitySource source
                    && sources.stream().noneMatch(existing -> existing == source)) {
                sources.add(source);
                ownedClasses.add(source.getClass());
            }
        }
        for (ObservabilitySource discovered : Installed.SOURCES) {
            if (!ownedClasses.contains(discovered.getClass())) {
                sources.add(discovered);
            }
        }
        return from(sources);
    }

    /** Collect the signals of every {@link ServiceLoader}-discovered {@link ObservabilitySource}. */
    public static ObservabilityReport discover() {
        return from(Installed.SOURCES);
    }

    /** The sources, discovered once for the life of the class loader, since request handlers ask for a report per
     *  request. What they report is read fresh each call; which sources exist cannot change within a JVM. */
    private static final class Installed {

        private static final List<ObservabilitySource> SOURCES = ServiceLoader.load(ObservabilitySource.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .map(ObservabilitySource.class::cast)
                .toList();

        private Installed() {
        }
    }

    /** The rows a source that threw is reported as: one {@link Health#UNKNOWN} check naming its class and the failure's
     *  type ({@link Contributions#reason}), no metrics or tasks, since inventing values would be worse. Never dropped:
     *  an absent signal would read as "this plugin reports nothing". */
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

    /** The worst health across every check, {@link Health#UP} when none reports trouble. */
    public Health overall() {
        Health overall = Health.UP;
        for (HealthCheck check : healthChecks) {
            overall = overall.worst(check.status());
        }
        return overall;
    }
}
