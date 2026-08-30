package build.jenesis.repository.format.testkit;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The store decorator that turns two streaming claims from assertions into proofs.
 *
 * <p><b>Sealing.</b> "A {@code HEAD} is answered from metadata" is usually asserted by checking the status and the
 * {@code Content-Length}, which a format that streamed the whole blob and threw it away would also pass. {@link #seal}
 * makes the blob physically unreadable: {@link #open} and {@link #read} of a sealed key <em>throw</em>, while
 * {@link #exists}, {@link #size} and every pointer read still work. A format whose {@code HEAD} touches the artifact
 * body therefore fails loudly with a message naming the key it opened, and one that answers from the store's metadata
 * passes because it never needed the bytes.
 *
 * <p><b>Watching.</b> "A proxy streams" is proven by the same trick from the other side: the fetched body is a
 * {@link GeneratedBody} that counts what it has handed out, and this decorator records that counter at the instant the
 * store is handed the stream ({@link #producedBeforeStore()}). A format that streams hands over an unread stream -
 * zero - while one that buffered the download first shows the whole length. In addition {@link #bufferedWriteCap}
 * trips on any artifact-sized body routed through the small-object ({@code byte[]}) paths, which is the other way an
 * artifact gets materialised.
 *
 * <p>Everything else delegates unchanged, so the format under test runs against the real backend. This is a test
 * double; a tripped tripwire throws {@link AssertionError} naming what happened, so it reads as a test failure rather
 * than as a store outage the format might legitimately handle.
 */
public final class WitnessStore implements ArtifactStore {

    /** State shared with every {@link #scope} derived from this witness, so a tripwire armed on the root still fires
     *  on a scoped view and the counters read back on one instance. */
    private static final class Witness {
        private final Set<String> sealed = ConcurrentHashMap.newKeySet();
        private final AtomicLong largestBuffered = new AtomicLong();
        private final AtomicInteger blobWrites = new AtomicInteger();
        private final AtomicLong producedBeforeStore = new AtomicLong(-1L);
        private volatile GeneratedBody watched;
        private volatile long bufferedCap = Long.MAX_VALUE;
    }

    private final ArtifactStore delegate;
    private final Witness witness;

    private WitnessStore(ArtifactStore delegate, Witness witness) {
        this.delegate = delegate;
        this.witness = witness;
    }

    /** Wrap a delegate store; with nothing armed this is a transparent pass-through. */
    public static WitnessStore over(ArtifactStore delegate) {
        return new WitnessStore(Objects.requireNonNull(delegate, "delegate"), new Witness());
    }

    // --- arming ----------------------------------------------------------------------------------------------------

    /** Make these keys' <em>content</em> unreadable: {@link #open} and {@link #read} throw, everything else still
     *  answers. The proof behind "HEAD answers from metadata". */
    public WitnessStore seal(String... keys) {
        witness.sealed.addAll(List.of(keys));
        return this;
    }

    /** Stop sealing - the keys become readable again, so a check can seal for the {@code HEAD} leg and then verify the
     *  bytes really are there. */
    public WitnessStore unseal() {
        witness.sealed.clear();
        return this;
    }

    /** Record {@code body}'s {@link GeneratedBody#produced()} at the moment a content-addressed write begins. */
    public WitnessStore watch(GeneratedBody body) {
        witness.watched = body;
        witness.producedBeforeStore.set(-1L);
        return this;
    }

    /** Trip when a body larger than {@code bytes} travels through a small-object ({@code byte[]}) write - the other
     *  shape of "the artifact was materialised". */
    public WitnessStore bufferedWriteCap(long bytes) {
        witness.bufferedCap = bytes;
        return this;
    }

    // --- what was witnessed ----------------------------------------------------------------------------------------

    /** How much of the watched body had already been read when the store was first handed it, or empty when no
     *  content-addressed write happened at all. Zero is the streaming answer. */
    public OptionalLong producedBeforeStore() {
        long produced = witness.producedBeforeStore.get();
        return produced < 0 ? OptionalLong.empty() : OptionalLong.of(produced);
    }

    /** How many content-addressed writes were made. */
    public int blobWrites() {
        return witness.blobWrites.get();
    }

    /** The largest body written through a small-object ({@code byte[]}) path. */
    public long largestBufferedWrite() {
        return witness.largestBuffered.get();
    }

    // --- the decorated surface -------------------------------------------------------------------------------------

    @Override
    public ArtifactStore scope(String tenant) {
        return new WitnessStore(delegate.scope(tenant), witness);
    }

    @Override
    public boolean exists(String key) {
        return delegate.exists(key);
    }

    @Override
    public void read(String key, OutputStream out) throws IOException {
        refuseSealed(key, "read");
        delegate.read(key, out);
    }

    @Override
    public InputStream open(String key) throws IOException {
        refuseSealed(key, "open");
        return delegate.open(key);
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
        GeneratedBody watched = witness.watched;
        if (watched != null) {
            // The tripwire: how much of the upstream body had this format already pulled into memory before it handed
            // the stream to the store? Recorded on the FIRST content-addressed write only, so a format that stores a
            // small sidecar afterwards cannot overwrite the answer.
            witness.producedBeforeStore.compareAndSet(-1L, watched.produced());
        }
        witness.blobWrites.incrementAndGet();
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
        buffered(key, content.length);
        return delegate.writeVersioned(key, content, expected);
    }

    @Override
    public List<BatchOutcome> writeBatch(List<BatchWrite> writes) throws IOException {
        for (BatchWrite write : writes) {
            buffered(write.key(), write.content().length);
        }
        return delegate.writeBatch(writes);
    }

    private void refuseSealed(String key, String operation) {
        if (witness.sealed.contains(key)) {
            throw new AssertionError("The artifact body at '" + key + "' was " + operation + "ed. This request must be "
                    + "answered from the store's metadata (exists/size/pointer reads) alone - opening the blob is what "
                    + "makes a HEAD of a multi-gigabyte artifact cost a full transfer.");
        }
    }

    private void buffered(String key, long length) {
        witness.largestBuffered.accumulateAndGet(length, Math::max);
        if (length > witness.bufferedCap) {
            throw new AssertionError("A " + length + "-byte body was written to '" + key + "' through the small-object "
                    + "byte[] path, past this check's " + witness.bufferedCap + "-byte cap. Only pointers, indexes and "
                    + "metadata may be materialised; an artifact streams through write/writeBlob (§1).");
        }
    }

    @Override
    public Scan scan(String prefix, String startAfter, int limit, Consumer<Listed> consumer) throws IOException {
        return delegate.scan(prefix, startAfter, limit, consumer);
    }
}
