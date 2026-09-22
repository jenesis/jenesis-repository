package build.jenesis.repository.index;

import module java.base;
import build.jenesis.repository.index.keys.PublishedIndexKeys;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Retries;
import build.jenesis.repository.store.DirtyFlag;

/**
 * The published index over one repository's scoped artifact store: the descriptor and the immutable chunk objects a
 * consumer syncs against, plus the write primitives the {@link PublishedIndexTask} commits through. Everything lives
 * under the {@code index/publish/} prefix of the same doubly-scoped store the repository already writes to - the
 * descriptor at {@code index/publish/descriptor} (compare-and-set so replicas converge) and each content-addressed
 * chunk at {@code index/publish/chunks/<sha256>} (write-once, so serving it with an immutable cache is truthful). No
 * database and no artifact blob is ever opened here.
 */
public final class PublishedIndex {

    static final String PREFIX = PublishedIndexKeys.PREFIX;
    static final String DESCRIPTOR = PublishedIndexKeys.DESCRIPTOR;
    static final String CHUNKS = PublishedIndexKeys.CHUNKS;

    /** The coalescing retraction flag - a single tiny object a sibling of the {@link #DESCRIPTOR}, under this module's
     *  own {@link PublishedIndexStorageNamespace storage namespace} root, whose PRESENCE (never its body) is the signal
     *  that a withhold transition happened and the next pass must rebase. */
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

    /**
     * The coalescing retraction flag: one tiny sibling object of the descriptor whose PRESENCE tells the next index pass
     * that a withhold transitioned (a retroactive hold landed, or a hold cleared), so the pass rebases the whole chain
     * and re-screens every path through {@code ServableNames} - dropping a now-withheld stanza and re-including a
     * cleared one. That is the only retraction immutable, content-addressed, {@code max-age} chunks consumers have
     * already cached can ever get: a descriptor/chain change, not a serve-time filter. A {@link DirtyFlag}: raised by
     * every {@code onWithheld}/{@code onWithholdCleared} writer without losing the signal, read with its token before
     * the pass's walk, lowered only against that token after the rebuilt descriptor has committed.
     */
    public DirtyFlag retraction() {
        return new DirtyFlag(store, RETRACT);
    }

    /** Store an immutable chunk content-addressed by its SHA-256 and return its id (also its checksum). */
    String writeChunk(byte[] bytes) throws IOException {
        String id = sha256(bytes);
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

    /**
     * The descriptor re-serialised as JSON for an external consumer. An empty index yields an empty chain, so the
     * document also carries an explicit {@code "built"} flag: it is {@code false} only before the first pass has
     * committed - the module installed but its sweep never run (or off), or a stored descriptor that parsed as corrupt
     * and awaits the self-healing rebase - and {@code true} once a pass has committed a generation, even one that
     * indexed an empty repository. A consumer reads {@code built:false} as "index not yet derived, retry later"
     * instead of mistaking a not-yet-built index for a repository that has published nothing (the honest
     * degrade-and-say-so; the chain still syncs correctly whether the flag is read or ignored, so it stays a
     * non-breaking signal on the same {@code 200}).
     */
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

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
