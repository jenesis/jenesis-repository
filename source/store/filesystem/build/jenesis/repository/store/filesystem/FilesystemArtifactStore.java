package build.jenesis.repository.store.filesystem;

import build.jenesis.repository.store.ArtifactStore;

import module java.base;

/**
 * The default {@link ArtifactStore}: blobs under a mounted root directory, keyed by their object path.
 * Version tokens are the file's last-modified time, so {@link #writeVersioned} is a last-modified
 * compare-and-set, adequate for a single node; a clustered deployment uses an object-store backend whose
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
        Path dir = resolve(prefix);
        if (limit <= 0) {
            return;
        }
        // A directory listing is unordered and a filesystem has no start-at seek, so select the page in one
        // bounded scan: keep the limit smallest names past startAfter in a capped TreeSet - O(limit) memory
        // however many millions of entries the directory holds, where sorting list() would buffer them all.
        TreeSet<String> smallest = new TreeSet<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            for (Path path : entries) {
                String name = path.getFileName().toString();
                // The same in-flight .upload*.tmp filter as list(), so a concurrent atomic write never pages out.
                if (name.startsWith(".upload") && name.endsWith(".tmp") || name.compareTo(startAfter) <= 0) {
                    continue;
                }
                if (smallest.size() < limit) {
                    smallest.add(name);
                } else if (name.compareTo(smallest.last()) < 0) {
                    smallest.add(name);
                    smallest.pollLast();
                }
            }
        } catch (NoSuchFileException | NotDirectoryException _) {
            return; // mirror list(): a vanished container, or one that is itself a stored object, pages as empty
        } catch (IOException failure) {
            // NOT mirrored from the old list(): a short page is how the shared walk learns a container is drained,
            // so an unreadable directory paging as empty ends a traversal early and reports it as exhausted.
            throw new UncheckedIOException("Cannot page the children of " + dir, failure);
        }
        smallest.forEach(consumer);
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
            // Token before content: a write landing in between then pairs OLD token with NEW content, so a
            // compare-and-set from this read loses and retries - the safe direction. The reverse order would pair
            // a fresh token with stale content and let a stale update pass as current.
            long token = Files.getLastModifiedTime(path).toMillis();
            return Optional.of(new Versioned(Files.readAllBytes(path), token));
        } catch (NoSuchFileException | FileNotFoundException e) {
            return Optional.empty();
        }
    }

    @Override
    public boolean writeVersioned(String key, byte[] content, Object expected) throws IOException {
        Path path = resolve(ArtifactStore.key(key));
        synchronized (LOCKS[Math.floorMod(path.hashCode(), LOCKS.length)]) {
            Object current = Files.isRegularFile(path) ? Files.getLastModifiedTime(path).toMillis() : null;
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
                // The token must advance on every successful update: two writes inside one clock tick would
                // otherwise leave it unchanged, and a third writer holding the pre-update token would still pass
                // the compare - a stale write disguised as a fresh one.
                if (current != null && Files.getLastModifiedTime(path).toMillis() <= (long) current) {
                    Files.setLastModifiedTime(path, FileTime.fromMillis((long) current + 1));
                }
            } catch (IOException e) {
                Files.deleteIfExists(temp);
                throw e;
            }
            return true;
        }
    }
}
