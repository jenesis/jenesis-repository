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
 * every discovered consumer from <em>one</em> enumeration, so N metadata rebuilders never mean N tree walks. The walk alone must be able to
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
 * <li><b>Error visibility (&sect;9).</b> An {@link IOException} out of {@link #onRetained} or {@link #beforeCheckpoint}
 *     propagates: it stops this worker's segment, leaves its claim to expire, and the pass resumes from the last
 *     committed cursor - a failure delays a rebuild but never silently truncates it, and the stuck pass is visible
 *     through {@link ArtifactWalk#pass} / {@link ArtifactWalk#segments}. A consumer must therefore not catch its own
 *     store failures into a shrug: a swallowed write is exactly the silently-incomplete projection &sect;5 forbids. The
 *     pass hooks are not declared to throw, so a consumer that persists in them wraps a store failure in an
 *     {@link UncheckedIOException}, which propagates out of the pass in the same way.</li>
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
 * </ol>
 */
public interface WalkConsumer {

    /** The consumer's name - its signal and settings namespace, and its {@code walks/<name>/} pass-state scope. */
    String name();

    /** One retained artifact, visited in total key order; must be idempotent per artifact (see the class contract
     *  for the exactly-once-per-pass / at-least-once-across-a-crash delivery semantics). */
    void onRetained(ArtifactDescriptor artifact, ArtifactStore store) throws IOException;

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

    /** The pass is starting - the moment a snapshot rebuilder resets its accumulation, and the moment it learns the
     *  {@link WalkPass#generation()} whose re-appearance is its only signal that a later pass is a crash-resume rather
     *  than a fresh start (clause 12). */
    default void onPassStarted(WalkPass pass) {
    }

    /** The pass enumerated everything - the commit / compact / heal hook for a consumer that acts at pass end. Like
     *  {@link #onPassStarted} it carries no {@link ArtifactStore}: a consumer that persists here uses the store it was
     *  handed by {@link #onRetained} (and so cannot commit anything for a pass that delivered it nothing), and wraps a
     *  store failure in an {@link UncheckedIOException}, which propagates out of the pass just as a checked one
     *  would. */
    default void onPassCompleted(WalkPass pass) {
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
