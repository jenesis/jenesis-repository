package build.jenesis.repository.store;

import module java.base;

/**
 * What a background job's stored record says that more than its own runner reads: a migration import's or an export's
 * {@code state}, which a reap and a resume both decide on.
 *
 * <p>The store has no conditional delete, so a finished job is never deleted on the strength of a read. A reap first
 * sets the record's state to {@link #DISMISSED} under compare-and-set against the record it read, and only then deletes
 * it; a resume's first write is a compare-and-set against the record it read. So of a reap and a resume that meet,
 * exactly one wins: a resume that read the record before the reap loses its compare-and-set and says
 * {@link Dismissed}, one that reads it after finds no job to resume, and a reap that read it before the resume loses
 * its own and leaves the job running.
 */
public final class JobState {

    /** The state a reaped job's record holds between the reap's compare-and-set and its delete. */
    public static final String DISMISSED = "dismissed";

    private JobState() {
    }

    /** A resume lost its first write to a reap: the job it named was dismissed in the meantime. */
    public static final class Dismissed extends IOException {

        public Dismissed(String job) {
            super("job " + job + " was dismissed while it was being resumed; start it again");
        }
    }
}
