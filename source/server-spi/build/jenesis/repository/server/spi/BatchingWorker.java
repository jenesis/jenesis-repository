package build.jenesis.repository.server.spi;

import module java.base;

/**
 * A bounded in-memory queue drained by one worker thread in batches: the shape every best-effort tracker here has -
 * the request path offers a hit and never blocks, a full queue drops rather than back-pressures, and the worker
 * folds what accumulated into a store write on its own schedule.
 *
 * <p>Two trackers kept one: the free core's credential-usage tracker and the enterprise last-download tracker had the
 * same queue, the same {@code poll}-then-{@code drainTo} loop, the same start and the same interrupt-and-join close,
 * and their javadocs cited each other as the rule to mirror. What each keeps is what it does with a batch
 * ({@link #drain}) and what it does on the two edges this class exposes as hooks: a failing iteration
 * ({@link #onIterationFailure}) and a close that did or did not manage to stop the worker ({@link #onClosed}).
 *
 * <p><b>Drop, never block.</b> A hit is a signal a request emits on its way out, never something the request waits
 * for; when the queue is full the hit is counted as dropped and the request proceeds. A tracker whose store is
 * failing therefore shows on the health surface as dropping, not as slow requests.
 *
 * <p><b>Close is a join, not a flush.</b> Interrupting the worker and joining it with a grace window is all this
 * class does; whether the un-drained tail is then flushed is the subclass's call in {@link #onClosed}, because it
 * depends on what a half-drained batch means for that tracker's counters - and only a subclass that knows the
 * worker has genuinely stopped may touch state the worker mutates.
 *
 * @param <H> the hit type the request path offers
 */
public abstract class BatchingWorker<H> {

    private final BlockingQueue<H> queue;
    private final int capacity;
    private final String threadName;
    private final boolean enabled;
    private final AtomicLong dropped = new AtomicLong();
    private volatile boolean running;
    // Written by start() and read by close(), which may run on different threads, so published safely - a stale
    // null in close() would neither interrupt nor join the worker and leak the thread on shutdown.
    private volatile Thread thread;

    /**
     * @param threadName the worker thread's name, so a thread dump says which tracker it is
     * @param enabled    whether hits are accepted at all; a disabled worker never starts and drops nothing
     * @param capacity   the queue bound past which a hit is dropped rather than blocking a request
     */
    protected BatchingWorker(String threadName, boolean enabled, int capacity) {
        this.threadName = Objects.requireNonNull(threadName, "threadName");
        this.enabled = enabled;
        this.capacity = capacity;
        this.queue = new LinkedBlockingQueue<>(capacity);
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean alive() {
        Thread worker = thread;
        return worker != null && worker.isAlive();
    }

    /** Hits dropped because the queue was full. Overridable so a tracker can fold in a second cause of loss - a
     *  store write that failed - and report both through the one figure its health surface reads. */
    public long dropped() {
        return dropped.get();
    }

    /** Offer a hit off the request path: accepted when enabled and there is room, counted as dropped otherwise. */
    protected final void offer(H hit) {
        if (enabled && !queue.offer(hit)) {
            dropped.incrementAndGet();
        }
    }

    public void start() {
        if (!enabled) {
            return;
        }
        running = true;
        Thread worker = new Thread(this::loop, threadName);
        thread = worker;
        worker.start();
    }

    /** Stop the worker: interrupt it, join it for ten seconds, then hand the subclass whether it actually stopped. A
     *  worker that did not stop within the grace window is still draining, and a subclass must not flush over it. */
    public void close() {
        running = false;
        Thread worker = thread;
        boolean terminated = worker == null;
        if (worker != null) {
            worker.interrupt();
            try {
                worker.join(10_000L);
                terminated = !worker.isAlive();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        onClosed(terminated);
    }

    /** After {@link #close}: {@code terminated} says the worker has stopped (or never started), so every structure
     *  it mutates is quiescent and a final flush is safe. When {@code false} the worker is still running and its
     *  next drain owns the tail; touching shared counters here would race it. */
    protected void onClosed(boolean terminated) {
    }

    /** A drain iteration threw. The worker stays alive regardless; a subclass may log, back off or both. */
    protected void onIterationFailure(RuntimeException failure) {
    }

    /** Everything still queued, removed - for a subclass's {@link #onClosed} to fold the tail of a clean shutdown. */
    protected final List<H> drainQueue() {
        List<H> tail = new ArrayList<>();
        queue.drainTo(tail);
        return tail;
    }

    public final int queueDepth() {
        return queue.size();
    }

    public final int capacity() {
        return capacity;
    }

    /** Fold one batch of hits at {@code now} - the tracker's whole job, invoked by the worker and by tests. */
    public abstract void drain(Collection<H> batch, Instant now);

    private void loop() {
        while (running) {
            try {
                H first = queue.poll(1, TimeUnit.SECONDS);
                if (first != null) {
                    List<H> batch = new ArrayList<>();
                    batch.add(first);
                    queue.drainTo(batch);
                    drain(batch, Instant.now());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                onIterationFailure(e);
            }
        }
    }
}
