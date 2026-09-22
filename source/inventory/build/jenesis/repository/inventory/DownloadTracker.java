package build.jenesis.repository.inventory;

import module java.base;

/**
 * Opt-in download tracking: a successful read offers a {@link Hit} and an implementation writes the coordinate's
 * last-downloaded marker into the repository inventory, off the request path - the signal the retention criterion
 * {@code not-downloaded-for} evicts by. How hits are batched and written is the implementation's part, supplied by
 * a {@link DownloadTrackerProvider} module discovered with {@link ServiceLoader}; with none installed {@link #NONE}
 * stands in - nothing records, a health surface reports the worker as off, and {@code not-downloaded-for} falls
 * back to judging by publish age. {@link #record} must never block or fail the read it observes.
 */
public interface DownloadTracker extends AutoCloseable {

    /** A read worth recording: which tenant/repository and ecosystem coordinate version was downloaded. */
    record Hit(String tenant, String repository, String ecosystem, String coordinate, String version) {
    }

    /** Whether tracking is switched on; {@link #record} is a no-op when it is not. */
    boolean enabled();

    /** How long hits are held before they are flushed, when the tracker holds them at all: the most a count or a
     *  last-download instant can be behind, which a surface showing them says beside them. Empty when every hit is
     *  written as it comes, or when nothing is tracked. */
    default Optional<Duration> flushInterval() {
        return Optional.empty();
    }

    /** Whether the worker is started and alive; an enabled tracker whose worker has died is unhealthy. */
    boolean alive();

    /** Reads dropped under back-pressure - surfaced on health. */
    long dropped();

    /** Offer a read for tracking - non-blocking, best-effort, a no-op when tracking is off. */
    void record(Hit hit);

    void start();

    @Override
    void close();

    /** The shared tracker standing in when no downloads module is installed: it records nothing. A singleton, so a
     *  composition can tell "not installed" by identity. */
    DownloadTracker NONE = new DownloadTracker() {

        @Override
        public boolean enabled() {
            return false;
        }

        @Override
        public boolean alive() {
            return false;
        }

        @Override
        public long dropped() {
            return 0L;
        }

        @Override
        public void record(Hit hit) {
        }

        @Override
        public void start() {
        }

        @Override
        public void close() {
        }
    };
}
