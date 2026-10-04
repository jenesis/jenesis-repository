package build.jenesis.repository.index;

import module java.base;
import build.jenesis.repository.store.Checksums;
import build.jenesis.repository.index.keys.PublishedIndexKeys;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Retries;
import build.jenesis.repository.store.DirtyFlag;

/**
 * The published index over one repository's scoped store: the descriptor and immutable chunks a consumer syncs against,
 * and the write primitives {@link PublishedIndexTask} commits through. The descriptor sits at
 * {@code index/publish/descriptor} (compare-and-set, so replicas converge) and each chunk at
 * {@code index/publish/chunks/<sha256>} (write-once, so serving it as immutable is truthful). No artifact blob is
 * opened here.
 */
public final class PublishedIndex {

    static final String PREFIX = PublishedIndexKeys.PREFIX;
    static final String DESCRIPTOR = PublishedIndexKeys.DESCRIPTOR;
    static final String CHUNKS = PublishedIndexKeys.CHUNKS;

    /** The coalescing retraction flag beside the {@link #DESCRIPTOR}, under this module's
     *  {@link PublishedIndexStorageNamespace storage namespace}; its presence, never its body, tells the next pass to
     *  rebase. */
    static final String RETRACT = PublishedIndexKeys.RETRACT;

    private final ArtifactStore store;

    public PublishedIndex(ArtifactStore store) {
        this.store = store;
    }

    /** The current descriptor, or empty when no index has been published for this repository yet. */
    public Optional<IndexDescriptor> descriptor() throws IOException {
        return store.readVersioned(DESCRIPTOR).map(versioned -> IndexDescriptor.parse(versioned.content()));
    }

    /** The descriptor with its version token, for a compare-and-set update by the pass. */
    Optional<ArtifactStore.Versioned> descriptorVersioned() throws IOException {
        return store.readVersioned(DESCRIPTOR);
    }

    /** Commit a descriptor only if the stored version still matches {@code expected}; false on a concurrent update. */
    boolean putDescriptor(IndexDescriptor descriptor, Object expected) throws IOException {
        return store.writeVersioned(DESCRIPTOR, descriptor.serialize(), expected);
    }

    /** The coalescing retraction flag, raised on every withhold transition: its presence makes the next pass rebase the
     *  whole chain and re-screen every path through {@code ServableNames}. That is the only retraction cached immutable
     *  chunks can get - a chain change, not a serve-time filter. A {@link DirtyFlag}: raised without losing a signal,
     *  read with its token before the pass's walk, lowered only against that token after the rebuilt descriptor
     *  commits. */
    public DirtyFlag retraction() {
        return new DirtyFlag(store, RETRACT);
    }

    /** Store an immutable chunk content-addressed by its SHA-256 and return its id. */
    String writeChunk(byte[] bytes) throws IOException {
        String id = Checksums.sha256(bytes);
        store.write(CHUNKS + "/" + id, new ByteArrayInputStream(bytes));
        return id;
    }

    /** Delete a superseded chunk once its grace period has elapsed. */
    void deleteChunk(String id) throws IOException {
        store.delete(CHUNKS + "/" + id);
    }

    /** Whether the chunk object is present. */
    public boolean chunkExists(String id) {
        return store.exists(CHUNKS + "/" + id);
    }

    /** The stored byte length of a chunk, or {@code -1} if absent. */
    public long chunkSize(String id) throws IOException {
        return store.size(CHUNKS + "/" + id);
    }

    /** Stream a chunk's immutable bytes to {@code out} without buffering it whole. */
    public void streamChunk(String id, OutputStream out) throws IOException {
        store.read(CHUNKS + "/" + id, out);
    }

    /** The descriptor as JSON for an external consumer. It carries an explicit {@code "built"} flag, {@code false} only
     *  before a pass has committed a generation (the pass never run or off, or a stored descriptor that parsed as
     *  corrupt and awaits its rebase), so a consumer reads "not yet derived, retry later" rather than "nothing
     *  published". The chain syncs correctly whether or not the flag is read. */
    public byte[] descriptorJson() throws IOException {
        Optional<IndexDescriptor> stored = descriptor();
        IndexDescriptor descriptor = stored.orElse(IndexDescriptor.empty());
        boolean built = stored.isPresent() && descriptor.generation() >= 1;
        StringBuilder json = new StringBuilder(256);
        json.append("{\"built\":").append(built);
        json.append(",\"generation\":").append(descriptor.generation());
        json.append(",\"watermark\":\"").append(descriptor.watermark().instant()).append('"');
        json.append(",\"rebased\":\"").append(descriptor.rebased()).append('"');
        json.append(",\"chunks\":[");
        for (int index = 0; index < descriptor.chain().size(); index++) {
            IndexDescriptor.Chunk chunk = descriptor.chain().get(index);
            if (index > 0) {
                json.append(',');
            }
            json.append("{\"id\":\"").append(chunk.id()).append('"')
                    .append(",\"uncompressedSize\":").append(chunk.uncompressedSize())
                    .append(",\"compressedSize\":").append(chunk.compressedSize())
                    .append(",\"records\":").append(chunk.records())
                    .append(",\"minPublished\":\"").append(chunk.minPublished()).append('"')
                    .append(",\"maxPublished\":\"").append(chunk.maxPublished()).append("\"}");
        }
        json.append("]}");
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }

}
