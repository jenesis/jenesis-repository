package build.jenesis.repository.gc.store;

import module java.base;

/**
 * What this node's collections have done, whichever collector instance did it: the blobs reclaimed by every collect
 * since the node started, the blobs the last sweep left condemned, and when the last collect ran and whether it
 * completed. Node-wide because a collector is resolved wherever one is asked for - per pass, by the walk, by the
 * maintenance screen, by the capabilities answer - so figures kept on an instance would restart with every resolve.
 */
final class CollectionRecord {

    private static final AtomicLong COLLECTED = new AtomicLong();

    private static volatile Last last;

    /** When the last collect ran, the blobs its sweep left condemned, and whether it completed both walk passes. */
    record Last(Instant at, long condemnedStanding, boolean complete) {
    }

    private CollectionRecord() {
    }

    /** Record one collect: what it reclaimed, what its sweep left condemned ({@code -1} when it never swept), when it
     *  ran and whether it completed. */
    static void collected(Instant at, long reclaimed, long condemnedStanding, boolean complete) {
        COLLECTED.addAndGet(reclaimed);
        Last previous = last;
        long standing = condemnedStanding >= 0 ? condemnedStanding : previous == null ? 0 : previous.condemnedStanding();
        last = new Last(at, standing, complete);
    }

    /** The blobs reclaimed by every collect this node has run. */
    static long reclaimed() {
        return COLLECTED.get();
    }

    /** The last collect this node ran, or empty before it has run one. */
    static Optional<Last> last() {
        return Optional.ofNullable(last);
    }
}
