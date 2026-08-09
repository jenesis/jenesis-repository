package build.jenesis.repository.store;

import module java.base;

/**
 * The storage backend for the repository server: every artifact byte, generated POM, checksum and
 * metadata object the server persists or enumerates goes through this interface, so the on-disk
 * filesystem can be swapped for an object store (S3 / Azure Blob / GCS) without touching the request,
 * layout, bridge or console code. The default implementation is {@code FilesystemArtifactStore}.
 *
 * Large blobs (jars) stream through {@link #read} / {@link #write}. Small objects (POMs and
 * {@code maven-metadata.xml}) use {@link #readVersioned} / {@link #writeVersioned}: a compare-and-set
 * keyed on an opaque token, so concurrent metadata edits never lose one another. On a filesystem the
 * token is the last-modified stamp; an object-store backend maps it to the blob's ETag or generation.
 *
 * <h2>Contract</h2>
 * Every clause below is executable: {@code StoreContract} in the store testkit states it once and each backend runs
 * it through a fixture, so a clause is proven on the filesystem and on containerised S3 / GCS / Azure alike rather
 * than being re-interpreted per backend.
 * <ol>
 * <li><b>Thread-safety.</b> A store is a shared singleton the server calls concurrently from every request thread;
 *     every method must be safe under concurrent use, including two writers racing on one key. {@link #scope} may be
 *     called concurrently and returns an independent view that is itself shared and thread-safe.</li>
 * <li><b>Idempotency / replay.</b> Every mutation is replay-safe, because a crash-resume re-runs it: {@link #write}
 *     of the same bytes to the same key converges on those bytes, {@link #writeBlob} of an identical body returns the
 *     same hash and stores it once, and {@link #delete} of an absent key is a no-op rather than a failure. Only
 *     {@link #writeVersioned} is deliberately <em>not</em> idempotent across a version change: a replayed write
 *     carrying a superseded token is refused, which is what makes it safe to retry.</li>
 * <li><b>Absence sentinel.</b> Absence is a value, never {@code null} and never an exception:
 *     {@link #exists} is {@code false}, {@link #size} is {@code -1}, {@link #list} and {@link #page} yield nothing,
 *     and {@link #readVersioned} is {@link Optional#empty()} - including when a concurrent delete vanishes the object
 *     mid-read. Reading the <em>body</em> of an absent key is the one exception: {@link #read} and {@link #open}
 *     throw {@link IOException} rather than serving an empty stream, because a caller streaming a missing artifact
 *     must not silently transfer zero bytes as if they were the artifact.</li>
 * <li><b>Streaming (&sect;1).</b> {@link #write}, {@link #writeBlob}, {@link #read} and {@link #open} are the
 *     artifact-sized paths and must not materialise a body: a backend that needs a length or a hash before it can
 *     upload spools to disk, never to the heap, so the JVM stays bounded under a multi-gigabyte publish.
 *     {@link #readVersioned} / {@link #writeVersioned} / {@link #writeBatch} are the small-object paths and do
 *     materialise, so only pointers, indexes and metadata may travel through them. A {@link RangedSink} passed to
 *     {@link #read} is a request to transfer only that window; a backend that cannot seek still writes the whole
 *     blob through and the sink forwards only the window, so the answer is correct either way.</li>
 * <li><b>Tenant scoping (&sect;6).</b> {@link #scope} is the only tenancy seam: the returned view confines every key
 *     to that subspace, a sibling scope can neither read nor enumerate across it, and scopes nest. The segment is
 *     screened through {@link #segment}, and a key through {@link #key}, so neither a traversal-shaped scope name nor
 *     a traversal-shaped key can address storage outside the subspace it was handed.</li>
 * <li><b>Error visibility (&sect;9).</b> Nothing on a correctness-bearing path is swallowed. Only a genuine
 *     object-level miss reads as absent: a throttle, an authorization failure or a missing bucket/container must
 *     surface, never degrade {@link #exists} to {@code false}, {@link #size} to {@code -1} or {@link #writeVersioned}
 *     to a {@code false} the caller would retry into exhaustion. A write that fails commits nothing at the key: an
 *     aborted upload leaves it absent, never a truncated body a later content-addressed probe would accept as
 *     already stored.</li>
 * <li><b>Lifecycle / ownership.</b> The composition builds one store through {@link ArtifactStoreProvider} and keeps
 *     it for the life of the process; a store may own the client, pool or threads its backend needs. A
 *     {@link #scope}d view is a cheap derived value, not a resource: callers create them freely and close nothing.
 *     A stream handed out by {@link #open} is the caller's to close.</li>
 * <li><b>Ordering / concurrency.</b> {@link #page} is the ordering primitive: names stream in lexicographic order of
 *     the child name, strictly after {@code startAfter}, so repeated pages traverse an arbitrarily large child set
 *     exactly once. A container and a same-named leaf are one child, and the ordering is by child name - never by the
 *     backend's raw key order, in which a grouped prefix sorts after a sibling whose name extends it past a character
 *     below {@code '/'}. {@link #list} enumerates the same children as a full paging. {@link #writeBatch} answers
 *     one outcome per write in input order, may execute disjoint keys concurrently, and never reorders or overlaps
 *     two writes to the same key.</li>
 * <li><b>Bounded work / cancellation.</b> {@link #page}'s {@code limit} bounds both what is emitted and what the
 *     backend buffers, so paging a millions-entry namespace costs O(limit) memory; a non-positive limit emits
 *     nothing. {@link #key} caps a new key at {@link #MAX_SEGMENTS} segments and {@link #MAX_KEY_BYTES} bytes, so no
 *     descent over stored keys can be driven arbitrarily deep. {@link #list} is deliberately unbounded and is for
 *     small child sets only - anything attacker-shaped pages.</li>
 * <li><b>Durability / delivery.</b> The commit point of {@link #write} and {@link #writeBlob} is the moment the key
 *     becomes readable, and it is atomic: a reader observes the whole previous object or the whole new one, never a
 *     partial write. {@link #writeVersioned} commits only while the stored version still matches the token it was
 *     given, which is what lets many nodes edit one pointer with no lock or database; the token is <em>opaque</em> -
 *     a caller may only hand back a value the store gave it - and changes on every successful write, so a superseded
 *     token can never pass. {@link #writeBatch} is explicitly <b>not</b> a transaction: there is no atomicity across
 *     keys and no rollback, each entry commits, conflicts or fails on its own, and a caller must read the per-entry
 *     outcomes rather than assume the batch succeeded or failed as a unit.</li>
 * </ol>
 */
public interface ArtifactStore {

    /** A view confined to one tenant's subspace (a subdirectory on a filesystem, a key prefix on an object store). */
    ArtifactStore scope(String tenant);

    /**
     * Validate {@code segment} as a single traversal-free scope name and return it - defence in depth for
     * {@link #scope(String)}. Every routing edge already rejects a non-{@code [A-Za-z0-9_-]} tenant / repository name
     * before it scopes the store, so this is a backstop: it stops a store backend from silently escaping its subspace
     * on a {@code scope("../x")} or misplacing one on a {@code scope("a/b")} should a future caller forget to validate.
     * A segment carrying a path separator ({@code /} or {@code \}) or resolving to the current / parent directory
     * ({@code .}, {@code ..}, empty, or {@code null}) is rejected; a plain hidden-subspace name (the {@code .tests} /
     * {@code .scans} internal spaces) is allowed. Each backend's {@code scope} runs the argument through this.
     */
    static String segment(String segment) {
        if (segment == null || segment.isEmpty() || segment.equals(".") || segment.equals("..")
                || segment.indexOf('/') >= 0 || segment.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("Not a traversal-free scope segment: " + segment);
        }
        return segment;
    }

    /**
     * The maximum number of {@code '/'}-separated segments a stored key may carry, enforced by {@link #key(String)}
     * in every backend's write path. 64 sits far above any legitimate ecosystem - Maven's deepest real groupIds run
     * under 20 segments, an OCI repository name a handful more - and far below the few-thousand call frames a naive
     * recursive descent of a key path would push before it overflows a default thread stack: a ceiling no real
     * publish ever reaches, yet one that makes an attacker-planted pathological key depth unrepresentable at the
     * source, so any descent anywhere - even a hand-rolled recursive one - is bounded by construction.
     */
    int MAX_SEGMENTS = 64;

    /**
     * The maximum length in UTF-8 bytes of a stored key, enforced by {@link #key(String)} in every backend's write
     * path. 4096 comfortably exceeds any real coordinate-derived key yet bounds the per-key work (path resolution, an
     * object-store round trip, a directory chain) a single attacker-controlled write can force.
     */
    int MAX_KEY_BYTES = 4096;

    /**
     * Validate {@code key} as a storable object key of bounded, traversal-free shape and return it - the write-path
     * companion of the {@link #segment(String)} scope screen, applied at the same choke point each backend already
     * screens a key on before a write lands. A key is rejected with an {@link IllegalArgumentException} when it
     * exceeds {@link #MAX_SEGMENTS} {@code '/'}-separated segments or {@link #MAX_KEY_BYTES} UTF-8 bytes, so an
     * attacker-controlled coordinate can never plant a key deep or long enough to drive an unbounded recursive
     * descent, or an outsized per-key cost, anywhere downstream.
     *
     * <p>A key carrying a {@code .} or {@code ..} segment is rejected here too, for the same reason
     * {@link #segment(String)} refuses one: on a filesystem such a key walks out of the subspace it was addressed in,
     * and on an object store it lands a literal key that no other backend can then address - so the same publish
     * would be refused on one backend and silently accepted on another (&sect;13). Screening it at the one write
     * choke point every backend already calls keeps the four backends interchangeable, which is what makes a store
     * migration a configuration change. Enforced on new writes only: any key already stored predates the screen and
     * stays readable, deletable and walkable (the iterative store walk bounds traversal regardless of a legacy key's
     * depth), so a store that predates this cannot become unreadable.
     */
    static String key(String key) {
        if (key == null) {
            throw new IllegalArgumentException("Not a storable key: null");
        }
        int bytes = key.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_KEY_BYTES) {
            throw new IllegalArgumentException(
                    "Key exceeds the " + MAX_KEY_BYTES + "-byte cap (" + bytes + " bytes): " + key);
        }
        if (!traversalFree(key)) {
            throw new IllegalArgumentException("Not a traversal-free storable key: " + key);
        }
        int segments = 1;
        for (int index = 0; index < key.length(); index++) {
            if (key.charAt(index) == '/') {
                segments++;
            }
        }
        if (segments > MAX_SEGMENTS) {
            throw new IllegalArgumentException(
                    "Key exceeds the " + MAX_SEGMENTS + "-segment cap (" + segments + " segments): " + key);
        }
        return key;
    }

    /**
     * Whether a {@code '/'}-separated path carries no {@code .} or {@code ..} segment - the traversal half of the
     * {@link #key(String)} screen, exposed as a predicate so a caller that must <em>decline</em> rather than throw
     * asks the same question at the same choke point instead of re-deriving the rule.
     *
     * <p>{@link #key(String)} is the write-path screen and throws, because a caller that has already decided to store
     * something at a traversal-shaped key has a bug. A {@code RepositoryFormat} screening an
     * <em>incoming request path</em> is in the opposite position: the path is client-supplied, the honest
     * answer is "this names nothing here", and a thrown {@link IllegalArgumentException} out of a request handler is an
     * unmapped {@code 500} where a {@code 404} is the truth. Both seams must nevertheless agree on exactly which
     * shapes are refused - a format that screened a wider or narrower set than the store would either reject a
     * legitimate publish or hand the store a key it refuses at a point the client cannot understand - so this
     * predicate is the single definition and {@link #key(String)} is stated in terms of it.
     *
     * <p>Only {@code .} and {@code ..} segments are refused. An empty segment is not a traversal (a trailing slash on
     * a directory listing request, a doubled separator) and a percent-encoded {@code %2e%2e} is not one either: it is
     * a literal name until something decodes it, and nothing below this line ever does.
     */
    static boolean traversalFree(String path) {
        if (path == null) {
            return false;
        }
        for (int index = 0, start = 0; index <= path.length(); index++) {
            if (index == path.length() || path.charAt(index) == '/') {
                int length = index - start;
                if (length == 1 && path.charAt(start) == '.' || length == 2 && path.startsWith("..", start)) {
                    return false;
                }
                start = index + 1;
            }
        }
        return true;
    }

    /** Whether a blob exists at this object key. */
    boolean exists(String key);

    /** Stream the blob to {@code out}. */
    void read(String key, OutputStream out) throws IOException;

    /**
     * Open the blob at this key for reading, so a caller that must pull the bytes through an existing stream
     * consumer - the SHA-256 concatenation that finalizes a chunked upload, or the jar inspection that reads a
     * just-stored artifact back rather than buffering it from the network - streams it without holding it whole in
     * memory. The symmetric counterpart of {@link #write(String, InputStream)}. The key must exist; the caller
     * closes the returned stream.
     */
    InputStream open(String key) throws IOException;

    /**
     * A short-lived URL a client can fetch this key from directly (a presigned object-store GET), or empty when
     * this backend cannot mint one (the filesystem default) - the caller then streams as today. The object-store
     * backends sign a {@code GET} for the fully-qualified object (the scope's {@link #scope key prefix} plus
     * {@code key}) valid for {@code ttl}, so a serve plane can 307 the client at the bucket instead of moving the
     * bytes through the JVM; every other store (and every decorator that does not delegate) answers empty, and the
     * caller falls back to {@link #read}. A URL is a bearer capability for its lifetime, so {@code ttl} should be
     * short and the caller must have already authorized the read before minting one.
     */
    default Optional<URI> presign(String key, Duration ttl) {
        return Optional.empty();
    }

    /** Atomically store the blob from {@code in}, so a reader never observes a partial write. */
    void write(String key, InputStream in) throws IOException;

    /**
     * Store a blob content-addressed by its SHA-256, computed as {@code in} streams through, and return the hex
     * digest. The content lands at {@code blobs/<hash>} - the same content-addressed key a keyed {@link #write}
     * would use - so an identical blob already present is left untouched. This is the primitive a large artifact
     * streams through on the way from the network to storage: the store never has the hash (and so the key) before
     * it has read the bytes, and there is no move once written, so the backend digests while it writes rather than
     * buffering the whole body in memory to hash it first.
     */
    String writeBlob(InputStream in) throws IOException;

    /** The stored byte length of the blob at this key, or {@code -1} if nothing is stored there. */
    long size(String key) throws IOException;

    /** Delete the blob, tidying any now-empty container it leaves behind. */
    void delete(String key) throws IOException;

    /** The immediate child names under a key prefix (for the console browse and metadata maintenance). */
    List<String> list(String prefix);

    /**
     * Stream up to {@code limit} immediate child names under {@code prefix} to {@code consumer}, in lexicographic
     * order, starting strictly after {@code startAfter} (the empty string starts from the beginning). This is the
     * ordered-paging primitive the shared artifact walk enumerates through: repeated pages, each resuming after the
     * last name of the one before, traverse an arbitrarily large child set - the flat, millions-entry {@code blobs/}
     * namespace - without ever materialising it as one {@code List} the way {@link #list} does. The default sorts
     * {@link #list} and filters, which is correct on every backend; a backend overrides it to page natively (an
     * object store's start-after pagination, the filesystem's bounded directory scan) so a resume deep inside a
     * huge child set is a seek, not a re-list.
     */
    default void page(String prefix, String startAfter, int limit, Consumer<String> consumer) {
        if (limit <= 0) {
            return;
        }
        List<String> children = new ArrayList<>(list(prefix));
        Collections.sort(children);
        int emitted = 0;
        for (String child : children) {
            if (child.compareTo(startAfter) <= 0) {
                continue;
            }
            if (emitted++ == limit) {
                break;
            }
            consumer.accept(child);
        }
    }

    /** A small object plus an opaque version token, for compare-and-set writes. */
    record Versioned(byte[] content, Object token) {
    }

    /** Read a small object with its version token; empty if absent. */
    Optional<Versioned> readVersioned(String key) throws IOException;

    /**
     * Write a small object only if the stored version still matches {@code expected} ({@code null} requires
     * the object be absent). Returns {@code false} on a mismatch, so the caller can re-read and retry; this
     * is how {@code maven-metadata.xml} stays consistent under concurrent deploys without a lock or database.
     */
    boolean writeVersioned(String key, byte[] content, Object expected) throws IOException;

    /** One compare-and-set write in a {@link #writeBatch} batch: exactly the arguments of
     *  {@link #writeVersioned(String, byte[], Object)} - store {@code content} at {@code key} only while the stored
     *  version still matches {@code expected} ({@code null} requires the key be absent). */
    record BatchWrite(String key, byte[] content, Object expected) {
    }

    /**
     * The outcome of one {@link BatchWrite}, reported by {@link #writeBatch} in input order, so a caller sees exactly
     * which keys landed and which did not:
     * <ul>
     *   <li>{@code COMMITTED} - the conditional write landed;</li>
     *   <li>{@code CONFLICTED} - the compare-and-set lost (the stored version no longer matched {@code expected}),
     *       exactly a {@code false} from {@link #writeVersioned}: the caller re-reads and retries that key;</li>
     *   <li>{@code FAILED} - the write threw, and {@link #failure()} carries the {@link IOException}.</li>
     * </ul>
     * {@code failure} is non-null only for {@code FAILED}.
     */
    record BatchOutcome(String key, Status status, IOException failure) {

        public enum Status {
            COMMITTED, CONFLICTED, FAILED
        }

        public static BatchOutcome committed(String key) {
            return new BatchOutcome(key, Status.COMMITTED, null);
        }

        public static BatchOutcome conflicted(String key) {
            return new BatchOutcome(key, Status.CONFLICTED, null);
        }

        public static BatchOutcome failed(String key, IOException failure) {
            return new BatchOutcome(key, Status.FAILED, failure);
        }
    }

    /** The bounded fan-out an object-store {@link #writeBatch} override issues its conditional writes with: a small
     *  fixed concurrency (deliberately not unbounded - a large batch must never open a connection per key), enough to
     *  turn a k-write commit from k sequential round-trips into roughly one. The filesystem backend keeps the
     *  sequential default. */
    int BATCH_FANOUT = 8;

    /**
     * Apply each {@link BatchWrite} with {@link #writeVersioned} semantics and return one {@link BatchOutcome} per
     * write, <em>in input order</em>, so a caller sees exactly which keys committed, which lost their compare-and-set
     * and which failed.
     *
     * <p><strong>Best-effort, per-key compare-and-set, explicitly NOT a transaction.</strong> There is no atomicity
     * across keys and no rollback: S3, GCS and Azure have no multi-object transaction (conditional writes are per-key
     * only), and the repository's reconcile-heals-partials model tolerates a partial batch by design. A crash or a
     * mid-batch failure leaves the keys already written committed; every key is still individually atomic and
     * compare-and-set-checked exactly as {@link #writeVersioned} - a conflicting token fails that one key
     * ({@code CONFLICTED}) or a thrown {@link IOException} fails it ({@code FAILED}) while the rest still proceed. A
     * backend may execute disjoint keys concurrently but never reorders or overlaps two writes to the same key.
     *
     * <p>The default applies the writes sequentially through {@link #writeVersioned} - correct on every backend, and
     * what the filesystem store uses; the object-store backends override it to issue the conditional writes
     * {@linkplain #BATCH_FANOUT bounded-parallel} (see {@link #writeBatchParallel}).
     */
    default List<BatchOutcome> writeBatch(List<BatchWrite> writes) throws IOException {
        List<BatchOutcome> outcomes = new ArrayList<>(writes.size());
        for (BatchWrite write : writes) {
            outcomes.add(writeOne(this, write));
        }
        return outcomes;
    }

    /**
     * Apply one {@link BatchWrite} through {@code store}'s {@link #writeVersioned} and classify the result into a
     * {@link BatchOutcome} exactly as {@link #writeBatch} documents. This is the single place every backend - the
     * default sequential loop and the object-store parallel overrides alike - turns a conditional write into an
     * outcome, so committed-vs-conflicted-vs-failed is classified identically everywhere. Never throws: a thrown
     * {@link IOException} becomes a {@code FAILED} outcome rather than escaping and aborting the rest of the batch.
     */
    static BatchOutcome writeOne(ArtifactStore store, BatchWrite write) {
        try {
            return store.writeVersioned(write.key(), write.content(), write.expected())
                    ? BatchOutcome.committed(write.key())
                    : BatchOutcome.conflicted(write.key());
        } catch (IOException failure) {
            return BatchOutcome.failed(write.key(), failure);
        }
    }

    /**
     * The shared bounded-parallel implementation the object-store {@link #writeBatch} overrides delegate to: issue
     * each write through {@link #writeOne} on a pool of at most {@link #BATCH_FANOUT} threads, collect the outcomes
     * in input order, and never reorder or overlap two writes to the same key (writes sharing a key run sequentially
     * in input order on one task; disjoint keys fan out). Best-effort, not a transaction - see {@link #writeBatch}.
     * A single write skips the pool entirely.
     */
    static List<BatchOutcome> writeBatchParallel(ArtifactStore store, List<BatchWrite> writes) throws IOException {
        int size = writes.size();
        if (size <= 1) {
            return size == 0 ? List.of() : List.of(writeOne(store, writes.get(0)));
        }
        BatchOutcome[] results = new BatchOutcome[size];
        // Group the write indices by key in input order: two writes to one key share a task and run in order (the
        // no-reorder-per-key rule above), while disjoint keys fan out across the pool - a batch of one key is never
        // parallelised into a lost update against itself.
        LinkedHashMap<String, List<Integer>> byKey = new LinkedHashMap<>();
        for (int index = 0; index < size; index++) {
            byKey.computeIfAbsent(writes.get(index).key(), _ -> new ArrayList<>()).add(index);
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(BATCH_FANOUT, byKey.size()));
        try {
            List<Future<?>> futures = new ArrayList<>(byKey.size());
            for (List<Integer> indices : byKey.values()) {
                futures.add(pool.submit(() -> {
                    for (int index : indices) {
                        results[index] = writeOne(store, writes.get(index));
                    }
                }));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted writing batch", e);
                } catch (ExecutionException e) {
                    // writeOne captures every IOException as a FAILED outcome, so a task body cannot throw a checked
                    // failure; an escape here is an unchecked programming error - surface it, never swallow it.
                    throw new IOException("Batch write task failed", e.getCause());
                }
            }
        } finally {
            pool.shutdown();
        }
        return Arrays.asList(results);
    }

    /**
     * A {@link OutputStream} that wants only a window of a blob: a {@link #read} target a backend recognizes to
     * seek to {@link #offset()} and write {@link #length()} bytes to {@link #sink()} - a ranged {@code GET} on S3
     * or Azure, a channel seek on the filesystem - rather than reading the whole blob and discarding the rest.
     * The serving layer wraps a client {@code Range} request in one; a store that does not recognize it just
     * writes the whole blob, and the stream forwards only the window, so the result is correct either way, only
     * not seeked. A decorating store (quota, content-addressing) passes {@code out} through unchanged, so the
     * capability reaches the leaf backend.
     */
    interface RangedSink {
        long offset();

        long length();

        OutputStream sink();
    }

    /** Copy exactly {@code length} bytes from {@code in} to {@code out}. */
    static void copy(InputStream in, OutputStream out, long length) throws IOException {
        byte[] buffer = new byte[8192];
        long remaining = length;
        while (remaining > 0) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) {
                break;
            }
            out.write(buffer, 0, read);
            remaining -= read;
        }
    }
}
