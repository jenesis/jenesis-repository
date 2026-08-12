package build.jenesis.repository.gc;

import build.jenesis.repository.store.Known;

import module java.base;

/**
 * The outcome of a garbage-collection pass or dry run - computed before anything is deleted, so it can be
 * previewed and reported, matching the retention sweeper's plan shape. From {@link GarbageCollector#plan} the
 * counters describe what a collection would do right now (nothing was written); from
 * {@link GarbageCollector#collect} they describe what the pass actually did.
 *
 * <p><b>Three outcomes, not two.</b> {@code complete} alone would fuse the two ways a pass can do nothing: one is
 * transient and self-healing (no earlier judgment to act on yet, or another node still holds walk segments - wait
 * for the next interval), the other is an operator action item (some ecosystem's roots cannot be named, so the pass
 * <em>refused</em> and will refuse again until a module is installed or the data is purged). {@link #refusal()}
 * separates them, and the constructor makes the separation structural rather than conventional: a refused plan
 * cannot claim completeness and cannot carry a non-zero counter, so a refusal can never be reported as a converged
 * store.
 *
 * @param complete   whether the judgment rests on a completed enumeration: a plan with no completed pass to judge
 *                   by, or a collection whose shared walk still had segments held by another node, reports
 *                   {@code false} - it is a partial answer, not an empty store
 * @param condemned  blobs newly judged unreferenced this pass and marked for the <em>next</em> one - never deleted
 *                   by the pass that first judged them (always {@code 0} from a dry run)
 * @param spared     condemned markers cleared because the blob turned out to be referenced again - the dedup
 *                   re-publish that re-linked content an earlier pass judged orphaned (always {@code 0} from a
 *                   dry run)
 * @param collected  blobs due for deletion: reclaimed by {@code collect}, previewed by {@code plan}
 * @param sample     the first {@link #SAMPLE} collected hashes, for a console preview - the count above is the
 *                   whole truth where a mass eviction condemns more than fits a report
 * @param refusal    why the pass declined to judge anything at all, when it did: the unanswerable pointer-root set
 *                   it was handed, carried through verbatim so a console and a log report the cause and the remedy
 *                   rather than an empty plan
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

    /** A pass that judged nothing because it was handed a root set it could not act on - the one outcome an operator
     *  must act on rather than wait out. Nothing was walked, condemned or deleted. */
    public static GcPlan refused(Known.Unknown<List<String>> reason) {
        return new GcPlan(false, 0, 0, 0, List.of(), Optional.of(Objects.requireNonNull(reason, "reason")));
    }

    /** An ordinary outcome - complete or partial - that was not a refusal. */
    public static GcPlan of(boolean complete, long condemned, long spared, long collected, List<String> sample) {
        return new GcPlan(complete, condemned, spared, collected, sample, Optional.empty());
    }

    /** Whether the pass changed or would change nothing - the quiet steady state of a converged store. A refused
     *  pass also changed nothing, which is exactly why it must be told apart by {@link #refusal()} rather than by
     *  this. */
    public boolean isEmpty() {
        return condemned == 0 && spared == 0 && collected == 0;
    }
}
