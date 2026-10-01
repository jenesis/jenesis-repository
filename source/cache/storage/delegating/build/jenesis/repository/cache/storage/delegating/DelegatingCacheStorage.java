package build.jenesis.repository.cache.storage.delegating;

import module java.base;

import build.jenesis.repository.cache.storage.CacheStorage;
import build.jenesis.repository.cache.storage.Names;
import build.jenesis.repository.cache.storage.Pages;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Documents;
import build.jenesis.repository.walk.Traversal;

/**
 * The one {@link CacheStorage} implementation: the build cache's model over an {@link ArtifactStore}, so a cache entry
 * is an object in the same keyed byte store an artifact is. The cache has no backends of its own, which would duplicate
 * the artifact store's SDK clients, endpoint screens, credential chains and conditional writes. {@link CacheStorage}
 * stays an SPI for a store that genuinely is not an artifact store - a Redis tier, an ephemeral local disk.
 *
 * <h2>The mapping</h2>
 *
 * An entry is the key {@code <project>/<step>/<inputs>}; a project's own files are {@code <project>/<file>} and its
 * settings {@code <project>/.system/config/settings/<module>.json}; the console's access tree is the {@code .users/}
 * paths, under the same tenant scope.
 *
 * <h2>What it may not cost</h2>
 *
 * Storage is on the hot path, so the delegation keeps the round trips low: {@link #entries} takes each entry's size and
 * recency from the listing that enumerated it rather than a stat per entry, and {@link #fileVersion} asks
 * {@link ArtifactStore#version} for a token rather than downloading the object.
 */
public final class DelegatingCacheStorage implements CacheStorage {

    /** The provisioning marker {@link #createProject} writes. */
    public static final String PROJECT_PROPERTIES = "project.properties";

    /** When the project was provisioned, in the marker. */
    public static final String CREATED = "created";

    private final ArtifactStore store;

    public DelegatingCacheStorage(ArtifactStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public CacheStorage scope(String tenant) {
        // The store's segment screen admits names the cache does not, so a tenant is named by the predicate every other
        // tenant-scoped surface uses.
        if (!Names.isTenant(tenant)) {
            throw new IllegalArgumentException("Not a tenant name: " + tenant);
        }
        return new DelegatingCacheStorage(store.scope(tenant));
    }

    // ---- entries

    /** An entry's store key, {@code <project>/<step>/<inputs>}. */
    private static String key(Entry entry) {
        return entry.project() + "/" + entry.step() + "/" + entry.inputs();
    }

    /** The write path's screen, applied before the delegate is touched. A store key is opaque, so an unaddressable
     *  entry would be stored literally at a key {@link #entries} then skips forever - invisible to the size cap, the
     *  ttl and the free-space sweep. {@link Names#isEntry} is the enumeration's own predicate, so what can be written
     *  can be found again. */
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

    /** The read path's leniency: an unaddressable name reads as absent - empty properties, a null version, no entries -
     *  because nothing can be stored under a name the write path refuses, and a caller asking about a typed name should
     *  get an empty page rather than an exception. */
    private static boolean addressable(String path) {
        return Names.isPath(path);
    }

    @Override
    public boolean exists(Entry entry) {
        return Names.isEntry(entry) && store.exists(key(entry));
    }

    // ---- recency stamps

    /** The container of an entry's stamps, {@code <entry key>.used/}. It sorts directly after the entry in a recursive
     *  listing, so {@link #entries} folds a stamp into its entry from the same page. */
    private static final String STAMPS = ".used/";

    /** How many of an entry's stamps a point read examines. The steady state is one, a renewal briefly leaves two; past
     *  this the newest may be missed, which costs one redundant stamp and never evicts a hot entry, since a hit stamps
     *  when in doubt. */
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
            // Never stamped: the entry's own time, by one point read - the enumeration's fallback. A backend listing no
            // time reads as the epoch, past any window, so it is stamped on use.
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
        // One recursive listing carries size and recency. Collecting limit + 1 makes the truncation answer exact: the
        // extra record proves there is more without a second request.
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
                    // An entry's stamps list directly after it and fold into it on this page; a stamp whose entry is
                    // not here belongs to the entry a resumed page started after, delivered with its stamps already -
                    // or to nothing.
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

    /** An entry as listed, gathering the stamps that follow it: recency is the newest stamp, and only without one the
     *  listing's modification time - so a stamped entry's recency is the same on every backend and survives a copy of
     *  the store, while a never-read entry ages from when this backend received it. */
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

    /** The deletion token: the entry's key and its stamps, so a delete leaves no stamp behind. Opaque to callers; its
     *  text is the key alone. */
    private record Located(String key, List<String> stamps) {

        @Override
        public String toString() {
            return key;
        }
    }

    /** Whether a project-relative key is an entry - exactly {@code <step>/<inputs>}, both hex, no deeper - rather than
     *  the project's own files or settings, which keeps a policy document out of an eviction pass's candidates. */
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

    // ---- projects

    @Override
    public boolean projectExists(String project) {
        // Anything under the prefix, one listing capped at one entry. A build writing an entry brings a project into
        // being and the console must see it, so this does not key on the marker; a provisioned empty project has its
        // marker under the prefix.
        try {
            return Names.isProject(project) && store.scan(project, "", 1, _ -> { }).delivered() > 0;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not probe project " + project, e);
        }
    }

    /**
     * Provision a project by writing its marker. A keyed store has no empty container, so this small document is what
     * makes a created project that nothing has pushed to yet survive a page reload. It carries {@code created}, and
     * whatever an operator sets later lives here too, read back through {@link #readConfig}.
     *
     * <p>Provisioning guarantees existence without defining it: {@link #projectExists} asks whether anything is under
     * the prefix, since a build pushing to an unprovisioned project creates it.
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

    // ---- configuration files

    @Override
    public Properties readConfig(String project, String file) {
        return Names.isProject(project) && Names.isFile(file)
                ? readFile(project + "/" + file)
                : new Properties();
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
            // A token without the body: a metadata request on the object stores, not a download per revalidation.
            return addressable(path) ? store.version(path).orElse(null) : null;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the version of " + path, e);
        }
    }

    // ---- directories

    @Override
    public Traversal.Result listDir(String prefix, String cursor, int limit, Consumer<String> names) {
        Pages.limit(limit);
        return children(prefix, cursor, limit, names, _ -> true);
    }

    /**
     * The body of {@link #projects} and {@link #listDir}: one page of immediate child containers under a prefix,
     * filtered, with the extra name that makes the truncation answer exact.
     *
     * <p>Containers only: a project is a container of entries and a {@code .users/} node one of documents, so a leaf
     * beside them is no child either surface reports. {@code page} merges a blob and a same-named container into one
     * name, so a child is a container iff something is under it - one bounded probe per candidate, bounded by the page
     * size.
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

    /** Whether a child name is a container: one bounded scan stopping at the first key beneath it. */
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
            // Deleting as it goes, the next page starts where this one ended; a crash leaves a partial tree a re-run
            // finishes - what a recursive delete can promise on a store with no atomic subtree operation.
            cursor = scan.cursor().orElseThrow();
        }
    }

    // ---- capacity

    @Override
    public long usableSpace() {
        return capacity().map(ArtifactStore.Capacity::usable).orElse(Long.MAX_VALUE);
    }

    @Override
    public long totalSpace() {
        return capacity().map(ArtifactStore.Capacity::total).orElse(0L);
    }

    /** The pair is answered together or not at all; the sentinels are this interface's "no volume", which the store
     *  says by answering empty. */
    private Optional<ArtifactStore.Capacity> capacity() {
        try {
            return store.capacity();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not measure the backing volume", e);
        }
    }
}
