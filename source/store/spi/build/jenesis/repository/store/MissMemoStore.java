package build.jenesis.repository.store;

import module java.base;

/**
 * A store whose serves remember their misses: the decorator a composition wraps its root store in so
 * {@link ServableNames#located} and every other pointer probe consult one {@link MissMemory}, and so every write
 * and delete through the store forgets the key it touched - write-through for the node, whatever path wrote, a
 * publish, a release, a reconcile pass regenerating a torn pointer or an unpublish. A scope of a remembering store
 * remembers with the same memory, keyed by the scope's own identity.
 *
 * <p>The memory is reached through the store rather than as a process-wide default so that a store nobody
 * decorated - every raw store a suite opens, a store the kernel did not build - probes exactly as before. Only a
 * composition that opted in pays the window the memory's ttl bounds, and it opts in at the one place it opens
 * its store.
 */
public final class MissMemoStore implements ArtifactStore {

    private final ArtifactStore delegate;
    private final MissMemory memory;

    private MissMemoStore(ArtifactStore delegate, MissMemory memory) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.memory = Objects.requireNonNull(memory, "memory");
    }

    /** {@code store} remembering its misses in {@code memory}; a store that already does is itself. */
    public static ArtifactStore over(ArtifactStore store, MissMemory memory) {
        return store instanceof MissMemoStore ? store : new MissMemoStore(store, memory);
    }

    /** The memory {@code store} remembers its misses in, or empty for a store that remembers none - a probe asks
     *  this before the store and records into it after; a request's read memo over a remembering store answers
     *  with the store's memory. */
    public static Optional<MissMemory> memory(ArtifactStore store) {
        return ReadMemo.underlying(store) instanceof MissMemoStore remembering
                ? Optional.of(remembering.memory)
                : Optional.empty();
    }

    @Override
    public ArtifactStore scope(String tenant) {
        return new MissMemoStore(delegate.scope(tenant), memory);
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
        memory.forget(this, key);
        return written;
    }

    @Override
    public boolean writeVersioned(String key, InputStream content, long length, Object expected) throws IOException {
        boolean written = delegate.writeVersioned(key, content, length, expected);
        memory.forget(this, key);
        return written;
    }

    @Override
    public void write(String key, InputStream in) throws IOException {
        delegate.write(key, in);
        memory.forget(this, key);
    }

    @Override
    public void delete(String key) throws IOException {
        delegate.delete(key);
        memory.forget(this, key);
    }

    @Override
    public void touch(String key) throws IOException {
        delegate.touch(key);
        memory.forget(this, key);
    }

    @Override
    public String writeBlob(InputStream in) throws IOException {
        return delegate.writeBlob(in);
    }

    @Override
    public boolean exists(String key) {
        return delegate.exists(key);
    }

    @Override
    public void read(String key, OutputStream out) throws IOException {
        delegate.read(key, out);
    }

    @Override
    public InputStream open(String key) throws IOException {
        return delegate.open(key);
    }

    @Override
    public long size(String key) throws IOException {
        return delegate.size(key);
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
        return "MissMemoStore[" + delegate + "]";
    }
}
