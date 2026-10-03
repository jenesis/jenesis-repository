package build.jenesis.repository.store.filesystem;

import module java.base;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.PrimitiveArtifactStore;
import build.jenesis.repository.store.OwnerOnly;

/**
 * The default {@link ArtifactStore}: blobs under a mounted root directory, keyed by their object path. A version token
 * pairs the file's last-modified stamp with a digest of its bytes (see {@link #token}), so {@link #writeVersioned} is a
 * compare-and-set on the stored incarnation rather than on the tick it was written in.
 *
 * <p>The compare and the move are exclusive across processes as well as threads: striped monitors for threads, and for
 * processes an operating-system lock on one of sixty-four stripe files under {@code .cas} at the root, held from the
 * compare to the move - so several nodes on one shared mount (NFS, EFS, a host path) lose no update to one another. The
 * stripe is chosen from the key relative to the root, so nodes mounting the share at different paths meet on the same
 * lock; no listing, page or scan reports the lock files. The lock is advisory, as file locks are: a mount that does not
 * honour them (an NFS export without its lock daemon) must not be shared.
 */
public final class FilesystemArtifactStore implements PrimitiveArtifactStore {

    /** Striped monitors for {@link #writeVersioned}, which is a check-then-move. Static, so every scoped view over the
     *  same tree serializes on the same stripes; two keys sharing a stripe only serialize a small-object write. The
     *  monitor also keeps one JVM from asking for the same stripe file's process lock twice, which the platform refuses
     *  rather than queues. */
    private static final Object[] LOCKS = new Object[64];

    /** The directory of stripe lock files under the top-level root: a dotted name, so no tenant or key can name it, on
     *  the shared mount where the other node can see the lock. A file is never deleted - a deleted lock file is a new
     *  inode to the next opener and no lock at all to the one still holding the old. */
    private static final String CAS_LOCKS = ".cas";

    /** How long a writer waits for another process's stripe lock: a holder keeps it for one compare and one small move,
     *  so a wait this long is a holder that will not return. */
    private static final Duration LOCK_PATIENCE = Duration.ofSeconds(30);

    static {
        for (int index = 0; index < LOCKS.length; index++) {
            LOCKS[index] = new Object();
        }
    }

    private final Path root;

    /** The stripe lock directory, shared by every scoped view: a scope narrows the root, not the mount. */
    private final Path locks;

    /** Whether a write is forced to the disk before it answers ({@link #durableMove}); every scoped view shares it. */
    private final boolean durable;

    /** A durable store at {@code root}. */
    public FilesystemArtifactStore(Path root) {
        this(root, true);
    }

    /** A store at {@code root} whose writes are forced to the disk before they answer when {@code durable}, and
     *  otherwise left to the operating system to flush - each still an atomic rename, so a crash loses the last few
     *  seconds of writes rather than tearing one. */
    public FilesystemArtifactStore(Path root, boolean durable) {
        this(root, root.resolve(CAS_LOCKS), durable);
    }

    private FilesystemArtifactStore(Path root, Path locks, boolean durable) {
        this.root = root;
        this.locks = locks;
        this.durable = durable;
    }

    /** Whether this store's writes are forced to the disk before they answer. */
    public boolean durable() {
        return durable;
    }

    @Override
    public ArtifactStore scope(String tenant) {
        return new FilesystemArtifactStore(root.resolve(FileNames.encode(ArtifactStore.segment(tenant))), locks,
                durable);
    }

    @Override
    public Object identity() {
        return "file:" + root.toAbsolutePath().normalize();
    }

    private Path resolve(String key) {
        Path path = root.resolve(FileNames.encode(key)).normalize();
        if (!path.startsWith(root.normalize())) {
            throw new IllegalArgumentException("Path escapes the store root: " + key);
        }
        return path;
    }

    @Override
    public boolean exists(String key) {
        Path path = resolve(key);
        try {
            return regularFile(path);
        } catch (IOException failure) {
            // The signature carries no checked exception, so unchecked is how this backend raises the failure the
            // object-store backends raise as their SDKs' unchecked types.
            throw new UncheckedIOException("Cannot tell whether an object is stored at " + path, failure);
        }
    }

    /**
     * Whether a regular file is stored at this path, telling "there is nothing here" apart from "I could not look" -
     * which {@link Files#isRegularFile} cannot, since it answers {@code false} for a permission refusal, an I/O error
     * or a stale mount. Store contract clause 6 forbids that fusion: a screen that fails closed on a store failure
     * needs the failure, most of all where an absent answer destroys or discloses (the un-condemn probe before a
     * re-publish links a condemned blob, an image manifest's reference lending, the withhold and blob-present probes of
     * every serve).
     *
     * <p>{@code ENOENT} and {@code ENOTDIR} - nothing at the key, or an ancestor of the key is itself a stored object,
     * which is ordinary in {@code publish/} - arrive as {@link NoSuchFileException} and are absent. Everything else is
     * raised.
     */
    private static boolean regularFile(Path path) throws IOException {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class).isRegularFile();
        } catch (NoSuchFileException | NotDirectoryException _) {
            return false;
        }
    }

    @Override
    public void read(String key, OutputStream out) throws IOException {
        try (InputStream in = Files.newInputStream(resolve(key))) {
            if (out instanceof ArtifactStore.RangedSink ranged) {
                in.skipNBytes(ranged.offset());
                ArtifactStore.copy(in, ranged.sink(), ranged.length());
            } else {
                in.transferTo(out);
            }
        }
    }

    @Override
    public InputStream open(String key) throws IOException {
        return Files.newInputStream(resolve(key));
    }

    @Override
    public InputStream open(String key, long offset) throws IOException {
        FileChannel channel = FileChannel.open(resolve(key), StandardOpenOption.READ);
        channel.position(offset);
        return Channels.newInputStream(channel);
    }

    /** Create an upload temp file in {@code dir}, re-creating the directory and retrying if a concurrent
     *  {@link #delete} tidied the empty container away in between - the other half of the race {@link #delete}'s
     *  {@code DirectoryNotEmptyException} catch handles. */
    private static Path createUploadTemp(Path dir) throws IOException {
        for (int attempt = 0; ; attempt++) {
            try {
                // Owner-only creation (rwx------ dir, rw------- temp), so a blob never inherits a world-readable umask;
                // the rename into place keeps the temp's mode.
                OwnerOnly.createDirectories(dir);
                return OwnerOnly.createTempFile(dir, ".upload", ".tmp");
            } catch (NoSuchFileException e) {
                if (attempt >= 4) {
                    throw e;
                }
            }
        }
    }

    @Override
    public void write(String key, InputStream in) throws IOException {
        Path path = resolve(ArtifactStore.key(key));
        Path temp = createUploadTemp(path.getParent());
        try {
            try (OutputStream out = Files.newOutputStream(temp)) {
                in.transferTo(out);
            }
            durableMove(temp, path);
        } catch (IOException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
    }

    /** Move a spooled file into place so that what a caller was told is stored survives a power loss: the file's bytes
     *  are forced before the rename, so the name never points at content still in the page cache, and the directory
     *  after it, so the rename itself is on the disk. That costs a synchronous flush or two per write, the price of a
     *  {@code 201} meaning stored. A file system that cannot open a directory for syncing skips the directory half. A
     *  store that is not {@linkplain #durable durable} renames and leaves the flushes to the operating system. */
    private void durableMove(Path temp, Path target) throws IOException {
        force(temp);
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        forceDirectory(target.getParent());
    }

    /** Force a spooled file's bytes to the disk, on a durable store. */
    private void force(Path file) throws IOException {
        if (!durable) {
            return;
        }
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    /** Force a directory's entries - a rename into it - to the disk, on a durable store. */
    private void forceDirectory(Path directory) throws IOException {
        if (!durable) {
            return;
        }
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (UnsupportedOperationException | AccessDeniedException _) {
            // No directory handle to sync on this file system; the rename stands as the platform leaves it.
        }
    }

    /** Whether the blob stored at {@code blob} holds exactly the bytes spooled at {@code temp}, whose SHA-256 is its
     *  name: a blob a crash tore before its bytes reached the disk has the right name and the wrong content. */
    private static boolean intact(Path blob, Path temp, String hash) throws IOException {
        if (Files.size(blob) != Files.size(temp)) {
            return false;
        }
        try (InputStream stored = Files.newInputStream(blob)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            new DigestInputStream(stored, digest).transferTo(OutputStream.nullOutputStream());
            return HexFormat.of().formatHex(digest.digest()).equals(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public String writeBlob(InputStream in) throws IOException {
        Path blobs = resolve("blobs");
        Path temp = createUploadTemp(blobs);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (OutputStream out = Files.newOutputStream(temp)) {
                new DigestInputStream(in, digest).transferTo(out);
            }
            String hash = HexFormat.of().formatHex(digest.digest());
            Path blob = blobs.resolve(hash);
            // A duplicate upload keeps the stored blob only once it proves it holds these bytes; a torn one is
            // replaced, so re-sending the artifact repairs it. The proof reads the stored blob only for a duplicate.
            if (Files.isRegularFile(blob) && intact(blob, temp, hash)) {
                Files.delete(temp);
            } else {
                durableMove(temp, blob);
            }
            return hash;
        } catch (NoSuchAlgorithmException e) {
            Files.deleteIfExists(temp);
            throw new IllegalStateException(e);
        } catch (IOException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
    }

    @Override
    public long size(String key) throws IOException {
        Path path = resolve(key);
        return Files.isRegularFile(path) ? Files.size(path) : -1L;
    }

    @Override
    public Optional<Listed> listed(String key) throws IOException {
        Path path = resolve(key);
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
            return attributes.isRegularFile()
                    ? Optional.of(Listed.of(key, attributes.size(), attributes.lastModifiedTime().toInstant()))
                    : Optional.empty();
        } catch (NoSuchFileException _) {
            return Optional.empty();
        }
    }

    @Override
    public void delete(String key) throws IOException {
        Path path = resolve(key);
        Files.deleteIfExists(path);
        Path parent = path.getParent(), top = root.normalize();
        while (parent != null && !parent.equals(top) && isEmpty(parent)) {
            try {
                Files.deleteIfExists(parent);
            } catch (DirectoryNotEmptyException _) {
                return; // a concurrent write repopulated the container between the check and the tidy - keep it
            }
            parent = parent.getParent();
        }
    }

    private static boolean isEmpty(Path dir) {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.findAny().isEmpty();
        } catch (IOException _) {
            return false;
        }
    }

    /** An empty child set and an unreadable container are different facts, and the difference decides deletions: the
     *  collector loads a reference shard through {@link #list}, and a shard that read empty because its directory could
     *  not be opened would mark every blob under that byte unreferenced. A container that is absent, or is itself a
     *  stored object, has no children; any other failure is raised. */
    @Override
    public List<String> list(String prefix) {
        Path dir = resolve(prefix);
        try (Stream<Path> entries = Files.list(dir)) {
            // The stripe locks under .cas are the store's own: a root listing skips them.
            return entries.filter(path -> !path.equals(locks)).map(path -> path.getFileName().toString())
                    // An atomic write's in-flight .upload*.tmp sibling is never a stored entry.
                    .filter(name -> !(name.startsWith(".upload") && name.endsWith(".tmp")))
                    .map(FileNames::decode)
                    .sorted().toList();
        } catch (NoSuchFileException | NotDirectoryException _) {
            return List.of();
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot enumerate the children of " + dir, failure);
        }
    }

    /** One page is one scan of the directory, whatever the page's width: a directory listing has no order and no seek,
     *  so selecting the {@code limit} names past {@code startAfter} reads every sibling and keeps the smallest -
     *  O(limit) memory, O(siblings) time per page, where an object store's listing is O(page). A request-path read
     *  rendering one window pays one scan and keeps the default width; a walk that drains a level pays one scan per
     *  page and takes {@code BoundedChildren.DRAIN_PAGE}, ten times wider, which turns some sixty rescans of a
     *  million-entry directory into a handful. */
    @Override
    public void pageListed(String prefix, String startAfter, int limit, Consumer<Listed> consumer) {
        Path dir = resolve(prefix);
        if (limit <= 0) {
            return;
        }
        // Keep the limit smallest names past startAfter in a capped TreeMap: O(limit) memory however large the
        // directory. The attributes come from the stat that already tells a directory from a file.
        TreeMap<String, Listed> smallest = new TreeMap<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            for (Path path : entries) {
                String name = FileNames.decode(path.getFileName().toString());
                // The same in-flight .upload*.tmp filter as list().
                if (name.startsWith(".upload") && name.endsWith(".tmp") || path.equals(locks) || name.compareTo(startAfter) <= 0) {
                    continue;
                }
                if (smallest.size() < limit || name.compareTo(smallest.lastKey()) < 0) {
                    smallest.put(name, listed(prefix, name, path));
                    if (smallest.size() > limit) {
                        smallest.pollLastEntry();
                    }
                }
            }
        } catch (NoSuchFileException | NotDirectoryException _) {
            return; // mirror list(): a vanished container, or one that is itself a stored object, pages as empty
        } catch (IOException failure) {
            // Unlike list(): a short page is how the shared walk learns a container is drained, so an unreadable
            // directory must not page as empty.
            throw new UncheckedIOException("Cannot page the children of " + dir, failure);
        }
        smallest.values().forEach(consumer);
    }

    /** A child as the listing saw it: a regular file carries its size and age, a directory neither. A stat that races a
     *  delete degrades to the names-only shape rather than failing the page; the walk re-judges every key on read. */
    private static Listed listed(String prefix, String name, Path path) {
        // The object stores' container normalisation, so a trailing slash yields a/b/name, not a/b//name.
        String container = ArtifactStore.container(prefix);
        String key = container.isEmpty() ? name : container + "/" + name;
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
            return attributes.isRegularFile()
                    ? Listed.of(key, attributes.size(), attributes.lastModifiedTime().toInstant())
                    : Listed.of(key);
        } catch (IOException _) {
            return Listed.of(key);
        }
    }

    /** How long a write temp is left alone before a scan reclaims it: far longer than any single atomic write, short
     *  enough that a crashed one does not outlive the day. */
    private static final Duration TEMP_GRACE = Duration.ofHours(1);

    /**
     * A scan descends the tree in key order and stops one entry past the page, so a page costs the directories on its
     * way rather than the tree under the prefix. A directory has no order and no seek, so each one entered is read
     * whole and the fewest of its children the page can still use are kept - the smallest past the cursor, compared
     * as a directory's key with its separator after it, which is how its keys sort among its siblings' - and a
     * subtree that lies wholly behind the cursor is never entered. A project of thousands of folders is paged by
     * reading the few on the page's way, where walking the whole prefix per page made a sweep's cost the square of
     * what it swept.
     */
    @Override
    public Scan scan(String prefix, String startAfter, int limit, Consumer<Listed> consumer) throws IOException {
        if (limit <= 0) {
            throw new IllegalArgumentException("A scan limit must be positive: " + limit);
        }
        Path base = resolve(prefix);
        if (!Files.isDirectory(base)) {
            return Scan.exhausted(0, 1);
        }
        Path rootPath = root.normalize();
        String baseKey = FileNames.decode(rootPath.relativize(base).toString().replace(File.separatorChar, '/'));
        Descent descent = new Descent(startAfter == null ? "" : startAfter, ArtifactStore.oneMoreThan(limit));
        descent.enter(base, baseKey);
        List<Listed> found = descent.found;
        boolean more = found.size() > limit;
        if (more) {
            found.removeLast();
        }
        String last = null;
        for (Listed entry : found) {
            consumer.accept(entry);
            last = entry.key();
        }
        return more ? Scan.truncated(last, found.size(), descent.steps)
                : Scan.exhausted(found.size(), descent.steps);
    }

    /** One scan's descent: the entries it has found, in key order, until it holds {@code wanted}. */
    private final class Descent {

        private final String after;
        private final int wanted;
        private final List<Listed> found = new ArrayList<>();
        private long steps;

        private Descent(String after, int wanted) {
            this.after = after;
            this.wanted = wanted;
        }

        /** A directory's child as its keys sort: a directory's key carries its separator, a file's does not. */
        private record Child(String order, String key, Path path, BasicFileAttributes attributes) {
        }

        /** Visit {@code dir}, whose key is {@code dirKey}, in key order; answers whether the page is full. */
        private boolean enter(Path dir, String dirKey) throws IOException {
            String floor = null;
            while (true) {
                int room = wanted - found.size();
                TreeMap<String, Child> next = smallest(dir, dirKey, floor, room);
                for (Child child : next.values()) {
                    if (child.attributes().isDirectory()) {
                        if (enter(child.path(), child.key())) {
                            return true;
                        }
                    } else {
                        found.add(listed(child.key(), child.attributes()));
                        if (found.size() == wanted) {
                            return true;
                        }
                    }
                }
                if (next.size() < room) {
                    return false;                   // the directory had no more children past the floor
                }
                floor = next.lastKey();
            }
        }

        /** The {@code room} smallest children of {@code dir} the page can still use: past the cursor, past
         *  {@code floor}, and not a subtree wholly behind the cursor. */
        private TreeMap<String, Child> smallest(Path dir, String dirKey, String floor, int room) throws IOException {
            steps++;
            TreeMap<String, Child> smallest = new TreeMap<>();
            try (DirectoryStream<Path> children = Files.newDirectoryStream(dir)) {
                for (Path path : children) {
                    if (path.equals(locks)) {
                        continue;                   // the stripe locks under .cas are the store's own
                    }
                    BasicFileAttributes attributes;
                    try {
                        attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    } catch (NoSuchFileException vanished) {
                        // A concurrent write renamed its temp into place between the read and the stat; a listing is
                        // entitled not to report a deleted file.
                        continue;
                    }
                    String name = FileNames.decode(path.getFileName().toString());
                    if (!attributes.isDirectory() && name.startsWith(".upload") && name.endsWith(".tmp")) {
                        reclaimTemp(path, attributes);
                        continue;
                    }
                    String key = dirKey.isEmpty() ? name : dirKey + "/" + name;
                    String order = attributes.isDirectory() ? key + "/" : key;
                    if (floor != null && order.compareTo(floor) <= 0) {
                        continue;
                    }
                    if (attributes.isDirectory()
                            ? order.compareTo(after) <= 0 && !after.startsWith(order)
                            : key.compareTo(after) <= 0) {
                        continue;                   // wholly behind the cursor
                    }
                    if (smallest.size() < room || order.compareTo(smallest.lastKey()) < 0) {
                        smallest.put(order, new Child(order, key, path, attributes));
                        if (smallest.size() > room) {
                            smallest.pollLastEntry();
                        }
                    }
                }
            } catch (NoSuchFileException | NotDirectoryException vanished) {
                return smallest;                    // a directory removed under the scan holds nothing to page
            }
            return smallest;
        }
    }

    /**
     * The in-flight {@code .upload*.tmp} filter, and past a grace window the one place such temps are reclaimed. A
     * crash between a write's temp and its rename leaves a temp that every listing hides, so no sweep would ever count
     * or remove it and the volume would fill. A scan reads every directory a full sweep crosses, and the grace window
     * keeps an in-flight write safe. A failed delete is ignored: another node may have won the race.
     */
    private static void reclaimTemp(Path path, BasicFileAttributes attributes) {
        if (attributes.lastModifiedTime().toInstant().isBefore(Instant.now().minus(TEMP_GRACE))) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException _) {
                // Raced, or not ours to delete; the next scan tries again.
            }
        }
    }

    /** Both halves of the metadata come from the attributes the visitor was handed, so a scan stats nothing. */
    private static Listed listed(String key, BasicFileAttributes attributes) {
        return Listed.of(key, attributes.size(), attributes.lastModifiedTime().toInstant());
    }

    @Override
    public Optional<Capacity> capacity() throws IOException {
        // A failure to measure throws rather than reporting empty, which would read as "no volume" and disable a
        // free-space policy. A scoped root need not exist yet - the first write creates it - so measure the nearest
        // existing ancestor; only a root with none at all fails.
        Path measured = root;
        while (!Files.exists(measured) && measured.getParent() != null) {
            measured = measured.getParent();
        }
        FileStore store = Files.getFileStore(measured);
        return Optional.of(new Capacity(store.getUsableSpace(), store.getTotalSpace()));
    }

    @Override
    public void touch(String key) throws IOException {
        Path path = resolve(key);
        if (regularFile(path)) {
            try {
                Files.setLastModifiedTime(path, FileTime.from(Instant.now()));
            } catch (NoSuchFileException _) {
                // Raced with a delete: nothing left to mark, and recency-on-read is advisory.
            }
        }
    }

    @Override
    public Optional<Versioned> readVersioned(String key) throws IOException {
        Path path = resolve(key);
        // The same discrimination as exists(): an unreadable pointer answering empty would decide "the marker is not
        // there, serve it" or "no other alias holds these bytes, lift the hold" on a store that could not be read.
        if (!regularFile(path)) {
            return Optional.empty();
        }
        // The probe and the reads are not atomic, so a concurrent delete between them throws NoSuchFileException (or
        // FileNotFoundException on some providers); that race maps to absent, as an object store's 404 does.
        try {
            // Stamp before content: a write landing in between pairs the OLD stamp with NEW content, so a
            // compare-and-set from this read loses and retries - the safe direction. The reverse would let a stale
            // update pass as current.
            long modified = Files.getLastModifiedTime(path).toMillis();
            byte[] content = Files.readAllBytes(path);
            return Optional.of(new Versioned(content, token(modified, content)));
        } catch (NoSuchFileException | FileNotFoundException e) {
            return Optional.empty();
        }
    }

    /** The token without the body: the file is read through the checksums rather than into an array, so a caller about
     *  to update a large document (a {@code StoredListing}) never holds it. */
    @Override
    public Optional<Object> version(String key) throws IOException {
        Path path = resolve(key);
        if (!regularFile(path)) {
            return Optional.empty();
        }
        try {
            // Stamp before content, as readVersioned explains.
            long modified = Files.getLastModifiedTime(path).toMillis();
            return Optional.of(token(modified, path));
        } catch (NoSuchFileException | FileNotFoundException e) {
            return Optional.empty();
        }
    }

    /**
     * The opaque version token: the last-modified stamp <em>and</em> a digest of the stored bytes, so it identifies the
     * object's incarnation rather than the tick it was written in.
     *
     * <p>A stamp alone identifies a moment: a key deleted and re-created inside one millisecond ({@code toMillis()}
     * truncates) carries the same stamp, so a token read from the gone object would pass the compare-and-set and a
     * stale write would land over content it never saw. {@link #delete} followed by a create-if-absent
     * {@link #writeVersioned} is how a revoked credential's metadata, a collection marker and a feed snapshot pointer
     * are re-created. Two writes in one tick are handled by nudging the stamp forward; a deleted and re-created
     * incarnation has no earlier stamp to compare against, and the digest covers it - as an S3 or Azure ETag and a GCS
     * generation do for those backends. A key re-created with byte-identical content at the same stamp is the state the
     * stale token's holder read, so the compare-and-set concedes nothing.
     *
     * <p>The digest is the length and two CRCs (CRC-32 and CRC-32C, 64 bits between them), not a cryptographic hash: it
     * tells incarnations apart rather than certifying bytes, and it is computed over the whole object on every
     * versioned read and write, so it runs at memory speed on intrinsics.
     */
    private static Object token(long modified, byte[] content) {
        CRC32 crc = new CRC32();
        crc.update(content);
        CRC32C crcc = new CRC32C();
        crcc.update(content);
        return token(modified, content.length, crc.getValue(), crcc.getValue());
    }

    /** The same token, streamed over the file rather than an array. Used where the object itself is not wanted -
     *  {@link #version} and the compare half of both {@link #writeVersioned} overloads - since the stored document can
     *  be a listing sized by the whole repository, and the heap a small write needs must not grow with it. */
    private static Object token(long modified, Path path) throws IOException {
        CRC32 crc = new CRC32();
        CRC32C crcc = new CRC32C();
        long length = 0;
        byte[] buffer = new byte[16 * 1024];
        try (InputStream content = Files.newInputStream(path)) {
            for (int read = content.read(buffer); read != -1; read = content.read(buffer)) {
                crc.update(buffer, 0, read);
                crcc.update(buffer, 0, read);
                length += read;
            }
        }
        return token(modified, length, crc.getValue(), crcc.getValue());
    }

    /** The one rendering both overloads share. */
    private static Object token(long modified, long length, long crc, long crcc) {
        return modified + ":" + length + ":" + Long.toHexString(crc) + ":" + Long.toHexString(crcc);
    }

    @Override
    public boolean writeVersioned(String key, byte[] content, Object expected) throws IOException {
        return compareAndSet(resolve(ArtifactStore.key(key)), expected, temp -> Files.write(temp, content));
    }

    /** The streaming compare-and-set: the temporary file is filled from the stream. The length is unused here; it is a
     *  parameter because the object stores cannot begin a conditional upload without one. */
    @Override
    public boolean writeVersioned(String key, InputStream content, long length, Object expected)
            throws IOException {
        return compareAndSet(resolve(ArtifactStore.key(key)), expected,
                temp -> Files.copy(content, temp, StandardCopyOption.REPLACE_EXISTING));
    }

    /** A compare-and-set on {@code path}: the new content is spooled beside it and forced to the disk, and the stored
     *  document is hashed into its token, with no lock held; the key's stripe is then locked for the comparison and
     *  rename alone, and the directory is forced after release. The slow parts - flushes and a hash proportional to the
     *  stored document - therefore cost no rival its turn, a write whose token is already stale loses without the lock,
     *  and a loser drops what it spooled. */
    private boolean compareAndSet(Path path, Object expected, Spool spool) throws IOException {
        // The .upload*.tmp shape, so listings hide it; createUploadTemp re-creates a parent a concurrent delete tidied.
        Path temp = createUploadTemp(path.getParent());
        boolean moved = false;
        try {
            spool.fill(temp);
            force(temp);
            Hashed before = expected == null ? Hashed.NONE : Hashed.of(path, Files.getLastModifiedTime(temp));
            if (before.consistent() && !Objects.equals(before.token(), expected)) {
                return false;
            }
            int stripe = stripe(path);
            synchronized (LOCKS[stripe]) {
                try (FileChannel channel = stripeLock(stripe); FileLock _ = acquire(channel)) {
                    moved = compareAndMove(path, expected, temp, before);
                }
            }
        } finally {
            if (!moved) {
                Files.deleteIfExists(temp);
            }
        }
        if (moved) {
            forceDirectory(path.getParent());
        }
        return moved;
    }

    /** What identifies a stored file without reading it: the file system's key for it (device and inode where there is
     *  one), its full-precision modification time and its length. {@code null} for a key with no file. */
    private record Stamp(Object file, FileTime modified, long size) {

        static Stamp of(Path path) throws IOException {
            try {
                BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
                return attributes.isRegularFile()
                        ? new Stamp(attributes.fileKey(), attributes.lastModifiedTime(), attributes.size())
                        : null;
            } catch (NoSuchFileException | FileNotFoundException e) {
                return null;
            }
        }
    }

    /**
     * The stored document's token, hashed before the stripe lock is taken, with stamps read on either side of the hash.
     *
     * <p>Under the lock it {@linkplain #stands stands} for the current file only when that file is provably the hashed
     * incarnation: both stamps agree with each other and with the one read under the lock, the file system names the
     * file, and the file was last modified at least {@link #SETTLED} before the spool file was written. That last
     * condition makes the stamp sufficient: a rename frees the old inode for reuse, so a re-created file could share
     * inode, length and - within one tick - modification time; but any file that replaced the hashed one did so after
     * the spool file was written, so its modification time is at least the spool file's on the same clock. A file
     * modified within the margin is hashed again under the lock.
     */
    private record Hashed(Stamp stamp, Object token, boolean consistent, FileTime spooled) {

        /** How far the hashed file's modification time must precede the spool file's: well beyond the coarsest
         *  file-system tick and a small clock step. */
        static final Duration SETTLED = Duration.ofSeconds(1);

        /** Nothing hashed: a create, which compares against absence and has no stored document to hash. */
        static final Hashed NONE = new Hashed(null, null, false, null);

        static Hashed of(Path path, FileTime spooled) throws IOException {
            Stamp first = Stamp.of(path);
            if (first == null) {
                return new Hashed(null, null, false, spooled);
            }
            Object token;
            try {
                token = FilesystemArtifactStore.token(first.modified().toMillis(), path);
            } catch (NoSuchFileException | FileNotFoundException e) {
                return new Hashed(null, null, false, spooled);
            }
            boolean consistent = first.file() != null && first.equals(Stamp.of(path));
            return new Hashed(first, token, consistent, spooled);
        }

        boolean stands(Stamp current) {
            return consistent && stamp.equals(current)
                    && stamp.modified().toInstant().plus(SETTLED).isBefore(spooled.toInstant());
        }
    }

    /** The stripe a key serializes on, chosen from the key relative to the top-level root, never the absolute path: two
     *  processes mounting one share at different paths must take the same lock for one write. */
    private int stripe(Path path) {
        String key = locks.getParent().normalize().relativize(path).toString().replace(File.separatorChar, '/');
        return Math.floorMod(key.hashCode(), LOCKS.length);
    }

    /** Fill a spool file for a versioned write. */
    @FunctionalInterface
    private interface Spool {
        void fill(Path temp) throws IOException;
    }

    /** The compare-and-set proper, under both locks: compare the stored incarnation's token with {@code expected} and,
     *  on a match, move the spooled content into place atomically. The pre-lock token is used when the file is provably
     *  the hashed incarnation ({@link Hashed#stands}), and re-hashed otherwise. The token must advance on every
     *  successful update, even of byte-identical content the digest cannot tell apart: otherwise two writes in one tick
     *  leave it unchanged and a third writer holding the old token would still pass. */
    private static boolean compareAndMove(Path path, Object expected, Path temp, Hashed before) throws IOException {
        Stamp now = Stamp.of(path);
        boolean present = now != null;
        long modified = present ? now.modified().toMillis() : -1L;
        if (present != (expected != null)) {
            return false;
        }
        if (present && !Objects.equals(before.stands(now) ? before.token() : token(modified, path), expected)) {
            return false;
        }
        Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        if (present && Files.getLastModifiedTime(path).toMillis() <= modified) {
            Files.setLastModifiedTime(path, FileTime.fromMillis(modified + 1));
        }
        return true;
    }

    /** The process lock on a stripe file, taken by polling rather than blocking. A blocking {@code lock()} is the
     *  kernel's {@code F_SETLKW}, and POSIX record locks belong to the process: two threads of one node waiting on two
     *  stripes the other node's threads hold look like a cycle between the processes, and the kernel refuses a waiter
     *  with {@code EDEADLK} ("Resource deadlock avoided"). {@code tryLock()} has no such detection, so the waiter polls
     *  at a millisecond, bounded so a holder that never returns fails the write loudly. */
    private static FileLock acquire(FileChannel channel) throws IOException {
        Instant deadline = Instant.now().plus(LOCK_PATIENCE);
        while (true) {
            FileLock lock = channel.tryLock();
            if (lock != null) {
                return lock;
            }
            if (Instant.now().isAfter(deadline)) {
                throw new IOException("the compare-and-set stripe lock was held by another process for over "
                        + LOCK_PATIENCE);
            }
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while waiting for the compare-and-set stripe lock", e);
            }
        }
    }

    /** The open channel of {@code stripe}'s lock file, created on first use; the caller locks and closes it. */
    private FileChannel stripeLock(int stripe) throws IOException {
        Files.createDirectories(locks);
        return FileChannel.open(locks.resolve(Integer.toString(stripe)),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    }

    /**
     * How a key becomes a file name and back: every byte of a key outside ASCII is written as upper-case {@code %XX} of
     * its UTF-8 encoding, and a name is read back by decoding exactly those. An ASCII key is its own file name.
     *
     * <p>A path is resolved through the JVM's file-name encoding ({@code sun.jnu.encoding}), which follows the process
     * locale: under a POSIX or C locale it is ASCII, and resolving a key holding {@code naïve} throws
     * {@code InvalidPathException}. Whether a client's coordinate can be stored must not depend on how the node's shell
     * was started, nor may two nodes over one share name one key two ways, so the mapping is decided from the key's
     * bytes alone.
     *
     * <p>Only {@code %} followed by a hex byte at or above {@code 0x80} decodes; {@code %2F} and {@code %25}, which a
     * scope segment writes for a slash and a percent sign, are ASCII and pass through unchanged. A raw non-ASCII file
     * name decodes to itself and is not the name this mapping resolves its key to.
     */
    static final class FileNames {

        private static final char[] HEX = "0123456789ABCDEF".toCharArray();

        private FileNames() {
        }

        static String encode(String key) {
            boolean ascii = true;
            for (int index = 0; index < key.length() && ascii; index++) {
                ascii = key.charAt(index) < 0x80;
            }
            if (ascii) {
                return key;
            }
            StringBuilder name = new StringBuilder(key.length() + 16);
            for (byte b : key.getBytes(StandardCharsets.UTF_8)) {
                int value = b & 0xFF;
                if (value < 0x80) {
                    name.append((char) value);
                } else {
                    name.append('%').append(HEX[value >> 4]).append(HEX[value & 0xF]);
                }
            }
            return name.toString();
        }

        static String decode(String name) {
            if (name.indexOf('%') < 0) {
                return name;
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(name.length());
            for (int index = 0; index < name.length(); index++) {
                char c = name.charAt(index);
                if (c == '%' && index + 2 < name.length()) {
                    int high = Character.digit(name.charAt(index + 1), 16);
                    int low = Character.digit(name.charAt(index + 2), 16);
                    if (high >= 8 && low >= 0) {
                        bytes.write((high << 4) | low);
                        index += 2;
                        continue;
                    }
                }
                if (c < 0x80) {
                    bytes.write(c);
                } else {
                    bytes.writeBytes(String.valueOf(c).getBytes(StandardCharsets.UTF_8));
                }
            }
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
