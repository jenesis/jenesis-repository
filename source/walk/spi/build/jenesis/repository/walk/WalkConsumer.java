package build.jenesis.repository.walk;

import module java.base;

import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Features;

/**
 * The walk half of the two-route derived-metadata contract. A plugin keeps its derived state correct by exactly two
 * routes, and a correct plugin implements <em>both</em>: <b>live events</b> ({@code PublicationObserver}'s
 * {@code onPublished} / {@code onDeleted}) for the steady state, and <b>the full walk</b> - this interface - for
 * first-activation back-fill, periodic refresh and self-heal. A scheduled walk pass ({@link RebuildPass}) drives
 * every discovered consumer from <em>one</em> enumeration of each key family they listen on (clause 13), so N
 * rebuilders over the same store never mean N tree walks, and a consumer that must see withheld pointers says so
 * (clause 14) rather than walking on its own. The walk alone must be able to
 * fully rebuild the plugin's derived state from the durable store wherever the truth model permits; where a surface
 * genuinely cannot be re-derived (a human decision, a point-in-time observation), the plugin's documentation names
 * it and the plugin degrades gracefully rather than serving a silently-incomplete view as if it were whole.
 *
 * <p>{@link #onRetained} is called once per retained artifact per pass - and, across a crash-resume, at least once
 * for the uncommitted stride tail - so it must be <em>idempotent</em> (upsert / re-judge semantics). A streaming
 * consumer (a reconcile leg, a sidecar heal, a per-shard index) simply resumes mid-pass with the segment cursor; a
 * snapshot rebuilder (one artifact committed at pass end) restarts its own accumulation after a crash and says so -
 * degrade-and-say-so is recorded per consumer, never silent.
 *
 * <h2>Contract</h2>
 * <ol>
 * <li><b>Thread-safety.</b> One {@link RebuildPass#run} call drives one worker, and that worker calls
 *     {@link #onPassStarted}, {@link #onRetained}, {@link #beforeCheckpoint} and {@link #onPassCompleted} on a single
 *     thread, in that order. A deployment that fans {@code run} across threads or nodes calls the <em>same</em>
 *     discovered instance from several workers concurrently, so a consumer that keeps per-pass state must either be
 *     safe under that fan-out or declare itself single-worker (the snapshot shape below). The instance is shared for
 *     the process's life; it is never given a worker of its own.</li>
 * <li><b>Idempotency / replay.</b> {@link #onRetained} is an <em>upsert / re-judge</em>, never an append or an
 *     increment. It is called exactly once per retained pointer in a pass that does not crash, and at least once for
 *     the uncommitted stride tail after a crash-resume, so the same artifact is legitimately delivered twice with the
 *     same arguments. A second full pass over unchanged stored state must leave the consumer's durable projection
 *     exactly as the first left it - same objects, same content - or the pass is a generator of garbage rather than a
 *     converge pass (&sect;4).</li>
 * <li><b>Absence sentinel.</b> {@link #name()} returns a non-blank, stable, lower-case name - the settings namespace,
 *     the {@code jenreg.<name>=false} toggle key and the consumer's own key space. {@code null} is never
 *     a legal return, and the hooks return nothing: a consumer signals "I could not converge" through its own durable
 *     say-so surface (clause 8), never by returning quietly.</li>
 * <li><b>Streaming (&sect;1).</b> {@link #onRetained} is handed a descriptor and the walked store, never the artifact's
 *     bytes. A consumer that must look inside an artifact streams it from {@code blobs/<hash>} through the store it is
 *     handed and bounds what it reads; it never materialises an artifact to derive metadata from it.</li>
 * <li><b>Tenant scoping (&sect;6).</b> The {@link ArtifactStore} argument <em>is</em> the scope: it is the store the
 *     pass enumerated, already scoped by the caller. A consumer derives every key it writes from that argument and
 *     never captures a store from anywhere else, so one deployment's pass can never write into another tenant's
 *     namespace.</li>
 * <li><b>Error visibility (&sect;9).</b> An {@link IOException} (or a runtime failure) out of any hook fails
 *     <em>this consumer's generation</em>: the pass records it under {@code walks/rebuild/failed/<name>} with the
 *     generation and the key, hands this consumer nothing more until the next generation, and completes for the
 *     others; the task that drove the pass reports the consumer as failed, and the next generation - a full pass -
 *     redelivers everything to it ({@link RebuildPass#failed}). A consumer must therefore not catch its own store
 *     failures into a shrug: a swallowed write is exactly the silently-incomplete projection &sect;5 forbids, while a
 *     thrown one is recorded, reported and redelivered. The pass hooks are not declared to throw, so a consumer that
 *     persists in them wraps a store failure in an {@link UncheckedIOException}, which is contained the same way.
 *     A failure of the walk itself - the store refusing the pass's own read or cursor commit - is nobody's and
 *     propagates, leaving the pass active and resumable.</li>
 * <li><b>Read purity (&sect;10).</b> A pass is a read of durable state plus a write of derived state. Neither hook may
 *     fetch from an upstream, call a scanner, or otherwise reach outside the store: the walk must produce the same
 *     projection when every external system is down.</li>
 * <li><b>Staleness (&sect;5, &sect;10).</b> A consumer that could not converge - the snapshot shape after a
 *     crash-resume - records that fact durably and surfaces it, rather than committing what it accumulated. It must
 *     never replace a whole projection with a partial one: serving a silently-incomplete view as if it were whole is
 *     the one outcome this SPI exists to prevent. The pass generation and {@link WalkPass#started()} are what a
 *     consumer stamps onto its projection so a reader can tell how fresh it is.</li>
 * <li><b>Lifecycle / ownership.</b> Instances come from {@link #discovered()}, which builds a fresh list per call from
 *     {@link ServiceLoader} and drops the ones a {@code jenreg.<name>=false} toggle disables. A consumer
 *     therefore owns no threads and no clients, and - because a process death is indistinguishable from a fresh
 *     start - keeps no cross-pass state it cannot rebuild from the store.</li>
 * <li><b>Ordering / concurrency.</b> Within one worker: {@link #onPassStarted} fires before that worker's first
 *     {@link #onRetained}; keys arrive in the walk's total path order <em>within a segment</em>; {@link #beforeCheckpoint}
 *     fires after the deliveries it covers and before the cursor that would skip them is committed; and
 *     {@link #onPassCompleted} fires once this worker observed the pass complete. Across segments and workers there
 *     is no global order at all, so a consumer must never derive meaning from delivery sequence.</li>
 * <li><b>Bounded work / cancellation.</b> The pass hands over one artifact at a time and buffers nothing on the
 *     consumer's behalf, so per-pass memory is the consumer's own choice and its own risk: a snapshot rebuilder that
 *     accumulates the whole store in heap is bounded by the store's artifact count and must say so. There is no
 *     cancellation hook - a consumer that must stop throws, which is the resumable failure of clause 6.</li>
 * <li><b>Durability / delivery.</b> The commit point is the walk's <em>cursor commit</em>: once
 *     {@code walks/<consumer>/segments/<nn>} carries a cursor, everything at or before it will not be delivered again
 *     in this pass. {@link #beforeCheckpoint} is the only moment at which a consumer's derived write is guaranteed to
 *     precede the cursor that would skip it, so exactly three delivery classes are honest here:
 *     <ul>
 *       <li><b>per-item durable</b> - the derived write completes inside {@link #onRetained}. Converges from every
 *           crash point; the replay is absorbed by clause 2.</li>
 *       <li><b>stride durable</b> - deliveries are buffered and flushed from {@link #beforeCheckpoint}. Converges from
 *           every crash point <em>because</em> the flush precedes the commit; a consumer that buffers without
 *           implementing {@link #beforeCheckpoint} loses every buffered item whose cursor landed, permanently, and is
 *           not a legal implementation of this interface.</li>
 *       <li><b>pass snapshot</b> - one artifact committed from {@link #onPassCompleted}. This class is <em>not</em>
 *           converged by a crash-resume: the resumed pass replays only the uncommitted tail, so the accumulation that
 *           reaches {@code onPassCompleted} is a fragment. Such a consumer must detect the resume - it is handed the
 *           same {@link WalkPass#generation()} it already began accumulating for, which is the signal, and it must
 *           persist that fact because its own memory did not survive - and then refuse to commit, leaving the previous
 *           snapshot standing and recording the degradation of clause 8. It converges on the next <em>full</em> pass.</li>
 *     </ul>
 *     No consumer may claim a stronger class than the one it implements: the walk's cursor is the only durability the
 *     pass itself provides.</li>
 * <li><b>Families.</b> A pass enumerates each {@linkplain Family key family} its consumers listen on exactly once
 *     per generation - the pointer roots, the inventory rows, the blob pool, the derived rows - and hands every
 *     member to every consumer that {@linkplain #families() listens} on that family: a pointer as a descriptor
 *     through {@link #onRetained} (or {@link #onWithheld}), any other member as a {@link Walked} key through
 *     {@link #onWalked}, whose body is read once for all of them. A family nobody listens on is not enumerated, so
 *     a consumer pays for exactly the streams it asked for; and every repair that used to walk the store on its
 *     own rides here instead, which is the reason the families exist.</li>
 * <li><b>Withheld pointers.</b> The pass decides once per pointer whether serving would 404 it - the quarantine
 *     subtree, the interceptor chain's hold, the {@code withheld/} marker - and delivers a withheld pointer through
 *     {@link #onWithheld} to a consumer that {@linkplain #seesWithheld() asked to see it} (a reconcile, a collector's
 *     mark: state that must be complete over what is stored, not over what serves) and to nobody else. A consumer
 *     that rebuilds a served view never sees one, so it cannot reinstate into an index what a GET would refuse.</li>
 * <li><b>Self-description.</b> {@link #description()} is one sentence an operator reads beside the consumer's
 *     checkbox on the walks screen: what it repairs and what riding a walk costs it, in the operator's terms rather
 *     than the implementation's. {@link #settings()} names the dials that govern what the consumer does with what it
 *     is handed - a retention policy's criteria, a collector's grace - so the screen can show them beside it; a
 *     consumer with no such dial answers none. Neither is consulted by the pass, and neither reaches the store.</li>
 * </ol>
 */
public interface WalkConsumer {

    /**
     * The key families one pass can enumerate, each once per generation. Which store roots make up a family is the
     * deployment's to say ({@link RebuildPass.Roots}); the family is the consumer's word for what it wants.
     */
    enum Family {
        /** The serving pointers: the free {@code publish/} root and every blobs-namespace root a format declares.
         *  Delivered as descriptors through {@link #onRetained} and {@link #onWithheld}. */
        POINTERS,
        /** The inventory's rows per published version - the sidecars under {@code published/} - delivered as keys. */
        INVENTORY,
        /** The content-addressed pool under {@code blobs/}, delivered as keys. */
        BLOBS,
        /** The per-coordinate derived rows - downloads, licenses, overrides, pins - delivered as keys. */
        DERIVED
    }

    /** One member of a non-pointer family, handed to every consumer listening on it: the key, the size the listing
     *  carried ({@code -1} when it did not), and the body, read from the store once on first ask and shared by every
     *  consumer of this delivery - so N listeners on the inventory rows cost one read per row, not N. */
    final class Walked {

        private final Family family;
        private final String key;
        private final long size;
        private final ArtifactStore store;
        private Optional<byte[]> body;

        public Walked(Family family, String key, long size, ArtifactStore store) {
            this.family = Objects.requireNonNull(family, "family");
            this.key = Objects.requireNonNull(key, "key");
            this.size = size;
            this.store = Objects.requireNonNull(store, "store");
        }

        public Family family() {
            return family;
        }

        public String key() {
            return key;
        }

        /** The size the listing carried, or {@code -1}. */
        public long size() {
            return size;
        }

        /** The member's bytes, read once for every consumer of this delivery; empty when it vanished between the
         *  listing and the read. */
        public Optional<byte[]> body() throws IOException {
            if (body == null) {
                body = store.readVersioned(key).map(ArtifactStore.Versioned::content);
            }
            return body;
        }

        @Override
        public String toString() {
            return family + ":" + key;
        }
    }

    /** The consumer's name - its signal and settings namespace, and its {@code walks/<name>/} pass-state scope. */
    String name();

    /** The families this consumer listens on (clause 13); the pointers alone by default. */
    default Set<Family> families() {
        return Set.of(Family.POINTERS);
    }

    /** Whether withheld pointers reach this consumer through {@link #onWithheld} (clause 14); {@code false} by
     *  default, which is right for every consumer that rebuilds a served view. */
    default boolean seesWithheld() {
        return false;
    }

    /**
     * Whether this consumer reads {@link ArtifactDescriptor#size()} off the pointers it is handed.
     *
     * <p>{@code true} by default, because most consumers do and one that has not thought about it must not
     * silently be handed a {@code -1} it would read as "unknown". Declaring {@code false} lets the walk skip a
     * round trip that no listing can answer: a pointer's own size comes from the listing that enumerated it, but
     * the size of the <em>blob</em> it names is a different key, so it costs a HEAD per pointer per pass.
     * Measured 2026-09-08 on a node counting by key family, that probe was 4.80 reads per blob held - a fifth of
     * everything a collection reads. The walk pays it when any consumer listening on {@link Family#POINTERS}
     * says it will read it, so the saving is real for a walk whose listeners do not - the daily retention walk
     * carries the retention sweep and the roll-up, and neither reads a blob's size.
     *
     * @return whether the blob's size must be resolved for this consumer.
     */
    default boolean needsBlobSize() {
        return true;
    }

    /**
     * Whether this consumer distinguishes a withheld pointer from a served one.
     *
     * <p>{@code true} by default, because every consumer that rebuilds a served view must: a withheld artifact
     * would 404 on a GET, so reinstating it into an index is the clause 14 breach {@link #seesWithheld} exists to
     * govern. Declaring {@code false} says the opposite - hand me every pointer through {@link #onRetained} and
     * do not work out which are held - and it lets the walk skip two reads per pointer per pass: the quarantine
     * chain and the content-addressed withheld marker, measured 2026-09-08 as 1.60 reads per blob held each.
     *
     * <p>The walk skips them only when NO consumer listening on {@link Family#POINTERS} distinguishes, so one
     * consumer that does keeps the status exact for everyone.
     *
     * @return whether the withheld status must be resolved for this consumer.
     */
    default boolean needsWithheldStatus() {
        return true;
    }

    /**
     * Whether this consumer uses the pointers it is handed at all.
     *
     * <p>{@code true} by default: a consumer listening on {@link Family#POINTERS} normally wants them. Declaring
     * {@code false} says it rides the walk only to act when the walk completes, and the walk then does not read
     * a pointer's body to build a delivery nobody takes - one read per pointer per pass, measured 2026-09-08 as
     * 1.60 reads per blob held.
     *
     * <p>It is deliberately this rather than declaring no {@link #families()}. A consumer that listens on nothing
     * leaves the walk with no root to enumerate, and the pass refuses to start - which is right for a
     * misconfigured deployment and wrong for a consumer that simply wants completion, and the two are the same
     * state to that check today. Keeping the family and declining the body says the same thing without asking a
     * refusal to tell them apart.
     *
     * @return whether pointer deliveries are of any use to this consumer.
     */
    default boolean needsPointers() {
        return true;
    }

    /** One sentence for the operator: what this consumer repairs when it rides a walk, and what that costs. The
     *  default is the name, which is what a consumer that has not yet described itself shows. */
    default String description() {
        return name();
    }

    /** The settings keys (bare, without the {@code jenreg.} prefix) of the dials that govern what this consumer
     *  does with what it is handed, for the walks screen to show beside it; none by default. */
    default List<String> settings() {
        return List.of();
    }

    /** One retained artifact, visited in total key order; must be idempotent per artifact (see the class contract
     *  for the exactly-once-per-pass / at-least-once-across-a-crash delivery semantics). */
    void onRetained(ArtifactDescriptor artifact, ArtifactStore store) throws IOException;

    /** One withheld pointer - a descriptor a GET would refuse - for a consumer that {@link #seesWithheld()}; the
     *  same idempotency and ordering as {@link #onRetained}. The default does nothing, and is never called for a
     *  consumer that did not ask. */
    default void onWithheld(ArtifactDescriptor artifact, ArtifactStore store) throws IOException {
    }

    /** One member of a non-pointer family this consumer {@linkplain #families() listens} on, in total key order
     *  within a segment; idempotent per key, like {@link #onRetained}. The default does nothing. */
    default void onWalked(Walked entry, ArtifactStore store) throws IOException {
    }

    /**
     * The walk is about to durably commit {@code cursor} as processed - every checkpoint stride and at segment
     * completion ({@code cursor} is {@code null} for a segment that held no keys) - so this is the moment a consumer
     * that buffers its derived writes flushes them. The cursor lands only after this returns: a flush failure leaves
     * the previous cursor standing and the re-visit replays exactly what was lost, while a consumer that buffered
     * <em>without</em> flushing here is resumed past items whose derived writes died with the process and never sees
     * them again. A consumer that writes through per item needs nothing; the default does nothing.
     *
     * <p>This is {@link ArtifactWalk.KeyVisitor#beforeCheckpoint} carried through to the consumer by
     * {@link RebuildPass}, so a consumer driven by the shared pass gets the same flush guarantee a visitor driving the
     * walk directly has always had. It fires only after {@link #onPassStarted}: a worker that has delivered nothing
     * has nothing to flush.
     */
    default void beforeCheckpoint(String cursor) throws IOException {
    }

    /** The pass over {@code store} is starting - the moment a snapshot rebuilder resets its accumulation, and the
     *  moment it learns the {@link WalkPass#generation()} whose re-appearance is its only signal that a later pass is
     *  a crash-resume rather than a fresh start (clause 12). A deployment fans passes over its repositories across
     *  workers, calling this one instance for several stores at once, so a consumer that keeps per-pass state keys it
     *  by {@link ArtifactStore#identity()} and resets only that store's here. There is deliberately no store-less
     *  form beside this one: two arities of one hook meant an implementor overriding the other got silence. */
    default void onPassStarted(WalkPass pass, ArtifactStore store) {
    }

    /** The pass over {@code store} enumerated everything - the commit / compact / heal hook for a consumer that acts
     *  at pass end, for that store alone. It declares no {@link IOException}: a consumer that persists here wraps a
     *  store failure in an {@link UncheckedIOException}, which propagates out of the pass just as a checked one
     *  would. */
    /**
     * Where this consumer's {@link #onPassCompleted} sits among the others: lower runs first, and equal order is
     * the order they were discovered in.
     *
     * <p>It exists for one relationship, and only completion is ordered by it. A consumer's completion work can
     * orphan content - a retention sweep unpublishes what it evicted - so anything that reclaims must see the store
     * as the others left it, or it reclaims one pass late. That used to hold only because the consumers happened to
     * be listed in one file in the right order, which stopped being true the moment one of them moved to a module
     * of its own.
     */
    default int order() {
        return 0;
    }

    /** The order of a consumer that must complete after every other: reclamation, which reads what they left. */
    int LAST = Integer.MAX_VALUE;

    default void onPassCompleted(WalkPass pass, ArtifactStore store) {
    }

    /** Every enabled consumer discovered via {@link ServiceLoader} (a parallel SPI: a
     *  {@code jenreg.<name>=false} skips one, {@link Features}), in discovery order - what the scheduled
     *  walk pass drives from its one enumeration. */
    static List<WalkConsumer> discovered() {
        List<WalkConsumer> consumers = new ArrayList<>();
        for (WalkConsumer consumer : ServiceLoader.load(WalkConsumer.class)) {
            if (Features.enabled(consumer.name())) {
                consumers.add(consumer);
            }
        }
        return List.copyOf(consumers);
    }
}
