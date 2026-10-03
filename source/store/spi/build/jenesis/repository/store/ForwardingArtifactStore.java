package build.jenesis.repository.store;

import module java.base;

/**
 * An {@link ArtifactStore} that wraps another and forwards every method to it, the defaulted ones included, so a
 * decorator overrides only what it changes. A decorator written against the interface inherits a default wherever it
 * forgets to forward, and the defaults are the fallbacks a backend with nothing better would take:
 * <ul>
 *   <li>{@link #scan} walks {@code list} into heap and refuses past ten thousand keys, so a bounded question asked
 *       through a decorator - a tenant existence probe with a page limit of one - becomes an unbounded one that fails
 *       over a large store;</li>
 *   <li>{@link #page} and {@link #pageListed} derive a page from names alone, dropping the sizes and ages the
 *       backend's listing carried, so a descent stats every leaf;</li>
 *   <li>{@link #version} reads the whole object to keep its token, and the streamed {@link #writeVersioned} buffers
 *       it, turning a streaming backend into a buffering one;</li>
 *   <li>a ranged {@link #open} reads from byte 0, and {@link #presign} answers empty.</li>
 * </ul>
 * Here the defaults are the wrapped store's.
 *
 * <p>{@link #scope} is the one method every decorator overrides, since a scope of a decorated store is decorated
 * too; it forwards here only so that a decorator's own override is what the compiler asks for.
 */
public abstract class ForwardingArtifactStore implements ArtifactStore {

    /** The store this one wraps. */
    protected final ArtifactStore delegate;

    protected ForwardingArtifactStore(ArtifactStore delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public abstract ArtifactStore scope(String tenant);

    @Override
    public Object identity() {
        return delegate.identity();
    }

    @Override
    public StoreBindings bindings() {
        return delegate.bindings();
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
    public InputStream open(String key, long offset) throws IOException {
        return delegate.open(key, offset);
    }

    @Override
    public Optional<URI> presign(String key, Duration ttl) {
        return delegate.presign(key, ttl);
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
    public Optional<Listed> listed(String key) throws IOException {
        return delegate.listed(key);
    }

    @Override
    public void delete(String key) throws IOException {
        delegate.delete(key);
    }

    @Override
    public Optional<Capacity> capacity() throws IOException {
        return delegate.capacity();
    }

    @Override
    public void touch(String key) throws IOException {
        delegate.touch(key);
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
    public Optional<Versioned> readVersioned(String key) throws IOException {
        return delegate.readVersioned(key);
    }

    @Override
    public Optional<Object> version(String key) throws IOException {
        return delegate.version(key);
    }

    @Override
    public boolean writeVersioned(String key, byte[] content, Object expected) throws IOException {
        return delegate.writeVersioned(key, content, expected);
    }

    @Override
    public boolean writeVersioned(String key, InputStream content, long length, Object expected) throws IOException {
        return delegate.writeVersioned(key, content, length, expected);
    }
}
