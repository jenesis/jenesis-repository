package build.jenesis.repository.gc;

import module java.base;

import build.jenesis.repository.store.Known;

/**
 * The outcome of a collection pass or dry run, matching the retention sweeper's plan shape. From
 * {@link GarbageCollector#plan} the counters say what a collection would do now; from {@link GarbageCollector#collect},
 * what it did.
 *
 * <p><b>Three outcomes, not two.</b> A pass can do nothing transiently (no earlier judgment yet, or another node holds
 * walk segments) or because it refused (some ecosystem's roots cannot be named, which needs an operator).
 * {@link #refusal()} separates them, and the constructor makes it structural: a refused plan cannot claim completeness
 * or carry a non-zero counter.
 *
 * @param complete whether the judgment rests on a completed enumeration; {@code false} for a plan with no completed
 *     pass to judge by, or a collection whose walk still had segments held elsewhere
 * @param condemned blobs newly judged unreferenced and marked for the next pass - never deleted by the pass that first
 *     judged them ({@code 0} from a dry run)
 * @param spared condemned markers cleared because the blob is referenced again ({@code 0} from a dry run)
 * @param collected blobs due for deletion: reclaimed by {@code collect}, previewed by {@code plan}
 * @param sample the first {@link #SAMPLE} collected hashes for a console preview; the count above is the whole truth
 * @param refusal why the pass declined to judge anything, when it did: the unanswerable root set it was handed, carried
 *     through so a console and a log report the cause and the remedy
 */
public record GcPlan(boolean complete, long condemned, long spared, long collected, List<String> sample,
                     Optional<Known.Unknown<List<String>>> refusal) {

    /** The most hashes {@link #sample} carries; {@link #collected} counts past it. */
    public static final int SAMPLE = 1000;

    public GcPlan {
        sample = List.copyOf(sample);
        Objects.requireNonNull(refusal, "refusal");
        if (refusal.isPresent() && (complete || condemned != 0 || spared != 0 || collected != 0
                || !sample.isEmpty())) {
            throw new IllegalArgumentException("A refused collection judged nothing, so it can neither claim a "
                    + "completed enumeration nor report work: " + refusal.get());
        }
    }

    /** A pass that judged nothing because its root set could not be acted on - the outcome an operator must act on
     *  rather than wait out. */
    public static GcPlan refused(Known.Unknown<List<String>> reason) {
        return new GcPlan(false, 0, 0, 0, List.of(), Optional.of(Objects.requireNonNull(reason, "reason")));
    }

    /** An ordinary outcome - complete or partial - that was not a refusal. */
    public static GcPlan of(boolean complete, long condemned, long spared, long collected, List<String> sample) {
        return new GcPlan(complete, condemned, spared, collected, sample, Optional.empty());
    }

    /** Whether the pass changed or would change nothing - a converged store's steady state. A refused pass also changed
     *  nothing, so it is told apart by {@link #refusal()}. */
    public boolean isEmpty() {
        return condemned == 0 && spared == 0 && collected == 0;
    }
}
