package build.jenesis.repository.format.contract.ecosystem.test;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.PrimitiveArtifactStore;

/**
 * A pass-through {@link ArtifactStore} that records every key and prefix its caller touches.
 *
 * <p>It exists because "the coordinate seam refused a hostile name" cannot be judged from the seam's <em>return value</em>
 * alone. A layout that composes {@code debian/../../pool} and pages it against an empty store answers an honest empty
 * list and looks compliant, while against a store that has anything under that escaped prefix it would answer keys an
 * eviction then deletes - and against an object-store backend the traversal-shaped prefix is a literal key namespace of
 * its own rather than a normalising path. The same shape that reads as "nothing found" on a temporary directory is a
 * live escape on three of the four backends.
 *
 * <p>So the seam is judged by <em>where it looked</em>: every key or prefix a layout hands the store must be
 * traversal-free and must lie under one of the format's own {@link build.jenesis.repository.blobs.BlobRoots#blobRoots()
 * declared blob roots} (or the shared content-addressed {@code blobs/} namespace, which every format legitimately
 * resolves through). Nothing here fails on its own - the recording is inert - so a check reads the trace and reports
 * the escape with the key that caused it.
 *
 * <p>It can also {@linkplain #holding hold} a caller at a content-addressed write, which is how a race is forced
 * rather than hoped for: concurrent publishes held there until every one has stored its bytes have all passed
 * whatever a format checks before storing, and none of them has linked yet.
 */
final class WatchingStore implements PrimitiveArtifactStore {
    @Override
    public Object identity() {
        return delegate.identity();   // a decorator answers its delegate's subspace
    }


    private final ArtifactStore delegate;
    private final List<String> touched;
    private final Runnable beforeBlob;

    private WatchingStore(ArtifactStore delegate, List<String> touched, Runnable beforeBlob) {
        this.delegate = delegate;
        this.touched = touched;
        this.beforeBlob = beforeBlob;
    }

    /** Wrap a delegate store; the recording is shared with every {@link #scope} derived from it. */
    static WatchingStore over(ArtifactStore delegate) {
        return holding(delegate, () -> { });
    }

    /** {@link #over}, running {@code beforeBlob} on the caller's thread ahead of every {@link #writeBlob}. */
    static WatchingStore holding(ArtifactStore delegate, Runnable beforeBlob) {
        return new WatchingStore(Objects.requireNonNull(delegate, "delegate"), new CopyOnWriteArrayList<>(),
                Objects.requireNonNull(beforeBlob, "beforeBlob"));
    }

    /** Every key and prefix handed to the store since the last {@link #forget()}, in order. */
    List<String> touched() {
        return List.copyOf(touched);
    }

    /** Drop the trace, so one store can be reused across probes without their traces running together. */
    void forget() {
        touched.clear();
    }

    private String watch(String key) {
        touched.add(key);
        return key;
    }

    @Override
    public ArtifactStore scope(String tenant) {
        return new WatchingStore(delegate.scope(tenant), touched, beforeBlob);
    }

    @Override
    public boolean exists(String key) {
        return delegate.exists(watch(key));
    }

    @Override
    public void read(String key, OutputStream out) throws IOException {
        delegate.read(watch(key), out);
    }

    @Override
    public InputStream open(String key) throws IOException {
        return delegate.open(watch(key));
    }

    @Override
    public Optional<URI> presign(String key, Duration ttl) {
        return delegate.presign(watch(key), ttl);
    }

    @Override
    public void write(String key, InputStream in) throws IOException {
        delegate.write(watch(key), in);
    }

    @Override
    public String writeBlob(InputStream in) throws IOException {
        beforeBlob.run();
        return delegate.writeBlob(in);
    }

    @Override
    public long size(String key) throws IOException {
        return delegate.size(watch(key));
    }

    @Override
    public void delete(String key) throws IOException {
        delegate.delete(watch(key));
    }

    @Override
    public List<String> list(String prefix) {
        return delegate.list(watch(prefix));
    }

    @Override
    public void page(String prefix, String startAfter, int limit, Consumer<String> consumer) {
        delegate.page(watch(prefix), startAfter, limit, consumer);
    }

    @Override
    public Optional<Versioned> readVersioned(String key) throws IOException {
        return delegate.readVersioned(watch(key));
    }

    @Override
    public boolean writeVersioned(String key, byte[] content, Object expected) throws IOException {
        return delegate.writeVersioned(watch(key), content, expected);
    }

    @Override
    public Scan scan(String prefix, String startAfter, int limit, Consumer<Listed> consumer) throws IOException {
        return delegate.scan(prefix, startAfter, limit, consumer);
    }
}
