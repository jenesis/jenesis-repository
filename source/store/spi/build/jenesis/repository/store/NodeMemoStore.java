package build.jenesis.repository.store;

import module java.base;

/**
 * A store with a node's two memories: the decorator a composition wraps its root store in so that
 * {@link ServableNames#located} and every other pointer probe consult one {@link MissMemory}, the listing a client
 * fetches is served from one {@link DocumentMemory}, and every write, delete and touch through the store forgets
 * the key it touched in both - write-through for the node, whatever path wrote: a publish, a release, a reconcile
 * pass regenerating a torn pointer, an unpublish, a listing observer re-deciding an entry. A scope of a remembering
 * store remembers with the same memories, keyed by the scope's own identity.
 *
 * <p>The memories are reached through the store rather than as a process-wide default so that a store nobody
 * decorated - every raw store a suite opens, a store the kernel did not build - reads exactly as before. Only a
 * composition that opted in pays the windows the two ttls bound, and it opts in at the one place it opens its
 * store.
 *
 * <p>The document memory sits on the stream faces - {@link #open}, {@link #read}, and the {@link #exists} and
 * {@link #size} a listing read probes first - and never on {@link #readVersioned}: the versioned read is what a
 * writer's compare-and-set reads its expectation from, and a remembered version would make every write on a busy
 * key lose its race against a copy of itself. A document past the memory's cap streams through untouched.
 */
public final class NodeMemoStore implements ArtifactStore {

    private final ArtifactStore delegate;
    private final MissMemory misses;
    private final DocumentMemory documents;

    private NodeMemoStore(ArtifactStore delegate, MissMemory misses, DocumentMemory documents) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.misses = Objects.requireNonNull(misses, "misses");
        this.documents = Objects.requireNonNull(documents, "documents");
    }

    /** {@code store} remembering its misses in {@code misses} and its listings in {@code documents}; a store that
     *  already remembers is itself. */
    public static ArtifactStore over(ArtifactStore store, MissMemory misses, DocumentMemory documents) {
        return store instanceof NodeMemoStore ? store : new NodeMemoStore(store, misses, documents);
    }

    /** The memory {@code store} remembers its misses in, or empty for a store that remembers none - a probe asks
     *  this before the store and records into it after; a request's read memo over a remembering store answers
     *  with the store's memory. */
    public static Optional<MissMemory> misses(ArtifactStore store) {
        return ReadMemo.underlying(store) instanceof NodeMemoStore remembering
                ? Optional.of(remembering.misses)
                : Optional.empty();
    }

    /** The memory {@code store} serves its listings from, or empty for a store that remembers none. */
    public static Optional<DocumentMemory> documents(ArtifactStore store) {
        return ReadMemo.underlying(store) instanceof NodeMemoStore remembering
                ? Optional.of(remembering.documents)
                : Optional.empty();
    }

    /**
     * {@code key} read past the listing memory, for the one caller that may not be served a remembered document:
     * the base of a compare-and-set.
     *
     * <p><b>A remembered body under a freshly read token is a lost write, not a stale read.</b> {@link #version}
     * always asks the store while {@link #open} may answer from memory, so an update that pairs them builds its
     * new document on a base up to a ttl old and then writes it under a token that is genuinely current. The
     * compare-and-set sees nothing wrong - the token really is the store's - and a peer's entries are erased for
     * good. Staleness is fine for serving a listing to a client and is the whole point of the memory; it is never
     * fine underneath a read-modify-write.
     *
     * <p><b>Evicting the entry first would not do.</b> A concurrent reader can repopulate the memory between the
     * eviction and the open, with bytes it read before the writer took its token - the same lost write, rarer and
     * harder to find. The read has to miss the memory rather than empty it, which is what this is for.
     */
    public static InputStream openUnremembered(ArtifactStore store, String key) throws IOException {
        ArtifactStore underlying = ReadMemo.underlying(store);
        return underlying instanceof NodeMemoStore remembering
                ? remembering.delegate.open(key)
                : store.open(key);
    }

    @Override
    public ArtifactStore scope(String tenant) {
        return new NodeMemoStore(delegate.scope(tenant), misses, documents);
    }

    @Override
    public Object identity() {
        return delegate.identity();
    }

    @Override
    public Optional<Versioned> readVersioned(String key) throws IOException {
        return delegate.readVersioned(key);
    }

    @Override
    public Optional<Object> version(String key) throws IOException {
        return delegate.version(key);
    }

    @Override
    public boolean writeVersioned(String key, byte[] content, Object expected) throws IOException {
        boolean written = delegate.writeVersioned(key, content, expected);
        forget(key);
        return written;
    }

    @Override
    public boolean writeVersioned(String key, InputStream content, long length, Object expected) throws IOException {
        boolean written = delegate.writeVersioned(key, content, length, expected);
        forget(key);
        return written;
    }

    @Override
    public void write(String key, InputStream in) throws IOException {
        delegate.write(key, in);
        forget(key);
    }

    @Override
    public void delete(String key) throws IOException {
        delegate.delete(key);
        forget(key);
    }

    @Override
    public void touch(String key) throws IOException {
        delegate.touch(key);
        forget(key);
    }

    private void forget(String key) {
        misses.forget(this, key);
        documents.forget(this, key);
    }

    @Override
    public String writeBlob(InputStream in) throws IOException {
        return delegate.writeBlob(in);
    }

    @Override
    public boolean exists(String key) {
        if (documents.get(this, key).isPresent()) {
            return true;
        }
        return delegate.exists(key);
    }

    @Override
    public void read(String key, OutputStream out) throws IOException {
        try (InputStream in = open(key)) {
            in.transferTo(out);
        }
    }

    /** A remembered listing as its bytes; an unremembered one read through and kept if it fits, else streamed on
     *  as it comes - a document past the cap is never buffered whole. */
    @Override
    public InputStream open(String key) throws IOException {
        if (!DocumentMemory.covers(key) || documents.ttl().isZero()) {
            return delegate.open(key);
        }
        Optional<byte[]> remembered = documents.get(this, key);
        if (remembered.isPresent()) {
            return new ByteArrayInputStream(remembered.get());
        }
        InputStream in = delegate.open(key);
        byte[] head;
        try {
            head = in.readNBytes(DocumentMemory.ENTRY_CAP + 1);
        } catch (IOException | RuntimeException e) {
            in.close();
            throw e;
        }
        if (head.length <= DocumentMemory.ENTRY_CAP) {
            in.close();
            documents.put(this, key, head);
            return new ByteArrayInputStream(head);
        }
        return new SequenceInputStream(new ByteArrayInputStream(head), in);
    }

    @Override
    public long size(String key) throws IOException {
        Optional<byte[]> remembered = documents.get(this, key);
        return remembered.isPresent() ? remembered.get().length : delegate.size(key);
    }

    @Override
    public Optional<Listed> listed(String key) throws IOException {
        return delegate.listed(key);
    }

    @Override
    public Optional<URI> presign(String key, Duration ttl) {
        return delegate.presign(key, ttl);
    }

    @Override
    public Optional<Capacity> capacity() throws IOException {
        return delegate.capacity();
    }

    @Override
    public boolean isEmpty(String prefix) throws IOException {
        return delegate.isEmpty(prefix);
    }

    @Override
    public List<String> list(String prefix) {
        return delegate.list(prefix);
    }

    @Override
    public void page(String prefix, String startAfter, int limit, Consumer<String> consumer) {
        delegate.page(prefix, startAfter, limit, consumer);
    }

    @Override
    public void pageListed(String prefix, String startAfter, int limit, Consumer<Listed> consumer) {
        delegate.pageListed(prefix, startAfter, limit, consumer);
    }

    @Override
    public Scan scan(String prefix, String startAfter, int limit, Consumer<Listed> consumer) throws IOException {
        return delegate.scan(prefix, startAfter, limit, consumer);
    }

    @Override
    public String toString() {
        return "NodeMemoStore[" + delegate + "]";
    }
}
