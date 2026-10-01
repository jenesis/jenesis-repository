package build.jenesis.repository.gc.store;

import module java.base;

import build.jenesis.repository.observation.Metric;
import build.jenesis.repository.observation.TaskStatus;
import build.jenesis.repository.observation.ObservabilitySource;

/**
 * The mark-and-sweep collector's signals from {@link CollectionRecord}: {@code jenrepo.gc.condemned}, the blobs the
 * last sweep left condemned; {@code jenrepo.gc.collected}, the blobs reclaimed since the node started; and
 * {@code jenrepo.gc.lastrun}, the last collect. A node that has not collected reports nothing rather than a
 * healthy-looking empty signal.
 */
public final class GarbageCollectorObservability implements ObservabilitySource {

    public GarbageCollectorObservability() {
    }

    @Override
    public List<Metric> metrics() {
        return CollectionRecord.last().map(last -> List.of(
                Metric.gauge("jenrepo.gc.condemned",
                        "Blobs currently condemned (gc/condemned/) awaiting the confirming pass - the "
                                + "condemn-then-collect in-flight set the last sweep left standing.",
                        last.condemnedStanding(), "blobs"),
                Metric.counter("jenrepo.gc.collected",
                        "Blobs reclaimed by the mark-sweep collector on this node, accumulated across collect passes "
                                + "- a monotonic count that climbs by what each collect deletes.",
                        CollectionRecord.reclaimed(), "blobs"))).orElseGet(List::of);
    }

    @Override
    public List<TaskStatus> taskStatuses() {
        return CollectionRecord.last().map(last -> List.of(TaskStatus.ran("jenrepo.gc.lastrun",
                "The garbage-collection pass, stamped with its last collect - the mark-then-sweep that condemns "
                        + "unreferenced blobs and reclaims those an earlier pass already condemned.",
                TaskStatus.State.IDLE, last.at(), null,
                "reclaimed " + CollectionRecord.reclaimed() + " blob(s), " + last.condemnedStanding()
                        + " condemned awaiting the next pass" + (last.complete() ? "" : " (partial: the shared walk "
                        + "still had segments held by another node)")))).orElseGet(List::of);
    }
}
