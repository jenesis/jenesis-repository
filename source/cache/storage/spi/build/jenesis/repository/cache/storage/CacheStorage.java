package build.jenesis.repository.cache.storage;

import module java.base;

import build.jenesis.repository.store.RepositoryDocument;
import build.jenesis.repository.walk.Traversal;

/**
 * The storage backend of the cache server: everything the server persists or enumerates goes through this interface, so
 * the request, authorisation, metrics and eviction-policy code is independent of where entries live. The default is
 * {@code DelegatingCacheStorage} over a segment of the repository's artifact store. The server keeps the eviction
 * <em>policy</em> (which entries a size cap, ttl or free-space target drops); the backend supplies the
 * <em>mechanics</em>: blob read and write, enumeration with size and recency, deletion, and the free-space numbers.
 *
 * <p>Recency is data, never a backend's own time: {@link #stamp} writes an empty object beside the entry whose name is
 * the instant, {@link #recency} reads the newest back, and {@link #entries} folds it from the same listing, so a read
 * counts as use on every backend and a store copied between backends keeps it. An entry never stamped ages from its
 * write time.
 *
 * <h2>Contract</h2>
 * <ol>
 *   <li><b>Thread-safety.</b> One instance is shared by every request thread and the reaper, so every method is safe to
 *       call concurrently. {@link #scope} hands out an independent view, and no method publishes half-written
 *       state.</li>
 *   <li><b>Idempotency / replay.</b> {@link #store} is last-writer-wins and replayable; a repeated {@link #delete}
 *       converges silently, since the reaper re-runs over snapshots a concurrent pass may have drained.
 *       {@link #createProject}, {@link #typeProject}, {@link #deleteDir} and {@link #stamp} are idempotent too.</li>
 *   <li><b>Absence sentinel.</b> A read never throws on absence and never returns {@code null} where a value is due:
 *       {@link #exists} and {@link #projectExists} answer {@code false}; {@link #project} an empty {@link Optional};
 *       {@link #readConfig} and {@link #readFile} an
 *       empty {@link Properties}; {@link #projects}, {@link #entries} and {@link #listDir} deliver nothing and answer
 *       {@linkplain Traversal.Result#exhausted() exhausted}, so an absent container is drained, not truncated.
 *       {@link #fileVersion} answers {@code null}, a version token having no absent value of its own. {@link #read} of
 *       an absent entry throws {@link IOException} rather than streaming an empty body a client would cache as a
 *       hit.</li>
 *   <li><b>Addressability.</b> An address that cannot be stored is refused before any I/O with
 *       {@link IllegalArgumentException}: {@link #store} an entry that is not a
 *       {@link Names#isEntry project and two hex segments}, the config-tree methods a path that is not
 *       {@link Names#isPath traversal-free} (or, for the project config pair, not a
 *       {@link Names#isFile plain file name}). A read of an unaddressable name degrades to absence, so older data stays
 *       readable and deletable. An object stored at such a key would be invisible to every enumeration, never counted,
 *       aged out or reclaimed.</li>
 *   <li><b>Streaming.</b> {@link #read} and {@link #store} stream; an entry blob is never materialised in heap. Only
 *       the small {@link Properties} documents are read whole.</li>
 *   <li><b>Tenant scoping.</b> {@link #scope} is the only tenant boundary, and a confinement: a scoped view can neither
 *       read nor write outside its subspace, and a sibling sees none of it. The tenant must be a
 *       {@link Names#isTenant valid tenant name}, or {@link IllegalArgumentException}.</li>
 *   <li><b>Error visibility.</b> {@link #delete}, {@link #stamp} and the {@code read*} methods are best-effort: the
 *       blast radius is an entry kept a little longer, a recency not advanced, or a project read as unconfigured. A
 *       failure of {@link #store}, {@link #writeConfig}, {@link #writeFile} or {@link #writeFileVersioned} surfaces,
 *       since a later read would trust what was never written. {@link #usableSpace} and {@link #totalSpace} surface
 *       too: their only degraded answer is the unlimited sentinel, which switches the free-space sweep off and lets the
 *       volume fill to a permanent {@code 507}, so a backend that has a volume and cannot measure it throws.</li>
 *   <li><b>Read purity.</b> Every read renders stored state; none provisions containers, writes metadata beyond
 *       {@link #stamp}, or reaches outside the backend.</li>
 *   <li><b>Staleness.</b> {@link Stored#recency} is "when was this last used": the newest stamp, else the backend's
 *       write time, on every backend, so eviction is least-recently-used everywhere. {@link #fileVersion} is the
 *       revalidation token a cached read compares.</li>
 *   <li><b>Lifecycle / ownership.</b> An instance owns no client, pool or thread: those are the store's it
 *       delegates to, held for the life of the application. Neither it nor a {@link #scope} view needs closing.</li>
 *   <li><b>Ordering / determinism.</b> {@link #projects}, {@link #entries} and {@link #listDir} deliver in the
 *       lexicographic byte order of the enumerated <em>key</em>, a container's key ending in the separator
 *       ({@code <name>/}) and an entry's not ({@code <project>/<step>/<inputs>}): the order object stores list in, so a
 *       cursor resumes with a server-side seek; a filesystem backend sorts to match. It is not name order -
 *       {@code acme-corp/} precedes {@code acme/} - so a caller needing names sorted sorts its page. Two enumerations
 *       of an unchanged scope agree, and no name repeats within one answer or across one resumed enumeration.</li>
 *   <li><b>Bounded work / cancellation.</b> Every enumeration delivers at most {@code limit} items one at a time and
 *       answers a {@link Traversal.Result}: {@code EXHAUSTED} when the scope was seen whole, {@code TRUNCATED} with a
 *       cursor when not, so a short answer is never silent. A backend may under-claim completeness, never over-claim
 *       it. There is no whole-store sweep: the one caller needing the union across projects composes it project by
 *       project. A backend keeps a {@link Stored} small, never retains bodies, and holds at most {@code limit} at once.
 *       A caller cancels by throwing from the consumer.</li>
 *   <li><b>Durability / delivery.</b> {@link #store} commits atomically: a reader sees no entry or the whole entry, and
 *       a source failing mid-stream commits nothing. {@link #writeConfig} and {@link #writeFile} are atomic likewise.
 *       {@link #writeFileVersioned} is the compare-and-set: {@code false} means the durable state is untouched.</li>
 * </ol>
 */
public interface CacheStorage {

    /** A view confined to one tenant's subspace - a subdirectory on a filesystem, a key prefix on an object store - so
     *  every project, entry and {@code .users/} path resolved through it is isolated under {@code <tenant>/}. The
     *  tenant must be a {@link Names#isTenant valid tenant name}. */
    CacheStorage scope(String tenant);

    /** A cache entry addressed by project and the two hex path segments {@code step}/{@code inputs}. */
    record Entry(String project, String step, String inputs) {
    }

    /** An enumerated stored blob: its byte {@code size}, {@code recency}, and an opaque backend {@code token}. */
    record Stored(long size, Instant recency, Object token) {
    }

    /** Whether a project container of this name exists. */
    boolean projectExists(String project);

    /** Read a project's own file ({@code stats.properties}, the counts a pass records); empty if absent. A project's
     *  policy is its settings ({@link ProjectPolicy}), not a file. */
    Properties readConfig(String project, String file);

    /** Whether the entry blob exists. */
    boolean exists(Entry entry);

    /**
     * When an entry was last used: the newest stamp, marked as such, else the entry's own write time, as
     * {@link #entries} reports it; empty when the entry is absent.
     *
     * <p>A stamp is an empty object at {@code <step>/<inputs>.used/<instant>}, never a modification time, so it is the
     * same on every backend, folds out of the listing {@link #entries} already makes, and survives copying a store
     * between backends. At most two point reads, never a listing of the project. Whether the answer is a stamp tells a
     * caller renewing recency whether there is one to retire.
     */
    Optional<Recency> recency(Entry entry);

    /** An entry's recency: the instant, and whether a stamp records it (to retire on renewal) or the entry's own time
     *  does. */
    record Recency(Instant at, boolean stamped) {
        public Recency {
            Objects.requireNonNull(at, "at");
        }
    }

    /** Record that the entry was used at {@code at}: write its stamp, then remove {@code previous} when known, so an
     *  entry keeps one stamp. Stamping an absent entry writes nothing, since such a stamp would be invisible and
     *  immortal. Advisory: a failure never fails the read it follows. */
    void stamp(Entry entry, Instant at, Instant previous);

    /** Stream the entry blob to {@code out}. */
    void read(Entry entry, OutputStream out) throws IOException;

    /** Atomically store the entry blob from {@code in}, so a reader never observes a partial write. */
    void store(Entry entry, InputStream in) throws IOException;

    /** The bound a caller uses when it has no reason to pick one: a useful unit of work whose working set is a bounded
     *  allocation, the same order as the shared walk caps. */
    int PAGE = 1_000;

    /** Deliver up to {@code limit} project names to {@code names}, strictly after {@code cursor}, and report whether
     *  that was every project. The cursor is the last delivered project's name, handed back verbatim; {@code null} or
     *  empty starts at the beginning. A non-positive {@code limit} is an {@link IllegalArgumentException}, never an
     *  empty page read as an empty store. */
    Traversal.Result projects(String cursor, int limit, Consumer<String> names);

    /** Deliver up to {@code limit} of one project's entries to {@code entries}, strictly after {@code cursor}, and
     *  report whether that was all. The cursor is the entry's key, {@code <project>/<step>/<inputs>}, so it cannot be
     *  replayed against another project: that is an {@link IllegalArgumentException}. An eviction pass walks a project
     *  of millions of entries in pages, never as one list. */
    Traversal.Result entries(String project, String cursor, int limit, Consumer<Stored> entries);

    /** Delete a previously enumerated stored blob (and tidy any now-empty container it leaves behind). */
    void delete(Stored entry);

    /** Free bytes on the backing volume; a backend without a capacity limit returns {@link Long#MAX_VALUE}. The
     *  sentinel is a declaration, never a failed probe: a backend with a volume it cannot measure throws (the
     *  error-visibility clause). */
    long usableSpace();

    /** Total bytes on the backing volume; a backend without a limit returns 0, paired with the {@link #usableSpace}
     *  sentinel. */
    long totalSpace();

    // --- Project provisioning (used by the ui) ---

    /**
     * A project's own record: the build tool it is a cache for - the name of the cache protocol it answers, such as
     * {@code gradle} - its description, and when it was provisioned. {@code type} is {@code null} for a project nothing
     * has typed yet: a build that pushed to a name no one provisioned creates it untyped, and its first write types
     * it ({@link #typeProject}).
     */
    record Project(String type, String description, Instant created) {

        public Project {
            if (type != null && !type.matches("[a-z0-9-]+")) {
                throw new IllegalArgumentException("Not a build tool's name: " + type);
            }
            description = RepositoryDocument.description(description);
        }
    }

    /** Provision a project of {@code type} with its description, so a project nothing has pushed to yet exists. A
     *  project already provisioned keeps what it has: provisioning never restamps or retypes. */
    void createProject(String project, String type, String description) throws IOException;

    /** A project's record, or empty when it has none - nothing provisioned it and nothing has typed it. */
    Optional<Project> project(String project);

    /** Replace a project's description, keeping its type and creation time; a project with no record gets one, untyped.
     *  A compare-and-set, so a concurrent {@link #typeProject} is not lost. */
    void describeProject(String project, String description) throws IOException;

    /** Type a project that has no type yet, as the first write of a build tool to it does; a project that has one keeps
     *  it, so this never changes which tool a project answers. A compare-and-set, idempotent. */
    void typeProject(String project, String type) throws IOException;

    /** Atomically write a project's own file, so a reader never sees a partial write. */
    void writeConfig(String project, String file, Properties properties) throws IOException;

    // --- Config files addressed by relative path (the {@code .users/} access tree) ---

    /** Read a config file at a relative path (nested allowed, e.g. {@code .users/<hash>/projects.properties}); empty if absent. */
    Properties readFile(String path);

    /** Atomically write a config file at a relative path, creating parents as needed. */
    void writeFile(String path, Properties properties) throws IOException;

    /** Compare-and-set a config file: write it only if the stored version still matches {@code expected} ({@code null}
     *  requires absence), returning {@code false} on a mismatch so the caller re-reads and retries. This keeps a shared
     *  per-tenant document consistent under concurrent read-modify-write without a lock. The token is
     *  {@link #fileVersion}'s; read it before the body, so a write landing between loses the compare-and-set, the safe
     *  direction. Every backend gets a cross-node compare-and-set by delegating to
     *  {@code ArtifactStore.writeVersioned}, whose contract governs. */
    boolean writeFileVersioned(String path, Properties properties, Object expected) throws IOException;

    /** An opaque version token for a file (its write time/etag), or null if absent; for revalidating caches. */
    Object fileVersion(String path);

    /** Deliver up to {@code limit} immediate child container names under a relative path to {@code names}, strictly
     *  after {@code cursor}, and report whether that was all. The cursor is the child's key, {@code <prefix>/<name>},
     *  or the bare name at the scope root; one that is not a child key of {@code prefix} is an
     *  {@link IllegalArgumentException}. The scope root is a legal prefix and lists every tenant, so pass it
     *  deliberately. */
    Traversal.Result listDir(String prefix, String cursor, int limit, Consumer<String> names);

    /** Recursively delete everything under a relative path. */
    void deleteDir(String path) throws IOException;
}
