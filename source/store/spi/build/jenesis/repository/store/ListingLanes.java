package build.jenesis.repository.store;

import module java.base;

import static build.jenesis.repository.store.StoredListing.*;
import static build.jenesis.repository.store.ListingFrame.*;
import static build.jenesis.repository.store.ListingMerge.*;

/**
 * The per-node lanes a {@link StoredListing} write rides: concurrent changes to one document queue behind the writer
 * that reached it first, which applies them all in one read-modify-write and hands each queued writer the outcome.
 */
final class ListingLanes {

    private ListingLanes() {
    }

    static final ConcurrentMap<LaneKey, Lane> LANES = new ConcurrentHashMap<>();

    /** Queue one change set or regeneration on the document's lane and wait for the round that carries it. */
    static Applied enqueue(ArtifactStore store, Spec spec, Pending mine) throws IOException {
        LaneKey laneKey = new LaneKey(store.identity(), spec.key());
        Lane lane = LANES.computeIfAbsent(laneKey, ignored -> new Lane());
        boolean runner;
        synchronized (lane) {
            if (lane.running && lane.runner == Thread.currentThread()) {
                // A generator or derivation updating the listing it belongs to would wait for itself: refuse rather
                // than deadlock. A derivation that needs another listing updates THAT listing, never its own.
                throw new IllegalStateException("re-entrant update of listing " + spec.key());
            }
            lane.queue.add(mine);
            runner = !lane.running;
            if (runner) {
                lane.running = true;
                lane.runner = Thread.currentThread();
            }
        }
        if (runner) {
            run(laneKey, lane, store, spec);
        }
        return await(mine.outcome());
    }

    record LaneKey(Object identity, String key) {
    }

    /** One queued round member: a change set, or a regeneration of the whole document from its generator. */
    record Pending(Map<String, Change> changes, Set<String> prefixes, boolean regenerate,
                           CompletableFuture<Applied> outcome) {
    }

    /** What a round did: whether the changes landed within the attempts, and the header written when it wrote. */
    record Applied(boolean landed, Header header) {
    }

    static final class Lane {

        private final ArrayDeque<Pending> queue = new ArrayDeque<>();
        private boolean running;
        private Thread runner;
    }

    static void run(LaneKey laneKey, Lane lane, ArtifactStore store, Spec spec) {
        while (true) {
            List<Pending> batch;
            synchronized (lane) {
                batch = List.copyOf(lane.queue);
                lane.queue.clear();
                if (batch.isEmpty()) {
                    lane.running = false;
                    lane.runner = null;
                    // A drained lane leaves the map, so the map holds only the documents being written right now. A
                    // writer that fetched this lane just before the removal runs it on its own - it loses nothing but
                    // the coalescing, since the compare-and-set decides between writers of one document regardless.
                    LANES.remove(laneKey, lane);
                    return;
                }
            }
            if (batch.size() > 1) {
                COALESCED.add(batch.size() - 1);
            }
            try {
                Applied applied = apply(store, spec, batch);
                for (Pending pending : batch) {
                    pending.outcome().complete(applied);
                }
            } catch (Throwable failure) {
                for (Pending pending : batch) {
                    pending.outcome().completeExceptionally(failure);
                }
                if (failure instanceof Error error) {
                    List<Pending> stranded;
                    synchronized (lane) {
                        stranded = List.copyOf(lane.queue);
                        lane.queue.clear();
                        lane.running = false;
                        lane.runner = null;
                        LANES.remove(laneKey, lane);
                    }
                    for (Pending pending : stranded) {
                        pending.outcome().completeExceptionally(failure);
                    }
                    throw error;
                }
            }
        }
    }
    static Applied await(CompletableFuture<Applied> outcome) throws IOException {
        try {
            return outcome.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted waiting for a listing update", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IOException("listing update failed", cause);
        }
    }
}
