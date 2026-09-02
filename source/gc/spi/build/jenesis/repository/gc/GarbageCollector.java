package build.jenesis.repository.gc;

import module java.base;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Known;

/**
 * Reclaims content blobs ({@code blobs/<hash>}) that no live pointer references any more - the residue of a
 * republish, an eviction, a rejected upload or an abandoned staging deploy. Deletion is the one unrecoverable act
 * in the product, so the contract is safety-first: an implementation must never delete a blob that is referenced,
 * that was re-linked at any point up to its final pre-delete check (the dedup re-publish race - identical content
 * is stored once, so a "new" publish may link a blob the collector already judged unreferenced), or that is
 * younger than one collection interval (an in-flight publish stores the blob before its pointer links). Sparing an
 * orphan for another pass is always acceptable; the reverse never is.
 *
 * <p><b>What counts as referenced</b> is layout knowledge the caller owns: the pointer roots name the
 * top-level key prefixes whose small leaf objects hold a referenced blob's hash - always {@code publish} (the
 * content-addressed publication namespace), plus every root a blobs-namespace format declares for its own
 * pointers. A root missing from the list makes its blobs invisible to the reference scan and eligible for
 * reclamation, so the caller must name every one.
 *
 * <p><b>And it must be able to say when it cannot.</b> The roots arrive as a {@link Known}{@code <List<String>>},
 * not as a bare {@code List<String>}, because "these are the roots" and "I cannot name all the roots" are different
 * facts and a list can only state the first. The set is unnameable exactly when a format module that owns an
 * ecosystem's layout is not installed: its pointers are then invisible to the mark, every blob it serves reads as
 * unreferenced, and the confirming pass deletes artifact bytes that are serving. There is no partial repair
 * available here - blobs are content-addressed and flat, so a collector cannot infer which of them belong to an
 * unenumerable root and spare just those, and naming the absent format's roots anyway would still miss the blobs a
 * stored document lends (an OCI config or layer digest lives inside the manifest and no pointer body names it). So
 * the only correct behaviour is refusal, and this seam takes it: an unanswerable root set is not a degraded scan,
 * it is a licence to delete, and the refusal is placed <em>at the deletion</em> rather than left to every caller to
 * remember.
 *
 * <p>Naming the roots is <em>necessary</em> and, for some formats, not <em>sufficient</em>: one leaf naming one blob
 * holds only where every served blob has a pointer, and a format may serve blobs reachable solely through a stored
 * document (OCI's config and layer digests live inside the manifest JSON, and a manifest pulled by digest carries no
 * tag pointer at all). Such a format declares the rest through
 * {@code build.jenesis.repository.format.BlobReferences.references}, which an implementation consults for the keys it
 * visits beneath that format's roots. The knowledge stays where it belongs - a collector still parses no format's
 * documents - and an implementation handed no lenders is the pointer-body-only scan described above.
 *
 * <p>Mirrors the retention sweeper's shape: {@link #plan} computes what would be reclaimed right now without
 * writing anything - the dry run a maintenance console previews - and {@link #collect} computes and applies. Both
 * run over arbitrarily large stores, so an implementation enumerates through the shared artifact walk (resumable,
 * segmented, multi-node-safe), never a private full listing.
 *
 * <h2>Contract</h2>
 * <ol>
 * <li><b>Thread-safety.</b> A collector is resolved once and shared by every maintenance surface that drives it, so
 *     both methods must be safe to call concurrently. Concurrency <em>between nodes</em> is not this interface's to
 *     police by locking: an implementation rides the shared artifact walk, whose segment claims are the single-writer
 *     mechanism, and a pass that cannot claim every segment reports an incomplete result rather than blocking.</li>
 * <li><b>Idempotency / replay.</b> {@link #plan} writes nothing at all - not a marker, not a checkpoint - so a
 *     preview is always safe to repeat. {@link #collect} converges: a repeated or crash-resumed pass never deletes a
 *     blob a previous pass would have spared, because deletion requires an <em>earlier</em> pass's condemnation that
 *     this pass re-confirms, and the write path clears a condemnation whenever a pointer links the blob.</li>
 * <li><b>Absence sentinel.</b> {@code null} is never returned; an empty store, a store with no collection history
 *     and a refused pass all answer a {@link GcPlan}. The three are distinguishable and deliberately so: an
 *     unremarkable pass is {@link GcPlan#complete()} with zero counters, a pass that could not finish is
 *     {@code complete() == false} with an empty {@link GcPlan#refusal()}, and a refused pass carries the
 *     {@link Known.Unknown} that caused it. An empty answer is never evidence that a store is clean.</li>
 * <li><b>Selection failure (&sect;9).</b> Which collector runs is {@link GarbageCollectorProvider}'s business, not
 *     this interface's; a deployment with no collector installed reclaims nothing rather than falling back to a
 *     default sweeper.</li>
 * <li><b>Streaming (&sect;1).</b> No artifact body is ever read. A collector reads pointer leaves and its own
 *     bookkeeping objects - small objects - and judges blobs by key, never by content.</li>
 * <li><b>Tenant scoping (&sect;6).</b> The {@link ArtifactStore} handed in is the scope the pass runs over, and the
 *     pointer roots are keys within it. A collector composes no key outside that scope, so a tenant-scoped store
 *     collects exactly one tenant's blobs and a root store collects the deployment-global layout.</li>
 * <li><b>Error visibility (&sect;9).</b> A store failure propagates as {@link IOException}; nothing on the judging
 *     path is caught and turned into an empty or complete-looking answer, because a pass that saw nothing because
 *     the backend was down must never read as a pass that found nothing to do. The one failure that is <em>not</em>
 *     an exception is the unanswerable root set, which is reported as a refusal rather than thrown because it is a
 *     legitimate deployment state (an uninstalled module) and not a fault.</li>
 * <li><b>Read purity.</b> {@link #plan} is a pure read. {@link #collect} writes only the collector's own bookkeeping
 *     and deletes only blobs it is entitled to delete; it never edits a pointer, a document or a format's layout.</li>
 * <li><b>Staleness.</b> A judgment is always against durable state read during the pass, never a cached census, and
 *     the pass is deliberately conservative about what has changed under it: the condemn-then-confirm protocol plus
 *     the pre-delete re-read means content re-linked at any point up to the final check is spared.</li>
 * <li><b>Ordering / concurrency.</b> The mark precedes the sweep within one {@link #collect}; beyond that a caller
 *     may not assume any order over blobs, and two concurrent passes on different nodes divide the work through the
 *     walk's segment claims rather than duplicating deletions.</li>
 * <li><b>Bounded work / cancellation.</b> Both methods run over arbitrarily large stores through the shared bounded
 *     walk - resumable, segmented, checkpointed - never a private full listing, and a pass that reaches a bound
 *     leaves a safely-incomplete, resumable state reported as {@code complete() == false} rather than a partial
 *     sweep presented as a whole one.</li>
 * <li><b>Durability / delivery.</b> A deletion is durable when the blob's key is gone; the condemnation marker that
 *     entitled it is the durable record that survives a crash between passes. A crash mid-sweep leaves some blobs
 *     deleted and the rest still condemned, which the next pass resumes - there is no all-or-nothing pass, and a
 *     caller must read the returned {@link GcPlan} rather than assume the pass ran to the end.</li>
 * </ol>
 */
public interface GarbageCollector {

    /**
     * The dry run: what {@link #collect} would reclaim right now, judged from the durable bookkeeping of earlier
     * passes. Writes nothing - not a marker, not a checkpoint - so it is always safe to preview. On a store where
     * no collection ever ran there is no earlier judgment and the plan is empty (with
     * {@link GcPlan#complete()} {@code false}): a first {@code collect} only condemns, it never deletes.
     *
     * <p>The dry run of a refusal is a refusal: an unanswerable {@code pointerRoots} previews the same
     * {@link GcPlan#refusal()} {@link #collect} would answer, so an operator's preview shows the reason nothing will
     * be reclaimed instead of an empty plan that reads as a converged store.
     */
    GcPlan plan(ArtifactStore store, Known<List<String>> pointerRoots, Instant now) throws IOException;

    /**
     * Run one collection pass and apply it: judge every blob against the live pointers under
     * {@code pointerRoots}, remember the unreferenced ones, and delete only what an <em>earlier</em> pass already
     * judged unreferenced and this pass confirms still is - at least one full collection interval of grace for
     * every crash-torn or in-flight publish. Returns what happened; a pass that could not finish (another node
     * still holds part of the shared enumeration) reports {@link GcPlan#complete()} {@code false} and has deleted
     * nothing it was not entitled to.
     *
     * <p><b>An unanswerable root set collects nothing.</b> When {@code pointerRoots} is a {@link Known.Unknown} - no
     * installed format can name some ecosystem's roots - the pass is refused before the mark begins: nothing is
     * walked, nothing is condemned, nothing is deleted, and the returned plan carries the reason in
     * {@link GcPlan#refusal()}. This is a contract clause, not an implementation courtesy: an implementation that
     * swept on an unanswerable root set would delete serving bytes it could not see.
     */
    GcPlan collect(ArtifactStore store, Known<List<String>> pointerRoots, Instant now) throws IOException;
}
