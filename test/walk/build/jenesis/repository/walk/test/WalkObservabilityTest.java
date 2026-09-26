package build.jenesis.repository.walk.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.observation.Metric;
import build.jenesis.repository.observation.ObservabilityReport;
import build.jenesis.repository.observation.TaskStatus;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.walk.store.ArtifactWalkObservability;
import build.jenesis.repository.walk.store.StoreArtifactWalk;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The shared walk's signals are what this node's walks have seen, whichever walk instance saw it: every walk records
 * into one node-wide record, and the discovered {@link ArtifactWalkObservability} reports {@code jenreg.walk.segments}
 * (a bounded gauge of the pass's done segments against its segment count), {@code jenreg.walk.resumes} (a counter of
 * segments this node reclaimed from an expired holder) and a {@code jenreg.walk.pass} task status. The suites of this
 * module share one JVM, so a counter is read as what this test's walks added. Exercised against a real
 * {@code FilesystemArtifactStore}, without the server or Micrometer.
 */
class WalkObservabilityTest {

    @TempDir
    Path root;

    private final MutableClock clock = new MutableClock();

    private final ArtifactWalkObservability signals = new ArtifactWalkObservability();

    private double resumes() {
        return signals.metrics().stream().filter(metric -> metric.name().equals("jenreg.walk.resumes")).findFirst()
                .map(Metric::value).orElse(0.0);
    }

    private ArtifactStore store(String name) {
        Path scoped = root.resolve(name);
        return ArtifactStoreProvider.resolve(
                "filesystem", key -> "jenreg.filesystem.root".equals(key) ? scoped.toString() : null);
    }

    private void seed(ArtifactStore store, String... names) throws IOException {
        for (String name : names) {
            store.writeVersioned("publish/" + name, name.getBytes(StandardCharsets.UTF_8), null);
        }
    }

    @Test
    void a_walk_that_is_only_resolved_changes_nothing_the_signals_say() throws IOException {
        ArtifactStore store = store("resolved");
        seed(store, "a");
        new StoreArtifactWalk(1000, 1, Duration.ofMinutes(15), clock).walk(store, "test", List.of("publish"), key -> { });
        List<TaskStatus> statuses = signals.taskStatuses();

        // What the capabilities answer does on every request: build one to ask whether it is installed.
        var _ = new StoreArtifactWalk(1000, 32, Duration.ofMinutes(15), clock);

        assertThat(signals.taskStatuses()).isEqualTo(statuses);
    }

    @Test
    void a_completed_pass_reports_a_bounded_segments_gauge_a_resumes_counter_and_the_pass_status() throws IOException {
        ArtifactStore store = store("done");
        seed(store, "a", "b", "c");
        StoreArtifactWalk walk = new StoreArtifactWalk(1000, 1, Duration.ofMinutes(15), clock);
        double resumed = resumes();

        List<String> visited = new ArrayList<>();
        walk.walk(store, "test", List.of("publish"), visited::add);
        assertThat(visited).hasSize(3);

        assertThat(signals.metrics()).satisfiesExactlyInAnyOrder(
                segments -> {
                    assertThat(segments.name()).isEqualTo("jenreg.walk.segments");
                    assertThat(segments.kind()).isEqualTo(Metric.Kind.GAUGE);
                    assertThat(segments.value()).isEqualTo(1.0);
                    assertThat(segments.limit()).hasValue(1.0);
                    assertThat(segments.usage()).hasValue(1.0);
                    assertThat(segments.description()).isNotBlank();
                },
                resumes -> {
                    assertThat(resumes.name()).isEqualTo("jenreg.walk.resumes");
                    assertThat(resumes.kind()).isEqualTo(Metric.Kind.COUNTER);
                    assertThat(resumes.value()).as("a clean pass takes nothing over").isEqualTo(resumed);
                    assertThat(resumes.limit()).isEmpty();
                    assertThat(resumes.description()).isNotBlank();
                });

        assertThat(signals.taskStatuses()).singleElement().satisfies(pass -> {
            assertThat(pass.name()).isEqualTo("jenreg.walk.pass");
            assertThat(pass.state()).isEqualTo(TaskStatus.State.IDLE);
            assertThat(pass.lastRun()).isNotNull();
            assertThat(pass.outcome()).contains("generation 1");
            assertThat(pass.description()).isNotBlank();
        });
    }

    @Test
    void every_signal_name_follows_the_jenesis_walk_grammar() throws IOException {
        ArtifactStore store = store("grammar");
        seed(store, "x");
        StoreArtifactWalk walk = new StoreArtifactWalk(1000, 1, Duration.ofMinutes(15), clock);
        walk.walk(store, "test", List.of("publish"), key -> { });

        assertThat(signals.metrics()).extracting(Metric::name)
                .allSatisfy(name -> assertThat(name).matches("jenreg\\.walk\\..+"));
        assertThat(signals.taskStatuses()).extracting(TaskStatus::name)
                .allSatisfy(name -> assertThat(name).matches("jenreg\\.walk\\..+"));
    }

    @Test
    void a_takeover_of_an_expired_holders_segment_climbs_the_resumes_counter() throws IOException {
        ArtifactStore store = store("resume");
        seed(store, "a", "b", "c", "d", "e");
        // checkpoint 1 commits a cursor per key; a crash mid-segment leaves it CLAIMED and expiring, so the same
        // instance's next walk takes the expired segment over from the last committed cursor - a resume.
        StoreArtifactWalk walk = new StoreArtifactWalk(1, 1, Duration.ofMinutes(10), clock);
        double resumed = resumes();

        List<String> before = new ArrayList<>();
        assertThatThrownBy(() -> walk.walk(store, "test", List.of("publish"), key -> {
            before.add(key);
            if (before.size() == 3) {
                throw new IOException("crash mid-segment");
            }
        })).hasMessageContaining("crash mid-segment");
        assertThat(resumes()).as("the pass is observed from the moment it is joined, crash or not: a reader of the "
                        + "fleet sees the walk that is under way, not only the one that returned").isEqualTo(resumed);
        assertThat(signals.taskStatuses()).as("and the pass reads as still running, its segment still claimed")
                .singleElement().extracting(TaskStatus::state).isEqualTo(TaskStatus.State.RUNNING);

        clock.advance(Duration.ofMinutes(11)); // let the abandoned claim expire
        List<String> after = new ArrayList<>();
        new StoreArtifactWalk(1, 1, Duration.ofMinutes(10), clock).walk(store, "test", List.of("publish"), after::add);

        assertThat(resumes()).as("a takeover by another walk instance adds to the node's count")
                .isEqualTo(resumed + 1);
    }

    @Test
    void the_signals_collect_into_the_report_the_consumers_read() throws IOException {
        ArtifactStore store = store("report");
        seed(store, "one", "two");
        StoreArtifactWalk walk = new StoreArtifactWalk(1000, 1, Duration.ofMinutes(15), clock);
        walk.walk(store, "test", List.of("publish"), key -> { });

        ObservabilityReport report = ObservabilityReport.from(List.of(signals));

        assertThat(report.metrics()).extracting(Metric::name)
                .containsExactly("jenreg.walk.resumes", "jenreg.walk.segments"); // name-sorted
        assertThat(report.tasks()).extracting(TaskStatus::name).containsExactly("jenreg.walk.pass");
    }
}
