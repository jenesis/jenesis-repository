package build.jenesis.repository.index;

import module java.base;
import build.jenesis.repository.walk.BoundedChildren;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.maintenance.MaintenanceTask;
import build.jenesis.repository.maintenance.RepositoryContext;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.index.keys.PublishedIndexKeys;
import build.jenesis.repository.store.DirtyIndexFeed;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.ServableNames;
import build.jenesis.repository.walk.PagedTreeWalk;
import build.jenesis.repository.walk.Traversal;

/**
 * The scheduled published-index pass: each repository's publications are walked (the {@code publish/} pointer tree,
 * served-view - a withheld path is skipped, the quarantine review subtree excluded) and the ones published past the
 * durable high-water mark are appended as a new immutable chunk to the chain, advancing the mark. On a configured
 * cadence a full-snapshot rebase re-indexes everything into a fresh chain (Central's weekly-full/daily-incremental
 * shape), superseding the old chain's chunks and garbage-collecting them after a grace period. Exclusive (the
 * default), so a replicated deployment publishes on one node per interval under the {@code index} lease and the
 * descriptor's compare-and-set never loses an update. The walk reads only pointer metadata and the small
 * {@code published/} sidecar - never an artifact blob.
 *
 * <p>Orphan note: a pass that dies between writing chunk objects and committing the descriptor - or a corrupt head
 * that {@link IndexDescriptor#parse} reads as empty - leaves chunk objects behind that no descriptor references.
 * They are inert (never served, never re-linked; the store dedupes a re-written identical chunk) and are reclaimed
 * today only by the explicit operator purge of this module's namespace; an automatic unreferenced-chunk reconcile
 * is deliberately not attempted here (a sweeping delete keyed off a failed read is worse than a bounded leak).
 *
 * <p>Self-heal note: the inverse - a chunk the current chain still <em>references</em> that is lost out of band
 * (a partial store failure, an object-lifecycle rule, a purge that dropped objects but not the head) - is not a leak
 * but a hole that would 404 a consumer's sync, and it is self-healed: a pass that finds any referenced chunk absent
 * ({@link #chainIntact}) forces a full rebase that re-derives the whole chain from the durable publish pointers,
 * exactly as a corrupt head does. This adds nothing (a re-scanning delete keyed off a failed read) beyond the rebase
 * the module already runs - it only brings the recovery forward from the scheduled rebase to the next pass.
 */
public final class PublishedIndexTask implements MaintenanceTask {

    /** Zstandard level; 3 is the library default - fast, and the records are tiny and repetitive. */
    static final int LEVEL = 3;

    /** Target uncompressed bytes per independent frame; capped to the chunk max so a small max still rotates. */
    static final int FRAME_TARGET = 32 * 1024;

    /** How long a superseded chunk lingers after a rebase, so an in-flight consumer finishes reading it. */
    static final Duration GC_GRACE = Duration.ofHours(1);

    private static final String BLOBS = "blobs/";
    private static final String ROOT = "publish";
    private static final String QUARANTINE = "quarantine";

    private final Duration interval;
    private final long maxChunkBytes;

    public PublishedIndexTask(Duration interval, long maxChunkBytes) {
        this.interval = interval;
        this.maxChunkBytes = maxChunkBytes;
    }

    @Override
    public String name() {
        return "index";
    }

    @Override
    public Duration interval() {
        return interval;
    }

    /** A gauge the pass reports: the task routes it to its registry, the walk consumer keeps its own. */
    @FunctionalInterface
    interface Gauges {
        void gauge(String name, String description, double value) throws IOException;
    }

    @Override
    public void repository(RepositoryContext context) throws IOException {
        Map<String, String> tags = Map.of("tenant", context.tenant(), "repository", context.repository());
        pass(context.store(), context.now(), false,
                (name, description, value) -> context.gauge(name, description, tags, value));
    }

    /**
     * Rebase the index onto a fresh chain from every served pointer, in path order: what a walk carrying
     * {@link IndexRebaseConsumer} does at its completion, and what the scheduled pass does on its own only when an
     * event demands it - no chain yet, a chain with a hole, the retraction flag standing.
     */
    public void rebase(ArtifactStore store, Instant now) throws IOException {
        pass(store, now, true, (name, description, value) -> { });
    }

    private void pass(ArtifactStore store, Instant now, boolean forced, Gauges gauges) throws IOException {
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(store);
        Publication publication = new Publication(store);
        // Route the served-view screen through the servable-name seam (P-E3 E20): a published pointer is indexed only
        // when a GET would serve it (SERVABLE) - a withheld hold/quarantine or a torn-blob pointer is skipped - composing
        // this pass's own Publication interceptor chain, the same discrimination the serve path makes.
        ServableNames servableNames = new ServableNames(store, publication);
        PublishedIndex index = new PublishedIndex(store);
        DirtyIndexFeed feed = new DirtyIndexFeed(store, PublishedIndexKeys.PREFIX);
        Optional<ArtifactStore.Versioned> stored = index.descriptorVersioned();
        IndexDescriptor descriptor = stored.map(versioned -> IndexDescriptor.parse(versioned.content()))
                .orElse(IndexDescriptor.empty());
        Object token = stored.map(ArtifactStore.Versioned::token).orElse(null);
        // The withhold-change retraction flag, read WITH its token before the walk: IndexRetractionObserver raises it
        // on any withhold transition, and its presence forces the same full rebase a missing or broken chain already
        // triggers - the rebase re-screens every path through ServableNames, so a now-withheld stanza drops out of
        // the rebuilt chain and a cleared one re-appears. Immutable, content-addressed, consumer-cached chunks admit
        // no other retraction (a serve-time filter never reaches a cached chunk). The scheduled rebase is the walk's.
        Optional<ArtifactStore.Versioned> retraction = index.retraction().peek();
        // No chain yet counts a head that reads as empty - a torn descriptor parses as generation 0 - so a corrupt
        // head is rebuilt over on the next pass rather than served empty until a walk happens to carry the rebase.
        boolean rebase = forced || stored.isEmpty() || descriptor.generation() == 0 || !chainIntact(index, descriptor)
                || retraction.isPresent();
        long cutoff = System.currentTimeMillis();
        int frameBudget = (int) Math.max(64, Math.min(FRAME_TARGET, maxChunkBytes));
        ChunkWriter writer = new ChunkWriter(index, maxChunkBytes, frameBudget, LEVEL);
        IndexDescriptor.Cursor watermark = descriptor.watermark();
        Progress progress = new Progress(watermark);
        List<DirtyIndexFeed.Entry> marked = List.of();
        if (rebase) {
            // Every served pointer, in path order, over the pass's own bounded walk of the publish tree; each record's
            // publish instant is read from its published/ sidecar as it is walked, never pre-buffered.
            walk(store, publication, servableNames, inventory, true, watermark, record -> {
                writer.add(record);
                progress.advance(record.published(), record.path());
            });
        } else {
            // Only what was published since the last pass: the paths the write path marked, each screened and read
            // exactly as the walk would have, and nothing enumerated to find them.
            marked = feed.pending();
            for (DirtyIndexFeed.Entry entry : marked) {
                String relative = entry.coordinate().startsWith("/") ? entry.coordinate().substring(1)
                        : entry.coordinate();
                if (relative.equals(QUARANTINE) || relative.startsWith(QUARANTINE + "/")) {
                    continue;                                // the quarantine review subtree is stored, never served
                }
                emit(store, publication, servableNames, inventory, relative, true, watermark, record -> {
                    writer.add(record);
                    progress.advance(record.published(), record.path());
                });
            }
        }
        List<IndexDescriptor.Chunk> appended = writer.finish();
        List<IndexDescriptor.Chunk> chain = new ArrayList<>();
        List<IndexDescriptor.Superseded> supersede = new ArrayList<>(descriptor.superseded());
        int generation = descriptor.generation();
        Instant rebased = descriptor.rebased();
        if (rebase) {
            for (IndexDescriptor.Chunk gone : descriptor.chain()) {
                supersede.add(new IndexDescriptor.Superseded(gone.id(), now.plus(GC_GRACE)));
            }
            chain.addAll(appended);
            generation = descriptor.generation() + 1;
            rebased = now;
        } else {
            chain.addAll(descriptor.chain());
            chain.addAll(appended);
        }
        Set<String> live = new HashSet<>();
        for (IndexDescriptor.Chunk chunk : chain) {
            live.add(chunk.id());
        }
        List<IndexDescriptor.Superseded> remaining = new ArrayList<>();
        boolean collected = false;
        for (IndexDescriptor.Superseded gone : supersede) {
            if (!now.isBefore(gone.deleteAfter()) && !live.contains(gone.id())) {
                try {
                    index.deleteChunk(gone.id());
                    collected = true;
                } catch (IOException undeleted) {
                    // One failed delete must not abort the pass before the descriptor commits (which would orphan
                    // every chunk this pass wrote, unreferenced forever); keep the entry so the next pass retries.
                    remaining.add(gone);
                }
            } else {
                remaining.add(gone);
            }
        }
        if (!rebase && appended.isEmpty() && !collected) {
            feed.clear(marked);                                  // marks that named nothing servable are spent
            return;                                              // nothing changed; leave the descriptor untouched
        }
        IndexDescriptor updated = new IndexDescriptor(generation, progress.watermark(), rebased, chain, remaining);
        if (!index.putDescriptor(updated, token)) {
            // A concurrent pass beat us (rare under the exclusive lease); discard the chunks we just orphaned. The
            // chunks are content-addressed, so a winner that indexed the same publications wrote the very same ids -
            // deleting those would tear a chunk out of the winner's served chain, so only unreferenced ones go (and
            // if the winner's head cannot be re-read, nothing goes: a leaked chunk is recoverable, a torn chain not).
            Set<String> winners = new HashSet<>();
            try {
                index.descriptor().ifPresent(winner -> {
                    for (IndexDescriptor.Chunk chunk : winner.chain()) {
                        winners.add(chunk.id());
                    }
                    for (IndexDescriptor.Superseded gone : winner.superseded()) {
                        winners.add(gone.id());
                    }
                });
            } catch (IOException unreadable) {
                return;
            }
            for (IndexDescriptor.Chunk chunk : appended) {
                if (!winners.contains(chunk.id())) {
                    index.deleteChunk(chunk.id());
                }
            }
            return;
        }
        // The committed chain has landed: the marks it applied are spent (a mark re-touched meanwhile keeps its newer
        // token and survives to the next pass), and after a rebase every mark older than the walk is redundant.
        if (rebase) {
            feed.compactThrough(cutoff);
        } else {
            feed.clear(marked);
        }
        // Clear the retraction flag, but only while its token is unchanged since it was read before the walk. A
        // withhold event that re-wrote the flag mid-pass changed its token, so the flag survives and the next pass
        // rebases again; a crash before here also leaves it, so the next pass rebases. Idempotent and crash-safe.
        if (rebase && retraction.isPresent()) {
            index.retraction().clearIf(retraction.get().token());
        }
        gauges.gauge("jenreg.index.chunks", "Published index chunks in the current chain", chain.size());
        gauges.gauge("jenreg.index.bytes", "Compressed size of the published index chain",
                chain.stream().mapToLong(IndexDescriptor.Chunk::compressedSize).sum());
    }

    /** Whether every chunk the descriptor's chain still references is present in the store. A referenced chunk lost
     *  out of band - a partial store failure, an over-eager object-lifecycle rule, a purge that dropped objects but
     *  not the head - leaves the served chain unusable ({@code GET /chunks/<id>} 404s the gap) and no incremental
     *  pass would ever re-derive it, since only publications past the watermark are appended. So a hole in the chain
     *  forces a full rebase that reconstructs the whole chain from the durable publish pointers on the very next
     *  pass, exactly as a corrupt descriptor head already does - rather than lingering broken until the scheduled
     *  rebase. A bounded per-chunk {@code exists} metadata probe (never an artifact read) evaluated only when the
     *  pass would otherwise stay incremental, so the steady-state read cost is one cheap probe per live chunk. */
    private static boolean chainIntact(PublishedIndex index, IndexDescriptor descriptor) {
        for (IndexDescriptor.Chunk chunk : descriptor.chain()) {
            if (!index.chunkExists(chunk.id())) {
                return false;
            }
        }
        return true;
    }

    /** A record sink that can throw, so the walk streams straight into the chunk writer without a buffer. */
    private interface Sink {
        void accept(IndexRecord record) throws IOException;
    }

    /** Tracks the advancing compound high-water cursor across a pass without a mutable capture. The cursor advances to
     *  the lexicographic maximum of ({@code published}, then {@code path}) over every record indexed, so a pass that
     *  only recovers a same-instant split artifact (published at the standing instant, later path) still advances the
     *  path component - and a next pass resumes strictly after it, re-processing neither. A racing record's EPOCH
     *  publish never advances the instant, exactly as before. */
    private static final class Progress {

        private IndexDescriptor.Cursor watermark;

        private Progress(IndexDescriptor.Cursor watermark) {
            this.watermark = watermark;
        }

        private void advance(Instant published, String path) {
            IndexDescriptor.Cursor candidate = new IndexDescriptor.Cursor(published, path);
            if (candidate.compareTo(watermark) > 0) {
                watermark = candidate;
            }
        }

        private IndexDescriptor.Cursor watermark() {
            return watermark;
        }
    }

    /** The bounds the index pass descends {@code publish/} under. An index that stopped early would publish a chunk
     *  chain a consumer syncs as complete while it is missing every coordinate past the cap, and would then advance
     *  the watermark past them - so the entry cap is only the per-call continuation {@link #walk} follows to
     *  exhaustion, and the binding bound is the step budget (one {@link ArtifactStore#exists} probe per opened node),
     *  which raises a named {@link build.jenesis.repository.walk.TraversalException} rather than answering short.
     *  Depth stays at the primitive's {@link ArtifactStore#MAX_SEGMENTS} default, so a request path deeper than the
     *  store's own write ceiling fails by name where the previous self-recursion would have descended it onto the
     *  call stack. */
    private static final PagedTreeWalk TREE = PagedTreeWalk.bounded().steps(5_000_000).page(BoundedChildren.DRAIN_PAGE);

    /** Stream every served publish pointer, in path order, through the shared bounded tree walk - iterative
     *  and paged, so neither a client-planted path depth nor a million-sibling folder reaches the call stack or heap.
     *  The quarantine review subtree is stored but never served, so it is filtered out of the delivered keys. */
    private static void walk(ArtifactStore store, Publication publication, ServableNames servableNames,
                             StoreRepositoryInventory inventory, boolean rebase,
                             IndexDescriptor.Cursor watermark, Sink sink) throws IOException {
        String cursor = null;
        while (true) {
            Traversal.Result result = TREE.walk(store, ROOT, cursor, key -> {
                String relative = key.substring(ROOT.length() + 1);
                if (relative.equals(QUARANTINE) || relative.startsWith(QUARANTINE + "/")) {
                    return;                                  // the quarantine review subtree is stored, never served
                }
                emit(store, publication, servableNames, inventory, relative, rebase, watermark, sink);
            });
            if (result.exhausted()) {
                return;
            }
            cursor = result.cursor().orElseThrow();
        }
    }

    private static void emit(ArtifactStore store, Publication publication, ServableNames servableNames,
                             StoreRepositoryInventory inventory, String relative, boolean rebase,
                             IndexDescriptor.Cursor watermark, Sink sink) throws IOException {
        String requestPath = "/" + relative;
        // Served-view screen through the seam (E20): index a pointer only when a GET would serve it. A withheld
        // hold/quarantine or a torn-blob pointer (BLOB_GONE) is skipped - the same WG discrimination located() made,
        // now the one shared servable-name decision so an index stanza can never disagree with the serve path.
        if (servableNames.state(requestPath) != ServableNames.State.SERVABLE) {
            return;                                          // withheld (a hold/quarantine) or the blob is gone
        }
        Optional<String> hash = publication.blob(requestPath);
        if (hash.isEmpty()) {
            return;                                          // raced away between the screen and the pointer read
        }
        String key = BLOBS + hash.get();
        String sha256 = hash.get();
        long size = store.size(key);
        Optional<ArtifactDescriptor> descriptor = inventory.describe(requestPath);
        String ecosystem = descriptor.map(ArtifactDescriptor::ecosystem).orElse(null);
        String coordinate = descriptor.map(ArtifactDescriptor::coordinate).orElse(null);
        String version = descriptor.map(ArtifactDescriptor::version).orElse(null);
        boolean prerelease = descriptor.map(ArtifactDescriptor::prerelease).orElse(false);
        Instant published = Instant.EPOCH;
        boolean racing = false;
        if (coordinate != null && version != null) {
            // The publish instant is read per walked coordinate from its published/ sidecar - a bounded small-object
            // read alongside the describe/size reads above - rather than from a snapshot map pre-buffered over the whole
            // release set. A publish racing this pass (its pointer walked, its sidecar just landed) is indexed at its
            // real instant, so the watermark advances correctly and it is not stranded below EPOCH.
            Instant at = inventory.publishedAt(ecosystem, coordinate, version).orElse(null);
            if (at != null) {
                published = at;
            } else {
                // A located coordinate/version whose publish instant is not yet recorded (the pointer landed but the
                // sidecar has not - the narrow window a publish races this pass): it is genuinely indexable now, so do
                // not let it fall below the watermark. Defaulting it to EPOCH and skipping it would strand it, because
                // the committed watermark advances past its real instant via the other publications in this pass, and
                // no later incremental pass would re-append it until the P7D rebase. Index it now (its EPOCH never
                // advances the watermark, so nothing else is stranded); the rebase later re-derives it with its instant.
                racing = true;
            }
        }
        if (!rebase && !racing && !watermark.precedes(published, requestPath)) {
            // Incremental: only artifacts strictly past the compound cursor. Comparing the whole (instant, path)
            // resumes strictly after the last processed same-instant path, so a same-millisecond artifact split into a
            // later pass (published exactly at the cursor's instant, larger path) is re-included and recovered here
            // rather than stranded until the rebase - while those already chained (path <= the cursor's) stay skipped.
            return;
        }
        sink.accept(new IndexRecord(requestPath, size, sha256, ecosystem, coordinate, version, prerelease, published));
    }

}
