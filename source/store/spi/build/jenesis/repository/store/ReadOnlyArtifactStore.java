package build.jenesis.repository.store;

import module java.base;

/**
 * An {@link ArtifactStore} decorator that refuses every write, so a deployment configured read-only
 * ({@code jenreg.read-only=true}) serves reads normally while every mutation - a hosted publish, a
 * {@code maven-metadata.xml} compare-and-set, a delete, a content-addressed blob write - is rejected at this single
 * low-level choke point, whether it originates at an HTTP write endpoint or an internal path (a write-through proxy
 * cache, an import replay, a background sweep). Because every serving, routing, tenant and console bean resolves
 * through the one wrapped store, wrapping here refuses <em>every</em> write by construction, not just the ones an
 * endpoint guard remembers to cover.
 *
 * <p>The read methods pass straight through to the delegate; {@link #scope} re-wraps the scoped delegate so every
 * tenant / repository subspace stays read-only too (the same recursion {@link QuotaArtifactStore#scope} uses). A
 * refused write raises {@link ReadOnlyException} before the delegate is touched - no partial bytes are stored - which
 * a server maps to HTTP {@code 403}. This wrapper is applied only when the deployment opts in, so an ordinary
 * read-write deployment never pays for it.
 */
public final class ReadOnlyArtifactStore implements ArtifactStore {

    private final ArtifactStore delegate;

    public ReadOnlyArtifactStore(ArtifactStore delegate) {
        this.delegate = delegate;
    }

    @Override
    public ArtifactStore scope(String tenant) {
        return new ReadOnlyArtifactStore(delegate.scope(tenant));
    }

    @Override
    public Object identity() {
        return delegate.identity();
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
    public Optional<URI> presign(String key, Duration ttl) {
        return delegate.presign(key, ttl);
    }

    @Override
    public List<String> list(String prefix) {
        return delegate.list(prefix);
    }

    /**
     * Delegate the scan, for the same reason {@link #page} is delegated and with the same consequence for getting it
     * wrong.
     *
     * <p>The SPI's inherited {@code scan} is {@code scanByListing}, which walks {@code list} recursively into heap
     * and then refuses past ten thousand keys - a deliberate bound, because a fallback that buffered a namespace to
     * answer one page would be worse than one that says it cannot. A decorator that forgets this method does not
     * merely lose performance: it *replaces* the backend's native, genuinely bounded prefix listing with that
     * fallback, so a bounded question asked through the decorator becomes an unbounded one.
     *
     * <p>Measured rather than reasoned: this was missing from every decorator at once, and the all-in-one image
     * stopped booting over a store holding more than ten thousand keys. A tenant existence probe - already written
     * as a point read with a page limit of one - reached this fallback through the decorator and materialised
     * 10,001 keys to answer it.
     */
    @Override
    public Scan scan(String prefix, String startAfter, int limit, Consumer<Listed> consumer) throws IOException {
        return delegate.scan(prefix, startAfter, limit, consumer);
    }

    @Override
    public void page(String prefix, String startAfter, int limit, Consumer<String> consumer) {
        delegate.page(prefix, startAfter, limit, consumer);
    }

    @Override
    public Optional<Versioned> readVersioned(String key) throws IOException {
        return delegate.readVersioned(key);
    }

    /** Delegated rather than inherited: the default asks the delegate for the whole object to keep its token. */
    @Override
    public Optional<Object> version(String key) throws IOException {
        return delegate.version(key);
    }

    @Override
    public void write(String key, InputStream in) throws IOException {
        throw new ReadOnlyException();
    }

    @Override
    public String writeBlob(InputStream in) throws IOException {
        throw new ReadOnlyException();
    }

    @Override
    public void delete(String key) throws IOException {
        throw new ReadOnlyException();
    }

    @Override
    public boolean writeVersioned(String key, byte[] content, Object expected) throws IOException {
        throw new ReadOnlyException();
    }

    /** Refused like every other write, and refused <em>before</em> the stream is read: the inherited body would
     *  buffer the whole content into heap and only then throw. */
    @Override
    public boolean writeVersioned(String key, InputStream content, long length, Object expected) {
        throw new ReadOnlyException();
    }
}
