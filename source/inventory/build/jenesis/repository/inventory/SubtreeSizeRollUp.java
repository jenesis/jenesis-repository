package build.jenesis.repository.inventory;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Documents;
import build.jenesis.repository.store.StoredCounter;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.walk.ArtifactWalk;
import build.jenesis.repository.walk.BoundedChildren;
import build.jenesis.repository.walk.PagedTreeWalk;
import build.jenesis.repository.walk.Traversal;
import build.jenesis.repository.walk.WalkPass;
import build.jenesis.repository.walk.WalkSegment;
import build.jenesis.repository.walk.Trees;

/**
 * The subtree-size roll-up subsystem extracted from {@link StoreRepositoryInventory}: a self-contained algorithm with
 * its own durable {@code sizes/} state format, invisible from the inventory's public surface. It recomputes every
 * browse folder's total subtree size so the console reads a folder's size with one direct key lookup
 * ({@link #subtreeSize}) instead of re-walking the tree on every request. Constructed with the shared
 * {@link ArtifactWalk} it rides a resumable, range-segmented {@code walks/rollup} pass (the {@link RollUpVisitor}
 * O(depth) post-order fold with CAS-fenced per-segment partials); walk-less it keeps the complete-per-call recursion.
 * It is a pass of its own rather than a consumer of the shared rebuild pass because its unit is the folder, folded
 * after its children, where that pass delivers leaves in path order and knows nothing of the tree between them.
 * The inventory owns the seam - {@link StoreRepositoryInventory#rollUpSizes} and
 * {@link StoreRepositoryInventory#subtreeSize} delegate here - so this class carries only the roll-up mechanism and
 * shares the inventory's compare-and-set {@code writeVersioned} and coordinate encoding.
 */
final class SubtreeSizeRollUp {

    private final StoreRepositoryInventory inventory;
    private final ArtifactStore store;
    private final ArtifactWalk walk;
    private final Publication publication;

    SubtreeSizeRollUp(StoreRepositoryInventory inventory, ArtifactStore store, ArtifactWalk walk,
                      Publication publication) {
        this.inventory = inventory;
        this.store = store;
        this.walk = walk;
        this.publication = publication;
    }

    /**
     * Recompute every browse folder's total subtree size - the sum of the recorded sizes of the artifact blobs
     * published anywhere beneath it - and commit each as a small cached roll-up object, so the console's browse reads
     * a folder's size with one direct key lookup ({@link #subtreeSize}) instead of re-walking the tree on every
     * request (the read-first bias: the background sweep does the work so the reader does not). Object-storage-native
     * and streaming-safe: it walks only the {@code publish/} pointer tree and reads each referenced blob's recorded
     * {@link ArtifactStore#size size}, never an artifact body, and commits each roll-up through the store's
     * compare-and-set ({@code writeVersioned}) - no database, no raw file, so it runs identically on filesystem and
     * every object store. Folders that no longer exist have their stale roll-up objects deleted (compaction), so the
     * object count tracks the live tree. Returns the whole repository's total. The retention sweep calls this after
     * garbage collection so the cached sizes reflect the post-sweep state; nothing recomputes on a read.
     *
     * <p>Constructed with the shared {@link ArtifactWalk}, the recomputation rides a resumable, range-segmented
     * {@code walks/rollup} pass instead of the private recursion: the walk's lexicographic key order is
     * subtree-contiguous, so the post-order folder totals fold over the stream with an O(depth) frame stack
     * ({@link RollUpVisitor}), and that stack is durably flushed before every cursor commit - a crash resumes from
     * the committed cursor with its partial totals intact, re-folding at most one checkpoint stride, and a dead
     * node's segment is taken over by any node sharing the store from that same cursor. A folder a segment cut
     * splits is summed from the segments' recorded partials when the pass completes (the merge, run idempotently by
     * every pass finisher); while the pass is still active - other workers hold the remaining segments - the call
     * returns the last completed total and the eventual finisher merges. Walk-less (the on-demand construction) it
     * keeps the single recursion: complete on every call, only without the resumable pass.
     */
    long rollUpSizes() throws IOException {
        if (walk == null) {
            Set<String> live = new HashSet<>();
            long total = rollUp(live);
            // Paged through the same bounded ROWS enumeration the walk-riding compaction drives: a repository has one
            // roll-up row per browse folder, so listing the namespace whole was the flat half of the same
            // unbounded-work defect the descent above carried.
            ROWS.scan(store, ROOT, name -> {
                String path = name.equals("~") ? "" : StoreRepositoryInventory.decode(name);
                if (!live.contains(path)) {
                    new StoredCounter(store, ROOT + "/" + name).delete();
                }
            });
            return total;
        }
        Optional<WalkPass> previous = walk.pass(store, "rollup").filter(WalkPass::complete);
        if (previous.isPresent()) {
            // A crash between the pass completing and its merge lost only the merge; the per-segment partials are
            // still in the store, so recover the split folders' totals before this call starts the next pass.
            mergeRollUp(previous.get().generation());
        }
        WalkPass pass = walk.walk(store, "rollup", List.of("publish"), new RollUpVisitor());
        if (!pass.complete()) {
            return subtreeSize("").orElse(0);       // other holders still walk; the last finisher merges
        }
        OptionalLong total = mergeRollUp(pass.generation());
        if (total.isEmpty()) {
            return subtreeSize("").orElse(0);       // superseded before the merge; the newer pass recomputes
        }
        compactRollUp();
        return total.getAsLong();
    }

    /**
     * Sum the per-segment partial totals of the completed pass {@code generation} into the final {@code sizes/}
     * roll-ups of every folder a segment cut split (a folder wholly inside one segment was already written by the
     * fold's pop; only the O(segments x depth) cut-straddling ancestors - the repository root among them - wait for
     * the merge). Idempotent and deterministic, so every racing pass finisher may run it; guarded by the pass
     * generation, so a merge that lost against the next pass writes nothing instead of stale totals. Partials of
     * passes before {@code generation} are deleted afterwards (a newer pass's are never touched: its own merge
     * consumes them). Returns the repository total, or empty when the pass was superseded.
     */
    private OptionalLong mergeRollUp(long generation) throws IOException {
        Optional<WalkPass> current = walk.pass(store, "rollup");
        if (current.isEmpty() || current.get().generation() != generation) {
            return OptionalLong.empty();
        }
        Map<String, Long> totals = new TreeMap<>();
        for (int index = 0; index < current.get().segments(); index++) {
            Optional<ArtifactStore.Versioned> stored = store.readVersioned(rollUpStateKey(index));
            RollUpState state = stored.map(versioned -> RollUpState.parse(versioned.content())).orElse(null);
            if (state == null || state.generation() != generation) {
                continue;                            // a segment that held no keys never wrote a partial
            }
            state.contributions().forEach((path, bytes) -> totals.merge(path, bytes, Long::sum));
        }
        for (Map.Entry<String, Long> total : totals.entrySet()) {
            // Through the counter: the recomputed total is the truth and supersedes what this node still holds
            // pending for the folder, which would otherwise be folded in on top of it at the next flush.
            new StoredCounter(store, sizeKey(rollUpFolder(total.getKey()))).set(total.getValue());
        }
        for (String name : store.list("walks/rollup/state")) {
            Optional<ArtifactStore.Versioned> stored = store.readVersioned("walks/rollup/state/" + name);
            RollUpState state = stored.map(versioned -> RollUpState.parse(versioned.content())).orElse(null);
            if (stored.isPresent() && (state == null || state.generation() < generation)) {
                store.delete("walks/rollup/state/" + name);
            }
        }
        return OptionalLong.of(totals.getOrDefault("publish", 0L));
    }

    /**
     * Whether this {@code publish/} store key is the compliance gate's review handle rather than a published
     * artifact - the single rule both the incremental fold and this reconcile apply, so the two cannot disagree
     * about what a browse total counts.
     *
     * <p>A retroactive hold links {@code publish/quarantine<path>} and <b>does not unpublish the artifact's own
     * pointer</b>, so for the life of the hold the same bytes sit under two {@code publish/} keys and were counted
     * twice in every ancestor total, the repository root included. Invisibly, too: the console hides the
     * {@code quarantine} subtree from browse, so the inflation had no row an operator could open to explain it
     *. A review handle is how a held artifact stays reachable to a reviewer; nothing serves from it, so it
     * contributes no bytes to a total exactly as it contributes no row to a listing.
     */
    static boolean underReview(String publishKey) {
        return publishKey.equals(Publication.QUARANTINE_ROOT)
                || publishKey.startsWith(Publication.QUARANTINE_ROOT + "/");
    }

    /** Delete the {@code sizes/} roll-up of every folder that no longer exists - the walk-riding pass's compaction,
     *  judged per row against the live tree (one child listing each) instead of a buffered whole-tree live set, and
     *  paged in bounded strides so a repository of many folders never materialises its row names as one list. */
    private void compactRollUp() throws IOException {
        ROWS.scan(store, ROOT, name -> {
            String path = name.equals("~") ? "" : StoreRepositoryInventory.decode(name);
            String key = path.isEmpty() ? "publish" : "publish/" + path;
            // A row under the review subtree is dropped whether or not its folder still exists: the fold stopped
            // creating them, so any that remain were written before it stopped and would otherwise stand until the hold
            // ended - a cached total for a folder browse does not show.
            if (underReview(key) || store.isEmpty(key)) {
                new StoredCounter(store, ROOT + "/" + name).delete();
            }
        });
    }

    /** The roll-up row namespace. A compaction that stopped early would leave stale rows standing while reporting a
     *  finished pass, so the entry cap is OFF and the binding bound is the round-trip budget - raised here, because a
     *  large repository has a roll-up row per browse folder, to 10^5 round-trips of the drain page - which raises a named
     *  {@code TraversalException} rather than silently shortening the sweep. Deleting a row behind the cursor is safe:
     *  the enumeration only ever pages forward from the last name it delivered, so a removed earlier name can never
     *  displace a later one. */
    /** The {@code sizes/} space's root, and this class is its single composer: every cached-total key is
     *  built from it here and the manifest declares this constant rather than re-spelling the literal. */
    static final String ROOT = "sizes";

    private static final BoundedChildren ROWS =
            BoundedChildren.bounded().entries(Integer.MAX_VALUE).steps(100_000).page(BoundedChildren.DRAIN_PAGE);

    /** The cached rolled-up subtree size of a browse folder - the sum of the sizes of the artifacts published beneath
     *  it, as last computed by {@link #rollUpSizes} - or empty when no roll-up has been computed for it yet (a
     *  repository whose retention sweep has not run, or a folder created since the last sweep). A single direct key
     *  read against a small object: a browse never recomputes the tree. The path is the folder's publish-relative
     *  request path, a leading slash optional so either console's browse convention resolves the same object. */
    OptionalLong subtreeSize(String path) throws IOException {
        // Through the counter, so a delta this node has deferred is in the size it shows: the same object the
        // observer moves, read the way the observer's node must read it.
        return new StoredCounter(store, sizeKey(relative(path))).readIfPresent();
    }

    /** The bounds the walk-less roll-up descends {@code publish/} under. A roll-up that stopped early would commit a
     *  folder total missing everything past the cap - a browse size that reads complete but under-counts, precisely
     *  gate 4's plausible-but-incomplete answer - so the entry cap is only a per-call continuation that
     *  {@link #rollUp} follows to exhaustion, and the binding bound is the step budget (one
     *  {@link ArtifactStore#exists} probe per opened node), which raises a named
     *  {@link build.jenesis.repository.walk.TraversalException} rather than answering short. Depth stays at the
     *  primitive's {@link ArtifactStore#MAX_SEGMENTS} default, so a request path deeper than the store's own write
     *  ceiling fails by name where the previous recursion would have descended it (and, past a few thousand segments,
     *  overflowed the stack). */
    private static final PagedTreeWalk TREE = PagedTreeWalk.bounded().steps(5_000_000).page(BoundedChildren.DRAIN_PAGE);

    /** Post-order roll-up of the whole {@code publish/} subtree: an artifact leaf contributes its blob's recorded size,
     *  and a folder sums its descendants, commits its own roll-up object and is marked live. Reads only the tiny
     *  publish pointer and the blob size, never the artifact body.
     *
     *  <p>Driven by the shared bounded descent ({@link PagedTreeWalk}) rather than a self-recursion per path segment:
     *  the leaf stream arrives in {@linkplain build.jenesis.repository.walk.Trees#order path order}, which is
     *  subtree-contiguous, so the post-order folder totals fold over it with an O(depth) frame stack - the same shape
     *  {@link RollUpVisitor} folds the resumable pass with, now shared with the walk-less path. A wide folder is paged
     *  rather than listed whole, and the descent is iterative, so neither a million-sibling level nor a client-planted
     *  path depth is held in heap or on the call stack. */
    private long rollUp(Set<String> live) throws IOException {
        Fold fold = new Fold(live);
        String cursor = null;
        while (true) {
            Traversal.Result result = TREE.walk(store, "publish", cursor, fold::leaf);
            if (result.exhausted()) {
                break;
            }
            cursor = result.cursor().orElseThrow();
        }
        return fold.finish();
    }

    /** The O(depth) post-order fold over the descent's path-ordered leaf stream: a frame is pushed when the stream
     *  descends into a folder and popped - written through and added to its parent - when the stream leaves it. The
     *  state survives a per-call entry cap because it lives outside the resume loop, so a continuation folds on
     *  exactly where the previous call stopped. */
    private final class Fold {

        private final Set<String> live;
        private final List<String> folders = new ArrayList<>();     // the open folder chain, root ("") first
        private final List<Long> totals = new ArrayList<>();
        private boolean any;

        private Fold(Set<String> live) {
            this.live = live;
        }

        /** One stored publish pointer, in path order. */
        private void leaf(String key) throws IOException {
            if (key.equals("publish")) {
                return;                                  // the root itself stored as an object: not a browse folder
            }
            String relative = key.substring("publish/".length());
            int slash = relative.lastIndexOf('/');
            enter(slash < 0 ? "" : relative.substring(0, slash));
            // The raw pointer, not Publication.located(): withholding (a quarantine hold) is a serving concern -
            // the held blob still occupies storage, so the roll-up counts it - and located() would additionally run
            // the whole interceptor chain (each a store read) once per leaf per sweep.
            Optional<String> blob = publication.blob("/" + relative);
            long size = blob.isPresent() ? Math.max(0, store.size("blobs/" + blob.get())) : 0;
            totals.set(totals.size() - 1, totals.getLast() + size);
        }

        /** Make {@code folder} the innermost open frame: close every open frame it does not sit under, then open the
         *  frames between the deepest surviving ancestor and it. */
        private void enter(String folder) throws IOException {
            any = true;
            if (folders.isEmpty()) {
                folders.add("");
                totals.add(0L);
            }
            while (!contains(folders.getLast(), folder)) {
                close();
            }
            String open = folders.getLast();
            while (!open.equals(folder)) {
                int slash = folder.indexOf('/', open.isEmpty() ? 0 : open.length() + 1);
                open = slash < 0 ? folder : folder.substring(0, slash);
                folders.add(open);
                totals.add(0L);
            }
        }

        /** Pop the innermost frame: its total is final, so commit the folder's roll-up object, mark it live, and add
         *  its bytes to the parent that is now innermost. */
        private void close() throws IOException {
            String folder = folders.removeLast();
            long total = totals.removeLast();
            new StoredCounter(store, sizeKey(folder)).set(total);   // supersedes this node's pending delta too
            live.add(folder);
            if (!totals.isEmpty()) {
                totals.set(totals.size() - 1, totals.getLast() + total);
            }
        }

        /** Close every still-open frame and answer the repository total. A {@code publish/} tree that held no pointer
         *  at all leaves no frame open and writes nothing - the previous recursion's behaviour, where an empty root
         *  was a childless leaf that committed no roll-up object. */
        private long finish() throws IOException {
            if (!any) {
                return 0;
            }
            long total = 0;
            while (!folders.isEmpty()) {
                total = totals.getLast();
                close();
            }
            return total;
        }

        /** Whether {@code folder} is {@code ancestor} or sits beneath it; the root ({@code ""}) contains everything. */
        private boolean contains(String ancestor, String folder) {
            return ancestor.isEmpty() || folder.equals(ancestor) || folder.startsWith(ancestor + "/");
        }
    }

    /**
     * Folds the shared walk's lexicographic {@code publish/} key stream into post-order folder totals with an
     * O(depth) frame stack: the stream is subtree-contiguous, so a frame is pushed when the
     * stream descends into a folder and popped-and-added-to-its-parent when the stream leaves it, and a popped
     * folder that lies wholly inside the current segment's range has seen every leaf it will ever see, so its total
     * is final and written through immediately (deterministic, hence idempotent under the walk's crash-stride
     * replay). A folder a range bound cuts is only partially seen by this segment; its partial goes to the
     * segment's {@code walks/rollup/state/<nnn>} object, summed by the pass-completion merge.
     *
     * <p>Resumability: the whole in-memory state (frame stack, cut partials, the last folded key) is flushed to the
     * segment's state object in {@link #beforeCheckpoint} - before the walk durably commits its cursor - so a
     * committed cursor never lies about a partial still sitting in a crashed buffer. A worker that takes a segment
     * over (or resumes after a crash) reloads that state; the walk redelivers at most the uncommitted stride tail,
     * and keys at or below the state's own cursor are skipped as already folded - the flush may only ever be
     * <em>ahead</em> of the walk's committed cursor, never behind it. State writes are compare-and-set fenced the
     * same way the walk fences its segment claims: a flush that loses the CAS (or re-reads another holder's write)
     * proves the segment was taken over, and this worker stops rather than double-counting.
     */
    private final class RollUpVisitor implements ArtifactWalk.KeyVisitor {

        /** This worker's identity inside the state objects it flushes - the fencing token's human-readable half. */
        private final String holder = UUID.randomUUID().toString().substring(0, 8);

        /** The pass's static segment plan, read once on the first visit (the manifest exists by then). */
        private List<WalkSegment> plan;
        private long generation;

        private int current = -1;
        private Object token;
        private String cursor;
        /** The open-folder chain, index 0 the {@code publish} root, the last entry the deepest open folder. */
        private final List<Frame> open = new ArrayList<>();
        /** Partials of range-cut folders this segment already left - only ever ancestors of the range's lower
         *  bound, so O(depth) entries. */
        private final Map<String, Long> closed = new LinkedHashMap<>();
        private boolean dirty;

        @Override
        public void visit(String key) throws IOException {
            if (plan == null) {
                plan = walk.segments(store, "rollup");
                generation = plan.isEmpty() ? 0 : plan.getFirst().generation();
            }
            int index = range(key);
            if (index != current) {
                load(index);
            }
            if (cursor != null && Trees.order(key, cursor) <= 0) {
                return;                              // the crash-stride replay: already folded and durably flushed
            }
            if (underReview(key)) {
                return;                              // a review handle, not a publication
            }
            fold(key);
            cursor = key;
            dirty = true;
        }

        @Override
        public void beforeCheckpoint(String committed) throws IOException {
            if (current < 0 || !dirty) {
                return;                              // nothing folded since the last flush (or an empty segment)
            }
            byte[] content = RollUpState.serialize(generation, holder, cursor, open, closed);
            String key = rollUpStateKey(current);
            if (!store.writeVersioned(key, content, token)) {
                throw new IOException("the roll-up partial of segment " + current + " was taken over");
            }
            // Re-read for the next compare-and-set's token - and verify the object is still ours, exactly as the
            // walk's own checkpoint commit does: a state taken over between the write and this read must not hand
            // this worker the new holder's token, or the next flush would double-count into a live holder's state.
            Optional<ArtifactStore.Versioned> written = store.readVersioned(key);
            RollUpState ours = written.map(versioned -> RollUpState.parse(versioned.content())).orElse(null);
            if (ours == null || !holder.equals(ours.holder())) {
                throw new IOException("the roll-up partial of segment " + current + " was taken over");
            }
            token = written.get().token();
            dirty = false;
        }

        /** The planned range containing {@code key}; the ranges partition the root, so exactly one matches. */
        private int range(String key) throws IOException {
            if (current >= 0 && contains(plan.get(current), key)) {
                return current;
            }
            for (int index = 0; index < plan.size(); index++) {
                if (contains(plan.get(index), key)) {
                    return index;
                }
            }
            throw new IOException("no planned roll-up range contains " + key);
        }

        private boolean contains(WalkSegment segment, String key) {
            return (segment.from() == null || Trees.order(segment.from(), key) <= 0)
                    && (segment.to() == null || Trees.order(key, segment.to()) < 0);
        }

        /** Enter a segment's range: drop the in-memory fold (any unflushed tail was never cursor-committed, so its
         *  keys will be redelivered to whoever walks that range next) and adopt the persisted partial, if the
         *  current pass wrote one - a superseded pass's leftover only donates its CAS token. */
        private void load(int index) throws IOException {
            open.clear();
            closed.clear();
            cursor = null;
            dirty = false;
            current = index;
            Optional<ArtifactStore.Versioned> stored = store.readVersioned(rollUpStateKey(index));
            token = stored.map(ArtifactStore.Versioned::token).orElse(null);
            RollUpState state = stored.map(versioned -> RollUpState.parse(versioned.content())).orElse(null);
            if (state == null || state.generation() != generation) {
                return;
            }
            cursor = state.cursor();
            state.open().forEach((path, bytes) -> open.add(new Frame(path, bytes)));
            closed.putAll(state.closed());
        }

        /** One post-order fold step: pop the folders the stream just left (adding each into its parent and
         *  disposing it), push the newly entered ones, and add the leaf's blob size to its own folder. */
        private void fold(String key) throws IOException {
            List<String> chain = chain(key);
            int shared = 0;
            while (shared < open.size() && shared < chain.size()
                    && open.get(shared).path.equals(chain.get(shared))) {
                shared++;
            }
            while (open.size() > shared) {
                Frame frame = open.removeLast();
                if (!open.isEmpty()) {
                    open.getLast().bytes += frame.bytes;
                }
                dispose(frame);
            }
            for (int index = open.size(); index < chain.size(); index++) {
                open.add(new Frame(chain.get(index), 0));
            }
            // The raw pointer, not Publication.located(): withholding (a quarantine hold) is a serving concern -
            // the held blob still occupies storage, so the roll-up counts it - and located() would additionally
            // run the whole interceptor chain (each a store read) once per leaf per pass.
            Optional<String> blob = publication.blob(key.substring("publish".length()));
            if (blob.isPresent()) {
                open.getLast().bytes += Math.max(0, store.size("blobs/" + blob.get()));
            }
        }

        /** A left folder's total is final if its whole span lies inside the current range - written through, the
         *  walk's replay simply recomputing the same value - and a range-cut partial otherwise. In path order a
         *  folder's subtree spans {@code [path + "/", path + "0")} ({@code '0'} the character after {@code '/'}). */
        private void dispose(Frame frame) throws IOException {
            WalkSegment segment = plan.get(current);
            if ((segment.from() == null || Trees.order(segment.from(), frame.path + "/") <= 0)
                    && (segment.to() == null || Trees.order(frame.path + "0", segment.to()) <= 0)) {
                new StoredCounter(store, sizeKey(rollUpFolder(frame.path))).set(frame.bytes);
            } else {
                closed.merge(frame.path, frame.bytes, Long::sum);
            }
        }

        /** The folder chain of a leaf key, root first: {@code publish/a/b/leaf} is under {@code publish},
         *  {@code publish/a} and {@code publish/a/b} - every proper prefix, the leaf itself excluded. */
        private List<String> chain(String key) {
            List<String> chain = new ArrayList<>();
            chain.add("publish");
            int from = "publish".length();
            while (true) {
                int slash = key.indexOf('/', from + 1);
                if (slash < 0) {
                    return chain;
                }
                chain.add(key.substring(0, slash));
                from = slash;
            }
        }
    }

    /** One open folder of the roll-up fold: its absolute key path and the bytes summed under it so far. */
    private static final class Frame {

        private final String path;
        private long bytes;

        private Frame(String path, long bytes) {
            this.path = path;
            this.bytes = bytes;
        }
    }

    /** A segment's flushed roll-up partial, parsed: the pass generation and flushing holder (the fencing pair), the
     *  last folded key, the open-folder chain and the range-cut partials - all O(depth), never a folder census. */
    private record RollUpState(long generation, String holder, String cursor,
                               SequencedMap<String, Long> open, Map<String, Long> closed) {

        /** Everything this segment contributed to folders it could not finalise - what the merge sums. A closed
         *  partial is self-contained (its descendants were all popped into it before it left the stack), but an
         *  open frame's bytes exclude what its still-open descendants hold - the pop cascade never ran for them -
         *  so each open folder contributes its own bytes plus every deeper frame's: the frames form one ancestor
         *  chain, so everything below a frame in the stack is inside it. */
        private Map<String, Long> contributions() {
            Map<String, Long> all = new LinkedHashMap<>(closed);
            long below = 0;
            for (Map.Entry<String, Long> frame : open.reversed().entrySet()) {
                below += frame.getValue();
                all.merge(frame.getKey(), below, Long::sum);
            }
            return all;
        }

        private static byte[] serialize(long generation, String holder, String cursor,
                                        List<Frame> open, Map<String, Long> closed) throws IOException {
            Properties properties = new Properties();
            properties.setProperty("generation", Long.toString(generation));
            properties.setProperty("holder", holder);
            if (cursor != null) {
                properties.setProperty("cursor", cursor);
            }
            properties.setProperty("open", Integer.toString(open.size()));
            for (int index = 0; index < open.size(); index++) {
                properties.setProperty("open." + index + ".path", open.get(index).path);
                properties.setProperty("open." + index + ".bytes", Long.toString(open.get(index).bytes));
            }
            properties.setProperty("closed", Integer.toString(closed.size()));
            int index = 0;
            for (Map.Entry<String, Long> entry : closed.entrySet()) {
                properties.setProperty("closed." + index + ".path", entry.getKey());
                properties.setProperty("closed." + index + ".bytes", Long.toString(entry.getValue()));
                index++;
            }
            return Documents.bytes(properties);
        }

        /** {@code null} for an unparseable object - treated as a superseded pass's leftover, never a failure. */
        private static RollUpState parse(byte[] content) {
            try {
                Properties properties = new Properties();
                properties.load(new ByteArrayInputStream(content));
                long generation = Long.parseLong(properties.getProperty("generation"));
                String holder = properties.getProperty("holder");
                if (holder == null) {
                    return null;
                }
                SequencedMap<String, Long> open = new LinkedHashMap<>();
                int count = Integer.parseInt(properties.getProperty("open"));
                for (int index = 0; index < count; index++) {
                    String path = properties.getProperty("open." + index + ".path");
                    if (path == null) {
                        return null;
                    }
                    open.put(path, Long.parseLong(properties.getProperty("open." + index + ".bytes")));
                }
                Map<String, Long> closed = new LinkedHashMap<>();
                count = Integer.parseInt(properties.getProperty("closed"));
                for (int index = 0; index < count; index++) {
                    String path = properties.getProperty("closed." + index + ".path");
                    if (path == null) {
                        return null;
                    }
                    closed.put(path, Long.parseLong(properties.getProperty("closed." + index + ".bytes")));
                }
                return new RollUpState(generation, holder, properties.getProperty("cursor"), open, closed);
            } catch (IOException | RuntimeException _) {
                return null;
            }
        }
    }

    /** Where a segment's roll-up partial lives, beside the pass state it belongs to - covered by the same
     *  {@code walks/} reclamation ownership, keyed by the segment index the walk's own objects use. */
    private static String rollUpStateKey(int index) {
        return "walks/rollup/state/" + String.format(Locale.ROOT, "%03d", index);
    }

    /** The publish-relative folder path of an absolute fold key - {@code publish} itself is the repository root. */
    private static String rollUpFolder(String path) {
        return path.equals("publish") ? "" : path.substring("publish/".length());
    }


    /** The store key a folder's rolled-up subtree size is cached under: one small flat object per folder, keyed by the
     *  folder's URL-encoded publish-relative request path so a nested path stays a single collision-free segment under
     *  {@code sizes/} (a filesystem then never needs a folder name to be both a file and a directory). The repository
     *  root uses a reserved leaf {@code ~} that the encoding of a real, non-empty path can never produce. */
    static String sizeKey(String path) {
        return ROOT + "/" + (path.isEmpty() ? "~" : StoreRepositoryInventory.encode(path));
    }

    /** Normalise a browse path to the publish-relative form the roll-up keys use - a leading slash is optional (the
     *  two consoles differ), a trailing one dropped - so {@code /maven/org} and {@code maven/org} resolve one object.
     *  Package-private so {@link SubtreeSizePublicationObserver}'s incremental fold builds the ancestor chain keys
     *  through the identical normalisation and encoding the full-walk reconcile writes, keeping one object layout. */
    static String relative(String path) {
        if (path == null) {
            return "";
        }
        int start = 0;
        int end = path.length();
        while (start < end && path.charAt(start) == '/') {
            start++;
        }
        while (end > start && path.charAt(end - 1) == '/') {
            end--;
        }
        return path.substring(start, end);
    }
}
