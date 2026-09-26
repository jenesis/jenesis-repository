package build.jenesis.repository.store;

import module java.base;

/**
 * The one retry policy for a compare-and-set through {@link ArtifactStore#writeVersioned}: how often a writer re-reads
 * the token and tries again before it gives the conflict up, and how long it waits between tries.
 *
 * <p>A lost compare-and-set is a peer writing the same key at the same moment - several identical publishes of one
 * version, the files of one revision arriving one after the other, a sweep and a publish meeting on a marker. Three
 * immediate retries were enough for two writers and not for four: with no pause between tries, the losers re-read
 * and re-write in lock step and lose again, and the writer that gave up answered a {@code 500} for a publish whose
 * bytes had landed. Twelve tries with a short, jittered pause between them spread the writers out; the pause stays
 * under a tenth of a second so the request that waits never waits long.
 *
 * <p>{@link #update} and {@link #tryUpdate} are the policy applied: read the key, let a {@link Mutation} decide the
 * new body from what is there, write it against the token that was read, and on a lost race back off and go round
 * again. Before they existed the loop was written out at some thirty-five sites with three, four, five, eight or
 * sixteen tries and no pause between them - the exact shape this class's own paragraph explains does not work - and
 * three different endings for the same exhaustion: throw, return silently, or spin. There are two endings now and a
 * caller picks one by name: {@link #update} throws, because a writer that gives up a compare-and-set has usually
 * lost something the caller must know about; {@link #tryUpdate} returns {@code false}, for the few writes whose loss
 * a later pass repairs - and a caller choosing it says in its javadoc which pass that is.
 *
 * <p>{@link #decide} and {@link #tryDecide} are the same policy for a write that has more to say than a body: the
 * {@link Decision} hands back a {@link Verdict} - write this body, or keep the key as it is - together with a value
 * the caller needs from the try that landed (the pointer that was replaced, whether a publish was the first, the
 * transition a re-fold is made from). Twenty-five loops still stood after the first sweep, most of them written out
 * only to keep such a value in a local; a loop that keeps a value is not a reason to keep a loop.
 *
 * <p><b>There is no delete verdict.</b> The store has no conditional delete, so a key deleted because the deciding try
 * found its set empty loses whatever a peer wrote into it between that read and the delete - an alias appended to a
 * group, a statement added to an identifier's list - with nothing to say so. A set that empties is written empty,
 * under the same compare-and-set as every other write, and a peer's append then meets a conflict and is retried
 * rather than lost. What stays empty goes with whatever the key belongs to, never by a delete decided on a read.
 */
public final class Retries {

    /** How often a compare-and-set is tried before its conflict is given up. */
    public static final int COMPARE_AND_SET = 12;

    private static final LongAdder TRIED = new LongAdder();
    private static final LongAdder REPLAYED = new LongAdder();
    private static final LongAdder LOST_TO_PEER = new LongAdder();
    private static final LongAdder LOST_UNSETTLED = new LongAdder();

    private Retries() {
    }

    /**
     * A compare-and-set that lost every one of its tries: peers on the same key at the same moment, more of them or
     * for longer than {@link #COMPARE_AND_SET} tries outlast. Nothing is wrong with the write or with the store, so an
     * edge answers it as a transient refusal - {@code 503} with a {@code Retry-After}, which clients retry - and never
     * as a server error. Within a node, writers of one document take turns, so what contends here is one writer per
     * node; a burst of a version's files through two nodes lands without meeting it.
     */
    public static final class Contended extends IOException {

        public Contended(String what) {
            super("lost the compare-and-set on " + what + " " + COMPARE_AND_SET + " times running; send it again");
        }
    }

    /** Wait before the next try: a few milliseconds at first, doubling to at most a hundred, plus a little jitter so
     *  peers that lost together do not retry together. */
    public static void backoff(int attempt) {
        try {
            Thread.sleep(Math.min(100L, 2L << Math.min(attempt, 6)) + ThreadLocalRandom.current().nextLong(5L));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * What a compare-and-set writes, decided from what the key holds at the moment of the read: the new body, or
     * {@code null} to leave the key as it is - the first-write-wins marker that is already there, the counter that
     * needs no change, the lease another holder now owns.
     */
    @FunctionalInterface
    public interface Mutation {

        byte[] apply(Optional<ArtifactStore.Versioned> current) throws IOException;
    }

    /**
     * One try of a compare-and-set that is not a plain store key - a document behind another versioned API - read,
     * changed and conditionally written by the caller; answers whether it landed (or found nothing to do).
     */
    @FunctionalInterface
    public interface Attempt {

        boolean tryOnce() throws IOException;
    }

    /**
     * What one try of {@link #decide} decided from the key's current content: a body to write, or nothing to do - and
     * the value the caller gets back from the try that lands.
     */
    public record Verdict<T>(byte[] body, T result) {

        /** Write {@code body} against the token that was read; {@code result} is the caller's if it lands. */
        public static <T> Verdict<T> write(byte[] body, T result) {
            return new Verdict<>(Objects.requireNonNull(body, "body"), result);
        }

        /** Leave the key as it is - nothing to change - and answer {@code result} at once. */
        public static <T> Verdict<T> keep(T result) {
            return new Verdict<>(null, result);
        }
    }

    /** A {@link Mutation} that also decides between writing and keeping, and carries a value back. */
    @FunctionalInterface
    public interface Decision<T> {

        Verdict<T> decide(Optional<ArtifactStore.Versioned> current) throws IOException;
    }

    /**
     * Apply {@code mutation} to {@code key} under compare-and-set, re-reading and retrying with {@link #backoff} for
     * up to {@link #COMPARE_AND_SET} tries. Throws when every try lost, naming the key: a writer that gives the
     * conflict up has usually lost a record the caller must not pretend it kept.
     */
    public static void update(ArtifactStore store, String key, Mutation mutation) throws IOException {
        compareAndSet(key, () -> tryOnce(store, key, mutation));
    }

    /**
     * {@link #update}, answering {@code false} instead of throwing when every try lost. For the few writes a caller
     * can afford to lose because a later pass re-derives the key - a usage counter, a size roll-up - and such a
     * caller names that pass where it chooses this form, so the loss is a known repair and not a silent one.
     */
    public static boolean tryUpdate(ArtifactStore store, String key, Mutation mutation) throws IOException {
        return tryCompareAndSet(() -> tryOnce(store, key, mutation));
    }

    /**
     * {@link #update} for a write with a {@link Verdict}: the {@link Decision} is asked of every re-read until a try
     * lands, and the value it attached to that try is answered - {@code null} if that is what it attached. Throws
     * when every try lost, naming the key.
     */
    public static <T> T decide(ArtifactStore store, String key, Decision<T> decision) throws IOException {
        Optional<Verdict<T>> landed = tryDecide(store, key, decision);
        if (landed.isEmpty()) {
            throw new Contended(key);
        }
        return landed.get().result();
    }

    /**
     * {@link #decide}, answering the verdict that landed - whose {@link Verdict#result} may be {@code null} - or empty
     * when every try lost, so a caller tells a landed nothing from exhaustion.
     */
    public static <T> Optional<Verdict<T>> tryDecide(ArtifactStore store, String key, Decision<T> decision)
            throws IOException {
        for (int tries = 0; tries < COMPARE_AND_SET; tries++) {
            Optional<ArtifactStore.Versioned> current = store.readVersioned(key);
            Verdict<T> verdict = decision.decide(current);
            if (verdict.body() == null) {
                return Optional.of(verdict);
            }
            TRIED.increment();
            if (store.writeVersioned(key, verdict.body(), current.map(ArtifactStore.Versioned::token).orElse(null))) {
                return Optional.of(verdict);
            }
            Optional<Verdict<T>> resolved = settled(store, key, decision, verdict);
            if (resolved.isPresent()) {
                return resolved;
            }
            backoff(tries);
        }
        return Optional.empty();
    }

    /** {@link #update} for a compare-and-set the caller performs itself: {@code attempt} is tried until it lands,
     *  with {@link #backoff} between tries, and the exhaustion throws naming {@code what}. */
    public static void compareAndSet(String what, Attempt attempt) throws IOException {
        if (!tryCompareAndSet(attempt)) {
            throw new Contended(what);
        }
    }

    /** {@link #tryUpdate} for a compare-and-set the caller performs itself. */
    private static boolean tryCompareAndSet(Attempt attempt) throws IOException {
        for (int tries = 0; tries < COMPARE_AND_SET; tries++) {
            if (attempt.tryOnce()) {
                return true;
            }
            backoff(tries);
        }
        return false;
    }

    private static boolean tryOnce(ArtifactStore store, String key, Mutation mutation) throws IOException {
        Optional<ArtifactStore.Versioned> current = store.readVersioned(key);
        byte[] body = mutation.apply(current);
        if (body == null) {
            return true;
        }
        TRIED.increment();
        return store.writeVersioned(key, body, current.map(ArtifactStore.Versioned::token).orElse(null))
                || settled(store, key, mutation, body);
    }

    /**
     * Whether the key already holds the body this attempt tried to write - the difference between losing a
     * compare-and-set and being told you lost one you won.
     *
     * <p><b>Why a refused write is not proof the write did not happen.</b> Between the caller and an object store
     * sits an SDK that retries. A conditional PUT that succeeds at the server and whose response is lost in
     * transit is sent again; the second attempt fails its precondition, because the first already moved the ETag;
     * and the store reports a lost compare-and-set for a write that landed. The caller then re-reads, recomputes
     * against its own bytes and writes again - once per retry, one read and one write each - which is why the
     * symptom is a publish whose read and write counts rise by <em>exactly the same</em> amount.
     *
     * <p>Measured 2026-09-11/12 by {@code StoreOperationsE2ETest}'s two-size claim: a serial publish - no peer,
     * nothing it could honestly lose to - paying twelve extra read-write pairs on azure-blob in one lane, eleven
     * on s3 two lanes later, and none on the filesystem in any of them. An SDK is the one thing an object store
     * has in that path and the filesystem does not.
     *
     * <p>So a refusal is re-read once before it is believed - and the question asked of the re-read is not "are
     * these my bytes" but "does this mutation still have anything to do". The mutation is applied to what the key
     * holds now: if it answers nothing to write, or bytes the key already holds, then the key is in the state the
     * mutation asks for and the caller's work is done however it got there. If it answers something else, the loss
     * is real - or is a replay this test cannot recognise - and the loop backs off as before. The check costs one
     * read and one further application on the losing path, against a whole read-write cycle for the retry it
     * replaces. The re-application is reached only where the key turned out to hold the bytes this try wrote,
     * because that is the only state a replay of this try could have left: a refusal over anything else is an
     * honest loss with no replay to recognise, and is retried without asking the mutation anything. So a mutation
     * that counts its own invocations sees one extra only on the path where its write may already have landed.
     *
     * <p><b>Why a fixed point rather than a comparison against the bytes this try wrote.</b> That was the first
     * cut, and it is wrong for two shapes of mutation, because for them "someone wrote these bytes" and "I wrote
     * these bytes" are different facts. An <em>accumulation</em> - {@link StoredCounter#add}'s
     * {@code current + delta} - gives two writers reading one base and flushing one delta byte-identical bodies,
     * so the loser reads its own arithmetic back and reports a success that dropped a delta, silently, where that
     * class documents a drop as {@code false} for the caller to log against the pass that repairs it. A
     * <em>claim</em> - a stamp a node writes to take a rebuild, whose whole point is which node holds it -
     * collides the same way for two nodes entering within one millisecond, and both would believe they hold it.
     * Re-applying catches both with nothing declared at the call site: the accumulator computes
     * {@code base + 2d} against a stored {@code base + d} and is not settled, and the claim's re-application sees
     * a rebuild in flight and keeps, which is the right answer for a loser.
     *
     * <p><b>What it therefore cannot do, deliberately.</b> A mutation whose re-application is not a fixed point
     * cannot be rescued from an SDK replay by reading the store, because the evidence that separates "my PUT
     * landed" from "a peer wrote the same bytes" is not in the store: both leave one key holding one body. Such a
     * mutation retries, and double-applies on a replay exactly as it did before this check existed. The paths the
     * replay cost was measured on - a publish's pointers, its blobs, its inventory sections, its listings - are
     * every one of them fixed points, which is why the repair reaches the cost without reaching the correctness.
     * A mutation whose rendering is not deterministic is not one either, and that is a trap worth naming because
     * it is invisible at the call site: {@link Properties#store(java.io.OutputStream, String)} writes a
     * {@code #<date>} line unasked, so a document rendered straight through it differs from itself every second
     * and can never settle. The product's own property documents do not have that problem - they render through
     * {@link Documents#bytes}, which exists to strip it - but anything reaching for {@code store} directly
     * reintroduces it, and pays a full retry on every phantom loss without anything saying so.
     *
     * <p>It is deliberately a comparison of content and not of tokens: a token says who wrote last, and the
     * question here is what the key holds.
     */
    private static boolean settled(ArtifactStore store, String key, Mutation mutation, byte[] tried)
            throws IOException {
        Optional<ArtifactStore.Versioned> now = store.readVersioned(key);
        if (now.isEmpty() || !Arrays.equals(now.get().content(), tried)) {
            LOST_TO_PEER.increment();
            return false;
        }
        byte[] again = mutation.apply(now);
        boolean settled = again == null || Arrays.equals(now.get().content(), again);
        (settled ? REPLAYED : LOST_UNSETTLED).increment();
        return settled;
    }

    /**
     * {@link #settled(ArtifactStore, String, Mutation, byte[])} for a {@link Decision}, which answers a value as well as a
     * body and so has one more case to get right.
     *
     * <p>A re-application that <em>keeps</em> - no body - is the decision saying that, given what the key holds,
     * there is nothing to write; its verdict is answered, because that is what the next try would have concluded
     * and because a decision declining on reality must not be overruled by the one that was made against a state
     * the store has moved past. A claim is the case that makes this load-bearing: the loser of a stamp collision
     * re-applies, sees a rebuild in flight and keeps, and must be told so rather than handed the win the first
     * application computed.
     *
     * <p>A re-application that writes bytes the key already holds is the fixed point, and there the verdict that
     * was <em>tried</em> is answered rather than the new one: this attempt's write is the one that landed, so the
     * state it read is the state it replaced, and a caller folding that transition needs the prior it saw and not
     * the value its own write left behind. Where two peers wrote identical bodies at once, both credit the same
     * transition; that over-counts a delta a recomputing pass corrects, which is the lesser of the two errors -
     * the other would report a fresh write as an overwrite and lose the event that hangs off freshness.
     */
    private static <T> Optional<Verdict<T>> settled(ArtifactStore store,
                                                    String key,
                                                    Decision<T> decision,
                                                    Verdict<T> tried) throws IOException {
        Optional<ArtifactStore.Versioned> now = store.readVersioned(key);
        if (now.isEmpty() || !Arrays.equals(now.get().content(), tried.body())) {
            LOST_TO_PEER.increment();
            return Optional.empty();
        }
        Verdict<T> again = decision.decide(now);
        if (again.body() == null) {
            REPLAYED.increment();
            return Optional.of(again);
        }
        if (Arrays.equals(now.get().content(), again.body())) {
            REPLAYED.increment();
            return Optional.of(tried);
        }
        LOST_UNSETTLED.increment();
        return Optional.empty();
    }

    /**
     * Every conditional write these loops have asked the store for - one per try that had a body to write, landed or
     * refused. The denominator the three verdicts below are read against: a refusal count means nothing until it is
     * a share of the tries, and the share is what tells a store that refuses one write in a thousand from one that
     * refuses every other one.
     */
    public static long tried() {
        return TRIED.sum();
    }

    /** Compare-and-sets refused by the store that {@link #settled(ArtifactStore, String, Mutation, byte[])} found had
     *  nothing left to do - a write reported lost that had landed. A number that climbs on an object store and
     *  stays at zero on a filesystem is the SDK-replay shape that method describes. */
    public static long replayed() {
        return REPLAYED.sum();
    }

    /** Compare-and-sets the re-application did not find settled - either a real loss, or a replay of a mutation
     *  whose re-application cannot recognise itself. Both are retried, which is right for the first and is the
     *  documented limit for the second. The sum of {@link #lostToPeer()} and {@link #lostUnsettled()}. */
    public static long lost() {
        return LOST_TO_PEER.sum() + LOST_UNSETTLED.sum();
    }

    /**
     * Refusals where the re-read found the key holding something other than this try's bytes, or nothing at all: a
     * peer wrote the key between the read and the write, or deleted it. The honest loss, and the one a replay can
     * never be mistaken for, because a replayed write leaves the key holding exactly what this try wrote.
     *
     * <p>Kept apart from {@link #lostUnsettled()} because the two answer different questions. On a serial publish -
     * no peer, nothing to lose to - this figure must stay at zero on every backing; one that climbs there is a store
     * refusing a write it did not, in fact, refuse for a reason this loop can see. Measured on the store-operations
     * canary of 2026-09-12 the question was open, because the two verdicts were one counter that nothing read.
     */
    public static long lostToPeer() {
        return LOST_TO_PEER.sum();
    }

    /** Refusals where the key held this try's bytes but the re-application still had work to do - an accumulation
     *  whose delta must be applied once more, a claim whose loser must not be handed the win, a decision whose answer
     *  is a delete. Retried, and on a genuine replay double-applied, which is the documented limit of the fixed-point
     *  check rather than a fault in the store. */
    public static long lostUnsettled() {
        return LOST_UNSETTLED.sum();
    }
}
