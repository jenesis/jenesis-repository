package build.jenesis.repository.store;

import module java.base;

/**
 * An after-commit observer of {@link Publication#published} and {@link Publication#unpublish} - the <em>general</em>
 * publication hook seam, whose verdict-bearing {@link PublishInterceptor} sub-interface (which {@code extends} this)
 * adds the assess/withhold/commit screen. One {@code uses PublicationObserver} clause therefore discovers both, and
 * {@link Publication} splits the discovered list by {@code instanceof PublishInterceptor} - so a base-only observer
 * plugs in here unchanged and never sits in a verdict chain. Discovered with {@link java.util.ServiceLoader} like the
 * screens, but notified only once an ingress edge has screened an accepted artifact and laid it out and fires the
 * {@link Publication#published} seam - a quarantined or rejected publish is never observed - or once a serving pointer
 * is removed, and an observer has no say in either disposition.
 * This is the seam for what rides a publication change without sitting in its verdict path - forwarding to another
 * repository, a webhook, replication, handing a deeper scan to a worker: an observer's failure is logged and contained
 * (it never unlinks the artifact, fails the upload or blocks the removal), and anything slow belongs in a background
 * worker the observer only leaves a note for - record a small store object here, drain it elsewhere - so a remote
 * target's latency or outage never couples into the local publish.
 *
 * <p><b>The two-route derived-metadata contract.</b> A plugin that derives metadata from what is published (an index,
 * a counter, a dependents table) keeps it correct by exactly two routes, and a correct plugin uses <em>both</em>:
 * these <b>live events</b> ({@link #onPublished} / {@link #onDeleted}) for the steady state, and <b>the full walk</b>
 * - the walk SPI's {@code WalkConsumer} ({@code build.jenesis.repository.walk}), whose {@code onRetained} streams
 * every retained artifact from one shared, resumable enumeration - for first-activation back-fill, periodic refresh
 * and self-heal. Events alone miss what happened while the plugin was absent or crashed; the walk alone is periodic,
 * not live. The walk must be able to fully rebuild the plugin's derived state wherever the data is re-derivable from
 * the durable store; primary rows that record a human decision or a point-in-time observation (a pin, an override, a
 * download marker) are never "rebuilt" and are excluded by design.
 *
 * <h2>Contract</h2>
 * <ol>
 *   <li><b>Thread-safety.</b> One discovered instance serves the whole process and is called from every request
 *       thread that publishes or removes, so an implementation must be safe for concurrent calls and must not keep
 *       per-call state in fields. Two notifications for two different artifacts may run at the same time.</li>
 *   <li><b>Idempotency / replay.</b> Every callback here is at-least-once <em>within</em> what it delivers: a
 *       byte-identical re-publish notifies again, a retried publish after a failed first attempt notifies again, and
 *       the {@code WalkConsumer} back-fill re-presents artifacts the live events already carried. An implementation
 *       must converge on repetition - upsert, never blind-append or blind-increment on a correctness-bearing
 *       counter.</li>
 *   <li><b>Absence sentinel.</b> Not applicable: these are void notifications, not lookups. A descriptor's optional
 *       fields ({@code coordinate}, {@code version}, {@code contentType}) may legitimately be {@code null} for a
 *       coordinate-less path, and {@code hash} is {@code null} only where the removal site could not read one - an
 *       observer must tolerate that rather than assume a coordinate.</li>
 *   <li><b>Selection failure.</b> There is nothing to select: the policy is additive, every discovered observer is
 *       notified, and no configuration key names one. Because this family carries no {@code name()}, it does not
 *       resolve through the shared {@code Providers} primitives - {@link Publication} loads it directly - so it also
 *       gets none of their packaging guards: an observer module registered twice is notified twice, and nothing
 *       reports it. An observer that must not act is switched off by its own module, not by this SPI. The one
 *       discovery site is {@link Publication}'s static list; a second load of this service would be the second
 *       delivery pipeline the design forbids, which is why the verdict-bearing {@link PublishInterceptor} rides this
 *       same clause instead of declaring its own.</li>
 *   <li><b>Streaming.</b> The callback receives a descriptor and the scoped store, never the artifact bytes. An
 *       observer that needs content re-opens {@code blobs/<hash>} through the store and streams it; it must never
 *       materialise an artifact (&sect;1), and anything slow belongs in a background worker this callback only leaves
 *       a small durable note for.</li>
 *   <li><b>Tenant scoping.</b> The {@link ArtifactStore} handed in is already scoped to the tenant and repository the
 *       artifact was published into; an observer records its derived state through that store and must not reach for
 *       a differently scoped one.</li>
 *   <li><b>Error visibility.</b> Every method here is <b>contained</b>: a thrown exception is logged by
 *       {@link Publication} and the publish, removal or withhold transition stands. That is deliberate - an
 *       after-commit observer has no say in the disposition - and it bounds the blast radius of a failure to the
 *       observer's own derived surface, which may then over-serve or over-count but can never hide a served artifact
 *       or a hold. The verdict-bearing legs of the {@link PublishInterceptor} sub-interface are the opposite and
 *       propagate; do not confuse the two.</li>
 *   <li><b>Read purity.</b> Not a read path: these fire on a write, and an observer may write its own derived store
 *       objects. It must not perform external I/O inline (a webhook, a replication push) - it records a durable note
 *       and a background drain performs the call, so a remote target's latency or outage never couples into a
 *       publish. <b>An effect performed inline has no delivery class</b>, and "external" here means outside the
 *       scoped store handed to the callback rather than outside this JVM. A derived write into that store is
 *       re-derivable, so the full walk repairs it; a note left for a drain is durable the moment the callback
 *       returns; but an effect handed straight to anything else - a remote target, another repository in this same
 *       process, a queue in another service - is neither of those. Nothing re-derives it, because the store never
 *       recorded that it was owed, and nothing redelivers it, because no note was left: the leg is at-most-once and
 *       sits outside every delivery class an implementation can honestly declare. An observer that keeps such a leg
 *       anyway says so on a surface an operator reads, rather than leaving the asymmetry to be discovered from an
 *       effect that never arrived.</li>
 *   <li><b>Lifecycle / ownership.</b> Instances are {@link java.util.ServiceLoader}-discovered once at
 *       {@code Publication} class load and cached for the life of the process; there is no close hook, so an observer
 *       that owns a thread or client owns it for the process lifetime and must size it accordingly.</li>
 *   <li><b>Ordering / concurrency.</b> Observers are notified sequentially in discovery order, which is
 *       <em>not</em> stable across module-path arrangements: an implementation must not depend on running before or
 *       after another observer. Ordering between the callbacks of one publication is fixed
 *       ({@code onWithheld}/{@code onWithholdCleared} fire at their own durable transitions,
 *       {@code onPublished}/{@code onDeleted} after theirs), but no ordering is promised <em>between</em> two
 *       concurrent publications. <b>A transition fires once, and no replay fires it again.</b> Because the two
 *       withhold legs fire only on an actual state transition, a retry that re-marks a marker already present or
 *       re-links a review pointer already linked is an idempotent converge rather than a transition and raises
 *       nothing - so a signal lost in the durable-write-to-notify window is never re-emitted, by that replay or by
 *       any later one. This is where the two halves of the family part company: a re-published artifact notifies
 *       again (clause 2), a re-held one does not, and clause 13's heal-all is weaker here than it reads - the walk
 *       re-presents a <em>state</em> the store still holds, never a <em>transition</em> that happened while nobody
 *       was listening. A subscriber whose surface must be complete therefore cannot wait for the signal to come
 *       back: its own periodic rebuild-from-truth is the only route back, and a deployment free to stretch that
 *       rebuild's cadence without bound has no route at all.</li>
 *   <li><b>Durability / delivery.</b> The declared delivery class is <b>best-effort, repaired by the full walk</b> -
 *       explicitly <em>not</em> at-least-once. {@link Publication#commit} makes the artifact visible at its declared
 *       serving pointer and only then calls {@code onPublished}, so a crash in that window leaves an artifact that
 *       serves and was never observed; the mirrors hold for {@code onDeleted} (after the pointer delete) and for both
 *       withhold legs (after the durable marker or review-pointer write). Writing an outbox <em>inside</em> the
 *       callback does not close the window - it only makes what was delivered durable. The durable source of truth is
 *       the store itself, and the heal-all is the second route of the two-route contract above: the walk SPI's
 *       {@code WalkConsumer} re-presents every retained artifact from the durable store, so a derived surface that
 *       must be complete rebuilds from it rather than trusting the event stream.</li>
 * </ol>
 */
public interface PublicationObserver {

    /** React to a committed publish: the linked {@link ArtifactDescriptor} (content-addressed hash and size set) and
     *  the same scoped store it was published through, so a recorded follow-up (an outbox entry, a replication
     *  marker) lands under exactly the space the artifact did. */
    void onPublished(ArtifactDescriptor artifact, ArtifactStore store) throws IOException;

    /** React to a removed serving pointer, fired once per pointer with the descriptor richness the removal site has:
     *  {@link Publication#unpublish} knows the request path and the blob hash the pointer named (the free store knows
     *  no layouts - a coordinate-needing observer describes the path through its format), while a layout-aware
     *  eviction enriches the descriptor with ecosystem and coordinate. A garbage collector's blob reclamation fires
     *  nothing - an unreferenced blob serves nothing, so no pointer-derived metadata can reference it, by
     *  construction. The default is a no-op, so an observer opts into removals without every existing one changing. */
    default void onDeleted(ArtifactDescriptor artifact, ArtifactStore store) throws IOException {
    }

    /**
     * After-commit notice that a withhold transitioned <em>on</em> for a served identity - the transition-ON leg of the
     * withhold-change feed a durable, name-bearing derived artifact (a published index, a future catalogue) subscribes
     * to so a <em>retroactive</em> hold retracts it, not only the emit-time screen. Fired at exactly the two durable
     * withhold choke points, and only on an actual state transition (never on the sweeps' idempotent converge re-marks),
     * after the durable write, with the failure logged and contained like every other notification here:
     * <ul>
     *   <li><b>marker route</b> - a {@link Withheld withheld/&lt;hash&gt;} marker was freshly written: the descriptor
     *       carries the content hash ({@code subject.hash()}) and a {@code null} path, because one marker retracts every
     *       alias of the bytes;</li>
     *   <li><b>pointer route</b> - a fresh {@code /quarantine<servedPath>} review pointer was linked: the descriptor
     *       carries the served path (the {@code /quarantine} prefix stripped) and the pointer's hash.</li>
     * </ul>
     * Because the write commits before this fires and a failure is contained, a crash or observer failure between the
     * two can lose a single signal; a durable consumer therefore keeps its own periodic rebuild-from-truth (the full
     * walk of the two-route contract) as the crash/miss heal-all backstop - worst case identical to today's exposure,
     * normally healed by the next rebuild. The default is a no-op, so an existing observer opts into the feed without
     * every provider changing.
     */
    default void onWithheld(ArtifactDescriptor subject, ArtifactStore store) throws IOException {
    }

    /** The transition-OFF mirror of {@link #onWithheld}: a {@code withheld/<hash>} marker was cleared (descriptor
     *  carries the hash, path null) or a {@code /quarantine<servedPath>} review pointer was removed (descriptor carries
     *  the stripped served path and the pointer's hash). Fired only on an actual transition, after the durable delete,
     *  failures logged and contained; the same two-route contract applies - a lost clear signal is healed by the
     *  consumer's periodic rebuild. Default no-op. */
    default void onWithholdCleared(ArtifactDescriptor subject, ArtifactStore store) throws IOException {
    }
}
