package build.jenesis.repository.walk.store;

import module java.base;

import build.jenesis.repository.observation.Metric;
import build.jenesis.repository.observation.TaskStatus;
import build.jenesis.repository.observation.ObservabilitySource;

/**
 * The shared walk's signals from {@link WalkRecord}: {@code jenrepo.walk.segments}, the last pass's done segments
 * against its count; {@code jenrepo.walk.resumes}, segments taken over from an expired holder - a climbing count is the
 * multi-node health signal; and {@code jenrepo.walk.pass}, that pass's generation and start, {@code RUNNING} while
 * segments are claimed and {@code IDLE} once all are done. A node that never walked reports nothing rather than a
 * healthy-looking empty signal.
 */
public final class ArtifactWalkObservability implements ObservabilitySource {

    public ArtifactWalkObservability() {
    }

    @Override
    public List<Metric> metrics() {
        return WalkRecord.last().map(pass -> List.of(
                Metric.bounded("jenrepo.walk.segments",
                        "Segments done against the current shared-walk pass's segment count - the used-vs-available "
                                + "shape, so the overview shows how far the running pass has converged without "
                                + "pre-computing a percentage.",
                        pass.done(), pass.segments(), "segments"),
                Metric.counter("jenrepo.walk.resumes",
                        "Segments this node reclaimed from an expired (dead) holder's cursor - takeovers, the "
                                + "multi-node health signal a steadily climbing count exposes (workers dying "
                                + "mid-segment).",
                        WalkRecord.resumes(), "segments"))).orElseGet(List::of);
    }

    @Override
    public List<TaskStatus> taskStatuses() {
        return WalkRecord.last().map(pass -> {
            boolean running = !pass.complete();
            return List.of(TaskStatus.ran("jenrepo.walk.pass",
                    "The current (or last completed) shared-walk pass - its generation and started stamp, RUNNING "
                            + "while segments are still claimed and IDLE once every segment is done.",
                    running ? TaskStatus.State.RUNNING : TaskStatus.State.IDLE, pass.started(), null,
                    "generation " + pass.generation() + ", " + pass.done() + " of " + pass.segments()
                            + " segments done" + (running ? " (segments still claimed)" : " (pass complete)")));
        }).orElseGet(List::of);
    }
}
