package build.jenesis.repository.inventory;

import module java.base;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Retries;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.PublicationObserver;
import build.jenesis.repository.store.StoredCounter;
import build.jenesis.repository.store.ServableNames;

/**
 * The after-commit hook that maintains {@link SubtreeSizeRollUp}'s cached browse-folder sizes as running counters on
 * every publish and delete, so the retention sweep's {@link StoreRepositoryInventory#rollUpSizes full walk} is no
 * longer the only thing that keeps a folder's rolled-up subtree size current - it becomes the periodic reconcile
 * backstop, exactly as {@code QuotaArtifactStore.recompute} backstops the running quota counter its {@code adjust}
 * maintains on each write and delete. A publish adds the published blob's size to every ancestor folder's cached
 * {@code sizes/<enc(path)>} object up the publish-relative chain (O(depth) compare-and-set increments, not a tree
 * walk), the repository root {@code sizes/~} among them - the single O(1) per-repo/tenant total read back through
 * {@link StoreRepositoryInventory#subtreeSize subtreeSize("")}; a delete subtracts the same up the chain.
 *
 * <p><b>The two-route derived-metadata contract</b> ({@link PublicationObserver}): this observer is the live-event
 * route, and {@link SubtreeSizeRollUp#rollUpSizes} is the full-walk route. The cached sizes are fully rebuildable from
 * the durable {@code publish/} pointer tree (they are pure derived state, never a human decision), so a delta this
 * observer drops - a publish before this plugin was discovered, a compare-and-set given up under sustained contention,
 * a republish that repoints a path at different content - is corrected the next time the sweep runs the full walk. The
 * fold is deliberately consistent with that walk's truth: it counts a publish only when a {@code publish/} leaf pointer
 * resolves at the path (a blobs-namespace publish - npm, PyPI, Cargo - lays out no such pointer and is not a browse-tree
 * leaf, so it is skipped here exactly as the walk skips it), and it decrements only a {@code publish/} pointer removal
 * (a request path, leading slash), never a blobs-namespace pointer removal (a raw store key, no leading slash).
 *
 * <p><b>Size-before-GC ordering.</b> On a delete the serving pointer is already gone, but the content blob is reclaimed
 * by a <em>separate later</em> garbage-collection pass, so its recorded {@link ArtifactStore#size size} is still
 * readable when this fires - the same order {@code QuotaArtifactStore.delete} reads a blob's size before it decrements.
 * A size that cannot be read (the blob already collected, or a store that never sized it) falls back to zero and skips
 * the decrement, logged, so an already-collected blob can neither throw through the (contained) observer call nor
 * double-subtract a folder's size.
 */
public final class SubtreeSizePublicationObserver implements PublicationObserver {

    private static final System.Logger LOGGER =
            System.getLogger(SubtreeSizePublicationObserver.class.getName());

    private static final String BLOBS = "blobs/";

    /** The compare-and-set retry budget per folder counter - the same bounded, best-effort budget
     *  {@code QuotaArtifactStore.adjust} spends before it logs a dropped delta and leaves the reconcile to heal it. */

    /** Add the published blob's size to every ancestor folder's cached roll-up (the root total included), but only for a
     *  {@code publish/} leaf - the same artifacts the full-walk reconcile counts. The publish-pointer membership test is
     *  one O(1) point read of the just-linked pointer; the chain is derived from the path string alone. Neither lists
     *  nor walks the tree, so the hot path stays free of the whole-tree walk this observer exists to retire. */
    @Override
    public void onPublished(ArtifactDescriptor artifact, ArtifactStore store) throws IOException {
        if (artifact.path() == null) {
            return;                              // a pointer-less descriptor carries nothing to roll up
        }
        if (SubtreeSizeRollUp.underReview("publish" + artifact.path())) {
            return;   // a review handle, not a publication - SubtreeSizeRollUp.underReview states why
        }
        Optional<ServableNames.Pointer> pointer = pointerBlob(store, artifact.path());
        if (pointer.isEmpty()) {
            return;                              // not a publish/ browse-tree leaf (a blobs-namespace publish) - skipped
        }
        // The length the publish recorded on the pointer, before a stat of the blob: what the write already said.
        long size = artifact.size() < 0 && pointer.get().size() >= 0
                ? pointer.get().size()
                : sizeOf(artifact, store, pointer.get().hash());
        if (size <= 0) {
            return;                              // an empty (or unreadable) blob adds nothing to a folder's total
        }
        // Fold the DELTA, not the size. A publish that overwrote a pointer describes what it replaced, and the
        // path's earlier contribution is still counted in every ancestor - so adding the whole size again counts the
        // artifact twice, byte-identical re-publish or not, until the next rollUpSizes() sweeps the drift away. A
        // descriptor that says nothing about a replacement is no information rather than a first publish (see
        // ArtifactDescriptor.replaced), so it folds the whole size exactly as before: that is the shape the two
        // ingress edges produce, and guessing a subtraction there would trade a double-count for a phantom one.
        long delta = size - replacedSize(artifact, store);
        if (delta == 0) {
            return;   // a byte-identical re-publish contributes nothing new; the counters already say so
        }
        for (String folder : ancestors(artifact.path())) {
            adjust(store, SubtreeSizeRollUp.sizeKey(folder), delta);
        }
    }

    /** What the blob this publish overwrote still contributes to every ancestor, or zero when this publish replaced
     *  nothing or was not told. Read from the store the same way a delete reads it, so an add and its matching
     *  subtract are always the same number - and defensively zero when the replaced blob can no longer be sized,
     *  because over-counting a live artifact is the lesser error against under-counting one and then reclaiming it. */
    private static long replacedSize(ArtifactDescriptor artifact, ArtifactStore store) {
        if (artifact.replaced() == null) {
            return 0L;
        }
        // Deliberately NOT sizeOf(...): that helper prefers the descriptor's own size when it has one, which is
        // this publish's blob, not the one it replaced - so routing through it would make every delta zero and the
        // counters stop moving altogether. The replaced blob has to be read from the store by its own hash.
        try {
            long previous = store.size(BLOBS + artifact.replaced());
            return previous > 0 ? previous : 0L;
        } catch (IOException unreadable) {
            // Already reclaimed, or never sized. Fold the whole size rather than a guess: over-counting a live
            // artifact is the lesser error against under-counting one and having a quota sweep act on it, and the
            // next rollUpSizes() reconcile heals the drift from the durable pointer tree either way.
            LOGGER.log(System.Logger.Level.DEBUG, "subtree-size roll-up: no readable size for the replaced blob "
                    + artifact.replaced() + " at " + artifact.path() + " - folding the whole size", unreadable);
            return 0L;
        }
    }

    /** Subtract the removed blob's size from every ancestor folder's cached roll-up (the root total included), reading
     *  the size before the blob is garbage-collected and skipping defensively when it is already gone - so a delete
     *  un-counts exactly what the matching publish counted, and a second removal or an already-collected blob is safe. */
    @Override
    public void onDeleted(ArtifactDescriptor artifact, ArtifactStore store) throws IOException {
        if (artifact.path() == null || artifact.hash() == null) {
            return;                              // no path, or a non-content pointer (a checksum, a metadata mirror)
        }
        if (!artifact.path().startsWith("/")) {
            return;                              // a blobs-namespace removal (a raw store key) - not a browse-tree leaf
        }
        long size = sizeOf(artifact, store, artifact.hash());
        if (size <= 0) {
            // The blob was already reclaimed (or never sized): fall back to no decrement rather than subtract a guessed
            // size, so an already-GC'd blob can neither throw nor double-subtract a folder that a prior delete already
            // un-counted. The next rollUpSizes() reconcile heals any resulting drift from the durable pointer tree.
            LOGGER.log(System.Logger.Level.DEBUG,
                    "subtree-size roll-up: no readable blob size for removed pointer " + artifact.path()
                            + " (blob " + artifact.hash() + " already collected?) - skipping the decrement");
            return;
        }
        for (String folder : ancestors(artifact.path())) {
            adjust(store, SubtreeSizeRollUp.sizeKey(folder), -size);
        }
    }

    /** The {@code publish/} pointer at a request path - the content hash it resolves to and the length it records -
     *  or empty when nothing is published there: the browse-tree membership test, one O(1) point read (never a list
     *  or a walk) of the pointer the publish just linked, read through the one pointer dialect seam. */
    private static Optional<ServableNames.Pointer> pointerBlob(ArtifactStore store, String path) throws IOException {
        String key = "publish" + (path.startsWith("/") ? path : "/" + path);
        return store.readVersioned(key)
                .map(versioned -> ServableNames.parse(versioned.content()))
                .filter(pointer -> !pointer.hash().isEmpty());
    }

    /** The blob's byte size: the descriptor's own when the removal/publish site already carried it, else the store's
     *  recorded {@link ArtifactStore#size size} for the content blob - which returns {@code -1} when nothing is stored
     *  there (the blob already collected), and whose {@link IOException} is contained to the same {@code -1} so a store
     *  read error can neither throw through the observer nor be mistaken for a real size. */
    private static long sizeOf(ArtifactDescriptor artifact, ArtifactStore store, String hash) {
        if (artifact.size() >= 0) {
            return artifact.size();
        }
        try {
            return store.size(BLOBS + hash);
        } catch (IOException exception) {
            LOGGER.log(System.Logger.Level.DEBUG,
                    "subtree-size roll-up: blob size read failed for " + BLOBS + hash, exception);
            return -1L;
        }
    }

    /** The publish-relative ancestor folders of a leaf request path, root first: the repository root {@code ""} (the
     *  per-repo/tenant total, read through {@code subtreeSize("")}) and every proper folder prefix, the leaf file itself
     *  excluded - exactly the folders {@link SubtreeSizeRollUp}'s full walk writes a {@code sizes/} object for. Built
     *  from the path string alone (one scan, no store I/O), so maintaining the chain never lists or walks the tree. */
    private static List<String> ancestors(String path) {
        List<String> folders = new ArrayList<>();
        folders.add("");                         // the repository root - the O(1) per-repo/tenant running total
        String relative = SubtreeSizeRollUp.relative(path);
        int slash = relative.indexOf('/');
        while (slash >= 0) {
            folders.add(relative.substring(0, slash));
            slash = relative.indexOf('/', slash + 1);
        }
        return folders;
    }

    /** Add a signed delta to a folder's cached rolled-up size - a {@link StoredCounter}, the same object the quota
     *  decorator moves its usage with, best-effort in the same way: a delta dropped after every try is logged and
     *  left for the next {@code rollUpSizes()} reconcile to heal, never thrown, so an observer failure never blocks
     *  the publish or the removal. The value is the plain decimal byte string {@link SubtreeSizeRollUp} itself writes
     *  and reads, so the incremental fold and the full-walk reconcile share the one {@code sizes/<enc(path)>} object
     *  format. */
    private void adjust(ArtifactStore store, String key, long delta) throws IOException {
        // Deferred: five or six ancestor folders per publish were five or six compare-and-sets, each a round trip and
        // a write-class call on an object store; the flusher folds a node's deltas into one write per folder per
        // cadence, and a delta that never lands is the drift the next rollUpSizes() reconcile already heals.
        new StoredCounter(store, key).addLater(delta);
    }
}
