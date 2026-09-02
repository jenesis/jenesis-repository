package build.jenesis.repository.feed.test;

import module java.base;

import build.jenesis.repository.store.ArtifactStore;

/**
 * A store decorator the snapshot tests bend one method of at a time: to prove the feed client never scopes a store
 * itself (it is handed one already scoped), and to inject the crash between the snapshot body write and the pointer
 * compare-and-set.
 */
class ForwardingStore implements ArtifactStore {

    private final ArtifactStore delegate;

    ForwardingStore(ArtifactStore delegate) {
        this.delegate = delegate;
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
    public void read(String key, OutputStream out) throws IOException {
        delegate.read(key, out);
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
    public List<String> list(String prefix) {
        return delegate.list(prefix);
    }

    @Override
    public void page(String prefix, String startAfter, int limit, Consumer<String> consumer) {
        delegate.page(prefix, startAfter, limit, consumer);
    }

    @Override
    public Optional<Versioned> readVersioned(String key) throws IOException {
        return delegate.readVersioned(key);
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
