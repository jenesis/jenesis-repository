package build.jenesis.repository.walk.store;

import module java.base;

import build.jenesis.repository.walk.WalkPass;

/**
 * What this node's walks have seen, whichever walk instance saw it: the pass it last joined or finished, and the
 * segments it took over from a holder whose claim had expired.
 *
 * <p>Node-wide because a walk has no single owner. One is resolved wherever a walk is asked for - by the rebuild
 * driver, by every collector, by the capabilities answer that only asks whether one is installed - so figures kept on
 * an instance started from zero with every resolve, and whichever instance registered last was the one reported,
 * usually one that never walked. Every walk records here instead.
 */
final class WalkRecord {

    private static final AtomicLong RESUMES = new AtomicLong();

    private static volatile WalkPass observed;

    private WalkRecord() {
    }

    /** A pass this node joined, advanced or finished - the one the {@code jenreg.walk.*} signals describe. */
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
