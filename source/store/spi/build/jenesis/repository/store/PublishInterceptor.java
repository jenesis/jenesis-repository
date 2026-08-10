package build.jenesis.repository.store;

import module java.base;

/**
 * The verdict-bearing publication hook: the {@code order}/{@code assess}/{@code withheld}/{@code committed}
 * <em>specialization</em> of the general {@link PublicationObserver} seam. An interceptor <b>is</b> an observer
 * (it {@code extends PublicationObserver}), so the whole publication surface is discovered through a single
 * {@code uses PublicationObserver} clause: {@link Publication} splits the one discovered list by
 * {@code instanceof PublishInterceptor}, driving the interceptor subset through the assess/withheld/committed
 * chain while still notifying every observer (interceptors included) after commit. A plugin author no longer picks
 * between two near-identical seams: implement {@code PublicationObserver} to ride an accepted publish, and opt into
 * a verdict by implementing this sub-interface instead - one concept, one discovery clause.
 *
 * <p>Run as an {@link #order() ordered} chain by {@link Publication#screen}: once the blob is stored
 * content-addressed but before any pointer is linked, every screen {@link #assess assesses} the neutral
 * {@link ArtifactDescriptor}; the publication is routed by the strongest {@link Disposition} across the chain, then
 * each screen is {@link #committed notified} of the outcome. The screen also holds the quarantine read side:
 * {@link Publication#located} asks the chain whether a published path is {@link #withheld}, so a verdict that
 * changes after the fact retracts an already-linked artifact from serving. By default no provider ships, so the
 * chain is empty and every upload is accepted, linked and served exactly as before; a deployment can plug a
 * compliance gate, quarantine audit or inventory recording in here - with no format-specific logic, since the
 * coordinate arrives on the descriptor.
 *
 * <p><b>Per-method failure semantics survive the merge.</b> The distinction between the two hook classes was never
 * only which methods they carry but how a failure is treated, and that is preserved per method: the verdict-bearing
 * methods {@link #assess} and {@link #committed} run on the publish path and <em>propagate</em> - an interceptor
 * that throws fails the write, because a gate that cannot render a verdict must not let an unscreened artifact
 * through (and {@link #withheld} propagating out of {@link Publication#located} likewise fails closed rather than
 * serving a path it could not clear). The observer-role methods {@link #onPublished} and {@link #onDeleted} this
 * type inherits from {@link PublicationObserver} are <em>contained</em> exactly as for any observer - logged and
 * swallowed, never failing the upload or blocking the removal - since an after-commit notification has no say in a
 * verdict already reached. An interceptor's post-route hook is {@link #committed} (which fires for <em>every</em>
 * disposition); its inherited {@link #onPublished} defaults to a no-op, so an interceptor observes the accepted
 * publish only if it explicitly overrides it, and the empty override never double-counts a screen as an observer.
 *
 * <h2>Contract</h2>
 * This is a role sub-interface of {@link PublicationObserver}: <b>every clause of that contract binds to the methods
 * this type inherits</b>, and the clauses below state what the verdict role adds or reverses. Where the two disagree -
 * clause 7 above all - this contract wins for {@link #assess}, {@link #withheld} and {@link #committed}, and the base
 * contract wins for {@link #onPublished}, {@link #onDeleted}, {@link PublicationObserver#onWithheld} and
 * {@link PublicationObserver#onWithholdCleared}. No contract kit drives this chain yet: T-205's interceptor kit is
 * what will, and the clauses are written first so that kit asserts a stated contract instead of inventing one. The
 * core ships no interceptor, so every clause below is a rule for a downstream implementor, and the choreography
 * every clause is stated against is {@link Publication#commit}'s.
 * <ol>
 * <li><b>Thread-safety.</b> One discovered instance serves the whole process and is called from every request thread
 *     that publishes <em>and</em> from every request thread that reads - {@link #withheld} rides
 *     {@link Publication#located}, so this type is on the serve path, not only the publish path. An implementation
 *     must be safe under concurrent calls and must keep no per-call state in fields.</li>
 * <li><b>Idempotency / replay.</b> {@link #assess} is a <em>function</em> of the descriptor and the content it is
 *     handed: a byte-identical re-{@link Publication#commit} - the replay that repairs a first attempt that crashed
 *     mid-layout - runs the whole chain again and must reach the same {@link Disposition}, or the repair converges on
 *     a different verdict than the attempt it repairs. A screen whose verdict legitimately changes over time (a new
 *     advisory against artifact that has served for months) expresses that through {@link #withheld}, which is
 *     re-consulted on every read, never by making {@code assess} time-dependent. {@link #committed} is called again on
 *     that replay too and must upsert rather than append or increment.</li>
 * <li><b>Absence sentinel.</b> {@link Disposition#ACCEPT} is the neutral answer - "this screen has nothing against
 *     it" - and {@code null} is never a legal return from {@link #assess}. {@link #withheld} answers {@code false} for
 *     "serves". {@link Content#sibling(String)} and {@link Content#sibling(String, int)} answer
 *     {@link Optional#empty()} for "nothing is published there", never a zero-length body a caller would parse as an
 *     empty document. The free product ships no interceptor at all, so the shipped chain is empty: every upload is
 *     accepted, nothing is diverted, and {@link Publication} reduces the screen to a plain content-addressed store.</li>
 * <li><b>Selection failure.</b> None: the chain is additive - every discovered interceptor participates, there is
 *     nothing to select and so nothing to fail at resolution. A screen that must not run is one whose module is off
 *     the module path.</li>
 * <li><b>Streaming (&sect;1).</b> A screen is handed a descriptor and a {@link Content} view, never the upload's
 *     bytes: the body was hashed on write into {@code blobs/<hash>} <em>before</em> the chain ran, so {@link
 *     Content#open} re-streams the stored blob and a screen that must look inside consumes that stream under its own
 *     bound rather than materialising the artifact. The two sibling reads answer a bound differently <em>by design</em>,
 *     and the difference is contractual: the whole-document {@link Content#sibling(String)} <b>throws</b> past
 *     {@link Content#LARGEST_SIBLING}, because its caller wants the document entire and a prefix presented as whole is
 *     the silently-incomplete answer &sect;5 forbids; the bounded {@link Content#sibling(String, int)} <b>never fails
 *     on size</b>, honours the caller's own limit and reports the overflow through
 *     {@link Content.Bounded#truncated()}. The <em>forbidden</em> composition is therefore one-directional, and it is
 *     the one the {@link Content} javadoc argues against: the bounded read must not be expressed over the
 *     whole-document one - reading the companion whole and trimming afterwards, or letting this seam's ceiling cut the
 *     caller's larger bound short - because that fails above {@link Content#LARGEST_SIBLING} however large a bound was
 *     requested, and buffers the whole companion before deciding to discard most of it. The other direction is sound
 *     and is what {@link Publication} ships: the whole-document read <em>is</em> the bounded read taken at
 *     {@link Content#LARGEST_SIBLING}, refusing a truncated result, which never materialises more than the ceiling and
 *     still fails loudly rather than returning a prefix. What an implementation owes is the two behaviours, not a
 *     particular pair of method bodies.</li>
 * <li><b>Tenant scoping (&sect;6).</b> {@link Content#store()} and the store handed to {@link #committed} and
 *     {@link #withheld} are the same doubly-scoped (tenant/repository) view the publication routed through, and they
 *     are the only storage a screen may touch. A verdict recorded against a store from anywhere else is a verdict
 *     recorded for the wrong repository.</li>
 * <li><b>Error visibility (&sect;9) - the clause that reverses the base contract.</b> The three verdict-bearing legs
 *     <b>propagate</b>: an exception out of {@link #assess} or {@link #committed} fails the publish and leaves nothing
 *     servable, and one out of {@link #withheld} fails the read closed rather than serving a path the chain could not
 *     clear. That is the whole point of the sub-interface - a gate that cannot render a verdict must never let an
 *     unscreened artifact through - so a screen must <em>not</em> catch its own store failures into a default
 *     {@code ACCEPT}. The observer legs this type inherits stay <b>contained</b> exactly as for any observer (logged,
 *     and the publish, removal or withhold transition stands). One class, two failure modes, keyed by method.
 *     Two details a reader only finds in the code: the containment is of {@code Exception}, so an {@link Error}
 *     escapes on <em>either</em> side; and where the enumeration seam calls {@link #withheld} over a hostile or
 *     unresolvable request path, {@code ServableNames} catches and treats the path as withheld - the same fail-closed
 *     direction reached by a different route.</li>
 * <li><b>Read purity (&sect;10).</b> {@link #withheld} is a read-path call: it runs inside {@link Publication#located}
 *     on <em>every</em> serve and, through {@code ServableNames}, on every enumeration surface. It therefore renders
 *     durably stored state only - no upstream fetch, no scanner call, no lazy refresh and no write - it must be cheap
 *     enough to sit on that path (a keyed probe, never a scan), and it must answer the same when every external system
 *     is down. {@link #assess} runs on the publish thread under the same rule: it may read the scoped store for state
 *     an earlier decision recorded, but it does not fetch.</li>
 * <li><b>Staleness.</b> A screen has no snapshot to date-stamp; its freshness model is the read-side re-consultation
 *     instead. Because {@link #withheld} is asked on every read rather than latched at publish time, a verdict that
 *     changes after the fact retracts an already-linked artifact from serving and from every enumeration surface at
 *     the next read, with no sweep and no pointer rewrite. A screen that memoised its own answer for the process
 *     lifetime would break exactly that and must not.</li>
 * <li><b>Lifecycle / ownership.</b> Instances are {@link java.util.ServiceLoader}-discovered once at
 *     {@link Publication} class load - through the single {@code uses PublicationObserver} clause - and cached for the
 *     life of the process. There is no close hook, so a screen that owns a thread or a client owns it for the process
 *     lifetime and must size it accordingly. A chain injected through {@code new Publication(store, interceptors)} is
 *     the embedder's to own instead, and is sorted on every construction exactly like the discovered one.</li>
 * <li><b>Ordering / concurrency.</b> The chain runs sequentially in ascending {@link #order()}, ties keeping discovery
 *     order - which is <em>not</em> stable across module-path arrangements, so ordering may only ever matter to a
 *     screen that reads what an earlier screen recorded. The collective verdict is order-independent: the strongest
 *     {@link Disposition} across the chain routes the publication, and the enum is declared weakest-to-strongest so
 *     "strongest" is its natural order. {@link #committed} is then called over the <em>whole</em> chain in the same
 *     order, including the screens that voted {@code ACCEPT}. No ordering is promised between two concurrent
 *     publications. The two chain walks stop differently, and that difference is contractual: {@link #assess} is
 *     <b>not</b> short-circuited - every screen is asked even after one has answered {@code REJECT}, so a screen that
 *     records what it saw sees every artifact - while {@link #withheld} <b>is</b>, the first screen answering
 *     {@code true} winning, so a screen must never rely on being asked.</li>
 * <li><b>Bounded work / cancellation.</b> {@link #assess} runs on the publish thread and {@link #withheld} on the
 *     serve path; neither has a timeout and there is no way to abandon a chain part-way, so each screen owns its own
 *     bound. The reads the seam offers are bounded by construction (clause 5); a screen that needs more than a bounded
 *     fact off the artifact records a durable note and defers the work to a background sweep rather than doing it
 *     inline. A screen that cannot finish throws, which fails the publish closed.</li>
 * <li><b>Durability / delivery.</b> The chain's position inside {@link Publication#commit} is exact and load-bearing:
 *     <ul>
 *       <li>the body is stored content-addressed <b>first</b> and only then assessed - store-then-gate - so a screen
 *           always sees a stored blob rather than a buffer, and <b>no pointer of the publication's own is linked
 *           before the chain has voted</b>: a {@code REJECT} links nothing at all and leaves an unreferenced blob for
 *           garbage collection, so there is no published-then-retracted window;</li>
 *       <li>a {@code QUARANTINE}'s {@code /quarantine<path>} review pointer is written inside the chain run, before
 *           {@link #committed} fires;</li>
 *       <li>{@link #committed} fires <b>before the accepted layout runs and before the commit point</b>, not after it.
 *           An {@code ACCEPT} reported there means "the chain accepted" and <em>not</em> "the artifact is visible":
 *           the republish policy may still refuse, the accepted layout may still decline, and a declared visibility
 *           write may still fail. A screen that must know the artifact really serves overrides the inherited
 *           {@link #onPublished}, which fires only once the declared visibility has committed - and takes that leg's
 *           contained, best-effort delivery in exchange.</li>
 *     </ul>
 *     The delivery class of {@link #assess} and {@link #committed} is therefore <b>synchronous and commit-coupled</b>:
 *     called exactly once per {@code commit}, inside the publish, with the failure propagating. The crash window a
 *     screen must reason about is the mirror of an observer's - a crash between {@link #committed} and the commit
 *     point leaves a screen believing it accepted an artifact that never became visible, which is why clause 2
 *     requires {@code committed} to be an upsert the replay may repeat.</li>
 * </ol>
 */
public interface PublishInterceptor extends PublicationObserver {

    /** The observer role a screen inherits from {@link PublicationObserver}: an interceptor's own post-route hook is
     *  {@link #committed} (fired for every disposition, on the verdict path), so by default it does <em>not</em>
     *  additionally observe an accepted publish - this defaults to a no-op. A screen that also wants the contained
     *  after-commit observe call (to ride the {@link Publication#published} seam like any observer) overrides it; the
     *  failure is then contained, not propagated, unlike the verdict methods below. */
    @Override
    default void onPublished(ArtifactDescriptor artifact, ArtifactStore store) throws IOException {
    }

    /** What to do with a just-stored artifact, ordered weakest-to-strongest so a chain keeps the strongest verdict:
     *  {@code ACCEPT} links it as published, {@code QUARANTINE} diverts its pointer to a quarantine view (stored, not
     *  served), {@code REJECT} links nothing (the orphaned blob is reclaimed by the usual garbage collection). */
    enum Disposition {
        ACCEPT,
        QUARANTINE,
        REJECT
    }

    /**
     * Read access to the just-stored blob and its already-published siblings, so a gate can inspect the artifact -
     * read a jar, or the sibling POM beside it - without reaching into the storage layer or buffering the upload.
     *
     * <p><b>Two sibling reads, two different bounds, and the difference is deliberate.</b> A gate reads a companion in
     * one of two shapes, and conflating them is how a bound stops being honestly reported:
     * <ul>
     *   <li>{@link #sibling(String)} is the <em>whole-document</em> read: give me this small published metadata
     *       document entire, because a fraction of it is worthless (half a POM, half a manifest, half an attestation
     *       envelope). It has no caller-supplied bound because the caller has no useful answer for a partial read - so
     *       it carries the seam's own ceiling, {@link #LARGEST_SIBLING}, and past it it <b>throws</b>. That is the only
     *       honest outcome: silently handing back a prefix the caller believes is whole is the silently-incomplete view
     *       PRINCIPLES &sect;5 and &sect;9 forbid, and reading without a ceiling turns a gate into an out-of-memory
     *       lever (&sect;1).</li>
     *   <li>{@link #sibling(String, int)} is the <em>bounded-fact</em> read: give me at most this many bytes and tell
     *       me whether there were more, because the caller only needs a bounded fact off the companion (a digest, a
     *       size, the head of a large document) and has a defined answer for "there was more". It honours the
     *       <em>caller's</em> bound - never this interface's - and it <b>never fails on size</b>: an over-bound sibling
     *       comes back as a {@code limit}-length prefix flagged {@link Bounded#truncated()}, which is bound-fails-visibly
     *       done as an explicit result rather than an exception.</li>
     * </ul>
     * A caller that asks for a bounded read must get the bound it asked for. Routing a bounded read through the
     * whole-document one - reading it whole and trimming afterwards, or letting the whole-document ceiling cut it short -
     * gives the caller neither: it fails above {@link #LARGEST_SIBLING} however large a bound was requested, and it
     * buffers the whole companion before deciding to discard most of it. The two reads are therefore separate methods
     * on this interface, and the bounded one is implemented against the store rather than over its neighbour.
     *
     * <p>The converse composition is fine and is what {@link Publication} does: the whole-document read is the bounded
     * read taken at {@link #LARGEST_SIBLING}, answering the buffer when it came back whole and throwing when it came
     * back {@linkplain Bounded#truncated() truncated}. Nothing beyond the ceiling is ever materialised and an
     * over-ceiling companion still fails loudly, so both obligations above hold exactly. The rule is about which
     * bound a caller gets, not about how many times the store is read.
     */
    interface Content {

        /**
         * The ceiling on a whole-document {@link #sibling(String)} read. A sibling read whole is small published
         * metadata a gate inspects beside the artifact (a jar reading its POM, a package reading its manifest) - never
         * a whole artifact - so the buffered read is capped at a few MiB. Past it the read fails loudly rather than
         * materialising an arbitrarily large blob into the heap, which would let a screen be turned into an
         * out-of-memory lever.
         *
         * <p>It bounds {@link #sibling(String)} <b>only</b>. It is emphatically not a ceiling on
         * {@link #sibling(String, int)}: that read honours the caller's own bound, which may be far larger, precisely
         * because it never materialises more than the caller asked for and reports the overflow instead of failing on
         * it. A screen on another ingress leg that caps its own whole-document companion read keys it to this constant
         * rather than restating the number, so the two legs cannot drift apart on what "too large to read whole" means.
         */
        int LARGEST_SIBLING = 8 * 1024 * 1024;

        /** Open the blob this artifact was stored under ({@code blobs/<hash>}); the caller closes the stream. */
        InputStream open() throws IOException;

        /** The bytes already published at a sibling request path (a jar reading its POM), or empty if nothing is
         *  there. The whole document or nothing: past {@link #LARGEST_SIBLING} this throws rather than returning a
         *  prefix the caller would read as complete. A caller that can use a prefix asks for one through
         *  {@link #sibling(String, int)} instead. */
        Optional<byte[]> sibling(String path) throws IOException;

        /**
         * Up to {@code limit} bytes of the sibling already published at this request path, read straight off the store
         * so nothing beyond the bound is ever materialised - or empty if nothing is published there. The seam a gate
         * uses when it needs a bounded fact off a companion (the digest of the artifact an attestation names, the head
         * of an SBOM attachment) and must not pull a multi-gigabyte companion whole into a {@code byte[]} on the
         * publish thread.
         *
         * <p><b>The bound is the caller's and it is honoured exactly.</b> A sibling of at most {@code limit} bytes
         * comes back whole with {@link Bounded#truncated()} {@code false}; a longer one comes back as its first
         * {@code limit} bytes with {@code truncated} {@code true}. Size alone never raises here, whatever
         * {@link #LARGEST_SIBLING} says - an over-bound companion is a fact the caller asked to be told, not a failure
         * of the publish. Implementations read one byte past {@code limit} to tell the two apart, so a sibling of
         * <em>exactly</em> {@code limit} bytes is reported whole rather than pessimistically flagged: the caller really
         * does hold every byte, and a digest computed over it really is the companion's digest.
         *
         * @param path  the sibling's published request path
         * @param limit the most bytes to materialise; must be positive
         */
        Optional<Bounded> sibling(String path, int limit) throws IOException;

        /** A bounded read of a sibling: its {@code content} - the whole sibling, or the leading {@code limit} bytes of
         *  a longer one - and whether it was cut short. {@code truncated} is the caller's signal that a fact derived
         *  from the whole (a digest, a length, a parse) cannot be confirmed from what it holds, so it must degrade
         *  explicitly rather than assert something about a prefix. The array is handed over rather than copied: this
         *  seam exists to keep exactly one bounded buffer alive, and defensively duplicating it would double the very
         *  heap the bound is protecting. */
        record Bounded(byte[] content, boolean truncated) {
        }

        /** The scoped store the publication routed through - the doubly-scoped (tenant/repository) space this upload
         *  belongs to, the same store {@link #committed} receives. A screen that must consult already-recorded state
         *  for its verdict - an operator's accept-risk waiver recorded against this coordinate's findings, say - reads
         *  it from here, so the decision is made against the repository's own durable record rather than a tenant-only
         *  view the gate cannot scope correctly. Keep any such read cheap; {@code assess} is on the publish path. */
        ArtifactStore store();
    }

    /** The screen's position in the chain, lower first; screens sharing a position keep their discovery order. The
     *  collective disposition is order-independent (the strongest verdict wins) - ordering matters to a screen that
     *  reads what an earlier one recorded, and to the {@link #committed} notification sequence. */
    default int order() {
        return 0;
    }

    /** Decide this artifact's disposition before its pointer is linked; {@code ACCEPT} by default. */
    default Disposition assess(ArtifactDescriptor artifact, Content content) throws IOException {
        return Disposition.ACCEPT;
    }

    /** Whether the artifact published at this request path is currently withheld from serving - the quarantine read
     *  side: a screen that diverts a fresh upload can also retract an already-linked path when its verdict changes
     *  after the fact (a new advisory against an artifact that has served for months). Consulted by
     *  {@link Publication#located} against the same scoped store the publication serves from, on every read - so an
     *  implementation keeps it cheap. Serves ({@code false}) by default. */
    default boolean withheld(String path, ArtifactStore store) throws IOException {
        return false;
    }

    /** React to the routed outcome once the collective disposition is decided - the seam for inventory recording on
     *  {@code ACCEPT}, a quarantine or rejection audit otherwise. The scoped store the publication routed through
     *  rides along so such a record lands in the publish's own tenant/repository space - the doubly-scoped store
     *  <em>is</em> the routed space, per this SPI's convention. A hook that only rides an accepted publish and has
     *  no say in the verdict belongs in the other hook class, the after-commit {@link PublicationObserver}. */
    default void committed(ArtifactDescriptor artifact, Disposition disposition, ArtifactStore store)
            throws IOException {
    }
}
