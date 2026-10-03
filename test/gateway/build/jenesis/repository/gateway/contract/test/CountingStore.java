package build.jenesis.repository.gateway.contract.test;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ForwardingArtifactStore;

/**
 * A store decorator that counts the {@code list}, {@code page} and {@code readVersioned} calls made through it, so a
 * scale test can
 * assert an inventory lookup touches only the store operations it should - a coordinate's own version folder, not the
 * whole tree. Every call delegates unchanged; only the tally is added. A test double, never a production backend.
 */
final class CountingStore extends ForwardingArtifactStore {
    private final AtomicInteger lists = new AtomicInteger();
    private final AtomicInteger pages = new AtomicInteger();
    private final AtomicInteger versionedReads = new AtomicInteger();
    private final AtomicInteger objectReads = new AtomicInteger();

    CountingStore(ArtifactStore delegate) {
        super(delegate);
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
     * Tallied apart from {@link #list}, and delegated to the backend's own page: a paged read counted as a listing
     * one would read a migrated caller as unmigrated - a false red on an {@code isEqualTo} assertion, and a false
     * green on a {@code isGreaterThan} one.
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
}
