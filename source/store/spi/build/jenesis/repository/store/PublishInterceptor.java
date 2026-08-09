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
     * buffers the whole companion before deciding to discard most of it. The two reads are therefore separate methods,
     * each implemented against the store, rather than one expressed in terms of the other.
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
