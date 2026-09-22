package build.jenesis.repository.cache.storage.delegating;

import module java.base;

import build.jenesis.repository.cache.storage.CacheStorage;
import build.jenesis.repository.cache.storage.Names;
import build.jenesis.repository.cache.storage.Pages;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Documents;
import build.jenesis.repository.walk.Traversal;

/**
 * The one {@link CacheStorage} implementation: the build cache's domain model expressed over an
 * {@link ArtifactStore}, so a cache entry is an object in the same keyed byte store an artifact is.
 *
 * <h2>Why this exists</h2>
 *
 * The cache used to ship its own {@code s3}, {@code gcs}, {@code azure-blob} and {@code filesystem} backends - about
 * two thousand lines that were a second copy of the artifact store's: the same SDK client build, the same endpoint
 * screen, the same credential chain, the same conditional write, the same paginator. Two copies of one thing drift,
 * and these had: until the settings were unified they read the same values under different key spellings, so an
 * operator who configured by property rather than by environment variable configured one and left the other empty.
 *
 * <p>{@link CacheStorage} stays an SPI, because a cache store that is genuinely NOT an artifact store - a Redis tier,
 * an ephemeral node-local disk with different durability - is a thing a deployment could want, and the seam is cheap
 * to keep and expensive to reintroduce. What goes is the duplicated storage beneath it, not the interface above it.
 *
 * <h2>The mapping</h2>
 *
 * A cache entry is the key {@code <project>/<step>/<inputs>}; a project's policy is {@code <project>/cache.properties};
 * the console's access tree is the {@code .users/} paths it already used. Those are the keys the cache backends wrote
 * before this class existed, at the same prefix under the same tenant scope, so the layout is unchanged and no
 * deployment migrates anything. The one exception is deliberate and is the provider's business, not this class's: the
 * filesystem cache root and the artifact-store root are different directories by design.
 *
 * <h2>What this class may not cost</h2>
 *
 * Storage is on the hot path, so the delegation is held to the round-trip count the hand-written backends had. Two
 * places would otherwise have regressed, and both are why {@link ArtifactStore} grew the primitives it did:
 * {@link #entries} takes each entry's size and recency from the listing that enumerated it, rather than stat-ing once
 * per entry on the pass that walks the whole cache; and {@link #fileVersion} / {@link #configVersion} ask
 * {@link ArtifactStore#version} for a token rather than downloading an object to read one off it.
 */
public final class DelegatingCacheStorage implements CacheStorage {

    /** The per-project policy document, and the file {@link #configVersion} reports the version of. */
    private static final String CACHE_PROPERTIES = "cache.properties";

    /** The provisioning marker {@link #createProject} writes - see there for why it exists. */
    public static final String PROJECT_PROPERTIES = "project.properties";

    /** When the project was provisioned, in the marker. */
    public static final String CREATED = "created";

    private final ArtifactStore store;

    public DelegatingCacheStorage(ArtifactStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public CacheStorage scope(String tenant) {
        // The store's own segment screen would admit names the cache does not, so the cache's rule is applied here:
        // a tenant subspace is named by the same predicate every other tenant-scoped surface uses.
        if (!Names.isTenant(tenant)) {
            throw new IllegalArgumentException("Not a tenant name: " + tenant);
        }
        return new DelegatingCacheStorage(store.scope(tenant));
    }

    // ---- entries -------------------------------------------------------------------------------------------------

    /**
     * The shared write-path addressability screen, applied before the delegate is touched. A store key is opaque, so
     * without it an unaddressable entry is stored LITERALLY, at a key {@link #entries} then skips forever - invisible
     * to the size cap, the ttl and the free-space sweep. {@link Names#isEntry} is the same predicate the enumeration
     * applies, so what can be written is exactly what can be found again.
     */
    private static String key(Entry entry) {
        return entry.project() + "/" + entry.step() + "/" + entry.inputs();
    }

    /** The write path's screen; see {@link #addressable}. */
    private static String requireEntry(Entry entry) {
        if (!Names.isEntry(entry)) {
            throw new IllegalArgumentException("Unaddressable cache entry: " + entry);
        }
        return key(entry);
    }

    /** The same screen for a relative config path: nesting is legal, escaping the scope is not. */
    private static String path(String path) {
        if (!Names.isPath(path)) {
            throw new IllegalArgumentException("Unaddressable cache path: " + path);
        }
        return path;
    }

    /** The same screen for the project-config pair, whose file name is one segment inside the container. */
    private static String config(String project, String file) {
        if (!Names.isProject(project) || !Names.isFile(file)) {
            throw new IllegalArgumentException("Unaddressable cache config file: " + project + "/" + file);
        }
        return project + "/" + file;
    }

    private static String project(String project) {
        if (!Names.isProject(project)) {
            throw new IllegalArgumentException("Unaddressable cache project: " + project);
        }
        return project;
    }

    /**
     * The screens above are the WRITE path's. A read is deliberately lenient: an unaddressable name reads as absent -
     * empty properties, a null version, no entries - because that is what the hand-written backends answered and
     * because it is the honest answer. Nothing can have been stored under a name the write path refuses, so "there is
     * nothing there" is true, and a caller asking about a name a user typed should get an empty page rather than an
     * exception it would have to catch to render one.
     */
    private static boolean addressable(String path) {
        return Names.isPath(path);
    }

    @Override
    public boolean exists(Entry entry) {
        return Names.isEntry(entry) && store.exists(key(entry));
    }

    // ---- recency stamps ----------------------------------------------------------------------------------------

    /** The container of an entry's stamps, beside the entry: {@code <entry key>.used/}. It sorts directly after the
     *  entry in a recursive listing (the entry's key is its prefix), which is what lets {@link #entries} fold a
     *  stamp into its entry from the same page. */
    private static final String STAMPS = ".used/";

    /** How many stamps of one entry a point read looks at. The steady state is one; a renewal leaves two for a
     *  moment; more than this is a backend that lost deletes, and the newest may then be missed - which costs one
     *  redundant stamp, never a wrong eviction of a hot entry, since a hit always stamps when in doubt. */
    private static final int STAMPS_SCANNED = 8;

    private static String stamps(Entry entry) {
        return key(entry) + STAMPS;
    }

    /** The stamp's name is its instant, zero-padded so names sort as instants do. */
    private static String stampKey(Entry entry, Instant at) {
        return stamps(entry) + String.format(Locale.ROOT, "%019d", at.getEpochSecond());
    }

    private static Instant stampInstant(String name) {
        try {
            return Instant.ofEpochSecond(Long.parseLong(name));
        } catch (NumberFormatException _) {
            return null;
        }
    }

    /** For a project-relative key of the shape {@code <step>/<inputs>.used/<stamp>}: the entry's relative key and
     *  the stamp's instant; {@code null} for anything else. */
    private static Map.Entry<String, Instant> stampShaped(String relative) {
        int used = relative.indexOf(STAMPS);
        if (used < 0 || !entryShaped(relative.substring(0, used))) {
            return null;
        }
        Instant at = stampInstant(relative.substring(used + STAMPS.length()));
        return at == null ? null : Map.entry(relative.substring(0, used), at);
    }

    @Override
    public Optional<Recency> recency(Entry entry) {
        if (!Names.isEntry(entry)) {
            return Optional.empty();
        }
        Instant[] newest = new Instant[1];
        try {
            store.scan(stamps(entry), null, STAMPS_SCANNED, listed -> {
                Instant at = stampInstant(listed.key().substring(listed.key().lastIndexOf('/') + 1));
                if (at != null && (newest[0] == null || at.isAfter(newest[0]))) {
                    newest[0] = at;
                }
            });
            if (newest[0] != null) {
                return Optional.of(new Recency(newest[0], true));
            }
            // Never stamped: the entry's own time, the same fallback the enumeration folds in, by one point read of
            // the entry itself. A backend that lists no time reads as the epoch - past any window, so stamped on use.
            return store.listed(key(entry))
                    .map(listed -> new Recency(listed.modified().orElse(Instant.EPOCH), false));
        } catch (IOException _) {
            return Optional.empty();    // unknown reads as "stamp it", never as a failed read
        }
    }

    @Override
    public void stamp(Entry entry, Instant at, Instant previous) {
        if (!Names.isEntry(entry)) {
            return;
        }
        try {
            if (!store.exists(key(entry))) {
                return;     // a stamp with no entry behind it would be invisible to the enumeration and immortal
            }
            store.write(stampKey(entry, at), InputStream.nullInputStream());
            if (previous != null && previous.getEpochSecond() != at.getEpochSecond()) {
                store.delete(stampKey(entry, previous));
            }
        } catch (IOException _) {
            // Recency is advisory: a failure to record a use must not fail the read that was the use.
        }
    }

    @Override
    public void read(Entry entry, OutputStream out) throws IOException {
        store.read(key(entry), out);
    }

    @Override
    public void store(Entry entry, InputStream in) throws IOException {
        store.write(requireEntry(entry), in);
    }

    @Override
    public Traversal.Result entries(String project, String cursor, int limit, Consumer<Stored> entries) {
        Pages.limit(limit);
        if (!Names.isProject(project)) {
            return Traversal.Result.exhausted(0, 0);    // nothing can have been stored under a refused name
        }
        String after = Pages.entry(project, cursor);
        // One recursive listing, and the size and recency ride along in it - the property this delegation had to keep.
        // Collecting limit + 1 is what makes the truncation answer exact rather than merely safe, exactly as the
        // hand-written backends did: the extra record proves there is more without a second request asking.
        SequencedMap<String, Folded> page = new LinkedHashMap<>();
        long steps = 0;
        String scanCursor = after.isEmpty() ? "" : project + "/" + after;
        try {
            while (page.size() <= limit) {
                ArtifactStore.Scan scan = store.scan(project, scanCursor, ArtifactStore.oneMoreThan(limit) - page.size(), listed -> {
                    String relative = listed.key().substring(project.length() + 1);
                    if (entryShaped(relative)) {
                        page.put(relative, new Folded(listed));
                        return;
                    }
                    // An entry's stamps list directly after it, so they fold into the entry already on the page; a
                    // stamp whose entry is not on it belongs to the entry a resumed page started after, which was
                    // delivered with its stamps by the page before - or to no entry at all, and is nothing.
                    Map.Entry<String, Instant> stamp = stampShaped(relative);
                    if (stamp != null) {
                        Folded owner = page.get(stamp.getKey());
                        if (owner != null) {
                            owner.stamped(listed.key(), stamp.getValue());
                        }
                    }
                });
                steps += scan.steps();
                if (!scan.truncated()) {
                    break;
                }
                scanCursor = scan.cursor().orElseThrow();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not enumerate the entries of project " + project, e);
        }
        SequencedMap<String, Stored> folded = new LinkedHashMap<>();
        page.forEach((relative, entry) -> folded.put(relative, entry.stored()));
        return Pages.entries(project, folded, limit, steps, entries);
    }

    /** An entry as the listing delivered it, gathering the stamps that follow it; recency is the newest stamp, and
     *  only without one the listing's modification time - so a stamped entry's recency is the same on every backend
     *  and survives a copy of the store, while a never-read entry ages from when this backend received it. */
    private static final class Folded {

        private final ArtifactStore.Listed listed;
        private final List<String> stamps = new ArrayList<>();
        private Instant newest;

        private Folded(ArtifactStore.Listed listed) {
            this.listed = listed;
        }

        private void stamped(String key, Instant at) {
            stamps.add(key);
            if (newest == null || at.isAfter(newest)) {
                newest = at;
            }
        }

        private Stored stored() {
            Instant recency = newest != null ? newest : listed.modified().orElse(Instant.EPOCH);
            return new Stored(listed.size().orElse(0L), recency, new Located(listed.key(), List.copyOf(stamps)));
        }
    }

    /** The deletion token: the entry's store key and the stamps that go with it, so a delete leaves no stamp behind
     *  for an entry that is gone. Opaque to every caller, which is why it may carry more than a key; its text is the
     *  key alone, which is what a caller that shows a token has always shown. */
    private record Located(String key, List<String> stamps) {

        @Override
        public String toString() {
            return key;
        }
    }

    /** Whether a project-relative key is an ENTRY rather than the project's own {@code cache.properties} or anything
     *  else that shares the prefix: exactly {@code <step>/<inputs>}, both hex, no deeper. The hand-written backends
     *  applied the same shape test to the same listing, and it is what keeps a policy document out of an eviction
     *  pass's candidate set. */
    private static boolean entryShaped(String relative) {
        int slash = relative.indexOf('/');
        if (slash < 0) {
            return false;
        }
        String step = relative.substring(0, slash);
        String inputs = relative.substring(slash + 1);
        return inputs.indexOf('/') < 0 && Names.isHex(step) && Names.isHex(inputs);
    }

    @Override
    public void delete(Stored entry) {
        Located located = (Located) entry.token();
        try {
            store.delete(located.key());
            for (String stamp : located.stamps()) {
                store.delete(stamp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not delete " + located.key(), e);
        }
    }

    // ---- projects ------------------------------------------------------------------------------------------------

    @Override
    public boolean projectExists(String project) {
        // Anything under the prefix, in one listing capped at a single entry. A project does not have to be
        // provisioned to exist - a build writing an entry brings it into being, and the console must see it - so this
        // deliberately does not key on a marker. A provisioned-but-empty project is covered by the same call, because
        // provisioning writes cache.properties, which is an object under the prefix.
        try {
            return Names.isProject(project) && store.scan(project, "", 1, _ -> { }).delivered() > 0;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not probe project " + project, e);
        }
    }

    /**
     * Provision a project by writing its marker.
     *
     * <p>A keyed store has no such thing as an empty container - a directory is an artefact of a filesystem, and on
     * an object store it simply does not exist until something is under it. So provisioning writes a small document
     * that IS the project's existence: without it, a project an operator created and has not yet pushed to would
     * vanish from the console the moment the page reloaded.
     *
     * <p>It carries {@code created}, which nothing recorded before - a project's age was previously unknowable, and
     * a marker that exists anyway may as well answer the question. A description or anything else an operator sets
     * later lives here too, and is read back through the ordinary {@link #readConfig}, so this needs no new API.
     *
     * <p>Provisioning does NOT define existence, only guarantees it: {@link #projectExists} asks whether anything is
     * under the prefix, because a build that pushes to an unprovisioned project brings it into being and the console
     * has to see that one too.
     */
    @Override
    public void createProject(String project) throws IOException {
        String key = project(project) + "/" + PROJECT_PROPERTIES;
        if (store.exists(key)) {
            return;         // idempotent: re-provisioning must not restamp a project's creation time
        }
        Properties marker = new Properties();
        marker.setProperty(CREATED, Instant.now().toString());
        store.write(key, new ByteArrayInputStream(Documents.bytes(marker)));
    }

    @Override
    public Traversal.Result projects(String cursor, int limit, Consumer<String> names) {
        Pages.limit(limit);
        return children("", cursor, limit, names, Names::isProject);
    }

    // ---- configuration files -------------------------------------------------------------------------------------

    @Override
    public Properties readConfig(String project, String file) {
        return Names.isProject(project) && Names.isFile(file)
                ? readFile(project + "/" + file)
                : new Properties();
    }

    @Override
    public Object configVersion(String project) {
        return Names.isProject(project) ? fileVersion(project + "/" + CACHE_PROPERTIES) : null;
    }

    @Override
    public void writeConfig(String project, String file, Properties properties) throws IOException {
        writeFile(config(project, file), properties);
    }

    @Override
    public Properties readFile(String path) {
        try {
            Properties properties = new Properties();
            if (!addressable(path)) {
                return properties;
            }
            Optional<ArtifactStore.Versioned> stored = store.readVersioned(path);
            if (stored.isPresent()) {
                properties.load(new ByteArrayInputStream(stored.get().content()));
            }
            return properties;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + path, e);
        }
    }

    @Override
    public void writeFile(String path, Properties properties) throws IOException {
        store.write(path(path), new ByteArrayInputStream(Documents.bytes(properties)));
    }

    @Override
    public boolean writeFileVersioned(String path, Properties properties, Object expected) throws IOException {
        return store.writeVersioned(path(path), Documents.bytes(properties), expected);
    }

    @Override
    public Object fileVersion(String path) {
        try {
            // A token WITHOUT the body: the object stores answer this with a metadata request, where reading the
            // object to take its token off it would download a document on every revalidation.
            return addressable(path) ? store.version(path).orElse(null) : null;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the version of " + path, e);
        }
    }

    // ---- directories ---------------------------------------------------------------------------------------------

    @Override
    public Traversal.Result listDir(String prefix, String cursor, int limit, Consumer<String> names) {
        Pages.limit(limit);
        return children(prefix, cursor, limit, names, _ -> true);
    }

    /**
     * The shared body of {@link #projects} and {@link #listDir}: one page of immediate child CONTAINERS under a
     * prefix, filtered, with the extra name that makes the truncation answer exact rather than merely safe.
     *
     * <p>Containers only, in both callers: a project is a container of entries and a {@code .users/} node is a
     * container of documents, so a leaf document sitting beside them is not a child either surface may report. The
     * delegate has no listing that distinguishes the two - {@code page} merges a blob and a same-named container into
     * one name by design - so a child is a container iff something is under it, which is one bounded probe per
     * candidate. That is the one place this delegation costs a call the hand-written backends did not make, and it is
     * bounded by the page size rather than by the container's size.
     */
    private Traversal.Result children(String prefix, String cursor, int limit, Consumer<String> names,
                                      Predicate<String> keep) {
        String after = Pages.child(prefix, cursor);
        List<String> page = new ArrayList<>();
        long steps = 0;
        String pageCursor = after;
        try {
            while (page.size() <= limit) {
                List<String> batch = new ArrayList<>();
                store.page(prefix, pageCursor, ArtifactStore.oneMoreThan(limit), batch::add);
                steps++;
                for (String name : batch) {
                    if (page.size() > limit) {
                        break;
                    }
                    if (keep.test(name) && container(prefix, name)) {
                        page.add(name);
                    }
                }
                if (batch.size() < ArtifactStore.oneMoreThan(limit)) {
                    break;              // the underlying page was short, so the container is drained
                }
                pageCursor = batch.getLast();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not list the children of '" + prefix + "'", e);
        }
        return Pages.names(prefix, page, limit, steps, names);
    }

    /** Whether a child name is a container: one bounded scan that stops at the first key beneath it. */
    private boolean container(String prefix, String name) throws IOException {
        String child = prefix.isEmpty() ? name : prefix + "/" + name;
        return store.scan(child, "", 1, _ -> { }).delivered() > 0;
    }

    @Override
    public void deleteDir(String path) throws IOException {
        String root = path(path);
        String cursor = "";
        while (true) {
            List<String> keys = new ArrayList<>();
            ArtifactStore.Scan scan = store.scan(root, cursor, PAGE, listed -> keys.add(listed.key()));
            for (String key : keys) {
                store.delete(key);
            }
            if (!scan.truncated()) {
                return;
            }
            // Deleting as we go means the next page starts where this one ended rather than re-listing from the top,
            // and a crash halfway leaves a partially deleted tree that a re-run finishes - which is what a recursive
            // delete can promise on a store with no atomic subtree operation.
            cursor = scan.cursor().orElseThrow();
        }
    }

    // ---- capacity ------------------------------------------------------------------------------------------------

    @Override
    public long usableSpace() {
        return capacity().map(ArtifactStore.Capacity::usable).orElse(Long.MAX_VALUE);
    }

    @Override
    public long totalSpace() {
        return capacity().map(ArtifactStore.Capacity::total).orElse(0L);
    }

    /** The pair is answered together or not at all, and the sentinels above are this interface's way of saying "this
     *  backend has no volume" - the store says it by answering empty. */
    private Optional<ArtifactStore.Capacity> capacity() {
        try {
            return store.capacity();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not measure the backing volume", e);
        }
    }
}
