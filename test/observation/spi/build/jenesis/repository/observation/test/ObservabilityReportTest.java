package build.jenesis.repository.observation.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.observation.Health;
import build.jenesis.repository.observation.HealthCheck;
import build.jenesis.repository.observation.Metric;
import build.jenesis.repository.observation.ObservabilityReport;
import build.jenesis.repository.observation.ObservabilitySource;
import build.jenesis.repository.observation.TaskStatus;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The single collected view: {@link ObservabilityReport#from} merges and name-sorts the signals of every source,
 * {@link ObservabilityReport#overall} collapses the health checks to the worst state, a source that reports nothing
 * contributes nothing (graceful degradation), and {@link ObservabilityReport#discover} finds the
 * {@code provides}-declared {@link SampleObservabilitySource} through {@link java.util.ServiceLoader}.
 */
class ObservabilityReportTest {

    private static ObservabilitySource source(HealthCheck health, Metric metric, TaskStatus task) {
        return new ObservabilitySource() {
            @Override
            public List<HealthCheck> healthChecks() {
                return List.of(health);
            }

            @Override
            public List<Metric> metrics() {
                return List.of(metric);
            }

            @Override
            public List<TaskStatus> taskStatuses() {
                return List.of(task);
            }
        };
    }

    @Test
    void merges_and_name_sorts_the_signals_of_every_source() {
        ObservabilityReport report = ObservabilityReport.from(List.of(
                source(HealthCheck.up("jenreg.zeta.check", "z"),
                        Metric.gauge("jenreg.zeta.gauge", "z", 1, ""),
                        TaskStatus.idle("jenreg.zeta.task", "z")),
                source(HealthCheck.up("jenreg.alpha.check", "a"),
                        Metric.gauge("jenreg.alpha.gauge", "a", 1, ""),
                        TaskStatus.idle("jenreg.alpha.task", "a"))));

        assertThat(report.healthChecks()).extracting(HealthCheck::name)
                .containsExactly("jenreg.alpha.check", "jenreg.zeta.check");
        assertThat(report.metrics()).extracting(Metric::name)
                .containsExactly("jenreg.alpha.gauge", "jenreg.zeta.gauge");
        assertThat(report.tasks()).extracting(TaskStatus::name)
                .containsExactly("jenreg.alpha.task", "jenreg.zeta.task");
    }

    @Test
    void overall_health_is_the_worst_across_all_checks() {
        ObservabilityReport clean = ObservabilityReport.from(List.of(
                source(HealthCheck.up("jenreg.a.check", "a"),
                        Metric.gauge("jenreg.a.g", "a", 1, ""), TaskStatus.idle("jenreg.a.t", "a"))));
        assertThat(clean.overall()).isEqualTo(Health.UP);

        ObservabilityReport mixed = ObservabilityReport.from(List.of(
                source(HealthCheck.up("jenreg.a.check", "a"),
                        Metric.gauge("jenreg.a.g", "a", 1, ""), TaskStatus.idle("jenreg.a.t", "a")),
                source(HealthCheck.of("jenreg.b.check", "b", Health.DOWN, "dead"),
                        Metric.gauge("jenreg.b.g", "b", 1, ""), TaskStatus.idle("jenreg.b.t", "b"))));
        assertThat(mixed.overall()).isEqualTo(Health.DOWN);
    }

    @Test
    void an_empty_source_set_is_healthy_and_carries_nothing() {
        ObservabilityReport empty = ObservabilityReport.from(List.of());
        assertThat(empty.healthChecks()).isEmpty();
        assertThat(empty.metrics()).isEmpty();
        assertThat(empty.tasks()).isEmpty();
        assertThat(empty.overall()).isEqualTo(Health.UP);
    }

    @Test
    void a_source_reporting_nothing_degrades_gracefully() {
        ObservabilityReport report = ObservabilityReport.from(List.of(new ObservabilitySource() {
        }));
        assertThat(report.healthChecks()).isEmpty();
        assertThat(report.metrics()).isEmpty();
        assertThat(report.tasks()).isEmpty();
    }

    @Test
    void a_failing_source_is_reported_as_unknown_and_never_takes_the_report_down() {
        // Before containment this threw out of from(), so one broken plugin cost the reader the whole overview - every
        // other plugin's health, metrics and task statuses included.
        ObservabilityReport report = ObservabilityReport.from(List.of(
                new ThrowingSource(),
                source(HealthCheck.up("jenreg.alpha.check", "a"),
                        Metric.gauge("jenreg.alpha.gauge", "a", 1, ""),
                        TaskStatus.idle("jenreg.alpha.task", "a"))));

        assertThat(report.healthChecks()).as("the healthy source is collected in full")
                .extracting(HealthCheck::name)
                .containsExactly("jenreg.alpha.check", "jenreg.observation.unavailable.throwingsource");
        assertThat(report.metrics()).extracting(Metric::name).containsExactly("jenreg.alpha.gauge");
        assertThat(report.tasks()).extracting(TaskStatus::name).containsExactly("jenreg.alpha.task");

        HealthCheck substitute = report.healthChecks().stream()
                .filter(check -> check.name().endsWith("throwingsource")).findFirst().orElseThrow();
        assertThat(substitute.status()).as("'a source could not determine its state' is exactly the truth here")
                .isEqualTo(Health.UNKNOWN);
        assertThat(substitute.description()).as("the row names the source that failed")
                .contains(ThrowingSource.class.getName());
        assertThat(substitute.detail()).as("and the kind of failure, plus a warning against reading the missing "
                        + "signals as an all-clear")
                .contains("IllegalStateException").contains("not an all-clear");
        assertThat(substitute.detail() + substitute.description())
                .as("an operator-facing detail never carries the exception message, which is uncontrolled text - the "
                        + "log has it")
                .doesNotContain(ThrowingSource.SECRET).doesNotContain("hunter2");

        assertThat(report.overall()).as("a report one of whose sources threw is not UP")
                .isEqualTo(Health.UNKNOWN);
    }

    @Test
    void a_source_answering_null_is_contained_like_a_throw() {
        // null is never a legal signal list; dropping it would leave the plugin looking like one reporting nothing.
        ObservabilityReport report = ObservabilityReport.from(List.of(new ObservabilitySource() {
            @Override
            public List<Metric> metrics() {
                return null;
            }
        }));
        assertThat(report.healthChecks()).singleElement()
                .satisfies(check -> assertThat(check.status()).isEqualTo(Health.UNKNOWN));
        assertThat(report.overall()).isEqualTo(Health.UNKNOWN);
    }

    @Test
    void discovers_the_service_loader_installed_source() {
        ObservabilityReport report = ObservabilityReport.discover();
        assertThat(report.healthChecks()).extracting(HealthCheck::name).contains("jenreg.gc.worker");
        assertThat(report.metrics()).extracting(Metric::name).contains("jenreg.quota.used.bytes");
        assertThat(report.tasks()).extracting(TaskStatus::name).contains("jenreg.gc.sweep");
        assertThat(report.overall()).isEqualTo(Health.DEGRADED);
    }
}
