package build.jenesis.repository.inventory;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ServableNames;
import build.jenesis.repository.walk.ArtifactWalk;
import build.jenesis.repository.store.Names;

/**
 * The store-primitive write-ordering reconcile: the minimal-harm sweep that reconciles the crash-torn intermediate
 * states the two-step publish (bytes first at {@code blobs/<hash>}, then the pointer {@code publish/<request-path> ->
 * <hash>}) can leave, over a single repository's scoped store. It is the dual of the mark-sweep garbage collector: the
 * collector reclaims a <em>blob no pointer references</em> (an orphan), this reconciles a <em>pointer whose blob is not
 * stored</em> (a dangling pointer), so between them every torn write converges to <em>fully published</em> (both the
 * blob and its pointer present) or <em>fully absent</em> (the serving pointer gone, any residual blob left for the
 * collector), never a half-visible artifact. It opens a walk of its own rather than riding the shared rebuild
 * pass because it must see every pointer, withheld ones included: a torn write under quarantine is still torn.
 *
 * <p>Because every ordered write lands the blob before the pointer that names it (see {@code Publication.storeBlob}
 * then {@code Publication.link}, and every layout / staging / promotion path built on them), a crash can only ever
 * leave a blob with no pointer - a benign orphan the collector already owns. A pointer resolving to a missing blob is
 * therefore <em>impossible under the ordering</em>; if the scan finds one it is corruption (an out-of-band delete, a
 * backend that lost an object), so it is flagged <strong>loudly</strong> and the dangling pointer removed - it serves
 * nothing anyway ({@code Publication.located} already filters a pointer whose blob is gone to empty), so removing it
 * only stops it drifting.
 *
 * <p>Minimal harm and idempotent by construction:
 * <ul>
 *   <li>a <b>dangling pointer</b> (pointer -&gt; missing blob) is flagged and, when {@code apply} is set, removed -
 *       and only after a re-read confirms it still names the same missing blob, so a pointer a concurrent republish
 *       just healed is never removed;</li>
 *   <li>a <b>referenced object</b> - a pointer whose blob is stored, or any stored blob a pointer names - is never
 *       removed;</li>
 *   <li>an <b>orphan blob</b> (a stored blob no pointer references) is confirmed and counted but <em>not</em> removed:
 *       it is the garbage collector's domain, and this sweep does not double-handle it.</li>
 * </ul>
 * With {@code apply} false the pass is a pure dry run - it flags and counts, mutates nothing - the audited,
 * never-automatic default the orphan purge uses; with it set the pass removes only the dangling pointers, so a re-run
 * over a converged store finds nothing to flag and changes nothing.
 *
 * <p>Only the tiny pointer objects and blob stats are read, never an artifact body: Pass 1 streams the {@code publish/}
 * pointer tree through the shared {@link ArtifactWalk} - the same resumable, segmented primitive the sibling
 * {@code InventoryReconciler} rides over the exact same root - visiting each pointer in the walk's bounded strides
 * rather than collecting the whole leaf set into one list first, and Pass 2 streams the flat {@code blobs/} namespace
 * with {@link ArtifactStore#page}, so the sweep runs identically on a filesystem and an object store and never
 * materialises the store in memory.
 */
public final class TornWriteReconciler {

    private static final System.Logger LOGGER = System.getLogger(TornWriteReconciler.class.getName());

    private static final String PUBLISH = "publish";
    private static final String BLOBS = "blobs";

    /** Names fetched per {@link ArtifactStore#page} call when streaming the flat {@code blobs/} namespace. */
    /**
     * The hashes the pointer walk saw a stored blob for, held to judge the blob walk against - as sixty-four-bit
     * prefixes in one sorted primitive array rather than as a hash set of strings. A referenced blob used to cost a
     * few hundred bytes of heap here (the hex string, the set's entry, the node), so a million published artifacts
     * were more than the heap of a server that sets no {@code -Xmx}, and the torn-write canary measured the pass
     * failing at a million pointers under 512 MiB. Eight bytes per referenced blob is the stated bound now: a
     * million blobs are eight megabytes, and the array grows by doubling and is sorted once, when the pointer walk
     * ends and the blob walk begins.
     *
     * <p>Sixty-four bits of a SHA-256 tell two stored blobs apart with a collision probability below one in ten
     * billion for a million of them; a collision would count one orphan too few, in a pass that counts orphans
     * and never removes them, so it is a bound worth taking for two orders of magnitude of heap.
     */
    static final class ReferencedHashes {

        private long[] prefixes = new long[1 << 12];
        private int size;
        private boolean sealed;

        void add(String hash) {
            if (sealed) {
                throw new IllegalStateException("the referenced set is sealed for reading");
            }
            if (size == prefixes.length) {
                prefixes = Arrays.copyOf(prefixes, prefixes.length * 2);
            }
            prefixes[size++] = prefix(hash);
        }

        void seal() {
            prefixes = Arrays.copyOf(prefixes, size);
            Arrays.sort(prefixes);
            sealed = true;
        }

        boolean contains(String hash) {
            return Arrays.binarySearch(prefixes, prefix(hash)) >= 0;
        }

        /** How many of this sealed set's members the sealed {@code other} lacks - a merge over two sorted arrays. */
        long countNotIn(ReferencedHashes other) {
            if (!sealed || !other.sealed) {
                throw new IllegalStateException("both sets are sealed before they are compared");
            }
            long missing = 0;
            int mine = 0;
            int theirs = 0;
            while (mine < prefixes.length) {
                while (theirs < other.prefixes.length && other.prefixes[theirs] < prefixes[mine]) {
                    theirs++;
                }
                if (theirs == other.prefixes.length || other.prefixes[theirs] != prefixes[mine]) {
                    missing++;
                }
                mine++;
            }
            return missing;
        }

        private static long prefix(String hash) {
            return Long.parseUnsignedLong(hash.substring(0, 16), 16);
        }
    }

    private final ArtifactStore store;
    private final ArtifactWalk walk;

    public TornWriteReconciler(ArtifactStore store, ArtifactWalk walk) {
        this.store = store;
        this.walk = walk;
    }

    /** The outcome of one reconcile pass over a repository: the dangling pointers found, how many were removed (zero on
     *  a dry run, and at most {@link #dangling} on an apply), and the orphan blobs confirmed for the collector. */
    public record Result(long dangling, long removed, long orphans) {
    }

    /**
     * Reconcile this repository's torn writes. When {@code apply} is false the pass flags and counts but mutates
     * nothing (the audited dry run); when true it additionally removes each dangling pointer it confirmed. Never
     * removes a referenced object, never removes an orphan blob (the collector's domain), and converges on re-run.
     */
    public Result reconcile(boolean apply, Instant now) throws IOException {
        // Pass 1 - stream the publish/ pointer tree through the shared walk: flag (and, on apply, remove) every pointer
        // whose blob is missing, and remember every hash a pointer resolves to whose blob IS stored - the referenced
        // set the orphan pass judges against. Each pointer is handled inside the walk's bounded strides, so the whole
        // leaf set is never buffered in memory; only sha256-shaped pointer contents are trusted as naming a blob,
        // anything else is left be. The task holds the single-writer lease, so the walk claims every segment and this
        // pass runs to completion in one invocation - the referenced set the orphan pass reads is whole.
        ReferencedHashes referenced = new ReferencedHashes();
        long[] counts = new long[2]; // [0] dangling, [1] removed - carried into the walk's visitor
        walk.walk(store, "reconcile-torn", List.of(PUBLISH), pointer -> {
            Optional<ArtifactStore.Versioned> current = store.readVersioned(pointer);
            if (current.isEmpty()) {
                return; // removed between the walk and this read - nothing references through it
            }
            String hash = ServableNames.hash(current.get().content());
            if (!isHash(hash)) {
                return; // not a content-addressed pointer (a maven-metadata mirror, a checksum) - never our concern
            }
            if (store.exists(BLOBS + "/" + hash)) {
                referenced.add(hash); // fully published: a referenced object, never removed
                return;
            }
            // A pointer resolving to a missing blob: impossible under the blob-before-pointer ordering, so it is
            // corruption. Flag it loudly whether or not we remove it.
            counts[0]++;
            LOGGER.log(System.Logger.Level.WARNING,
                    "torn-write reconcile: dangling pointer " + pointer + " -> " + BLOBS + "/" + hash
                            + " (blob not stored, impossible under blob-before-pointer ordering)"
                            + (apply ? " - removing" : " - dry run, not removing"));
            if (apply && removeDangling(pointer, hash)) {
                counts[1]++;
            }
        });
        long dangling = counts[0];
        long removed = counts[1];

        // Pass 2 - stream the flat blobs/ namespace: a stored blob no pointer referenced is an orphan. Confirm and
        // count it, but never delete it - reclaiming an unreferenced blob is the garbage collector's job, and
        // double-handling it here would race the collector's own condemn-then-collect grace.
        long orphans = 0;
        referenced.seal();
        Names blobs = Names.over(store, BLOBS);
        for (String name = blobs.next(); name != null; name = blobs.next()) {
            if (isHash(name) && !referenced.contains(name)) {
                orphans++;
            }
        }
        return new Result(dangling, removed, orphans);
    }

    /** Remove a dangling pointer, but only after re-reading it under the pass's single-writer lease and confirming it
     *  still names the <em>same</em> missing blob: a concurrent republish that re-linked the path (or re-stored the
     *  blob) between the scan and here means the pointer is no longer dangling, so it is left untouched. Idempotent -
     *  a pointer already gone returns false. */
    boolean removeDangling(String pointer, String hash) throws IOException {
        Optional<ArtifactStore.Versioned> current = store.readVersioned(pointer);
        if (current.isEmpty()) {
            return false; // already removed - convergence, not a lost update
        }
        String nowHash = ServableNames.hash(current.get().content());
        if (!nowHash.equals(hash) || store.exists(BLOBS + "/" + hash)) {
            return false; // re-linked or the blob re-appeared - no longer dangling, never remove a healed pointer
        }
        // Re-confirm immediately before the delete: the pointer still holds the token we judged it at (no re-link
        // since) and the blob is still absent. A republish that re-linked the path or re-stored the blob between the
        // checks above and here advances the pointer's token or materialises the blob, so a healed pointer is never
        // removed - the delete is guarded, not the unconditional one that could clobber a concurrent heal.
        Optional<ArtifactStore.Versioned> confirm = store.readVersioned(pointer);
        if (confirm.isEmpty() || !Objects.equals(confirm.get().token(), current.get().token())
                || store.exists(BLOBS + "/" + hash)) {
            return false;
        }
        store.delete(pointer);
        return true;
    }

    /** Whether a value is a bare lower-case SHA-256 hex - the only shape a pointer's content or a {@code blobs/} name
     *  is trusted as naming content, matching {@code Publication} and the collector. */
    static boolean isHash(String value) {
        if (value.length() != 64) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if ((character < '0' || character > '9') && (character < 'a' || character > 'f')) {
                return false;
            }
        }
        return true;
    }
}
