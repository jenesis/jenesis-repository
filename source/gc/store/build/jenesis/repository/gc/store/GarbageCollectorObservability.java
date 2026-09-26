package build.jenesis.repository.gc.store;

import module java.base;

import build.jenesis.repository.observation.Metric;
import build.jenesis.repository.observation.TaskStatus;
import build.jenesis.repository.observation.ObservabilitySource;

/**
 * The mark-and-sweep collector's signals, from what this node's collections have recorded ({@link CollectionRecord}):
 * {@code jenreg.gc.condemned}, the blobs the last sweep left condemned awaiting the confirming pass;
 * {@code jenreg.gc.collected}, the blobs reclaimed since the node started; and {@code jenreg.gc.lastrun}, the last
 * collect. A node that has not collected reports nothing, so a deployment without the collector contributes no
 * signal rather than a healthy-looking empty one.
 */
public final class GarbageCollectorObservability implements ObservabilitySource {

    public GarbageCollectorObservability() {
    }

    @Override
    public List<Metric> metrics() {
        return CollectionRecord.last().map(last -> List.of(
                Metric.gauge("jenreg.gc.condemned",
                        "Blobs currently condemned (gc/condemned/) awaiting the confirming pass - the "
                                + "condemn-then-collect in-flight set the last sweep left standing.",
                        last.condemnedStanding(), "blobs"),
                Metric.counter("jenreg.gc.collected",
                        "Blobs reclaimed by the mark-sweep collector on this node, accumulated across collect passes "
                                + "- a monotonic count that climbs by what each collect deletes.",
                        CollectionRecord.reclaimed(), "blobs"))).orElseGet(List::of);
    }

    @Override
    public List<TaskStatus> taskStatuses() {
        return CollectionRecord.last().map(last -> List.of(TaskStatus.ran("jenreg.gc.lastrun",
                "The garbage-collection pass, stamped with its last collect - the mark-then-sweep that condemns "
                        + "unreferenced blobs and reclaims those an earlier pass already condemned.",
                TaskStatus.State.IDLE, last.at(), null,
                "reclaimed " + CollectionRecord.reclaimed() + " blob(s), " + last.condemnedStanding()
                        + " condemned awaiting the next pass" + (last.complete() ? "" : " (partial: the shared walk "
                        + "still had segments held by another node)")))).orElseGet(List::of);
    }
}
