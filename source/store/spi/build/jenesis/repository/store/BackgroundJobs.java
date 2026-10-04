package build.jenesis.repository.store;

import module java.base;
import module org.slf4j;

/**
 * The background jobs a deployment started over its store - an import, an export, a stored report, a repository's
 * removal - which end with the deployment rather than outliving it.
 *
 * <p>A job is started through {@link #start(ArtifactStore, String, Runnable)} with the store it writes into. A
 * deployment binds one of these to its store ({@link StoreBindings}) and closes it before the store as it shuts down:
 * closing interrupts every job still running and waits a bounded time for each to stop, so no job of a closed
 * deployment keeps writing into its store. A job is resumable by design - its record says how far it got and marks a
 * run that stopped part way - so an interrupted one is picked up again as an interrupted run is. A store no deployment
 * bound one to - a tool, a test - starts its jobs untracked, to end with the process.
 */
public final class BackgroundJobs implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(BackgroundJobs.class);

    /** How long closing waits for the jobs it interrupted, in all. */
    private static final Duration STOPPING = Duration.ofSeconds(30);

    private final Set<Thread> running = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    /** Start {@code job} on a thread named {@code name}, tracked by the jobs bound to {@code store} when any are. */
    public static Thread start(ArtifactStore store, String name, Runnable job) {
        Optional<BackgroundJobs> jobs = store.bindings().get(BackgroundJobs.class);
        return jobs.isPresent() ? jobs.get().start(name, job) : Thread.ofVirtual().name(name).start(job);
    }

    /** Start {@code job} on a thread named {@code name}, tracked until it ends.
     *
     *  @throws IllegalStateException once these jobs are closed, so a job is not started for a deployment going away */
    public Thread start(String name, Runnable job) {
        if (closed) {
            throw new IllegalStateException("the deployment is shutting down, so " + name + " is not started");
        }
        Thread thread = Thread.ofVirtual().name(name).unstarted(() -> {
            try {
                job.run();
            } finally {
                running.remove(Thread.currentThread());
            }
        });
        running.add(thread);
        thread.start();
        return thread;
    }

    /** Interrupt every job still running and wait a bounded time for each to stop; one still running after says so. */
    @Override
    public void close() {
        closed = true;
        List<Thread> stopping = List.copyOf(running);
        stopping.forEach(Thread::interrupt);
        Instant deadline = Instant.now().plus(STOPPING);
        for (Thread thread : stopping) {
            Duration left = Duration.between(Instant.now(), deadline);
            try {
                if (left.isNegative() || !thread.join(left)) {
                    LOGGER.warn("{} did not stop within {} of being interrupted as the deployment shut down",
                            thread.getName(), STOPPING);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
