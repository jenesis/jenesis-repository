package build.jenesis.repository.server.spi;

import module java.base;

import build.jenesis.repository.store.HeldWrites;

/**
 * A bounded in-memory queue drained by one worker thread in batches: the shape every best-effort tracker here has -
 * the request path offers a hit and never blocks, a full queue drops rather than back-pressures, and the worker
 * folds what accumulated into a store write on its own schedule.
 *
 * <p>The credential-usage tracker and the last-download tracker share it: the queue, the {@code poll}-then-
 * {@code drainTo} loop, the start and the stop-and-join close. What each keeps is what it does with a batch
 * ({@link #drain}) and what it does on the two edges this class exposes as hooks: a failing iteration
 * ({@link #onIterationFailure}) and a close that did or did not manage to stop the worker ({@link #onClosed}).
 *
 * <p><b>Drop, never block.</b> A hit is a signal a request emits on its way out, never something the request waits
 * for; when the queue is full the hit is counted as dropped and the request proceeds. A tracker whose store is
 * failing therefore shows on the health surface as dropping, not as slow requests.
 *
 * <p><b>Held, and written on request.</b> A running worker is one of the node's {@link HeldWrites}: it says what it
 * holds, and {@link #writeNow} asks it to write all of it after the batch it is folding, on its own thread.
 *
 * <p><b>Close is a join, not a flush.</b> Asking the worker to stop and joining it with a grace window is all this
 * class does. It asks by waking the worker's poll with a stop marker rather than by interrupting it: an interrupt
 * that lands inside a batch's store write closes the file channel under it, so a write that landed answers as
 * failed and the tracker re-applies a delta the store already holds. Whether the un-drained tail is then flushed is the subclass's call in {@link #onClosed}, because it
 * depends on what a half-drained batch means for that tracker's counters - and only a subclass that knows the
 * worker has genuinely stopped may touch state the worker mutates.
 *
 * @param <H> the hit type the request path offers
 */
public abstract class BatchingWorker<H> implements HeldWrites.Holder {

    /** What {@link #close} queues to wake a worker waiting on an empty queue; never handed to {@link #drain}. */
    private static final Object STOP = new Object();

    /** What {@link #writeNow} queues to wake the worker; never handed to {@link #drain}. */
    private static final Object WRITE = new Object();

    /** The hits, and at most one {@link #STOP} once the worker has been asked to stop. */
    private final BlockingQueue<Object> queue;
    private final int capacity;
    private final String threadName;
    private final boolean enabled;
    private final AtomicLong dropped = new AtomicLong();
    private volatile boolean running;
    private volatile boolean writeRequested;
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
        HeldWrites.hold(this);
    }

    /**
     * Ask the worker to write everything it holds after the batch it is folding: it drains what is queued and hands
     * it to {@link #onWriteRequested} on its own thread, the one that owns the accumulators, so the request never races
     * a drain. A full queue refuses the wake-up, which costs nothing: a worker with a full queue is not waiting, and it
     * reads the request after its batch. A worker that is not running holds nothing to write.
     */
    @Override
    public final void writeNow() {
        if (!running) {
            return;
        }
        writeRequested = true;
        queue.offer(WRITE);
    }

    /** Stop the worker: ask it to stop after the batch it is folding, join it for ten seconds, then hand the subclass
     *  whether it actually stopped. A worker that did not stop within the grace window is still draining, and a
     *  subclass must not flush over it. A full queue refuses the stop marker, which costs nothing: a worker with a
     *  full queue is not waiting, and it reads the cleared flag after its batch. */
    public void close() {
        HeldWrites.release(this);
        running = false;
        Thread worker = thread;
        boolean terminated = worker == null;
        if (worker != null) {
            queue.offer(STOP);
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

    /** On the worker's thread, after {@link #writeNow}: write everything held, cadence or not, the queue's tail
     *  included ({@link #drainQueue}). Does nothing by default; a failure in it is reported through
     *  {@link #onIterationFailure}. */
    protected void onWriteRequested(Instant now) {
    }

    /** A drain iteration threw. The worker stays alive regardless; a subclass may log, back off or both. */
    protected void onIterationFailure(RuntimeException failure) {
    }

    /** Called about once a second while nothing is queued, so a worker that holds work back on a clock - a delta
     *  waiting for its flush interval - releases it without waiting for the next hit. Does nothing by default; a
     *  failure in it is reported through {@link #onIterationFailure} like a failing drain. */
    protected void onIdle(Instant now) {
    }

    /** Everything still queued, removed - for a subclass's {@link #onClosed} to fold the tail of a clean shutdown. */
    protected final List<H> drainQueue() {
        List<Object> tail = new ArrayList<>();
        queue.drainTo(tail);
        return hits(tail);
    }

    /** The hits among what was taken off the queue, without the stop marker. */
    @SuppressWarnings("unchecked")
    private List<H> hits(List<Object> taken) {
        List<H> hits = new ArrayList<>(taken.size());
        for (Object each : taken) {
            if (each != STOP && each != WRITE) {
                hits.add((H) each);
            }
        }
        return hits;
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
                Object first = queue.poll(1, TimeUnit.SECONDS);
                if (first == STOP) {
                    continue;
                }
                if (first != null && first != WRITE) {
                    List<Object> taken = new ArrayList<>();
                    taken.add(first);
                    queue.drainTo(taken);
                    List<H> batch = hits(taken);
                    if (!batch.isEmpty()) {
                        drain(batch, Instant.now());
                    }
                } else if (first == null) {
                    onIdle(Instant.now());
                }
                if (writeRequested) {
                    writeRequested = false;
                    onWriteRequested(Instant.now());
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
