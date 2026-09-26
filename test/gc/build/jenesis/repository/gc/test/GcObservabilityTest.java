package build.jenesis.repository.gc.test;

import module org.junit.jupiter.api;
import module java.base;
import build.jenesis.repository.gc.GarbageCollectorProvider;
import build.jenesis.repository.gc.store.GarbageCollectorObservability;
import build.jenesis.repository.gc.store.MarkSweepGarbageCollector;
import build.jenesis.repository.observation.Metric;
import build.jenesis.repository.observation.ObservabilityReport;
import build.jenesis.repository.observation.TaskStatus;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Known;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.walk.store.StoreArtifactWalk;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The collector's signals are what this node's collections have done, whichever collector instance did it: every
 * {@code collect} adds to one record, and the discovered {@link GarbageCollectorObservability} reports it -
 * {@code jenreg.gc.condemned} (the in-flight condemned set the last sweep left), the {@code jenreg.gc.collected}
 * counter and a {@code jenreg.gc.lastrun} task status. The record is the node's, and the suites of this module share
 * one JVM, so each test reads what its own collects changed rather than a count from zero. Exercised against a real
 * filesystem store, without the server or Micrometer.
 */
class GcObservabilityTest {

    @TempDir
    Path root;

    private final MutableClock clock = new MutableClock();

    private final GarbageCollectorObservability signals = new GarbageCollectorObservability();

    private ArtifactStore store() {
        return ArtifactStoreProvider.resolve(
                "filesystem", key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
    }

    private MarkSweepGarbageCollector collector() {
        return new MarkSweepGarbageCollector(new StoreArtifactWalk(5, 4, Duration.ofMinutes(10), clock));
    }

    private static ByteArrayInputStream bytes(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    private double metric(String name) {
        return signals.metrics().stream().filter(metric -> metric.name().equals(name)).findFirst()
                .map(Metric::value).orElse(0.0);
    }

    @Test
    void a_collect_reports_the_in_flight_set_it_left_and_when_it_ran() throws IOException {
        ArtifactStore store = store();
        Publication publication = new Publication(store);
        String kept = publication.storeBlob(bytes("kept"));
        publication.link("/maven/kept.jar", kept);
        var _ = publication.storeBlob(bytes("orphan"));
        double before = metric("jenreg.gc.collected");
        Instant ran = Instant.parse("2026-09-26T10:00:00Z");

        collector().collect(store, Known.known(List.of("publish")), ran);

        assertThat(signals.metrics()).satisfiesExactlyInAnyOrder(
                condemned -> {
                    assertThat(condemned.name()).isEqualTo("jenreg.gc.condemned");
                    assertThat(condemned.kind()).isEqualTo(Metric.Kind.GAUGE);
                    assertThat(condemned.value()).isEqualTo(1.0);
                    assertThat(condemned.description()).isNotBlank();
                },
                collected -> {
                    assertThat(collected.name()).isEqualTo("jenreg.gc.collected");
                    assertThat(collected.kind()).isEqualTo(Metric.Kind.COUNTER);
                    assertThat(collected.value()).as("a first pass condemns and reclaims nothing").isEqualTo(before);
                    assertThat(collected.description()).isNotBlank();
                });
        assertThat(signals.taskStatuses()).singleElement().satisfies(lastrun -> {
            assertThat(lastrun.name()).isEqualTo("jenreg.gc.lastrun");
            assertThat(lastrun.state()).isEqualTo(TaskStatus.State.IDLE);
            assertThat(lastrun.lastRun()).isEqualTo(ran);
            assertThat(lastrun.description()).isNotBlank();
        });
    }

    @Test
    void the_reclaimed_counter_climbs_across_collector_instances() throws IOException {
        ArtifactStore store = store();
        var _ = new Publication(store).storeBlob(bytes("orphan"));
        double before = metric("jenreg.gc.collected");

        collector().collect(store, Known.known(List.of("publish")), clock.instant()); // condemns
        collector().collect(store, Known.known(List.of("publish")), clock.instant()); // another instance collects

        assertThat(metric("jenreg.gc.collected")).as("a fresh collector adds to the node's count, never restarts it")
                .isEqualTo(before + 1);
        assertThat(metric("jenreg.gc.condemned")).as("the orphan was reclaimed, so nothing is left condemned")
                .isEqualTo(0.0);
    }

    @Test
    void resolving_a_collector_that_never_runs_changes_nothing_the_signals_say() throws IOException {
        ArtifactStore store = store();
        var _ = new Publication(store).storeBlob(bytes("orphan"));
        Instant ran = Instant.parse("2026-09-26T11:00:00Z");
        collector().collect(store, Known.known(List.of("publish")), ran);
        List<Metric> metrics = signals.metrics();

        // What the capabilities answer and the maintenance screen do on every request: resolve one to ask.
        var _ = GarbageCollectorProvider.resolve(key -> "gc".equals(key) ? "mark-sweep" : null);
        var _ = collector();

        assertThat(signals.metrics()).isEqualTo(metrics);
        assertThat(signals.taskStatuses()).singleElement().extracting(TaskStatus::lastRun).isEqualTo(ran);
    }

    @Test
    void every_signal_name_follows_the_jenesis_gc_grammar() throws IOException {
        collector().collect(store(), Known.known(List.of("publish")), clock.instant());

        assertThat(signals.metrics()).extracting(Metric::name)
                .allSatisfy(name -> assertThat(name).matches("jenreg\\.gc\\..+"));
        assertThat(signals.taskStatuses()).extracting(TaskStatus::name)
                .allSatisfy(name -> assertThat(name).matches("jenreg\\.gc\\..+"));
    }

    @Test
    void the_signals_collect_into_the_report_the_consumers_read() throws IOException {
        ArtifactStore store = store();
        var _ = new Publication(store).storeBlob(bytes("orphan"));
        collector().collect(store, Known.known(List.of("publish")), clock.instant());

        ObservabilityReport report = ObservabilityReport.from(List.of(signals));

        assertThat(report.metrics()).extracting(Metric::name)
                .containsExactly("jenreg.gc.collected", "jenreg.gc.condemned"); // name-sorted
        assertThat(report.tasks()).extracting(TaskStatus::name).containsExactly("jenreg.gc.lastrun");
    }
}
