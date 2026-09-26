package build.jenesis.repository.walk.store;

import module java.base;

import build.jenesis.repository.observation.Metric;
import build.jenesis.repository.observation.TaskStatus;
import build.jenesis.repository.observation.ObservabilitySource;

/**
 * The shared walk's signals, from what this node's walks have recorded ({@link WalkRecord}): {@code jenreg.walk.segments}
 * - the pass last joined or finished, done segments against its segment count, so the overview shows how far it has
 * converged; {@code jenreg.walk.resumes} - segments this node took over from an expired (dead) holder's cursor, the
 * multi-node health signal a steadily climbing count exposes; and {@code jenreg.walk.pass} - that pass's generation
 * and started stamp, {@code RUNNING} while segments are still claimed and {@code IDLE} once every segment is done. A
 * node that has never walked reports nothing, so a deployment without the feature contributes no signal rather than a
 * healthy-looking empty one.
 */
public final class ArtifactWalkObservability implements ObservabilitySource {

    public ArtifactWalkObservability() {
    }

    @Override
    public List<Metric> metrics() {
        return WalkRecord.last().map(pass -> List.of(
                Metric.bounded("jenreg.walk.segments",
                        "Segments done against the current shared-walk pass's segment count - the used-vs-available "
                                + "shape, so the overview shows how far the running pass has converged without "
                                + "pre-computing a percentage.",
                        pass.done(), pass.segments(), "segments"),
                Metric.counter("jenreg.walk.resumes",
                        "Segments this node reclaimed from an expired (dead) holder's cursor - takeovers, the "
                                + "multi-node health signal a steadily climbing count exposes (workers dying "
                                + "mid-segment).",
                        WalkRecord.resumes(), "segments"))).orElseGet(List::of);
    }

    @Override
    public List<TaskStatus> taskStatuses() {
        return WalkRecord.last().map(pass -> {
            boolean running = !pass.complete();
            return List.of(TaskStatus.ran("jenreg.walk.pass",
                    "The current (or last completed) shared-walk pass - its generation and started stamp, RUNNING "
                            + "while segments are still claimed and IDLE once every segment is done.",
                    running ? TaskStatus.State.RUNNING : TaskStatus.State.IDLE, pass.started(), null,
                    "generation " + pass.generation() + ", " + pass.done() + " of " + pass.segments()
                            + " segments done" + (running ? " (segments still claimed)" : " (pass complete)")));
        }).orElseGet(List::of);
    }
}
