package build.jenesis.repository.store.filesystem;

import build.jenesis.repository.store.ArtifactStore;

import module java.base;

/**
 * The default {@link ArtifactStore}: blobs under a mounted root directory, keyed by their object path.
 * Version tokens pair the file's last-modified stamp with a digest of its bytes (see {@link #token}), so
 * {@link #writeVersioned} is a compare-and-set on the stored <em>incarnation</em> rather than on the tick it was
 * written in - adequate for a single node; a clustered deployment uses an object-store backend whose
 * ETag / generation gives true cross-node compare-and-set.
 */
public final class FilesystemArtifactStore implements ArtifactStore {

    /** Striped monitors for {@link #writeVersioned}: the last-modified compare-and-set is a check-then-move, so two
     *  in-process threads holding the same token would otherwise both pass the check and both land - a lost update on
     *  the very node the mtime token is documented adequate for. Static, so every scoped view (each a new instance
     *  over the same directory tree) serializes against the same stripes; two unrelated keys sharing a stripe merely
     *  serialize a small-object write, never a blob stream. */
    private static final Object[] LOCKS = new Object[64];

    static {
        for (int index = 0; index < LOCKS.length; index++) {
            LOCKS[index] = new Object();
        }
    }

    private final Path root;

    public FilesystemArtifactStore(Path root) {
        this.root = root;
    }

    @Override
    public ArtifactStore scope(String tenant) {
        return new FilesystemArtifactStore(root.resolve(ArtifactStore.segment(tenant)));
    }

    @Override
    public Object identity() {
        return "file:" + root.toAbsolutePath().normalize();
    }

    private Path resolve(String key) {
        Path path = root.resolve(key).normalize();
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
            // The signature carries no checked exception - and widening it would not help, since the object-store
            // backends fail with their SDK's own unchecked types - so unchecked is how this backend reaches the same
            // visibility the other three already have.
            throw new UncheckedIOException("Cannot tell whether an object is stored at " + path, failure);
        }
    }

    /**
     * Whether a regular file is stored at this path, telling <em>"there is nothing here"</em> apart from <em>"I could
     * not look"</em> - the discrimination {@link Files#isRegularFile} does not make and cannot be asked to make.
     *
     * <p>{@code Files.isRegularFile} answers {@code false} for a permission refusal, an I/O error and a stale or
     * disconnected mount exactly as it does for an absent object, because it swallows every {@link IOException}
     * internally. That fusion is the whole defect class this store's contract clause 6 forbids: the object stores
     * already re-throw everything that is not a 404, and a screen that fails closed on a store failure cannot do so
     * if the store answers a confident {@code false} instead. It matters most where an absent answer <em>destroys</em>
     * or <em>discloses</em>: the un-condemn probe a re-publish makes before linking a blob the collector condemned,
     * the reference lending an image's manifest does for its layers, and the withhold and blob-present probes every
     * serve screen runs.
     *
     * <p>Both {@code ENOENT} and {@code ENOTDIR} - nothing at the key, and an ancestor of the key is itself a stored
     * object, which is ordinary in the {@code publish/} namespace where a pointer and a path below it coexist - arrive
     * as {@link NoSuchFileException} and are genuinely absent. Everything else is a failure to look and is raised,
     * checked here and mapped by each caller to whatever its own signature can carry.
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

    /** Create an upload temp file in {@code dir}, (re-)creating the directory first and retrying if a concurrent
     *  {@link #delete} tidied the now-empty container away between the create-directory and the create-file. Without
     *  the retry a publish into a directory another thread is emptying fails with a spurious {@code NoSuchFileException}
     *  - the {@link #delete} tidy already guards the reverse direction (its {@code DirectoryNotEmptyException} catch),
     *  so this closes the other half of the same race. */
    private static Path createUploadTemp(Path dir) throws IOException {
        for (int attempt = 0; ; attempt++) {
            try {
                // Owner-only creation (rwx------ dir, rw------- temp), so a blob never inherits the process
                // umask's world-readable default; the rw------- temp keeps those perms through the atomic move
                // into its final blob/key path (rename preserves the inode's mode).
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
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.deleteIfExists(temp);
            throw e;
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
            if (Files.isRegularFile(blob)) {
                Files.delete(temp);
            } else {
                Files.move(temp, blob, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
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
     *  collector loads a whole reference shard through {@link #list}, and a shard that reads empty because the
     *  directory could not be opened marks every blob under that leading byte unreferenced. A container that is
     *  absent or is itself a stored object genuinely has no children; anything else surfaces. */
    @Override
    public List<String> list(String prefix) {
        Path dir = resolve(prefix);
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.map(path -> path.getFileName().toString())
                    // Skip an atomic write's in-flight .upload*.tmp file, a sibling here until it is renamed
                    // into place, so a concurrent listing never returns it as if it were a stored entry.
                    .filter(name -> !(name.startsWith(".upload") && name.endsWith(".tmp")))
                    .sorted().toList();
        } catch (NoSuchFileException | NotDirectoryException _) {
            return List.of();
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot enumerate the children of " + dir, failure);
        }
    }

    @Override
    public void page(String prefix, String startAfter, int limit, Consumer<String> consumer) {
        // Expressed over pageListed so the selection below exists once: this form is the names-only view of it.
        pageListed(prefix, startAfter, limit, listed -> consumer.accept(name(listed.key())));
    }

    private static String name(String key) {
        int slash = key.lastIndexOf('/');
        return slash < 0 ? key : key.substring(slash + 1);
    }

    @Override
    public void pageListed(String prefix, String startAfter, int limit, Consumer<Listed> consumer) {
        Path dir = resolve(prefix);
        if (limit <= 0) {
            return;
        }
        // A directory listing is unordered and a filesystem has no start-at seek, so select the page in one
        // bounded scan: keep the limit smallest names past startAfter in a capped TreeMap - O(limit) memory
        // however many millions of entries the directory holds, where sorting list() would buffer them all.
        // The attributes come from the same stat the selection already needs to tell a directory from a file, so
        // carrying them costs nothing beyond what a names-only page paid.
        TreeMap<String, Listed> smallest = new TreeMap<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            for (Path path : entries) {
                String name = path.getFileName().toString();
                // The same in-flight .upload*.tmp filter as list(), so a concurrent atomic write never pages out.
                if (name.startsWith(".upload") && name.endsWith(".tmp") || name.compareTo(startAfter) <= 0) {
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
            // NOT mirrored from the old list(): a short page is how the shared walk learns a container is drained,
            // so an unreadable directory paging as empty ends a traversal early and reports it as exhausted.
            throw new UncheckedIOException("Cannot page the children of " + dir, failure);
        }
        smallest.values().forEach(consumer);
    }

    /** A child as the listing saw it: a regular file carries its size and age, a directory carries neither because
     *  a container has none of its own. A stat that races a delete degrades to the names-only shape rather than
     *  failing the page - the walk re-judges every key on read anyway. */
    private static Listed listed(String prefix, String name, Path path) {
        // Through the same container normalisation the object stores compose their keys with, so a caller's
        // trailing slash yields a/b/name here as it does there rather than the doubled a/b//name a raw join makes.
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

    /** How long a write temp is left alone before a scan reclaims it: comfortably longer than any single atomic
     *  write, so an in-flight one is never touched, and short enough that a crashed one does not outlive the day. */
    private static final Duration TEMP_GRACE = Duration.ofHours(1);

    @Override
    public Scan scan(String prefix, String startAfter, int limit, Consumer<Listed> consumer) throws IOException {
        if (limit <= 0) {
            throw new IllegalArgumentException("A scan limit must be positive: " + limit);
        }
        Path base = resolve(prefix);
        Path rootPath = root.normalize();
        // The same capped-TreeMap selection page() uses, for the same reason: a file tree is walked in whatever order
        // the directories hand it over, and the page owed is the lexicographically smallest keys past startAfter.
        // Holding limit + 1 of them costs O(limit) however many millions the prefix contains. The extra one is what
        // distinguishes "the page exactly drained the prefix" from "there is more" without a second pass.
        TreeMap<String, Listed> smallest = new TreeMap<>();
        String after = startAfter == null ? "" : startAfter;
        if (!Files.isDirectory(base)) {
            return Scan.exhausted(0, 1);
        }
        Files.walkFileTree(base, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) {
                String name = path.getFileName().toString();
                // The same in-flight .upload*.tmp filter as list() and page(), so a concurrent atomic write is never
                // scanned out as a stored object - and, past a grace window, the one place they are RECLAIMED.
                //
                // A write creates a temp and atomically moves it into place; a crash between the two leaves the temp
                // behind, filtered out of every listing and therefore invisible to everything - it is not an object,
                // so no sweep counts it, and the volume ratchets toward a permanent full. Reaping it here is a side
                // effect in a read, which needs justifying: this is the only traversal that visits every file under a
                // prefix, the class that creates the temps is the one that knows their shape, and the grace window is
                // what keeps a concurrent in-flight write safe. A failure to delete is ignored - another node may
                // have won the race, and a scan must not fail because a reclaim did.
                if (name.startsWith(".upload") && name.endsWith(".tmp")) {
                    if (attributes.lastModifiedTime().toInstant().isBefore(Instant.now().minus(TEMP_GRACE))) {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException _) {
                            // Raced, or not ours to delete. The next scan tries again.
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
                String key = rootPath.relativize(path.normalize()).toString().replace(File.separatorChar, '/');
                if (key.compareTo(after) <= 0) {
                    return FileVisitResult.CONTINUE;
                }
                if (smallest.size() < ArtifactStore.oneMoreThan(limit)) {
                    smallest.put(key, listed(key, attributes));
                } else if (key.compareTo(smallest.lastKey()) < 0) {
                    smallest.put(key, listed(key, attributes));
                    smallest.pollLastEntry();
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path path, IOException failure) throws IOException {
                // A file that VANISHED is not a file that could not be examined. The walk reads a directory and
                // then stats each name it found, and a concurrent write closes that gap constantly: the temp this
                // very visitor filters out is renamed into place between the two, and the stat then fails. An
                // enumeration that omits a file which no longer exists is not short - it is correct, because a
                // deleted file is exactly what a listing is entitled not to report.
                //
                // Measured by the cache soak before this was so: under sustained publishing the reaper's project
                // walk aborted on a NoSuchFileException for a .upload*.tmp and logged "cache reaper sweep failed;
                // retrying next interval" - so the sweep was skipped whenever a write was in flight, which under
                // continuous load is every interval. The cap then stops being enforced by the mechanism whose
                // whole job is to enforce it, silently, with nothing but a warning to say so.
                //
                // Every other failure still throws, and for the original reason: a short scan is how a sweep
                // learns a prefix is drained, so a file that is THERE and cannot be read must fail the call rather
                // than silently shorten it into a claim of completeness.
                if (failure instanceof NoSuchFileException) {
                    return FileVisitResult.CONTINUE;
                }
                throw failure;
            }
        });
        boolean more = smallest.size() > limit;
        if (more) {
            smallest.pollLastEntry();
        }
        String last = null;
        for (Listed entry : smallest.values()) {
            consumer.accept(entry);
            last = entry.key();
        }
        return more ? Scan.truncated(last, smallest.size(), 1) : Scan.exhausted(smallest.size(), 1);
    }

    /** Both halves of the metadata come from the attributes the visitor was handed, so a scan stats nothing. */
    private static Listed listed(String key, BasicFileAttributes attributes) {
        return Listed.of(key, attributes.size(), attributes.lastModifiedTime().toInstant());
    }

    @Override
    public Optional<Capacity> capacity() throws IOException {
        // A real volume, so a real answer - and a failure to measure one throws rather than reporting empty, which
        // would read as "this backend has no volume" and silently disable a free-space policy.
        // A scoped store's root need not exist yet - a tenant subspace is a directory the first write creates - and
        // the volume is the same either way, so measure the nearest ancestor that does. Only a root with no existing
        // ancestor at all is a real failure, and that throws.
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
                // Raced with a delete: there is nothing left to mark, and recency-on-read is advisory anyway.
            }
        }
    }

    @Override
    public Optional<Versioned> readVersioned(String key) throws IOException {
        Path path = resolve(key);
        // Through the same discrimination exists() makes, and for the same reason: an unreadable pointer answering
        // Optional.empty() is how "the marker is not there, serve it" and "no other alias holds these bytes, lift the
        // hold" get decided on a store that could not be read. Absence is a value; a failure to look is not.
        if (!regularFile(path)) {
            return Optional.empty();
        }
        // The regular-file probe and the token/content reads are not one atomic operation: a concurrent delete can
        // vanish the file in the window between the probe and the reads (or between reading the token and the bytes),
        // which throws NoSuchFileException (or, on some providers, FileNotFoundException) where the contract - and the
        // object-store backends' 404 -> empty behaviour - is Optional.empty(). Map that race to absent, so a reader
        // that lost to a delete simply sees no object, never an escaping exception.
        try {
            // Stamp before content: a write landing in between then pairs the OLD stamp with NEW content, so a
            // compare-and-set from this read loses and retries - the safe direction. The reverse order would pair
            // a fresh stamp with stale content and let a stale update pass as current.
            long modified = Files.getLastModifiedTime(path).toMillis();
            byte[] content = Files.readAllBytes(path);
            return Optional.of(new Versioned(content, token(modified, content)));
        } catch (NoSuchFileException | FileNotFoundException e) {
            return Optional.empty();
        }
    }

    /**
     * The opaque version token: the last-modified stamp <em>and</em> a digest of the stored bytes, so it identifies
     * the object incarnation rather than the tick it was written in.
     *
     * <p>The stamp alone was not enough, and the gap is. A stamp is a property of a <em>moment</em>, not of an
     * object: delete a key and re-create it inside the same millisecond and the new incarnation carries the same
     * stamp, so a token read from the object that is now gone still passes the compare-and-set and a stale write lands
     * over content it never saw. The window is not the filesystem's - ext4 timestamps are nanosecond-resolution - it is
     * this token's, because {@code toMillis()} truncates to it; and it is reachable through the plain SPI, where
     * {@link #delete} plus a create-if-absent {@link #writeVersioned} on the same key is how a revoked credential's
     * metadata, a swept garbage-collection marker and a feed snapshot pointer are all re-created. Two writes inside one
     * tick were already handled (the stamp is nudged forward below); the deleted-and-re-created incarnation was the
     * case that hack could not see, because there is no earlier stamp left to compare against.
     *
     * <p>Folding the content in closes it without inventing a rule: an S3 or Azure ETag <em>is</em> a content
     * identity, and a GCS generation is a per-incarnation counter, so this is the filesystem reaching the identity its
     * three peer backends already have rather than a fourth semantics (&sect;13). What remains identical across an
     * incarnation boundary is a key deleted and re-created with byte-identical content at the same stamp - where the
     * stored state a stale token still passes against is the state its holder read, so the compare-and-set concedes
     * nothing.
     *
     * <p>The content digest is the length and two CRCs (CRC-32 and CRC-32C, 64 bits between them), not a
     * cryptographic hash: the token tells incarnations apart, it does not certify bytes, and every versioned read
     * and write computes it over the whole object - a multi-megabyte listing included - so it is computed at memory
     * speed. Both CRCs are intrinsics on every platform the JDK targets.
     */
    private static Object token(long modified, byte[] content) {
        CRC32 crc = new CRC32();
        crc.update(content);
        CRC32C crcc = new CRC32C();
        crcc.update(content);
        return modified + ":" + content.length + ":" + Long.toHexString(crc.getValue()) + ":"
                + Long.toHexString(crcc.getValue());
    }

    @Override
    public boolean writeVersioned(String key, byte[] content, Object expected) throws IOException {
        Path path = resolve(ArtifactStore.key(key));
        synchronized (LOCKS[Math.floorMod(path.hashCode(), LOCKS.length)]) {
            boolean present = Files.isRegularFile(path);
            long modified = present ? Files.getLastModifiedTime(path).toMillis() : -1L;
            Object current = present ? token(modified, Files.readAllBytes(path)) : null;
            if (!Objects.equals(current, expected)) {
                return false;
            }
            // The same .upload*.tmp shape a keyed write spools through, so list()'s in-flight filter hides this
            // temp file too and an aborted write never leaves it behind; createUploadTemp re-creates the parent if a
            // concurrent delete tidied it away.
            Path temp = createUploadTemp(path.getParent());
            try {
                Files.write(temp, content);
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                // The token must advance on every successful update, including a re-write of byte-identical content
                // (which the digest half of the token cannot distinguish): two writes inside one clock tick would
                // otherwise leave it unchanged, and a third writer holding the pre-update token would still pass
                // the compare - a stale write disguised as a fresh one.
                if (present && Files.getLastModifiedTime(path).toMillis() <= modified) {
                    Files.setLastModifiedTime(path, FileTime.fromMillis(modified + 1));
                }
            } catch (IOException e) {
                Files.deleteIfExists(temp);
                throw e;
            }
            return true;
        }
    }
}
