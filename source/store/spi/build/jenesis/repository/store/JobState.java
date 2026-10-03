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
 *
 * <p>A job runs as a {@link Run}: it holds a lease named for its record while it runs, renewed on its own and at every
 * write, and writes its record only while it still holds it. So a record that says {@code running} is believed only
 * while a run holds the lease: one whose node died reads {@link #INTERRUPTED} ({@link #effective}) and may be resumed
 * or reaped like a failed one, and a run that lost its lease - its node paused past the lease while a resume took the
 * job over - stops at its next write rather than writing over the run that replaced it.
 */
public final class JobState {

    /** The state a reaped job's record holds between the reap's compare-and-set and its delete. */
    public static final String DISMISSED = "dismissed";

    /** The state a running job's record is read as once no run holds it: its node stopped without finishing. */
    public static final String INTERRUPTED = "interrupted";

    /** The state a job's record holds while a run works on it. */
    public static final String RUNNING = "running";

    /** How long a run's hold on its job outlives its last renewal: long enough that a busy node renewing a third of the
     *  way through keeps it, short enough that a job whose node died reads interrupted within minutes. */
    static final Duration LEASE = Duration.ofMinutes(3);

    private JobState() {
    }

    /**
     * The state a reader is told of the job whose record is {@code records/id} and says {@code stored}: the stored
     * state, except that a {@code running} record no run holds reads {@link #INTERRUPTED}. One lease read, and only for
     * a running record.
     */
    public static String effective(ArtifactStore store, String records, String id, String stored)
            throws IOException {
        if (!RUNNING.equals(stored)) {
            return stored;
        }
        return new Lease(store, LEASE).holder(name(records, id), Instant.now()).isPresent() ? RUNNING : INTERRUPTED;
    }

    private static String name(String records, String id) {
        return records + "-" + id;
    }

    /**
     * A job running on this node: the hold on its record {@code records/id}, taken by {@link #claim} and given up by
     * {@link #close}. While it is open a virtual thread renews the hold a third of the way through each lease, and
     * {@link #write} renews it too, so a run between checkpoints keeps it for as long as its node lives.
     */
    public static final class Run implements AutoCloseable {

        private final ArtifactStore store;
        private final String key;
        private final String name;
        private final String holder = "job/" + UUID.randomUUID();
        private final Lease lease;
        private final Thread renewal;

        private Run(ArtifactStore store, String records, String id) {
            this.store = store;
            this.key = records + "/" + id;
            this.name = name(records, id);
            this.lease = new Lease(store, LEASE);
            this.renewal = Thread.ofVirtual().name("job-hold-" + id).unstarted(this::renew);
        }

        /**
         * Take the job {@code records/id} and write its first record, {@code first}, against {@code expected} - the
         * token of the record a resume read, or {@code null} for a new job.
         *
         * @throws Running   when a run elsewhere holds the job
         * @throws Dismissed when the record changed since it was read - a reap dismissed it, or another resume took it
         */
        public static Run claim(ArtifactStore store, String records, String id, byte[] first, Object expected)
                throws IOException {
            Run run = new Run(store, records, id);
            if (!run.lease.acquire(run.name, run.holder, Instant.now())) {
                throw new Running(id);
            }
            boolean claimed = false;
            try {
                claimed = store.writeVersioned(run.key, first, expected);
            } finally {
                if (!claimed) {
                    run.lease.release(run.name, run.holder, Instant.now());
                }
            }
            if (!claimed) {
                throw new Dismissed(id);
            }
            run.renewal.start();
            return run;
        }

        /** Write the job's record while this run still holds it.
         *
         * @throws Lost when it no longer does: another run took the job over, and this one must stop */
        public void write(byte[] record) throws IOException {
            if (!lease.guarded(name, holder, Instant.now(),
                    () -> store.write(key, new ByteArrayInputStream(record)))) {
                throw new Lost(key);
            }
        }

        /** Give the job up: stop renewing and release the hold, so a reader sees the record as written. */
        @Override
        public void close() throws IOException {
            renewal.interrupt();
            lease.release(name, holder, Instant.now());
        }

        private void renew() {
            while (true) {
                try {
                    Thread.sleep(LEASE.dividedBy(3));
                } catch (InterruptedException closed) {
                    return;
                }
                try {
                    if (!lease.renew(name, holder, Instant.now())) {
                        return;                                  // lost: the next write says so
                    }
                } catch (IOException unanswered) {
                    // Asked again a third of a lease later; if the store stays away the hold lapses, and a reader sees
                    // the job interrupted - which it is, for as long as the store cannot be written.
                }
            }
        }
    }

    /** A job could not be claimed: a run elsewhere holds it. */
    public static final class Running extends IOException {

        public Running(String job) {
            super("job " + job + " is running; wait for it to finish or fail before resuming it");
        }
    }

    /** A run lost its job: its hold lapsed and another run took the job over, so this one stops. */
    public static final class Lost extends IOException {

        public Lost(String record) {
            super("the job at " + record + " was taken over by another run");
        }
    }

    /** A resume lost its first write to a reap: the job it named was dismissed in the meantime. */
    public static final class Dismissed extends IOException {

        public Dismissed(String job) {
            super("job " + job + " was dismissed while it was being resumed; start it again");
        }
    }
}
