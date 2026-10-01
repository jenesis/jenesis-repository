package build.jenesis.repository.search.lucene;

import module java.base;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Documents;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import build.jenesis.repository.store.OwnerOnly;
import build.jenesis.repository.store.Names;

/**
 * Where the search index lives: its segment files in the store, once each, and a generation as the list of them.
 *
 * <p>Lucene writes an index as immutable segment files later generations share, so the store holds each file once under
 * its content digest, and a generation is a manifest naming its files. A pass fetches only the files its node lacks,
 * appends and uploads only what it created; a reader opens the generation as a file-backed directory. Rewriting a whole
 * index per pass would cost O(index) steps for one marker and a heap copy that does not fit at a million documents.
 *
 * <h2>The node's cache</h2>
 *
 * <p>Segment files are cached under the JVM's temporary directory by digest, one tree per store identity, and a
 * generation opens as a directory of hard links onto them (a copy where links are refused). Nothing durable lives in
 * the cache; it is rebuilt from the store when missing, and a generation's link directory is removed when it is
 * superseded.
 *
 * <h2>Layout</h2>
 *
 * <pre>
 * index/search/current                 the manifest pointing at the generation served (compare-and-set)
 * index/search/&lt;generation&gt;.manifest    one line per file: name, digest, length
 * index/search/segments/&lt;digest&gt;        a segment file, written once, shared by every generation naming it
 * </pre>
 */
final class SearchIndex {

    static final String DIRECTORY = "index/search";
    static final String MANIFEST = DIRECTORY + "/current";
    static final String SEGMENTS = DIRECTORY + "/segments";
    /** Bumped when the storage or document shape changes, so a reader of the old shape does not open the new one; the
     *  first pass after an upgrade rebuilds fully. 6: documents carry the manifest's description, keywords and authors,
     *  and when the index was last reconciled. */
    static final int FORMAT = 6;

    private final ArtifactStore store;

    /** How long an uncommitted generation's claim blocks the next rebuild of it: the {@code search-index-claim} dial,
     *  {@link #STALE_CLAIM} when unset. */
    private final Duration staleClaim;

    SearchIndex(ArtifactStore store) {
        this(store, STALE_CLAIM);
    }

    SearchIndex(ArtifactStore store, Duration staleClaim) {
        this.store = store;
        this.staleClaim = staleClaim;
    }

    Optional<ArtifactStore.Versioned> manifestVersioned() throws IOException {
        return store.readVersioned(MANIFEST);
    }

    /** Whether this repository holds an index at all - one existence probe of the manifest. */
    boolean exists() throws IOException {
        return store.exists(MANIFEST);
    }

    /** Remove the whole index: the manifest first, so readers and the publish observer stop seeing it at once, then
     *  every generation, segment and change marker, a page at a time. A removal that stops part way leaves no manifest,
     *  and the next one finishes it. */
    void remove() throws IOException {
        store.delete(MANIFEST);
        Documents.over(store).deleteAll(DIRECTORY + "/");
    }

    boolean putManifest(SearchManifest manifest, Object expected) throws IOException {
        return store.writeVersioned(MANIFEST, manifest.serialize(), expected);
    }

    /** One file of a generation: its Lucene name, the digest it is stored under, and its length. */
    record Segment(String name, String digest, long length) {
    }

    /** Record {@code directory} as {@code generation}: every file digested, uploaded under its digest if absent, linked
     *  into the node's cache and named in the manifest. Returns the manifest's own digest, the checksum the current
     *  manifest carries. */
    Optional<String> writeSnapshot(int generation, Directory directory) throws IOException {
        List<Segment> segments = new ArrayList<>();
        for (String file : directory.listAll()) {
            if (file.startsWith("write.lock")) {
                continue;
            }
            long length = directory.fileLength(file);
            String digest = digest(directory, file);
            if (!store.exists(segmentKey(digest))) {
                try (IndexInput input = directory.openInput(file, IOContext.READONCE)) {
                    store.write(segmentKey(digest), new IndexInputStream(input));
                }
            }
            cache(directory, file, digest);
            segments.add(new Segment(file, digest, length));
        }
        byte[] manifest = serialise(segments, Instant.now());
        if (!claim(generation, manifest)) {
            return Optional.empty();
        }
        return Optional.of(HexFormat.of().formatHex(sha256().digest(manifest)));
    }

    /** A claim on a generation older than this belongs to a rebuild that never cut over - a crash between writing its
     *  manifest and advancing the current pointer - and the next rebuild takes it over; a live rebuild cuts over within
     *  moments. The text is what the {@code search-index-claim} dial and the settings reference read, and the
     *  {@link Duration} derives from it. */
    static final String STALE_CLAIM_TEXT = "PT10M";

    static final Duration STALE_CLAIM = Duration.parse(STALE_CLAIM_TEXT);

    /** Write {@code manifest} under the generation's key only if no rebuild holds the generation: a create-only
     *  compare-and-set, or a takeover of a claim older than {@link #STALE_CLAIM} that never committed. Otherwise two
     *  rebuilds racing to one generation could leave the winner's committed checksum naming the loser's bytes, which a
     *  reader refuses. A rebuild whose claim fails has written only content-addressed segments for the segment
     *  collection, and commits nothing. */
    private boolean claim(int generation, byte[] manifest) throws IOException {
        Optional<ArtifactStore.Versioned> existing = store.readVersioned(generationKey(generation));
        if (existing.isEmpty()) {
            return store.writeVersioned(generationKey(generation), manifest, null);
        }
        int committed = manifestVersioned()
                .map(versioned -> SearchManifest.parse(versioned.content()).generation()).orElse(0);
        if (committed >= generation) {
            return false;                                // the generation is committed: nothing left to claim
        }
        Optional<Instant> claimed = claimedAt(existing.get().content());
        if (claimed.isPresent() && Instant.now().isBefore(claimed.get().plus(staleClaim))) {
            return false;                                // a live rebuild holds the generation and cuts over next
        }
        return store.writeVersioned(generationKey(generation), manifest, existing.get().token());
    }

    /** Open {@code generation} as a file-backed directory: its manifest read, every file fetched into the node's cache
     *  if absent, and a generation directory of links onto them. The caller closes it, and may append and hand it to
     *  {@link #writeSnapshot} as the next generation. */
    Directory openSnapshot(int generation) throws IOException {
        return openInto(generation, generationDirectory(generation));
    }

    /** Open {@code generation}'s files in the next generation's own directory, so a writer appending there never adds
     *  files to a directory a reader of {@code generation} holds open. */
    Directory next(int generation) throws IOException {
        return openInto(generation, generationDirectory(generation + 1));
    }

    private Directory openInto(int generation, Path links) throws IOException {
        List<Segment> segments = parse(readAll(generationKey(generation)));
        Files.createDirectories(links);
        Set<String> wanted = new HashSet<>();
        for (Segment segment : segments) {
            wanted.add(segment.name());
            Path cached = cached(segment.digest());
            if (!Files.exists(cached)) {
                fetch(segment.digest(), cached);
            }
            Path link = links.resolve(segment.name());
            // A link is verified against the cached file, not trusted by name: Lucene reuses segment names across
            // lineages, so a directory left by an uncommitted apply can hold another index's _0.si. Without hard links
            // the copy is never the same file and is re-copied on every open.
            if (Files.exists(link) && !Files.isSameFile(link, cached)) {
                Files.delete(link);
            }
            if (!Files.exists(link)) {
                link(cached, link);
            }
        }
        // Files the manifest does not name, left by an uncommitted apply, would read as a newer commit, so they go
        // first.
        try (Stream<Path> present = Files.list(links)) {
            for (Path file : present.toList()) {
                if (!wanted.contains(file.getFileName().toString())) {
                    Files.deleteIfExists(file);
                }
            }
        }
        return FSDirectory.open(links);
    }

    /** A fresh, empty, file-backed directory for a full rebuild, so the index being built is never held in heap. */
    static Directory scratch() throws IOException {
        return FSDirectory.open(OwnerOnly.createTempDirectory("jenrepo-search-rebuild"));
    }

    /** Close and remove a full rebuild's scratch directory once its files are linked or the build is abandoned. The
     *  path is read first, since a closed {@link FSDirectory} will not say where it was. */
    static void discard(Directory directory) throws IOException {
        if (directory instanceof FSDirectory local) {
            Path root = local.getDirectory();
            local.close();
            deleteTree(root);
        } else {
            directory.close();
        }
    }

    List<Integer> generations() throws IOException {
        List<Integer> generations = new ArrayList<>();
        Names names = Names.over(store, DIRECTORY);
        for (String name = names.next(); name != null; name = names.next()) {
            if (name.endsWith(".manifest")) {
                try {
                    generations.add(Integer.parseInt(name.substring(0, name.length() - ".manifest".length())));
                } catch (NumberFormatException _) {
                }
            }
        }
        return generations;
    }

    /** Forget a generation: its manifest and link directory go; its segment files stay until {@link #gcSegments} finds
     *  no generation naming them. */
    void deleteSnapshot(int generation) throws IOException {
        store.delete(generationKey(generation));
        deleteTree(generationDirectory(generation));
    }

    /** Remove every stored segment file no remaining generation names, by the pass holding the index's lease. */
    void gcSegments() throws IOException {
        Set<String> referenced = new HashSet<>();
        for (int generation : generations()) {
            try {
                for (Segment segment : parse(readAll(generationKey(generation)))) {
                    referenced.add(segment.digest());
                }
            } catch (IOException | RuntimeException unreadable) {
                return;   // a manifest that cannot be read keeps every segment: absence of evidence is not evidence
            }
        }
        Names digests = Names.over(store, SEGMENTS);
        for (String digest = digests.next(); digest != null; digest = digests.next()) {
            if (!referenced.contains(digest)) {
                store.delete(segmentKey(digest));
                Files.deleteIfExists(cached(digest));
            }
        }
    }

    long snapshotSize(int generation) throws IOException {
        long total = 0;
        for (Segment segment : parse(readAll(generationKey(generation)))) {
            total += segment.length();
        }
        return total;
    }

    static String generationKey(int generation) {
        return DIRECTORY + "/" + generation + ".manifest";
    }

    static String segmentKey(String digest) {
        return SEGMENTS + "/" + digest;
    }

    // ---- the manifest ----

    /** The manifest line that carries the claim instant, ahead of the segment lines. */
    private static final String CLAIMED = "claimed ";

    private static byte[] serialise(List<Segment> segments, Instant claimed) {
        StringBuilder text = new StringBuilder(CLAIMED).append(claimed.toEpochMilli()).append('\n');
        for (Segment segment : segments) {
            text.append(segment.name()).append(' ').append(segment.digest()).append(' ').append(segment.length())
                    .append('\n');
        }
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** When a manifest's generation was claimed, or empty for a manifest without a claim instant, which a takeover
     *  treats as stale. */
    private static Optional<Instant> claimedAt(byte[] manifest) {
        for (String line : new String(manifest, StandardCharsets.UTF_8).split("\n")) {
            if (line.startsWith(CLAIMED)) {
                try {
                    return Optional.of(Instant.ofEpochMilli(Long.parseLong(line.substring(CLAIMED.length()).trim())));
                } catch (NumberFormatException _) {
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }

    private static List<Segment> parse(byte[] manifest) throws IOException {
        List<Segment> segments = new ArrayList<>();
        for (String line : new String(manifest, StandardCharsets.UTF_8).split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            if (line.startsWith(CLAIMED)) {
                continue;                                // the claim instant, read by claimedAt
            }
            String[] fields = line.split(" ");
            if (fields.length != 3 || !fields[1].matches("[0-9a-f]{64}")) {
                throw new IOException("malformed search manifest line: " + line);
            }
            segments.add(new Segment(fields[0], fields[1], Long.parseLong(fields[2])));
        }
        return segments;
    }

    private byte[] readAll(String key) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        store.read(key, buffer);
        return buffer.toByteArray();
    }

    // ---- the node's cache ----

    private Path cacheRoot() throws IOException {
        String identity = HexFormat.of().formatHex(sha256().digest(
                String.valueOf(store.identity()).getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        Path root = Path.of(System.getProperty("java.io.tmpdir"), "jenrepo-search", identity);
        Files.createDirectories(root);
        return root;
    }

    private Path cached(String digest) throws IOException {
        return cacheRoot().resolve("segments").resolve(digest);
    }

    private Path generationDirectory(int generation) throws IOException {
        return cacheRoot().resolve("generation-" + generation);
    }

    private void fetch(String digest, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Path partial = OwnerOnly.createTempFile(target.getParent(), "fetch-", ".part");
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(partial))) {
            store.read(segmentKey(digest), out);
        }
        Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Put a file the writer produced into the cache under its digest, so the next open finds it local. */
    private void cache(Directory directory, String file, String digest) throws IOException {
        Path target = cached(digest);
        if (Files.exists(target)) {
            return;
        }
        if (directory instanceof FSDirectory local) {
            Path source = local.getDirectory().resolve(file);
            if (Files.exists(source)) {
                Files.createDirectories(target.getParent());
                try {
                    Files.createLink(target, source);
                    return;
                } catch (IOException | UnsupportedOperationException _) {
                    // fall through to a copy
                }
            }
        }
        Files.createDirectories(target.getParent());
        Path partial = OwnerOnly.createTempFile(target.getParent(), "cache-", ".part");
        try (IndexInput input = directory.openInput(file, IOContext.READONCE);
             OutputStream out = new BufferedOutputStream(Files.newOutputStream(partial))) {
            new IndexInputStream(input).transferTo(out);
        }
        Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void link(Path source, Path link) throws IOException {
        try {
            Files.createLink(link, source);
        } catch (IOException | UnsupportedOperationException _) {
            Files.copy(source, link, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static String digest(Directory directory, String file) throws IOException {
        MessageDigest digest = sha256();
        try (IndexInput input = directory.openInput(file, IOContext.READONCE)) {
            byte[] buffer = new byte[1 << 16];
            long remaining = input.length();
            while (remaining > 0) {
                int read = (int) Math.min(buffer.length, remaining);
                input.readBytes(buffer, 0, read);
                digest.update(buffer, 0, read);
                remaining -= read;
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** An {@link IndexInput} as a stream, for a store write. */
    private static final class IndexInputStream extends InputStream {

        private final IndexInput input;
        private long remaining;

        IndexInputStream(IndexInput input) {
            this.input = input;
            this.remaining = input.length();
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            remaining--;
            return input.readByte() & 0xff;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int read = (int) Math.min(length, remaining);
            input.readBytes(buffer, offset, read);
            remaining -= read;
            return read;
        }
    }
}
