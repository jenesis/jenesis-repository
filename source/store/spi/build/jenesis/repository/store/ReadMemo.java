package build.jenesis.repository.store;

import module java.base;

/**
 * An {@link ArtifactStore} decorator that remembers, for the life of one operation, what {@link #readVersioned}
 * answered for each key - so an operation whose parts each resolve the same small object (an edge, a screen, a
 * layout and a listing observer all reading one serving pointer, a hold check and a link both reading one review
 * pointer, a read-modify-write reading a document its caller had just read) pays the store once for it. The memo
 * is created for the operation and dropped with it, which is the whole of its correctness argument: no answer it
 * remembers can reach a later request, so there is no staleness across requests to reason about, and within the
 * operation a peer's concurrent write is seen by the compare-and-set that would act on it (below), never by a
 * plain read - the same window an unmemoised operation already has between its read and its write.
 *
 * <p><b>A compare-and-set never acts on a remembered token.</b> Every write through this store - a
 * {@link #writeVersioned}, a {@link #write}, a {@link #delete}, a {@link #touch} - forgets the key before it
 * reaches the store, whether or not it lands: a versioned write that lost to a peer therefore re-reads the key on
 * its retry and sees the peer's token, and one that landed re-reads its own. A read-modify-write loop through
 * {@code Retries} over this store behaves exactly as over the bare one, at most one lost try more (the try that
 * carried the remembered token, which the store refuses), and never a lost update. The one thing a memo cannot
 * see is a write made to the same key through <em>another</em> instance over the same backing during the operation;
 * a read then answers what this instance last saw, and a compare-and-set on it fails once and re-reads, so the
 * hazard is bounded to a wasted try and confined to the operation.
 *
 * <p><b>Bounded.</b> A memo remembers at most {@value #CAPACITY} keys and passes every later read through
 * unremembered, so an operation that enumerates (a rebuild, a walk) cannot grow a request's memory with the store.
 * Installed by the hosted-publish edge ({@code ScreenedDispatch}) around one screened write, where the duplicate
 * reads were measured (2026-09-12, the per-key print of a Maven publish: the serving pointer three times, the
 * review pointer twice, the version's document twice), and deliberately not around a served read: a download has no
 * repeated key, and the proxy's coalesced follower re-tries its local read after a leader filled the store through
 * another instance - the one shape a memo would turn from a hit into a second fetch.
 *
 * <p>Memoised absence is memoised too, on purpose: the duplicates a publish pays are mostly of keys that are absent
 * (a review pointer that does not stand, a sidecar that was never uploaded), and a first link of a path reads its
 * review pointer for exactly the answer the screen read a moment earlier.
 */
public final class ReadMemo implements ArtifactStore {

    /** The most keys one memo remembers; a read past it is answered by the store and not kept. */
    static final int CAPACITY = 512;

    private final ArtifactStore delegate;
    private final Map<String, Optional<Versioned>> remembered = new HashMap<>();

    private ReadMemo(ArtifactStore delegate) {
        this.delegate = delegate;
    }

    /** A memoising view over {@code store} for one operation - the store itself when it already is one, so a
     *  nested edge does not stack a second memo that would remember what the first forgot. */
    public static ArtifactStore over(ArtifactStore store) {
        return store instanceof ReadMemo ? store : new ReadMemo(store);
    }

    /** Whether {@code store} is a memoising view. */
    public static boolean is(ArtifactStore store) {
        return store instanceof ReadMemo;
    }

    /**
     * The store beneath a memoising view, or {@code store} itself - for a holder that outlives the operation. A
     * memo is right for exactly one operation and wrong for anything that keeps a store: a shared cache created
     * during a screened publish over the request's memo would read through it for the life of the process and
     * answer, after every expiry, what the memo remembered from one request. {@link StoreCache#of} unwraps here,
     * and so must anything else that keys a long-lived store reference by identity.
     */
    public static ArtifactStore underlying(ArtifactStore store) {
        return store instanceof ReadMemo memo ? memo.delegate : store;
    }

    /** Forget everything remembered, so the next read of every key is the store's. */
    public synchronized void forget() {
        remembered.clear();
    }

    @Override
    public ArtifactStore scope(String tenant) {
        return new ReadMemo(delegate.scope(tenant));   // its own memo: keys are relative to the subspace
    }

    @Override
    public Object identity() {
        return delegate.identity();
    }

    @Override
    public synchronized Optional<Versioned> readVersioned(String key) throws IOException {
        Optional<Versioned> known = remembered.get(key);
        if (known != null) {
            return known;
        }
        Optional<Versioned> read = delegate.readVersioned(key);
        if (remembered.size() < CAPACITY) {
            remembered.put(key, read);
        }
        return read;
    }

    /** Answered off the remembered object, so a token probe after a read costs nothing more. */
    @Override
    public Optional<Object> version(String key) throws IOException {
        return readVersioned(key).map(Versioned::token);
    }

    @Override
    public synchronized boolean writeVersioned(String key, byte[] content, Object expected) throws IOException {
        remembered.remove(key);   // landed or lost, the next read of this key is the store's
        return delegate.writeVersioned(key, content, expected);
    }

    @Override
    public synchronized boolean writeVersioned(String key, InputStream content, long length, Object expected)
            throws IOException {
        remembered.remove(key);
        return delegate.writeVersioned(key, content, length, expected);
    }

    @Override
    public synchronized void write(String key, InputStream in) throws IOException {
        remembered.remove(key);
        delegate.write(key, in);
    }

    @Override
    public synchronized void delete(String key) throws IOException {
        remembered.remove(key);
        delegate.delete(key);
    }

    @Override
    public synchronized void touch(String key) throws IOException {
        remembered.remove(key);
        delegate.touch(key);
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
}
