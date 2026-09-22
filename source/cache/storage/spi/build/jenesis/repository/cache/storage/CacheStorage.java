package build.jenesis.repository.cache.storage;

import module java.base;

import build.jenesis.repository.walk.Traversal;

/**
 * The storage backend for the cache server: everything the server persists or enumerates goes
 * through this interface, so the on-disk filesystem can be swapped for an object store
 * (S3/Azure Blob/GCS) without touching the request, authorisation, metrics or eviction-policy code.
 *
 * The default implementation is {@code DelegatingCacheStorage} over the {@code filesystem}
 * artifact store (a mounted volume). The server keeps the
 * eviction <em>policy</em> (which entries to drop for a size cap / ttl / free-space target) and
 * measures free space through its own overridable {@code usableSpace()}/{@code totalSpace()}; the
 * backend supplies the <em>mechanics</em>: blob read/write, entry enumeration with size and recency,
 * deletion, and the free-space numbers.
 *
 * Recency is exposed as an {@link Instant} and recorded as data, never as a backend's own time: {@link #stamp}
 * writes an empty object beside the entry whose name is the instant, {@link #recency} reads the newest one back - or the entry's own time when there is none -
 * and {@link #entries} folds it into the entry's recency from the same listing - so a read counts as use on every
 * backend alike, and a store copied from a filesystem into a bucket keeps every entry's recency. An entry never
 * stamped ages from the write time the backend reports for it.
 *
 * <h2>Contract</h2>
 * <ol>
 * <li><b>Thread-safety.</b> One instance is shared by every request thread and by the background reaper, so every
 *     method must be safe to call concurrently. {@link #scope} hands out an independent view rather than mutating
 *     the receiver, and no method may publish state a concurrent reader can observe half-written.</li>
 * <li><b>Idempotency / replay.</b> {@link #store} is last-writer-wins and may be replayed byte-for-byte; a repeated
 *     {@link #delete} of an already-deleted blob converges silently rather than throwing, because the reaper
 *     re-runs over a snapshot that a concurrent pass may already have drained. {@link #createProject},
 *     {@link #deleteDir} and {@link #stamp} are likewise idempotent.</li>
 * <li><b>Absence sentinel.</b> Absence never throws on a read and is never {@code null} where a value is returned:
 *     {@link #exists} and {@link #projectExists} answer {@code false}; {@link #readConfig} and {@link #readFile}
 *     answer an <em>empty</em> {@link Properties}; {@link #projects}, {@link #entries} and {@link #listDir} deliver
 *     nothing and answer an {@linkplain Traversal.Result#exhausted() exhausted} result, which is the one honest way to say
 *     "there was nothing there" - an absent container is drained, not truncated, so a caller never resumes a cursor
 *     into a space that does not exist; {@link #configVersion} and {@link #fileVersion} answer {@code null} -
 *     the one place this SPI does use {@code null} as a sentinel, deliberately, because a version token is opaque
 *     and has no "absent" value of its own. {@link #read} is the exception: streaming an absent entry throws
 *     {@link IOException} rather than producing an empty body a client would cache as a hit.</li>
 * <li><b>Addressability (&sect;6, &sect;13).</b> An address that cannot be stored is refused before any I/O, by the
 *     one shared predicate every backend already applies when it enumerates: {@link #store} rejects an entry that
 *     is not a {@link Names#isEntry valid project plus two hex segments}, and the config-tree methods reject a path
 *     that is not a {@link Names#isPath traversal-free relative path} (or, for the project-config pair, a
 *     {@link Names#isFile plain file name}), each with {@link IllegalArgumentException}. A read of an unaddressable
 *     name degrades to the absence sentinel above rather than throwing, so data written before the screen stays
 *     readable and deletable. Without this clause a filesystem backend refuses what an object store stores
 *     literally - and an object stored at a non-hex or traversal-shaped key is invisible to every enumeration, so
 *     it is never counted toward a size cap, never aged out by the ttl and never reclaimed.</li>
 * <li><b>Streaming (&sect;1).</b> {@link #read} and {@link #store} stream: an entry blob is never fully materialised
 *     in heap by the backend, whatever its size. Only the small {@link Properties} documents are read whole.</li>
 * <li><b>Tenant scoping (&sect;6).</b> {@link #scope} is the only tenant boundary and it is a confinement, not a
 *     hint: a scoped view can neither read nor write outside its subspace, and a sibling scope observes none of its
 *     projects, entries or config files. The tenant must be a {@link Names#isTenant valid tenant name}, which is
 *     what makes it a traversal-free segment; anything else throws {@link IllegalArgumentException}.</li>
 * <li><b>Error visibility (&sect;9).</b> {@link #delete}, {@link #stamp} and the {@code read*} methods are
 *     best-effort and may swallow a backend failure: their blast radius is an entry that stays stored a little
 *     longer, a recency stamp that does not advance, or a project treated as unconfigured. A failure on
 *     {@link #store}, {@link #writeConfig}, {@link #writeFile} or {@link #writeFileVersioned} must surface, because
 *     losing one of those would let a later read serve or trust something that was never written.
 *     {@link #usableSpace}/{@link #totalSpace} are on the surfacing side too, and for a sharper reason: their only
 *     available "degraded" answer is the unlimited sentinel, which the free-space sweep reads as "there is no volume
 *     to run out of" and therefore switches the sweep off entirely, letting the volume ratchet toward a permanent
 *     {@code 507}. A backend that has a volume and cannot measure it throws naming what it could not measure; it
 *     never borrows the sentinel of a backend that has no volume at all.</li>
 * <li><b>Read purity (&sect;10).</b> Every read renders stored state; no read path provisions containers, rewrites
 *     recency-bearing metadata beyond {@link #stamp}, or reaches outside the backend.</li>
 * <li><b>Staleness.</b> {@link Stored#recency} is the answer to "when was this last used": the newest stamp where the
 *     entry has one and the write time the backend reports where it has none, on every backend alike, so an
 *     eviction policy built on it is least-recently-used everywhere. {@link #configVersion} and
 *     {@link #fileVersion} are the revalidation tokens a cached read compares against.</li>
 * <li><b>Lifecycle / ownership.</b> The composition owns the instance: {@code CacheStorageProvider.resolve} builds
 *     exactly one, and a backend that holds a client, pool or thread owns it for the life of the application. A
 *     {@link #scope} view borrows the parent's resources and owns nothing, so it needs no closing.</li>
 * <li><b>Ordering / determinism.</b> {@link #projects}, {@link #entries} and {@link #listDir} deliver in one total,
 *     deterministic order: the lexicographic byte order of the enumerated <em>key</em>, where a container's key ends
 *     in the separator ({@code <name>/}) and an entry's does not ({@code <project>/<step>/<inputs>}). That is exactly
 *     the order the object stores list in natively, which is what lets a cursor resume with a server-side seek rather
 *     than a re-scan of everything before it; a filesystem backend, whose directory order is arbitrary, sorts to match.
 *     It is emphatically not a promise about <em>name</em> order - {@code acme-corp} precedes {@code acme} because
 *     {@code acme-corp/} precedes {@code acme/} - so a caller that needs names sorted sorts the page it holds. Two
 *     enumerations of an unchanged scope agree exactly, and a name never repeats within one answer or across the
 *     pages of one resumed enumeration.</li>
 * <li><b>Bounded work / cancellation.</b> Every enumeration is bounded and resumable: {@link #projects},
 *     {@link #entries} and {@link #listDir} deliver at most the caller's {@code limit} names or entries, stream them
 *     one at a time rather than returning a collection, and answer a {@link Traversal.Result} - {@code EXHAUSTED}
 *     when the scope was seen whole, {@code TRUNCATED} plus a continuation cursor when it was not. A short answer is
 *     therefore never silent: the outcome carries the truncation, and {@link Traversal.Result} makes "truncated
 *     without a cursor" and "exhausted with one" unrepresentable, so a caller cannot mistake a page for a listing.
 *     The bias is one-way, as it is for the free core's traversals: a backend may under-claim completeness and cost
 *     the caller one extra empty round, and may never over-claim it. There is deliberately no whole-store sweep in
 *     this SPI - the union across projects is composed by the one caller that needs it (the free-space reclaim),
 *     project by project, so no backend is asked to materialise every entry of every project in heap. A backend must
 *     keep a {@link Stored} small (size, instant, token), must never retain the bodies, and must hold no more than
 *     {@code limit} of them at once. A caller cancels by throwing from the consumer.</li>
 * <li><b>Durability / delivery.</b> {@link #store} commits atomically at a single visible instant: a reader observes
 *     either no entry or the whole entry, never a prefix, and a source that fails mid-stream commits nothing at all.
 *     {@link #writeConfig} and {@link #writeFile} are atomic in the same sense. {@link #writeFileVersioned} is the
 *     compare-and-set: it commits only if the stored version still matches, and a {@code false} return means the
 *     durable state is untouched.</li>
 * </ol>
 */
public interface CacheStorage {

    /**
     * A view of this storage confined to one tenant's subspace - a subdirectory on a filesystem, a
     * key prefix on an object store - so that every project, entry and {@code .users/} path resolved
     * through the returned view is isolated under {@code <tenant>/}. The whole interface (auth,
     * eviction, provisioning) then operates within a single tenant unchanged. The tenant name must be
     * a valid {@link Names#isTenant tenant name}, which is also what makes it a traversal-free segment.
     */
    CacheStorage scope(String tenant);

    /** A cache entry addressed by project and the two hex path segments {@code step}/{@code inputs}. */
    record Entry(String project, String step, String inputs) {
    }

    /** An enumerated stored blob: its byte {@code size}, {@code recency}, and an opaque backend {@code token}. */
    record Stored(long size, Instant recency, Object token) {
    }

    /** Whether a project container of this name exists. */
    boolean projectExists(String project);

    /** Read a project config file ({@code cache.properties}); empty if absent. */
    Properties readConfig(String project, String file);

    /** Opaque token capturing the current version of a project's {@code cache.properties}, for the server's config cache. */
    Object configVersion(String project);

    /** Whether the entry blob exists. */
    boolean exists(Entry entry);

    /**
     * When an entry was last used, as the store records it: the newest stamp when it has one, marked as such; else
     * the entry's own time - the moment this backend received it, which is what {@link #entries} reports for an
     * entry never stamped, so a point answer and the enumeration agree; empty when the entry is not there.
     *
     * <p>A stamp is data beside the entry - an empty object at {@code <step>/<inputs>.used/<instant>}, the instant in
     * its name - and never a backend's own modification time. That is what makes it the same on every backend, lets
     * {@link #entries} fold it out of the one listing it already makes, and lets a store copied from a filesystem into
     * a bucket keep every entry's recency, which a copy of modification times does not. Two point reads at most, bounded
     * to the entry - its own stamps, then the entry itself - never a listing of the project. Whether the answer is a
     * stamp is what tells a caller renewing recency whether there is one to retire.
     */
    Optional<Recency> recency(Entry entry);

    /** An entry's recency as {@link #recency} reports it: the instant, and whether a stamp records it (the caller
     *  renewing it then retires that stamp) or the entry's own time does (there is nothing to retire). */
    record Recency(Instant at, boolean stamped) {
        public Recency {
            Objects.requireNonNull(at, "at");
        }
    }

    /**
     * Record that the entry was used at {@code at}: write its stamp, then remove the {@code previous} one when the
     * caller knows it, so an entry keeps one stamp in the steady state. Stamping an absent entry writes nothing - a
     * stamp with no entry behind it would be invisible to the enumeration and immortal. Advisory, like the read it
     * follows: a failure to stamp never fails that read.
     */
    void stamp(Entry entry, Instant at, Instant previous);

    /** Stream the entry blob to {@code out}. */
    void read(Entry entry, OutputStream out) throws IOException;

    /** Atomically store the entry blob from {@code in}, so a reader never observes a partial write. */
    void store(Entry entry, InputStream in) throws IOException;

    /**
     * The bound a caller uses when it has no reason of its own to pick one: large enough that a page is a useful unit
     * of work, small enough that one call's working set is a bounded, committable allocation whatever the store holds.
     * It is the same order as the free core's shared walk caps, because it answers the same question - how large may
     * one bounded read's working set get.
     */
    int PAGE = 1_000;

    /**
     * Deliver up to {@code limit} project container names to {@code names}, starting strictly after {@code cursor},
     * and report whether that was every project or only a page of them.
     *
     * <p>The continuation {@link Traversal.Result#cursor() cursor} is the last delivered project's key - here the bare
     * project name - and is handed back verbatim to resume; {@code null} or the empty string starts at the beginning.
     * A resume continues strictly after the last delivered name, so no project is delivered twice and none between two
     * pages is skipped. A {@code limit} that is not positive is an {@link IllegalArgumentException} rather than an
     * empty page, which a caller would read as an empty store.
     */
    Traversal.Result projects(String cursor, int limit, Consumer<String> names);

    /**
     * Deliver up to {@code limit} of one project's stored entries to {@code entries}, starting strictly after
     * {@code cursor}, and report whether that was all of them. The cursor is the entry's key relative to this scope -
     * {@code <project>/<step>/<inputs>} - so it names the project it belongs to and cannot be replayed against a
     * different one: a cursor that is not a key under {@code project} is an {@link IllegalArgumentException}.
     *
     * <p>This is the enumeration an eviction pass drives, and the one that decides how much heap a reclaim costs: a
     * project holding millions of entries is walked in {@code limit}-sized pages, never materialised as one list the
     * way a size-cap sweep once did on the way to sorting it.
     */
    Traversal.Result entries(String project, String cursor, int limit, Consumer<Stored> entries);

    /** Delete a previously enumerated stored blob (and tidy any now-empty container it leaves behind). */
    void delete(Stored entry);

    /** Free bytes on the backing volume; a backend without a capacity limit returns {@link Long#MAX_VALUE}. The
     *  sentinel is a <em>declaration</em>, never an admission that the probe failed: a backend that has a volume and
     *  cannot measure it throws (see the contract's error-visibility clause). */
    long usableSpace();

    /** Total bytes on the backing volume; a backend without a capacity limit returns 0, paired with the
     *  {@link #usableSpace} sentinel - the two are answered together or not at all. */
    long totalSpace();

    // --- Project provisioning (used by the ui) ---

    /** Create a project container (a directory on the filesystem; a no-op for object stores). */
    void createProject(String project) throws IOException;

    /** Atomically write a project config file ({@code cache.properties}), so a reader never sees a partial write. */
    void writeConfig(String project, String file, Properties properties) throws IOException;

    // --- Config files addressed by relative path (the {@code .users/} access tree) ---

    /** Read a config file at a relative path (nested allowed, e.g. {@code .users/<hash>/projects.properties}); empty if absent. */
    Properties readFile(String path);

    /** Atomically write a config file at a relative path, creating parents as needed. */
    void writeFile(String path, Properties properties) throws IOException;

    /**
     * Compare-and-set a config file: write it only if the stored version still matches {@code expected}
     * (an {@code expected} of {@code null} requires the file be absent), returning {@code false} on a
     * mismatch so the caller can re-read and retry. This is how a shared per-tenant document (the console
     * a tenant's per-project cache configuration, written concurrently by an operator and a build)
     * stays consistent under concurrent read-modify-write without a lock or database - the same
     * {@code writeVersioned} compare-and-set the artifact store exposes, mirrored for the console's config
     * tree. The version token is the one {@link #fileVersion} reports; read the token <em>before</em> the
     * body so a write landing in between pairs the old token with the new body and loses the compare-and-set
     * (the safe direction). Every backend gets a true cross-node compare-and-set, because this delegates to the
     * artifact store's: an object store's from its ETag or generation, and the filesystem's from a last-modified
     * and digest token guarded by an operating-system lock held from the comparison to the move, so several nodes
     * on one shared mount lose no update. This javadoc said the filesystem's was "adequate for a single node" long
     * after that stopped being true - a restated claim about another type's contract, which is the shape that
     * drifts; the contract is {@code ArtifactStore.writeVersioned} and it is stated there.
     */
    boolean writeFileVersioned(String path, Properties properties, Object expected) throws IOException;

    /** An opaque version token for a file (its write time/etag), or null if absent; for revalidating caches. */
    Object fileVersion(String path);

    /**
     * Deliver up to {@code limit} of the immediate child container names (directories/prefixes) under a relative path
     * to {@code names}, starting strictly after {@code cursor}, and report whether that was all of them. The cursor is
     * the child's key, {@code <prefix>/<name>} - or the bare name under the scope root, where a child's name already
     * is its key - and a cursor that is not an immediate child key of {@code prefix} is an
     * {@link IllegalArgumentException}.
     *
     * <p>The scope root is a legal prefix and is where the console's tenant listing sits, so an accidentally empty
     * prefix enumerates every tenant: pass it deliberately.
     */
    Traversal.Result listDir(String prefix, String cursor, int limit, Consumer<String> names);

    /** Recursively delete everything under a relative path. */
    void deleteDir(String path) throws IOException;
}
