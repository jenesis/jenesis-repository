package build.jenesis.repository.walk.store;

import module java.base;

import build.jenesis.repository.walk.WalkPass;

/**
 * What this node's walks have seen, whichever walk instance saw it: the pass last joined or finished, and the segments
 * taken over from an expired holder. Node-wide because a walk is resolved wherever one is asked for - the rebuild
 * driver, every collector, the capabilities answer - so figures on an instance would restart with every resolve.
 */
final class WalkRecord {

    private static final AtomicLong RESUMES = new AtomicLong();

    private static volatile WalkPass observed;

    private WalkRecord() {
    }

    /** A pass this node joined, advanced or finished - the one the {@code jenrepo.walk.*} signals describe. */
    static void observed(WalkPass pass) {
        observed = pass;
    }

    /** A segment this node took over from an expired holder's cursor. */
    static void resumed() {
        RESUMES.incrementAndGet();
    }

    /** The pass this node last saw, or empty before it has walked. */
    static Optional<WalkPass> last() {
        return Optional.ofNullable(observed);
    }

    /** The segments this node has taken over since it started. */
    static long resumes() {
        return RESUMES.get();
    }
}
