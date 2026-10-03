package build.jenesis.repository.metadata;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Retries;

/**
 * A node's writers of one metadata document land together rather than one after another.
 *
 * <p>A version's document is written by every file of that version - its published section, origin row, licences,
 * each file's own facts - and the files of one release arrive at once. Each writer is a read-decide-compare-and-set,
 * and taken one at a time, sixteen files on one node are sixteen compare-and-sets, each of which another node's writer
 * of the same document may beat; a writer that loses every one of its tries fails its upload. So a writer hands its
 * {@link Retries.Decision} in, and whichever writer holds the document's turn decides for every writer waiting on it:
 * each decision in the order they arrived, each over the body the one before it decided, and the result written once.
 * A burst on one node is then a handful of compare-and-sets whatever its size, and what contends is one write per
 * node.
 *
 * <p>Every writer of the document comes through here - the section mutate and the inventory's publish, licence and
 * reconcile writes - since a document some writers bypass is one whose writes race again. A decision decides from what
 * it is handed and writes nothing, so it is safe to ask it again on every try and over another writer's body. One that
 * throws fails its own writer and is left out of the write; the others land without it. Striped, so the set of turns is
 * bounded; two documents sharing a stripe only wait for each other.
 */
public final class DocumentTurns {

    private static final ReentrantLock[] STRIPES = IntStream.range(0, 256).mapToObj(_ -> new ReentrantLock())
            .toArray(ReentrantLock[]::new);

    /** The writers waiting on each document, by the store's identity and the key; a document nobody waits on has none. */
    private static final ConcurrentMap<String, Queue<Waiting<?>>> WAITING = new ConcurrentHashMap<>();

    private DocumentTurns() {
    }

    /**
     * Decide {@code key} of {@code store} with {@code decision} under compare-and-set, together with this node's other
     * writers of the same document, and answer what the decision attached to the try that landed. Throws what the
     * decision threw, or {@link Retries.Contended} when every try lost.
     */
    public static <T> T decide(ArtifactStore store, String key, Retries.Decision<T> decision) throws IOException {
        String id = store.identity() + "\u0000" + key;
        Waiting<T> mine = new Waiting<>(decision);
        Queue<Waiting<?>> queue = WAITING.computeIfAbsent(id, _ -> new ConcurrentLinkedQueue<>());
        queue.add(mine);
        ReentrantLock stripe = STRIPES[Math.floorMod(id.hashCode(), STRIPES.length)];
        stripe.lock();
        try {
            if (!mine.done) {
                List<Waiting<?>> batch = new ArrayList<>();
                for (Waiting<?> waiting = queue.poll(); waiting != null; waiting = queue.poll()) {
                    batch.add(waiting);
                }
                WAITING.computeIfPresent(id, (_, waiting) -> waiting.isEmpty() ? null : waiting);
                land(store, key, batch);
            }
        } finally {
            stripe.unlock();
        }
        return mine.answer();
    }

    /** Decide every waiting writer over one read, try after try, and settle each with what the landed try said. */
    private static void land(ArtifactStore store, String key, List<Waiting<?>> batch) {
        Optional<Retries.Verdict<List<Outcome>>> landed;
        try {
            landed = Retries.tryDecide(store, key, current -> {
                Optional<ArtifactStore.Versioned> state = current;
                byte[] body = null;
                List<Outcome> outcomes = new ArrayList<>(batch.size());
                for (Waiting<?> waiting : batch) {
                    Retries.Verdict<?> verdict;
                    try {
                        verdict = waiting.decision.decide(state);
                    } catch (IOException | RuntimeException failure) {
                        outcomes.add(new Outcome(null, failure));
                        continue;
                    }
                    outcomes.add(new Outcome(verdict.result(), null));
                    if (verdict.body() != null) {
                        body = verdict.body();
                        state = Optional.of(new ArtifactStore.Versioned(body,
                                current.map(ArtifactStore.Versioned::token).orElse(null)));
                    }
                }
                return body == null ? Retries.Verdict.keep(outcomes) : Retries.Verdict.write(body, outcomes);
            });
        } catch (IOException | RuntimeException failure) {
            batch.forEach(waiting -> waiting.settle(new Outcome(null, failure)));
            return;
        }
        if (landed.isEmpty()) {
            batch.forEach(waiting -> waiting.settle(new Outcome(null, new Retries.Contended(key))));
            return;
        }
        List<Outcome> outcomes = landed.get().result();
        for (int index = 0; index < batch.size(); index++) {
            batch.get(index).settle(outcomes.get(index));
        }
    }

    /** What one writer's decision came to on the try that landed: its result, or what it threw. */
    private record Outcome(Object result, Exception failure) {
    }

    /** One writer waiting on a document: its decision, and once the write landed, what it came to. Settled under the
     *  document's turn and read by its own writer after taking that turn, so the lock orders the two. */
    private static final class Waiting<T> {

        private final Retries.Decision<T> decision;
        private boolean done;
        private Outcome outcome;

        private Waiting(Retries.Decision<T> decision) {
            this.decision = decision;
        }

        private void settle(Outcome outcome) {
            this.outcome = outcome;
            this.done = true;
        }

        @SuppressWarnings("unchecked")
        private T answer() throws IOException {
            if (outcome.failure() instanceof IOException failure) {
                throw failure;
            }
            if (outcome.failure() instanceof RuntimeException failure) {
                throw failure;
            }
            return (T) outcome.result();
        }
    }
}
