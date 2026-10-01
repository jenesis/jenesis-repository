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
 * The scheduled published-index pass: the publications marked since the last pass - served view only, a withheld path
 * and the quarantine subtree skipped - are appended as a new immutable chunk, advancing the durable high-water mark. A
 * full rebase re-indexes everything into a fresh chain and deletes the superseded chunks after a grace period.
 * Exclusive, so a replicated deployment publishes on one node per interval under the {@code index} lease. It reads only
 * pointer metadata and each version's document, never an artifact blob.
 *
 * <p>A pass that dies between writing chunks and committing the descriptor, or a corrupt head that
 * {@link IndexDescriptor#parse} reads as empty, leaves unreferenced chunks behind. They are inert (never served,
 * deduped on rewrite) and reclaimed only by the operator purge of this namespace: a sweeping delete keyed off a failed
 * read would be worse than a bounded leak.
 *
 * <p>The inverse - a chunk the chain still references, lost out of band - is a hole that would 404 a consumer's sync,
 * so a pass that finds one ({@link #chainIntact}) rebases from the publish pointers at once, as for a corrupt head.
 */
public final class PublishedIndexTask implements MaintenanceTask {

    /** Zstandard level; 3 is the library default - fast, and the records are tiny and repetitive. */
    static final int LEVEL = 3;

    /** Target uncompressed bytes per frame; capped to the chunk maximum so a small maximum still rotates. */
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

    /** Rebase the index onto a fresh chain from every served pointer, in path order: what a walk carrying
     *  {@link IndexRebaseConsumer} does at completion, and what the pass does itself only when an event demands it. */
    public void rebase(ArtifactStore store, Instant now) throws IOException {
        pass(store, now, true, (name, description, value) -> { });
    }

    private void pass(ArtifactStore store, Instant now, boolean forced, Gauges gauges) throws IOException {
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(store);
        Publication publication = new Publication(store);
        // A pointer is indexed only when a GET would serve it - the serve path's own decision.
        ServableNames servableNames = new ServableNames(store, publication);
        PublishedIndex index = new PublishedIndex(store);
        DirtyIndexFeed feed = new DirtyIndexFeed(store, PublishedIndexKeys.PREFIX);
        Optional<ArtifactStore.Versioned> stored = index.descriptorVersioned();
        IndexDescriptor descriptor = stored.map(versioned -> IndexDescriptor.parse(versioned.content()))
                .orElse(IndexDescriptor.empty());
        Object token = stored.map(ArtifactStore.Versioned::token).orElse(null);
        // The retraction flag, read with its token before the walk: it forces the full rebase, which re-screens every
        // path, so a withheld path drops out and a cleared one comes back.
        Optional<ArtifactStore.Versioned> retraction = index.retraction().peek();
        // A torn descriptor parses as generation 0, so a corrupt head is rebuilt on the next pass rather than served
        // empty.
        boolean rebase = forced || stored.isEmpty() || descriptor.generation() == 0 || !chainIntact(index, descriptor)
                || retraction.isPresent();
        long cutoff = System.currentTimeMillis();
        int frameBudget = (int) Math.max(64, Math.min(FRAME_TARGET, maxChunkBytes));
        ChunkWriter writer = new ChunkWriter(index, maxChunkBytes, frameBudget, LEVEL);
        IndexDescriptor.Cursor watermark = descriptor.watermark();
        Progress progress = new Progress(watermark);
        List<DirtyIndexFeed.Entry> marked = List.of();
        if (rebase) {
            // Every served pointer, in path order; each publish instant is read as it is walked, never pre-buffered.
            walk(store, publication, servableNames, inventory, true, watermark, record -> {
                writer.add(record);
                progress.advance(record.published(), record.path());
            });
        } else {
            // Only the paths the write path marked, each screened and read as the walk would, nothing enumerated.
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
                    // One failed delete must not abort the pass before the descriptor commits, which would orphan every
                    // chunk it wrote; the entry is kept so the next pass retries.
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
            // A concurrent pass won (rare under the lease). Chunks are content-addressed, so the winner may hold the
            // very ids this pass wrote: only ids its chain does not reference are deleted, and none if its head cannot
            // be read - a leaked chunk is recoverable, a torn chain is not.
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
        // The committed chain has landed: its marks are spent (a mark re-touched meanwhile keeps a newer token and
        // survives), and after a rebase every mark older than the walk is redundant.
        if (rebase) {
            feed.compactThrough(cutoff);
        } else {
            feed.clear(marked);
        }
        // Clear the flag only while its token is the one read before the walk: a withhold that re-raised it mid-pass,
        // or a crash before here, leaves it for the next pass to rebase again.
        if (rebase && retraction.isPresent()) {
            index.retraction().clearIf(retraction.get().token());
        }
        gauges.gauge("jenrepo.index.chunks", "Published index chunks in the current chain", chain.size());
        gauges.gauge("jenrepo.index.bytes", "Compressed size of the published index chain",
                chain.stream().mapToLong(IndexDescriptor.Chunk::compressedSize).sum());
    }

    /** Whether every chunk the chain references is present. A chunk lost out of band leaves the served chain broken and
     *  no incremental pass would re-derive it, so a hole forces a rebase on the next pass. One {@code exists} probe per
     *  live chunk, made only when the pass would otherwise stay incremental. */
    private static boolean chainIntact(PublishedIndex index, IndexDescriptor descriptor) {
        for (IndexDescriptor.Chunk chunk : descriptor.chain()) {
            if (!index.chunkExists(chunk.id())) {
                return false;
            }
        }
        return true;
    }

    /** A record sink that can throw, so the walk streams into the chunk writer without a buffer. */
    private interface Sink {
        void accept(IndexRecord record) throws IOException;
    }

    /** Tracks the advancing high-water cursor across a pass: the maximum of ({@code published}, {@code path}) over
     *  every record indexed, so a pass that only recovers a same-instant artifact still advances the path and the next
     *  pass resumes after it. A racing record's EPOCH instant never advances the cursor. */
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

    /** The bounds the pass descends {@code publish/} under. A truncated index would be synced as complete and its
     *  watermark would pass the missing coordinates, so the entry cap is only the per-call page {@link #walk} follows
     *  to exhaustion; the binding bound is the step budget, which raises a
     *  {@link build.jenesis.repository.walk.TraversalException} rather than answering short. Depth stays at
     *  {@link ArtifactStore#MAX_SEGMENTS}. */
    private static final PagedTreeWalk TREE = PagedTreeWalk.bounded().steps(5_000_000).page(BoundedChildren.DRAIN_PAGE);

    /** Stream every served publish pointer, in path order, through the shared bounded tree walk, so neither a deep path
     *  nor a huge folder reaches the call stack or heap. The quarantine subtree is stored but never served, so it is
     *  skipped. */
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
        // Index a pointer only when a GET would serve it: a withheld or BLOB_GONE pointer is skipped, so an index line
        // never disagrees with the serve path.
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
            // The publish instant is read per coordinate as walked, so a publish racing this pass is indexed at its
            // real instant.
            Instant at = inventory.publishedAt(ecosystem, coordinate, version).orElse(null);
            if (at != null) {
                published = at;
            } else {
                // The pointer landed but the version document has not yet: index it now rather than let the watermark,
                // advanced by the other publications in this pass, strand it until the rebase. Its EPOCH instant never
                // advances the watermark, and the rebase later records its real instant.
                racing = true;
            }
        }
        if (!rebase && !racing && !watermark.precedes(published, requestPath)) {
            // Incremental: only artifacts strictly past the (instant, path) cursor, so a same-millisecond artifact
            // split into this pass is recovered while those already chained stay skipped.
            return;
        }
        sink.accept(new IndexRecord(requestPath, size, sha256, ecosystem, coordinate, version, prerelease, published));
    }

}
