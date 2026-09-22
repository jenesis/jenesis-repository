package build.jenesis.repository.gateway.contract.test;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;

/**
 * A store decorator that counts the {@code list}, {@code page} and {@code readVersioned} calls made through it, so a
 * scale test can
 * assert an inventory lookup touches only the store operations it should - a coordinate's own version folder, not the
 * whole tree. Every call delegates unchanged; only the tally is added. A test double, never a production backend.
 */
final class CountingStore implements ArtifactStore {
    @Override
    public Object identity() {
        return delegate.identity();   // a decorator answers its delegate's subspace
    }


    private final ArtifactStore delegate;
    private final AtomicInteger lists = new AtomicInteger();
    private final AtomicInteger pages = new AtomicInteger();
    private final AtomicInteger versionedReads = new AtomicInteger();
    private final AtomicInteger objectReads = new AtomicInteger();

    CountingStore(ArtifactStore delegate) {
        this.delegate = delegate;
    }

    int lists() {
        return lists.get();
    }

    int versionedReads() {
        return versionedReads.get();
    }

    /** The number of streamed object bodies read through {@code read(key, out)} - the tally a paging assertion bounds. */
    int objectReads() {
        return objectReads.get();
    }

    /** The total addressed reads - list enumerations plus small-object reads - the tally a scale assertion bounds. */
    int reads() {
        return lists.get() + versionedReads.get();
    }

    @Override
    public List<String> list(String prefix) {
        lists.incrementAndGet();
        return delegate.list(prefix);
    }

    /**
     * Delegated explicitly, and that is the whole point of it being here.
     *
     * <p>Without this override the inherited {@code ArtifactStore.page} is {@code pageByListing}, which answers a
     * page by calling {@link #list} - so every paged read through this double was tallied as a listing one and the
     * counter measured the fallback instead of the backend. A migrated caller then reads as unmigrated: a false
     * red on an {@code isEqualTo} assertion, and a false green on a {@code isGreaterThan} one. The inherited
     * fallback exists so an un-migrated <em>backend</em> fails visibly; in a double it does the opposite.
     */
    @Override
    public void page(String prefix, String startAfter, int limit, Consumer<String> consumer) {
        pages.incrementAndGet();
        delegate.page(prefix, startAfter, limit, consumer);
    }

    @Override
    public void read(String key, OutputStream out) throws IOException {
        objectReads.incrementAndGet();
        delegate.read(key, out);
    }

    @Override
    public Optional<Versioned> readVersioned(String key) throws IOException {
        versionedReads.incrementAndGet();
        return delegate.readVersioned(key);
    }

    @Override
    public ArtifactStore scope(String tenant) {
        return delegate.scope(tenant);
    }

    @Override
    public boolean exists(String key) {
        return delegate.exists(key);
    }

    @Override
    public InputStream open(String key) throws IOException {
        return delegate.open(key);
    }

    @Override
    public void write(String key, InputStream in) throws IOException {
        delegate.write(key, in);
    }

    @Override
    public String writeBlob(InputStream in) throws IOException {
        return delegate.writeBlob(in);
    }

    @Override
    public long size(String key) throws IOException {
        return delegate.size(key);
    }

    @Override
    public void delete(String key) throws IOException {
        delegate.delete(key);
    }

    @Override
    public boolean writeVersioned(String key, byte[] content, Object expected) throws IOException {
        return delegate.writeVersioned(key, content, expected);
    }

    @Override
    public Scan scan(String prefix, String startAfter, int limit, Consumer<Listed> consumer) throws IOException {
        return delegate.scan(prefix, startAfter, limit, consumer);
    }
}
