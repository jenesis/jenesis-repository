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
 * keyed on an opaque token, so concurrent metadata edits never lose one another. An object-store backend maps the
 * token to the blob's ETag or generation; the filesystem pairs the last-modified stamp with a digest of the bytes,
 * because a stamp alone names a moment and the token has to name an incarnation (clause 10).
 *
 * <h2>Contract</h2>
 * Most clauses below are executable: {@code StoreContract} in the store testkit states one once and each backend runs
 * it through a fixture, so it is proven on the filesystem and on containerised S3 / GCS / Azure alike rather than
 * being re-interpreted per backend. The enforcement is named per clause, because it is not uniform, and a clause is
 * <em>not</em> weaker for being unproven - it is an audit item (the earlier residue) rather than a build guard:
 * <ul>
 *   <li><b>kit-proven</b> - clause 2 ({@code CONTENT_ADDRESSED_WRITE}, {@code KEYED_BLOB_ROUND_TRIP}'s repeated
 *       delete, {@code VERSIONED_UPDATE_IF_UNCHANGED}), clause 3 ({@code KEYED_BLOB_ROUND_TRIP},
 *       {@code LISTING_IMMEDIATE_CHILDREN}, {@code PAGING_ORDER_AND_START_AFTER}, {@code VERSIONED_CREATE_IF_ABSENT}),
 *       clause 5 ({@code SCOPE_ISOLATION}, {@code SEGMENT_TRAVERSAL_REJECTED}, {@code KEY_TRAVERSAL_REJECTED}),
 *       clause 6 ({@code ABORTED_WRITE_COMMITS_NOTHING}, {@code BATCH_FAILURE_IS_PER_ENTRY}), clause 8
 *       ({@code PAGING_ORDER_AND_START_AFTER}, {@code BATCH_ORDERED_PER_ENTRY_OUTCOMES}, and the native-paging
 *       obligation itself as {@code NATIVE_PAGING}), clause 9's key caps
 *       ({@code KEY_SHAPE_REJECTED}) and page limit, and clause 10's compare-and-set and batch halves
 *       ({@code VERSION_TOKEN_OPAQUE}, {@code VERSION_TOKEN_PER_INCARNATION}, {@code BATCH_IS_NOT_A_TRANSACTION});</li>
 *   <li><b>documented only</b> - clause 1 in full, clause 3's concurrent-delete-mid-read leg, clause 4, clause 6's
 *       throttle/authorization leg, clause 7, clause 9's {@link #list} rule and resident-memory claim, and clause
 *       10's write-atomicity claim.
 *       Each needs a concurrent or fault-injecting driver the kit does not have; every one of them is stated below in
 *       a form such a driver could assert.
 *
 *       <p>Clause 4 and clause 9's listing rule were once approximated by scans over the free tree's call sites.
 *       They are not any more, and the reason is worth stating: such a scan sees a call site, never a backend, so it
 *       reported a store that read a whole blob into memory internally as compliant while failing a caller that
 *       named a method it disliked. It answered a question about this repository's text rather than about an
 *       implementation of this interface, which is what the clause is about. An implementer owes the clause; the
 *       driver that could hold them to it is a memory-bounded fixture, not a regular expression.</li>
 * </ul>
 * Two of the kit's properties are deliberately <em>not</em> clauses of this interface, and the mismatch is recorded
 * rather than papered over: {@code PLAINTEXT_ENDPOINT_REFUSED} is a resolution-time rule and belongs to
 * {@link ArtifactStoreProvider}'s contract (where it is now stated), and {@code STORE_INVARIANTS} asserts the
 * {@code publish/}-pointer-and-{@code blobs/} layout convention that {@link Publication} owns, not a property of this
 * interface - a store is free to hold an unreferenced blob, which is exactly what a rejected upload leaves behind.
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
 *     must not silently transfer zero bytes as if they were the artifact.
 *     <b>Inability to answer is not absence</b> and shares none of these sentinels - see clause 6. The three
 *     signatures that carry no checked exception ({@link #exists}, {@link #list}, {@link #page}) are exactly the ones
 *     where the two would otherwise be indistinguishable, so a backend that cannot look raises an unchecked
 *     exception there: {@link UncheckedIOException} on the filesystem, the SDK's own type on the object stores.
 *     Widening those signatures would not help, because the object stores fail with unchecked SDK types either
 *     way.</li>
 * <li><b>Streaming (&sect;1).</b> {@link #write}, {@link #writeBlob}, {@link #read} and {@link #open} are the
 *     artifact-sized paths and must not materialise a body: a backend that needs a length or a hash before it can
 *     upload spools to disk, never to the heap, so the JVM stays bounded under a multi-gigabyte publish.
 *     {@link #readVersioned} / {@link #writeVersioned} are the small-object paths and do
 *     materialise, so only pointers, indexes and metadata may travel through them. A {@link RangedSink} passed to
 *     {@link #read} is a request to transfer only that window; a backend that cannot seek still writes the whole
 *     blob through and the sink forwards only the window, so the answer is correct either way.</li>
 * <li><b>Tenant scoping (&sect;6).</b> {@link #scope} is the only tenancy seam: the returned view confines every key
 *     to that subspace, a sibling scope can neither read nor enumerate across it, and scopes nest. The segment is
 *     screened through {@link #segment}, and a key through {@link #key}, so neither a traversal-shaped scope name nor
 *     a traversal-shaped key can address storage outside the subspace it was handed. <b>Both screens count {@code \}
 *     as a path separator</b>, because it is one on a Windows-hosted filesystem backend and a literal on the object
 *     stores: a screen that read only {@code /} would call {@code a\..\b} traversal-free and let it walk a level up on
 *     the one backend where it can.</li>
 * <li><b>Error visibility (&sect;9).</b> Nothing on a correctness-bearing path is swallowed. Only a genuine
 *     object-level miss reads as absent: a throttle, an authorization failure, a permission refusal, a missing
 *     bucket/container or a stale mount must surface, never degrade {@link #exists} to {@code false}, {@link #size}
 *     to {@code -1}, {@link #list} or {@link #page} to an empty child set, {@link #readVersioned} to
 *     {@link Optional#empty()}, or {@link #writeVersioned}
 *     to a {@code false} the caller would retry into exhaustion. A write that fails commits nothing at the key: an
 *     aborted upload leaves it absent, never a truncated body a later content-addressed probe would accept as
 *     already stored.
 *     <b>What "genuine miss" means on each read is exact, because everything above it is built on it.</b> An absent
 *     object and an object below a key that is itself an object (ordinary in the {@code publish/} namespace, where a
 *     pointer and a path beneath it coexist) are misses; a container that is absent or is itself an object has no
 *     children. Nothing else is. A degraded answer here is not a degraded read: an empty listing is how a bounded
 *     traversal learns a container is drained and how a reference scan learns a blob is unreferenced, a {@code false}
 *     from {@link #exists} is how a serve screen learns nothing is withheld and how a re-publish learns its blob was
 *     never condemned - so a swallowed failure on any of them deletes artifact bytes or serves held ones. This is
 *     stated per-read rather than as a platitude because the default backend once read every one of these failures as
 *     an absence, through {@code Files.isRegularFile} and a {@code catch (IOException) -> empty}, and every
 *     fail-closed screen above it was therefore fiction on a filesystem deployment.</li>
 * <li><b>Lifecycle / ownership.</b> The composition builds one store through {@link ArtifactStoreProvider} and keeps
 *     it for the life of the process; a store may own the client, pool or threads its backend needs. A
 *     {@link #scope}d view is a cheap derived value, not a resource: callers create them freely and close nothing.
 *     A stream handed out by {@link #open} is the caller's to close.</li>
 * <li><b>Ordering / concurrency.</b> {@link #page} is the ordering primitive: names stream in lexicographic order of
 *     the child name, strictly after {@code startAfter}, so repeated pages traverse an arbitrarily large child set
 *     exactly once. A container and a same-named leaf are one child, and the ordering is by child name - never by the
 *     backend's raw key order, in which a grouped prefix sorts after a sibling whose name extends it past a character
 *     below {@code '/'}. {@link #list} enumerates the same children as a full paging.</li>
 * <li><b>Bounded work / cancellation.</b> {@link #page}'s {@code limit} bounds what is emitted - always, on every
 *     backend - and a non-positive limit emits nothing. It bounds what the backend <em>buffers</em> only where the
 *     backend pages natively, which is the obligation on an implementation and the reason every shipped backend
 *     overrides {@link #page}: the filesystem scans a directory in bounded strides and the three object stores use
 *     their own start-after pagination, so paging a millions-entry namespace costs O(limit) memory there. The SPI's
 *     own {@code default} is a correctness fallback, not that guarantee - it delegates to {@link #pageByListing},
 *     which sorts a whole {@link #list} and filters, so an implementation that inherits it is bounded in what it emits
 *     while still materialising the container's entire child set to do it. That fallback is therefore itself bounded,
 *     and its bound <em>throws</em>: past {@link #MAX_INHERITED_CHILDREN} children it raises an
 *     {@link IllegalStateException} naming the inheriting class and the prefix rather than allocating without limit or
 *     emitting a short page a caller would read as a drained container. Throwing is the right half of the
 *     truncate-or-throw asymmetry here because {@link #page} hands back names and no outcome, so it has no way to say
 *     "short, resume here"; only a bound with a continuation may end a read as a value. A backend answers natively
 *     instead - the kit's {@code NATIVE_PAGING} property fails one that does not - and an implementation whose child
 *     set genuinely is in memory calls {@link #pageByListing} by name, making the cost a decision rather than an
 *     inheritance.
 *     {@link #key} caps a new key at {@link #MAX_SEGMENTS} segments and {@link #MAX_KEY_BYTES} bytes, so no descent
 *     over stored keys can be driven arbitrarily deep. {@link #list} is deliberately unbounded and is for small child
 *     sets only - anything attacker-shaped pages.</li>
 * <li><b>Durability / delivery.</b> The commit point of {@link #write} and {@link #writeBlob} is the moment the key
 *     becomes readable, and it is atomic: a reader observes the whole previous object or the whole new one, never a
 *     partial write. {@link #writeVersioned} commits only while the stored version still matches the token it was
 *     given, which is what lets many nodes edit one pointer with no lock or database; the token is <em>opaque</em> -
 *     a caller may only hand back a value the store gave it - and changes on every successful write, so a superseded
 *     token can never pass. <b>It identifies the stored incarnation, not the instant of the write</b>: a key that is
 *     deleted and re-created carries a token no reader of the previous incarnation holds, so a compare-and-set from
 *     before the delete is refused rather than landing over content it never saw. A backend whose token is a
 *     wall-clock stamp therefore has to add something the re-creation cannot repeat - the filesystem folds in a digest
 *     of the bytes, the object stores already have an ETag or a generation. The one collision this permits is
 *     a re-creation that is byte-identical at the same stamp, where the state a stale token passes against is
 *     precisely the state its holder read.</li>
 * </ol>
 */
public interface ArtifactStore {

    /** A view confined to one tenant's subspace (a subdirectory on a filesystem, a key prefix on an object store). */
    ArtifactStore scope(String tenant);

    /**
     * A stable identity of this store's subspace: equal for two instances that address the same root directory or
     * bucket prefix, unequal across scopes, and stable across restarts of the process.
     *
     * <p>Fifteen consumers key on it. {@link StoredListing} keys its per-document writer queues, so the concurrent
     * writers of one listing - and only they - coalesce into one rewrite; {@link StoreCache} and
     * {@link StoredCounter} key their entries; every walk consumer keys the per-repository state it carries across a
     * pass; and the search index <em>hashes it into the name of a durable index</em>.
     *
     * <p><b>There is no default, and that is the whole of the design here.</b> It used to fall back to the instance
     * itself, described as staying correct and merely coalescing nothing - which was true of the one consumer it was
     * written for and of none of the fourteen that arrived afterwards. An instance identity makes every new store
     * object a new key: a long-lived map grows without bound, a gauge that sums its values counts stale entries, and
     * an index name changes when nothing about the repository did. Whether a future consumer merely coalesces or
     * durably names cannot be known from here, and the failure is silent in both directions - nothing throws, a
     * number is quietly wrong - so the answer is required rather than assumed.
     *
     * <p>Answering is never hard, which is why requiring it costs nothing: every implementation in this build is its
     * scheme and its location ({@code "s3:" + bucket + "/" + keyPrefix}), and <b>a decorator answers its delegate's</b>
     * - the case a default made easiest to forget, and the one where forgetting it silently moves a whole
     * repository's coalescing, caching and counting onto a key nothing else shares.
     */
    Object identity();

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
        if (!safeSegment(segment)) {
            throw new IllegalArgumentException("Not a traversal-free scope segment: " + segment);
        }
        return segment;
    }

    /**
     * Whether {@code value} is a single traversal-free segment: non-empty, neither {@code .} nor {@code ..}, and
     * carrying no path separator ({@code /} or {@code \}) and no control character.
     *
     * <p>The predicate behind {@link #segment(String)}, public because callers outside the write path ask the same
     * question and need an answer rather than an exception - a format deciding whether to store a version under a
     * key, an inspector deciding whether a segment read out of a request path is one the repository could hold.
     * Three copies of this rule existed and had already drifted: both callers rejected control characters and the
     * store's own guard did not.
     */
    static boolean safeSegment(String value) {
        // One segment is a traversal-free path with no separator in it: the same refusals, stated once, in
        // traversalFree, which is where the control-character argument lives.
        return value != null && !value.isEmpty() && value.indexOf('/') < 0 && traversalFree(value);
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
     * <p>A key carrying a {@code .} or {@code ..} segment, or a {@code \} anywhere, is rejected here too, for the same
     * reason {@link #segment(String)} refuses one: on a filesystem such a key walks out of the subspace it was
     * addressed in, and on an object store it lands a literal key that no other backend can then address - so the same
     * publish would be refused on one backend and silently accepted on another (&sect;13). Screening it at the one write
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
     * Whether a {@code '/'}-separated path carries no {@code .} or {@code ..} segment <em>and</em> no {@code \} - the
     * traversal half of the {@link #key(String)} screen, exposed as a predicate so a caller that must <em>decline</em>
     * rather than throw asks the same question at the same choke point instead of re-deriving the rule.
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
     * <p>{@code .} and {@code ..} segments are refused, and so is a {@code \} anywhere - the separator half
     * {@link #segment(String)} has always applied to a scope name, applied here to a key. It is not a stylistic
     * restriction: {@code \} <em>is</em> a path separator on a Windows-hosted filesystem backend and a literal
     * character on the three object stores, so {@code a\..\b} walks a level up on one backend and names a single
     * literal object on the others, and {@code a\b} is a nested key on one and a flat one on the others. Judging that
     * shape "traversal-free" would let the very publish this screen exists to refuse through on the one backend where
     * it escapes, and would break the interchangeability {@link #key(String)} is here to keep (&sect;13). Nothing below
     * this line re-screens it: the {@code segment}/{@code ArtifactLayout.addressable}/{@code RepositoryImporter}
     * seams above all refuse a backslash already, and this was the one place that did not.
     *
     * <p><b>A C0 control character is refused too</b>, and for the reason the backslash is: it is a character that
     * means one thing here and another somewhere downstream. A {@code NUL} truncates the key at the first C API that
     * touches it, so a key screened whole is acted on in part; a {@code CR} or {@code LF} forges a line in every log
     * record, generated index and listing document the key later reaches, so a coordinate can write rows that read as
     * the server's own. Neither is part of a legitimate coordinate in any ecosystem this product serves, so the cost
     * of refusing them is nothing, and this was the last shape the free core screened nowhere - not here, not in
     * {@link #key(String)}, not in {@link #segment(String)} - while this edition's downstream request guard had
     * refused it since it was written. Two layers disagreeing about which shapes are legal is the divergence this
     * predicate exists to prevent, so the rule is stated here and the downstream guard delegates to it (&sect;2).
     * The boundary is the C0 range: {@code 0x7F} and the C1 range are left out deliberately, because widening beyond
     * the rule being lifted would be a second, unproven change riding a first.
     *
     * <p>The name is now narrower than the question. A backslash was already not a traversal, and a control character
     * plainly is not; what every caller actually asks here is "may this path be stored and routed" - see the naming
     * note on D-288 in the hardening plan.
     *
     * <p>An empty segment is not a traversal (a trailing slash on a directory listing request, a doubled separator)
     * and a percent-encoded {@code %2e%2e} is not one either: it is a literal name until something decodes it, and
     * nothing below this line ever does.
     */
    static boolean traversalFree(String path) {
        if (path == null) {
            return false;
        }
        for (int index = 0; index < path.length(); index++) {
            char character = path.charAt(index);
            if (character == '\\' || character < 0x20) {
                return false;
            }
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

    /**
     * One more than {@code limit}, saturating: the probe size a paged read asks for so that receiving the extra
     * record proves more remains, without a second request asking.
     *
     * <p>Every paged backend in this product computes that, and every one of them computed it as {@code limit + 1}.
     * At {@link Integer#MAX_VALUE} - a positive, legal bound, and the obvious way for a caller to ask for
     * everything - that wraps to {@link Integer#MIN_VALUE}, the underlying page comes back empty, and the read dies
     * somewhere unrelated: a bare {@code NoSuchElementException} out of a {@code getLast()} on the empty batch. The
     * {@code Math.min(limit + 1, 1000)} guards that look like they cover it do not, because they clamp <em>after</em>
     * the wrap and {@code MIN_VALUE} is smaller than every ceiling.
     *
     * <p>Saturating is the right answer rather than throwing: a caller asking for {@code MAX_VALUE} is asking for
     * everything, and everything is what {@code MAX_VALUE} records already means. It is stated here, once, because
     * six backends were each spelling it and each getting it wrong the same way.
     */
    static int oneMoreThan(int limit) {
        return limit == Integer.MAX_VALUE ? limit : limit + 1;
    }

    /**
     * Whether a blob exists at this object key.
     *
     * <p><b>Not a basis for a fail-closed decision.</b> It answers {@code boolean}, so it cannot tell "absent" from
     * "the backend could not answer": a store hiccup reads as {@code false}. That is harmless where absence is the
     * conservative reading - a serve that 404s an artifact it could not confirm - and it is a disclosure where
     * absence is the permissive one. A screen keying a verdict or a {@code withheld} probe on this
     * <b>structurally cannot</b> honour {@link PublishInterceptor}'s fail-closed rule, because an outage under the
     * probe reads as "nothing is withheld" and the unscreened artifact serves.
     *
     * <p>So a caller whose {@code false} branch <em>permits</em> something uses {@link #readVersioned} and treats a
     * failure as a failure - {@link Withheld#is} is exactly that read, and is why the withhold probes go through it
     * rather than through here.
     */
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

    /**
     * What a listing would report for the one object at {@code key} - its size and the backend's own modification
     * time, see {@link Listed} - by a point request (a stat, a HEAD), or empty when nothing is stored there. The
     * one-object form of the metadata {@link #scan} carries: a caller that knows the key and wants the object's own
     * time pays one request rather than a listing of its container. The default answers presence alone, from
     * {@link #exists}, with neither size nor time - enough for a double; a backend overrides it with the metadata
     * request it already makes for {@link #size}, and a decorator forwards it.
     */
    default Optional<Listed> listed(String key) throws IOException {
        return exists(key) ? Optional.of(Listed.of(key)) : Optional.empty();
    }

    /** Delete the blob, tidying any now-empty container it leaves behind. */
    void delete(String key) throws IOException;

    /** Free and total bytes of a backing volume. */
    record Capacity(long usable, long total) {
    }

    /**
     * The backing volume's free and total bytes, or empty when this backend has no volume to report.
     *
     * <p>Empty is a statement about the backend, not a failed probe: an object store has no capacity a client can
     * meaningfully be told about, so it reports nothing rather than a number that would have to be read as "assume
     * unlimited". A backend that HAS a volume and cannot measure it throws, because that is a failure and must not be
     * mistaken for the absence.
     */
    default Optional<Capacity> capacity() throws IOException {
        return Optional.empty();
    }

    /**
     * Mark a key as recently used, where the backend has an access time to set; a no-op where it has not.
     *
     * <p>Recency-on-read, for an eviction policy that wants least-recently-USED rather than least-recently-written.
     * An object store has no settable access time and so does nothing here - which is honest rather than lossy: its
     * {@link Listed#modified} is the write time, and a policy reading it gets least-recently-written and should know
     * that is what it got.
     */
    default void touch(String key) throws IOException {
    }

    /**
     * Whether nothing at all is stored under {@code prefix} - <b>one child answers it</b>, and the probe stops
     * there.
     *
     * <p>It exists because the obvious spelling is a trap that this repository has now paid for three times:
     * {@code list(prefix).isEmpty()} materialises a container's whole child set to answer a yes/no question, so
     * asking whether the blob pool is empty costs one string per blob. A console browse once listed a namespace
     * per row to draw a folder icon; a tenant existence probe reached the scan fallback and stopped the bundled
     * image booting over ten thousand keys.
     *
     * <p>So the point of this method is not that it is faster. It is that <em>the correct form is now shorter than
     * the incorrect one</em>, which is the only version of this rule that survives contact with people. A reviewer
     * spotting {@code list(..).isEmpty()} is a rule; a method that reads better and cannot be unbounded is a
     * mechanism.
     */
    default boolean isEmpty(String prefix) throws IOException {
        boolean[] any = {false};
        page(prefix, "", 1, _ -> any[0] = true);
        return !any[0];
    }

    /**
     * The immediate child names under a key prefix (for the console browse and metadata maintenance). A prefix names
     * a container with or without a trailing slash - {@code a/b} and {@code a/b/} are the same one, here and for
     * {@link #page}, {@link #pageListed} and {@link #scan}; a backend normalises it with {@link #container} before it
     * asks its storage, whose key grammar may admit only the first. A leading slash is not normalised here: the
     * filesystem refuses it like any other traversal-shaped key, and an object store's own key grammar decides.
     */
    List<String> list(String prefix);

    /**
     * A listing prefix as a container name: trailing slashes removed, so that {@code a/b} and {@code a/b/} name one
     * container. The filesystem resolves both to one directory; an object store's key grammar does not, and a key
     * holding {@code //} is refused outright by some services - so every object-store listing runs its prefix
     * through this first.
     */
    static String container(String prefix) {
        if (prefix == null) {
            return "";
        }
        int end = prefix.length();
        while (end > 0 && prefix.charAt(end - 1) == '/') {
            end--;
        }
        return prefix.substring(0, end);
    }

    /**
     * The page a drain of a level takes - a pass that must follow a level to exhaustion, paging with
     * {@link #page}. On the filesystem store every page rescans its directory, so for such a walk the page width is
     * the number of rescans: a thousand a page over a million names was a thousand scans of a million, measured by
     * the memory canaries as a pass that never finished, and ten thousand a page a hundred scans - the arithmetic the
     * walk module's {@code BoundedChildren} records in full. Every drain in the product pages at this width through
     * {@link Names}, and this is the one place the number is written.
     */
    int DRAIN_PAGE = 10_000;

    /**
     * Stream up to {@code limit} immediate child names under {@code prefix} to {@code consumer}, in lexicographic
     * order, starting strictly after {@code startAfter} (the empty string starts from the beginning). This is the
     * ordered-paging primitive the shared artifact walk enumerates through: repeated pages, each resuming after the
     * last name of the one before, traverse an arbitrarily large child set - the flat, millions-entry {@code blobs/}
     * namespace - without ever materialising it as one {@code List} the way {@link #list} does.
     *
     * <p><strong>This is the names-only view of {@link #pageListed}, and only that.</strong> A backend implements
     * {@code pageListed} - the filesystem scans a directory in bounded strides, the three object stores use their own
     * start-after pagination - and this form derives from it losslessly, the child's name being the last segment of
     * its key. It used to be the other way round on paper and both ways in practice: four backends each carried the
     * identical three-line override and the identical {@code name} helper to express this over that one, which is
     * the shape a default exists for. The contract kit's {@code NATIVE_PAGING} property still proves a backend pages
     * natively, and a backend that overrides neither inherits {@code pageListed}'s bounded listing fallback, which
     * refuses past {@link #MAX_INHERITED_CHILDREN} children rather than pretending to page.
     */
    default void page(String prefix, String startAfter, int limit, Consumer<String> consumer) {
        pageListed(prefix, startAfter, limit, listed -> consumer.accept(name(listed.key())));
    }

    /** The last segment of {@code key}: the child's own name, as {@link #page} reports it and
     *  {@link Listed#key} does not. */
    static String name(String key) {
        int slash = key.lastIndexOf('/');
        return slash < 0 ? key : key.substring(slash + 1);
    }

    /**
     * The metadata-bearing form of {@link #page}: the same bounded, ordered page of immediate children, delivered as
     * {@link Listed} so a caller that needs each child's size or age does not have to ask for it one request at a
     * time.
     *
     * <p>A {@link Listed#key} here is the child's whole key ({@code prefix} joined with its name), not the bare name
     * {@link #page} reports, because a caller holding metadata is about to act on the object and would otherwise
     * re-compose it. The metadata obeys {@link Listed}'s rule exactly: it carries what the backend's listing already
     * returned and never costs a request of its own, so a child that is a CONTAINER - which has no size or age of its
     * own - reports neither.
     *
     * <p>Every shipped backend implements this one, and {@link #page} derives from it: the ordering rules a
     * hierarchical listing needs (a container's grouped prefix sorting after a sibling whose name extends it) are
     * subtle enough that two copies would drift, and the names-only form is the one that can be derived losslessly.
     *
     * <p><strong>The inherited body is a small-container fallback and says so out loud.</strong> It is
     * {@link #pageByListing} - {@link #list}-and-sort, which emits the right children in the right order but
     * materialises the container's whole child set to do it, the opposite of what paging is for - so it refuses
     * rather than pretending: past {@link #MAX_INHERITED_CHILDREN} children it throws an {@link IllegalStateException}
     * naming the inheriting class, the prefix and the remedy, instead of quietly turning one page request into an
     * unbounded heap allocation (&sect;9, and the "bounds fail visibly" gate). It reports no metadata, because it has
     * none it did not ask for. An implementation whose whole child set genuinely <em>is</em> in memory (a map-backed
     * test double, an in-process spool) may call {@link #pageByListing} by name from its {@code page}: the cost is then
     * a decision at the call site rather than an accident of inheritance.
     */
    default void pageListed(String prefix, String startAfter, int limit, Consumer<Listed> consumer) {
        // The container name normalised as every backend normalises it, so a trailing-slash prefix keys its children
        // exactly as the bare one does; the decorator legs of the store contract found an earlier default keying them
        // kit/listing//alpha.
        String container = container(prefix);
        pageByListing(this, prefix, startAfter, limit, name -> consumer.accept(Listed.of(child(container, name))));
    }

    /** A child's key under {@code prefix} - the root's children are keyed by their bare names. */
    private static String child(String prefix, String name) {
        return prefix == null || prefix.isEmpty() ? name : prefix + "/" + name;
    }

    /**
     * The most children {@link #pageByListing} will materialise before it refuses. It is deliberately far above any
     * container an in-memory or in-process store legitimately holds and far below a namespace that would exhaust the
     * heap, so it separates "this backend never needed native paging" from "this backend is about to buffer a
     * millions-entry namespace to answer one page". It is the same order as the shared walk's per-call entry cap, since
     * both answer the same question: how large may one bounded read's working set get.
     */
    int MAX_INHERITED_CHILDREN = 10_000;

    /**
     * Page {@code store}'s children by sorting and filtering its whole {@link #list} - the explicit, named form of the
     * fallback {@link #page} inherits, for an implementation whose child set is already materialised (a map-backed
     * store, an in-process spool) and for which a "native" paging would be this code anyway.
     *
     * <p>It is bounded, and the bound throws rather than truncating: a caller of {@link #page} is handed names, not an
     * outcome, so there is nowhere to report "the page you got is short because the container was too big", and a
     * silently short page reads to the shared walk as a drained container - it would skip keys while answering in the
     * vocabulary of completeness. That is the same asymmetry the bounded store traversals draw (an entry cap truncates
     * with a cursor because it has a continuation; a bound on how pathological the key space is throws because it has
     * none), and this bound is the second kind. Past {@link #MAX_INHERITED_CHILDREN} children it throws an
     * {@link IllegalStateException} naming {@code store}'s class, the prefix and the count, and pointing at the fix:
     * override {@link #pageListed}.
     *
     * @throws IllegalStateException when {@code prefix} holds more than {@link #MAX_INHERITED_CHILDREN} children
     */
    static void pageByListing(ArtifactStore store, String prefix, String startAfter, int limit,
                              Consumer<String> consumer) {
        if (limit <= 0) {
            return;
        }
        List<String> children = new ArrayList<>(store.list(prefix));
        if (children.size() > MAX_INHERITED_CHILDREN) {
            throw new IllegalStateException(store.getClass().getName() + " pages '" + prefix + "' by materialising its "
                    + children.size() + " children, past the " + MAX_INHERITED_CHILDREN + "-child bound on the "
                    + "inherited ArtifactStore.pageListed fallback. Override pageListed(...) with the backend's own start-after "
                    + "pagination; a paging primitive that first buffers the whole container is not one.");
        }
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

    /**
     * One enumerated object: its {@code key}, plus whatever the backend's own listing <em>already told it</em>.
     *
     * <p>{@code size} and {@code modified} are optional, and the rule behind the optionality is the whole point of
     * this record: <strong>a backend may never make a call to fill them</strong>. They carry what the native listing
     * returned in the same response and nothing else, so enriching a scan costs no round trip and a caller that
     * ignores them pays nothing for their presence. A backend whose listing is names-only reports them empty, and the
     * caller decides whether the answer is worth a {@link ArtifactStore#size} of its own.
     *
     * <p>Every shipped backend fills both, because every one of their listings carries them: S3's
     * {@code ListObjectsV2} returns {@code Size} and {@code LastModified}, GCS returns {@code size} and
     * {@code updated}, Azure returns {@code contentLength} and {@code lastModified}, and the filesystem's file-tree
     * visitor is handed {@code BasicFileAttributes} before it ever decides to emit the entry. The optionality is for
     * an in-memory double and for a future backend that genuinely cannot - not a licence the four may take, which is
     * why the store kit asserts it as a property.
     */
    record Listed(String key, OptionalLong size, Optional<Instant> modified) {

        public Listed {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(size, "size");
            Objects.requireNonNull(modified, "modified");
        }

        /** An entry whose listing carried no metadata - the names-only shape. */
        public static Listed of(String key) {
            return new Listed(key, OptionalLong.empty(), Optional.empty());
        }

        /** An entry whose listing carried both, which is the shape every shipped backend produces. */
        public static Listed of(String key, long size, Instant modified) {
            return new Listed(key, OptionalLong.of(size), Optional.of(Objects.requireNonNull(modified, "modified")));
        }
    }

    /**
     * What one {@link #scan} call saw: how many entries it {@code delivered}, how many {@code steps} - listing
     * round-trips - it spent, and the continuation {@code cursor}, present exactly when a cap cut the call short.
     *
     * <p>It is deliberately the same shape as the traversal tier's own result, and deliberately a different type: the
     * walk module that owns that one {@code requires} this one, so a store-level primitive cannot name it without a
     * module cycle. A caller in the traversal tier converts; the two agree on the one rule that matters, which is
     * that a cursor is present exactly when there is more to come and a scan may never over-claim completeness.
     */
    record Scan(long delivered, long steps, Optional<String> cursor) {

        public Scan {
            Objects.requireNonNull(cursor, "cursor");
            if (delivered < 0 || steps < 0) {
                throw new IllegalArgumentException("Negative scan counters: " + delivered + " / " + steps);
            }
        }

        /** The prefix was seen whole: nothing more is coming. */
        public static Scan exhausted(long delivered, long steps) {
            return new Scan(delivered, steps, Optional.empty());
        }

        /** A cap was reached after {@code cursor}: resume with it to receive the rest. */
        public static Scan truncated(String cursor, long delivered, long steps) {
            return new Scan(delivered, steps, Optional.of(Objects.requireNonNull(cursor, "cursor")));
        }

        /** Whether a cap cut this call short, so {@link #cursor()} must be followed to see the rest. */
        public boolean truncated() {
            return cursor.isPresent();
        }
    }

    /**
     * Stream up to {@code limit} objects at or below {@code prefix} to {@code consumer}, in key order, resuming
     * strictly after {@code startAfter} (the empty string starts from the beginning), each carrying whatever metadata
     * the backend's listing already knew - see {@link Listed}.
     *
     * <p><strong>Recursive, where {@link #page} is not.</strong> {@code page} answers "what are the immediate children
     * of this container", which is the question a browse tree asks. This answers "what objects are under here", which
     * is the question a sweep asks - a garbage collection walking {@code blobs/}, an eviction pass walking a project.
     * The two are different questions and stay different methods; neither is the other with a flag.
     *
     * <p><strong>Why the metadata rides along.</strong> A sweep needs each object's size, and usually its age. Getting
     * those from a names-only listing costs one metadata request per object - the classic N+1, on the one code path
     * that by construction touches every object in the store. Every object store returns both in the listing response
     * already, so the cost of carrying them here is zero and the cost of not carrying them is a round trip per entry.
     *
     * <p>A {@link Listed#key} is a whole KEY relative to this store's scope - not a child name relative to
     * {@code prefix}, which is what {@link #list} and {@link #page} report. A sweep hands what it is given straight
     * back to {@link #size}, {@link #delete} or {@link #readVersioned}, so a scan that reported names would make
     * every caller re-compose them. {@code startAfter} is one of those keys, handed back verbatim.
     *
     * <p><strong>Abstract, deliberately, and this used to be a default.</strong> The default was
     * {@link #scanByListing}, which walks {@link #list} recursively into heap and refuses past
     * {@link #MAX_INHERITED_CHILDREN} keys. That is a reasonable <em>fallback</em> and it was a terrible
     * <em>default</em>: a store that did not think about scanning silently got the materialising one, and the
     * failure only appears once a prefix is large - by which time it is a deployment, not a test.
     *
     * <p>It cost exactly that. Three decorators - metering, quota and read-only - each overrode {@code page} for
     * this very reason and each forgot {@code scan}, so all three replaced their backend's native bounded listing
     * with the fallback. A tenant existence probe, already written as a point read with a page limit of one,
     * reached it through the metering decorator, materialised 10,001 keys and refused - and the image
     * stopped booting over any store holding more than ten thousand keys.
     *
     * <p>Made abstract, none of that can recur quietly: a store that does not implement this does not compile, and
     * a decorator that forgets it does not compile either. What a decorator wants is one line delegating to what it
     * wraps; what a store whose key space is already materialised wants is {@link #scanByListing}, named out loud.
     * A test double that never scans wants whichever is shorter - and either way it had to decide.
     *
     * @throws IllegalArgumentException when {@code limit} is not positive - an empty page would read as a drained
     *                                  prefix, and a scan may not answer in the vocabulary of completeness by accident
     */
    Scan scan(String prefix, String startAfter, int limit, Consumer<Listed> consumer) throws IOException;

    /**
     * Scan {@code store} by walking {@link #list} recursively - the explicit, named form of the fallback {@link #scan}
     * inherits, for an implementation whose key space is already materialised (a map-backed store, an in-process
     * spool) and for which a "native" scan would be this code anyway.
     *
     * <p>It delivers {@link Listed#of(String) names-only} entries: a listing that does not carry metadata does not
     * acquire it here by stat-ing, because that is precisely the N+1 {@link #scan} exists to avoid, and a fallback
     * that quietly made one request per object would be worse than the one that refuses.
     *
     * <p>Bounded like {@link #pageByListing} and for the same reason: past {@link #MAX_INHERITED_CHILDREN} examined
     * keys it throws rather than buffering a namespace to answer one page.
     *
     * @throws IllegalStateException when the prefix holds more than {@link #MAX_INHERITED_CHILDREN} keys
     */
    static Scan scanByListing(ArtifactStore store, String prefix, String startAfter, int limit,
                              Consumer<Listed> consumer) {
        if (limit <= 0) {
            throw new IllegalArgumentException("A scan limit must be positive: " + limit);
        }
        List<String> keys = new ArrayList<>();
        collect(store, prefix, keys);
        if (keys.size() > MAX_INHERITED_CHILDREN) {
            throw new IllegalStateException(store.getClass().getName() + " scans '" + prefix + "' by materialising its "
                    + keys.size() + " keys, past the " + MAX_INHERITED_CHILDREN + "-key bound on the inherited "
                    + "ArtifactStore.scan fallback. Override scan(...) with the backend's own prefix listing; a "
                    + "recursive scan that first buffers the whole prefix is not one.");
        }
        Collections.sort(keys);
        long delivered = 0;
        String last = null;
        for (String key : keys) {
            if (startAfter != null && !startAfter.isEmpty() && key.compareTo(startAfter) <= 0) {
                continue;
            }
            if (delivered == limit) {
                return Scan.truncated(last, delivered, 1);
            }
            consumer.accept(Listed.of(key));
            delivered++;
            last = key;
        }
        return Scan.exhausted(delivered, 1);
    }

    /** Depth-first accumulation of every key at or below {@code prefix}, for {@link #scanByListing}. A child with no
     *  children of its own is a leaf and therefore a key; the recursion is what makes the fallback recursive. */
    private static void collect(ArtifactStore store, String prefix, List<String> keys) {
        List<String> children = store.list(prefix);
        if (children.isEmpty()) {
            if (!prefix.isEmpty() && store.exists(prefix)) {
                keys.add(prefix);
            }
            return;
        }
        for (String child : children) {
            collect(store, prefix.isEmpty() ? child : prefix + "/" + child, keys);
            if (keys.size() > MAX_INHERITED_CHILDREN) {
                return;         // the caller's bound reports this; stop digging rather than finish an illegal walk
            }
        }
    }

    /** A small object plus an opaque version token, for compare-and-set writes. */
    record Versioned(byte[] content, Object token) {
    }

    /** Read a small object with its version token; empty if absent. */
    Optional<Versioned> readVersioned(String key) throws IOException;

    /**
     * The version token alone, without transferring the body; empty if absent.
     *
     * <p>The metadata half of {@link #readVersioned}, for the caller that only wants to know whether something
     * changed - a revalidating config cache, an {@code ETag} answer. Every object store answers it with a metadata
     * request where {@code readVersioned} costs a full download, so a caller that asks "has this changed?" through
     * {@code readVersioned} pays for the bytes it then throws away.
     *
     * <p>The inherited body is exactly that mistake, made explicit and correct: it reads the object and keeps the
     * token. It is the right answer only for a backend that has no cheaper probe and holds its objects in memory
     * anyway, and the wrong one for everything else - <b>including a store that merely wraps another</b>, where the
     * default silently converts a delegate's metadata request back into a download. All four shipped backends
     * override it and so do the wrapping stores; a new implementation of either kind must.
     *
     * <p>The store kit checks that this token is the one {@link #readVersioned} pairs with the body and that a write
     * moves it on. It cannot check that no body was transferred - that is invisible from the SPI - so the claim above
     * is a review question, and the thing that presses it is a canary publishing into a listing too large to hold.
     */
    default Optional<Object> version(String key) throws IOException {
        return readVersioned(key).map(Versioned::token);
    }

    /**
     * Write a small object only if the stored version still matches {@code expected} ({@code null} requires
     * the object be absent). Returns {@code false} on a mismatch, so the caller can re-read and retry; this
     * is how {@code maven-metadata.xml} stays consistent under concurrent deploys without a lock or database.
     */
    boolean writeVersioned(String key, byte[] content, Object expected) throws IOException;

    /**
     * The same compare-and-set, over a stream of known length, for an object too big to hold.
     *
     * <h2>Why this exists</h2>
     *
     * <p>Every listing this product serves is a single object written under compare-and-set, and some of them are
     * proportional to the repository - a catalogue, a Simple index, a folder page. Writing those through
     * {@link #writeVersioned(String, byte[], Object)} means the whole document in heap, and in practice more than
     * once: a growing buffer, the array it is copied into, and the framed copy that carries the header. That is
     * the peak a large listing reaches, and no amount of streaming on the producing side removes it while the
     * write itself takes an array.
     *
     * <h2>Why the length is a parameter</h2>
     *
     * <p>Not decoration: two of the four shipped backends cannot start a conditional upload without it. S3 and
     * Azure both take a stream and a length, and the multipart alternative that avoids the length is a different
     * request whose interaction with the precondition is not the same. So the caller commits to a length, which
     * for a rendered document means rendering it somewhere measurable first - a temporary file rather than heap.
     *
     * <h2>The condition is exactly the buffered one</h2>
     *
     * <p>{@code null} requires the object be absent; a token requires it be unchanged; {@code false} means the
     * caller should re-read and retry. The backends implement it with the same precondition they already use -
     * an {@code If-Match} on the ETag, a generation match, or the read-compare-then-rename the filesystem does -
     * so this adds a body shape and no new semantics. The store contract holds both forms to the same two rules.
     *
     * <p>The inherited body buffers, which is correct for a <em>backend</em> without a streaming upload: it is
     * exactly the call it replaces, so such a backend is no worse for not overriding this.
     *
     * <p><b>It is not correct for a decorator, and this is a trap that has already been sprung.</b> A store that
     * wraps another one and inherits this body silently converts a streaming backend into a buffering one - the
     * whole document into heap, on the delegate's behalf, defeating the override the backend does have. Four
     * decorators inherited it and a folder page of a hundred thousand entries died of it under a bounded heap,
     * naming this method in the stack. So a decorator overrides this and delegates; if it genuinely holds content
     * by design, it overrides it anyway and says so, because otherwise nothing distinguishes the deliberate case
     * from the accident.
     */
    default boolean writeVersioned(String key, InputStream content, long length, Object expected) throws IOException {
        return writeVersioned(key, content.readAllBytes(), expected);
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
