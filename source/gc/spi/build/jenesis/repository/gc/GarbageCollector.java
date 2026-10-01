package build.jenesis.repository.gc;

import module java.base;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Known;

/**
 * Reclaims content blobs ({@code blobs/<hash>}) no live pointer references - the residue of a republish, an eviction, a
 * rejected upload or an abandoned staging deploy. Deletion is the one unrecoverable act in the product, so the contract
 * is safety-first: an implementation never deletes a blob that is referenced, that a publish relies on up to the moment
 * of deletion (identical content is stored once, so a new publish may link a blob already judged unreferenced), or that
 * is younger than one collection interval (an in-flight publish stores the blob before linking it). Sparing an orphan
 * for another pass is always acceptable; the reverse never is.
 *
 * <p><b>What counts as referenced</b> is layout knowledge the caller owns: the pointer roots are the top-level prefixes
 * whose small leaf objects name a referenced blob's hash - always {@code publish}, plus every root a blobs-namespace
 * format declares. A root missing from the list makes its blobs reclaimable, so the caller names every one.
 *
 * <p><b>And it says when it cannot.</b> The roots arrive as a {@link Known}{@code <List<String>>} because "these are
 * the roots" and "I cannot name all the roots" are different facts. The set is unnameable exactly when a format module
 * owning an ecosystem's layout is not installed: its pointers are invisible to the mark and its serving blobs would
 * read as unreferenced. Blobs are content-addressed and flat, so no partial repair can spare just those - so an
 * unanswerable root set is refused, at the deletion rather than by every caller.
 *
 * <p>Naming the roots is necessary and, for some formats, not sufficient: a format may serve blobs reachable only
 * through a stored document (OCI's config and layer digests live inside the manifest, and a manifest pulled by digest
 * has no tag pointer). Such a format declares the rest through
 * {@code build.jenesis.repository.format.BlobReferences.references}, which an implementation consults for keys under
 * that format's roots; the collector itself parses no format's documents.
 *
 * <p>{@link #plan} computes what would be reclaimed without writing anything - the dry run a console previews - and
 * {@link #collect} computes and applies. Both run over arbitrarily large stores through the shared artifact walk, never
 * a private full listing.
 *
 * <h2>Contract</h2>
 * <ol>
 *   <li><b>Thread-safety.</b> A collector is resolved once and shared, so both methods are safe to call concurrently.
 *       Between nodes the shared walk's segment claims are the single-writer mechanism, and a pass that cannot claim
 *       every segment reports an incomplete result rather than blocking.</li>
 *   <li><b>Idempotency / replay.</b> {@link #plan} writes nothing, so a preview is always safe to repeat.
 *       {@link #collect} converges: a repeated or resumed pass never deletes a blob a previous pass would have spared,
 *       because deletion needs an earlier pass's condemnation that this pass re-confirms, and a publish relying on the
 *       bytes spares them by compare-and-set on the same marker.</li>
 *   <li><b>Absence sentinel.</b> {@code null} is never returned; an empty store, one with no collection history and a
 *       refused pass all answer a {@link GcPlan}, distinguishably: an unremarkable pass is {@link GcPlan#complete()}
 *       with zero counters, an unfinished one {@code complete() == false} with an empty {@link GcPlan#refusal()}, and a
 *       refused one carries the {@link Known.Unknown} that caused it. An empty answer is never evidence that a store is
 *       clean.</li>
 *   <li><b>Selection failure.</b> Which collector runs is {@link GarbageCollectorProvider}'s business; a deployment
 *       with none reclaims nothing.</li>
 *   <li><b>Streaming.</b> No artifact body is read: a collector reads pointer leaves and its own small bookkeeping and
 *       judges blobs by key.</li>
 *   <li><b>Tenant scoping.</b> The {@link ArtifactStore} handed in is the scope, and the roots are keys within it; no
 *       key outside it is composed.</li>
 *   <li><b>Error visibility.</b> A store failure propagates as {@link IOException}; nothing on the judging path becomes
 *       an empty or complete-looking answer, because a pass that saw nothing because the backend was down must never
 *       read as one that found nothing to do. The unanswerable root set is reported as a refusal rather than thrown: an
 *       uninstalled module is a deployment state, not a fault.</li>
 *   <li><b>Read purity.</b> {@link #plan} is a pure read. {@link #collect} writes only its own bookkeeping and deletes
 *       only blobs it is entitled to; it never edits a pointer, a document or a layout.</li>
 *   <li><b>Staleness.</b> A judgment is against durable state read during the pass, never a cached census, and the
 *       condemn-then-confirm protocol with its claim on the marker spares content a publish relies on up to the
 *       delete.</li>
 *   <li><b>Ordering / concurrency.</b> The mark precedes the sweep within one {@link #collect}; beyond that no order
 *       over blobs is promised, and concurrent passes on different nodes divide the work through the walk's segment
 *       claims.</li>
 *   <li><b>Bounded work / cancellation.</b> Both methods run through the shared bounded walk - resumable, segmented,
 *       checkpointed - and a pass that reaches a bound leaves a resumable state reported as
 *       {@code complete() == false}, never a partial sweep presented as whole.</li>
 *   <li><b>Durability / delivery.</b> A deletion is durable when the blob's key is gone; the condemnation marker is the
 *       record that survives a crash between passes. A crash mid-sweep leaves some blobs deleted and the rest
 *       condemned, which the next pass resumes, so a caller reads the returned {@link GcPlan} rather than assume the
 *       pass ran to the end.</li>
 * </ol>
 */
public interface GarbageCollector {

    /**
     * The dry run: what {@link #collect} would reclaim now, judged from earlier passes' bookkeeping. Writes nothing. On
     * a store where no collection ran there is no earlier judgment, so the plan is empty with {@link GcPlan#complete()}
     * {@code false}: a first {@code collect} only condemns.
     *
     * <p>An unanswerable {@code pointerRoots} previews the same {@link GcPlan#refusal()} {@link #collect} would answer,
     * so a preview shows why nothing will be reclaimed rather than an empty plan that reads as converged.
     */
    GcPlan plan(ArtifactStore store, Known<List<String>> pointerRoots, Instant now) throws IOException;

    /**
     * Run one collection pass: judge every blob against the live pointers under {@code pointerRoots}, condemn the
     * unreferenced, and delete only what an earlier pass condemned and this pass confirms - at least one collection
     * interval of grace for every in-flight or crash-torn publish. A pass that could not finish (another node holds
     * part of the walk) reports {@link GcPlan#complete()} {@code false} and has deleted nothing it was not entitled to.
     *
     * <p><b>An unanswerable root set collects nothing.</b> For a {@link Known.Unknown} {@code pointerRoots} the pass is
     * refused before the mark - nothing walked, condemned or deleted - and the plan carries the reason in
     * {@link GcPlan#refusal()}. This is a contract clause: sweeping on such a set deletes serving bytes it cannot see.
     */
    GcPlan collect(ArtifactStore store, Known<List<String>> pointerRoots, Instant now) throws IOException;

    /** The wall-clock floor between condemning a blob and deleting it when {@code jenrepo.gc.grace} names none: two
     *  hours. A multi-step upload leaves its pieces unreferenced for a while - {@code docker push} sends layers before
     *  the manifest naming them - and frequent collection could otherwise take them; Harbor spares blobs uploaded
     *  within two hours for the same reason. It only ever delays a deletion. */
    static Duration defaultGrace() {
        return Duration.parse(DEFAULT_GRACE);
    }

    /** {@link #defaultGrace()} as the text the setting catalogue declares - a compile-time constant, because the
     *  generated settings reference reads a default out of the class file. */
    String DEFAULT_GRACE = "PT2H";
}
