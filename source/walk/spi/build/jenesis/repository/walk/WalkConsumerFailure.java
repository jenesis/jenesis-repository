package build.jenesis.repository.walk;

/**
 * A marker suppressed onto a failure that came out of one {@link WalkConsumer}, naming which one.
 *
 * <p>It exists because the rebuild pass deliberately does <em>not</em> contain a consumer failure. The cursor a
 * pass commits is shared - one walk, one committed position, N consumers - so containing one consumer's failure
 * while the stride commits would advance past items that consumer never received, permanently, with it then
 * reporting itself converged. Propagating is what holds the cursor, so the pass resumes from the last committed
 * position and a failure delays a rebuild rather than truncating it.
 *
 * <p>The cost of propagating was attribution. The failure that reaches the caller is the consumer's own, so its
 * type and message are the consumer's, and nothing in it says which of the installed consumers produced it - the
 * class appears only in a stack frame, and in no counter and no log line. An operator watching a rebuild stall
 * was told that a pass failed, not which plugin stalled it, and with a dozen consumers installed that is the
 * difference between a name and a bisect.
 *
 * <p><b>Why a suppressed marker rather than a wrapper.</b> Wrapping would change the exception a caller sees, and
 * callers distinguish an {@link java.io.IOException} - the store or the walk giving way, retry the pass - from a
 * runtime failure out of plugin code. Rewrapping everything as one type would erase that. Suppression adds the
 * name without touching the type, the message or the cause chain, and it prints with the stack trace, so the
 * attribution reaches every log that already renders one.
 */
public final class WalkConsumerFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param consumer the consumer whose delivery failed; its {@link WalkConsumer#name} is used when it can be
     *                 read and its class name when {@code name()} itself is what threw - a consumer broken enough
     *                 to fail its own identity is exactly the one worth naming.
     */
    WalkConsumerFailure(WalkConsumer consumer) {
        super("delivered by walk consumer " + describe(consumer), null, false, false);
    }

    private static String describe(WalkConsumer consumer) {
        String type = consumer.getClass().getName();
        try {
            String name = consumer.name();
            return name == null || name.isBlank() ? type : name + " (" + type + ")";
        } catch (RuntimeException unnameable) {
            // Naming the failure must never become a second failure that replaces the first: this marker is
            // suppressed onto a real one, and losing that to a broken name() would be a strictly worse outcome
            // than an unnamed marker.
            return type;
        }
    }
}
