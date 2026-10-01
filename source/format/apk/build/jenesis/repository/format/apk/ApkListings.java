package build.jenesis.repository.format.apk;

import module java.base;
import module org.apache.commons.compress;

import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.format.lifecycle.Lifecycle;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.StoredListing;
import build.jenesis.repository.store.OwnerOnly;

/**
 * {@code APKINDEX} as a stored listing, and the {@code APKINDEX.tar.gz} a client fetches.
 *
 * <p>The index is one block per package version separated by a blank line ({@link StoredListing.Codec#delimited}), so a
 * publish rewrites one block. The archive is a derived twin: a block can be replaced in text but not inside a
 * compressed archive, and the twin derives from the document's sequence, so a client never reads an archive older than
 * its index.
 *
 * <p>Each publish stores its rendered block at {@code apk/<repo>/<arch>/index/<file>} beside the pool pointer, the
 * durable truth a hold, release or yank re-decides membership from, and the generator rebuilds from.
 *
 * <p>No {@code DESCRIPTION} member: that is Alpine's own release metadata. The signature member is written
 * ({@link ApkSigner}).
 */
final class ApkListings {

    /** The tar block size the container is laid out in. */
    private static final int BLOCK = 512;

    /** The name of the index inside the archive, which is what a client reads. */
    private static final String APKINDEX = "APKINDEX";

    /** Blocks are separated by a blank line, and a block names itself on its {@code P:} and {@code V:} lines. */
    static final StoredListing.Codec INDEX = StoredListing.Codec.delimited("\n\n", ApkIndex::keyOf);

    private final Blobs blobs;

    private final ArtifactStore store;

    ApkListings(Blobs blobs) {
        this.blobs = blobs;
        this.store = blobs.store();
    }

    // ---- names ----

    /** The index text of one repository and architecture; the client asks per architecture. */
    static String index(String repo, String architecture) {
        return "apk/" + repo + "/" + architecture + "/APKINDEX";
    }

    /** The archive derived from it, which is the name a client requests. */
    static String archive(String repo, String architecture) {
        return index(repo, architecture) + ".tar.gz";
    }

    /** Where a package's bytes are pointed at, keyed by the file name the client asks for. */
    static String packageKey(String repo, String architecture, String file) {
        return "apk/" + repo + "/" + architecture + "/" + file;
    }

    /** The container of one repository and architecture's rendered blocks: empty until something is published. */
    static String blocks(String repo, String architecture) {
        return "apk/" + repo + "/" + architecture + "/index";
    }

    /** Where a package's rendered block is stored, so a later transition re-decides it without the archive. */
    static String blockKey(String repo, String architecture, String file) {
        return "apk/" + repo + "/" + architecture + "/index/" + file;
    }

    StoredListing.Spec indexSpec(String repo, String architecture) {
        return StoredListing.Spec.materialising(index(repo, architecture), INDEX, () -> generate(repo, architecture))
                .deriving(document -> derive(repo, architecture, document));
    }

    // ---- reading ----

    /** Derive the archive from the index document without holding either: each stage is a file, and the digests fall
     *  out of the assembly pass. */
    private void derive(String repo, String architecture, StoredListing.Derived document) throws IOException {
        Path assembled = wrap(document::open, document.header().size());
        try {
            MessageDigest sha256 = digest("SHA-256");
            try (InputStream archive = new BufferedInputStream(Files.newInputStream(assembled));
                 OutputStream sink = OutputStream.nullOutputStream();
                 DigestOutputStream digesting = new DigestOutputStream(sink, sha256)) {
                archive.transferTo(digesting);
            }
            long size = Files.size(assembled);
            StoredListing.Header header = StoredListing.Header.of(document.header().seq(), size, "",
                    HexFormat.of().formatHex(sha256.digest()), StoredListing.Header.UNKNOWN);
            StoredListing.derive(store, archive(repo, architecture), header, size,
                    () -> new BufferedInputStream(Files.newInputStream(assembled)));
        } finally {
            Files.deleteIfExists(assembled);
        }
    }

    private static MessageDigest digest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(algorithm + " is required of every JVM", e);
        }
    }

    /** Re-derive the archive from the newest stored index, which a read inside the derivation's window runs, so a
     *  client never fetches an archive older than the index: a client checks each package against the {@code C:} it
     *  read, so a stale index is a failed install. */
    void rederive(String repo, String architecture) throws IOException {
        // Streamed, never read whole: the index is every package of the architecture.
        Optional<StoredListing.Served> served = StoredListing.open(store, indexSpec(repo, architecture));
        if (served.isPresent()) {
            try (StoredListing.Served document = served.get()) {
                derive(repo, architecture, document.derived());
            }
        }
    }

    /**
     * The archive a client downloads: a signature member, then an {@code APKINDEX} member.
     *
     * <p>It is <b>one tar stream split across gzip members</b>, not two tars: the signature covers every byte after the
     * first member, so the boundary is a gzip member boundary while the tar runs through it. <b>Only the last member
     * carries the tar end-of-archive blocks</b>, and none the 10 KiB record padding. An end-of-archive marker in the
     * signature member makes {@code apk update} answer {@code BAD archive} while every Java tar reader parses the file,
     * since each opens the members separately.
     */
    byte[] wrap(byte[] index) throws IOException {
        Path assembled = wrap(() -> new ByteArrayInputStream(index), index.length);
        try {
            return Files.readAllBytes(assembled);
        } finally {
            Files.deleteIfExists(assembled);
        }
    }

    /** The signed archive as a file: the document member is built first and the signer scans it, two passes over a
     *  file; the signature member is a few hundred bytes and stays an array. */
    private Path wrap(StoredListing.Body index, long size) throws IOException {
        Path document = member(APKINDEX, index, size, true);
        try {
            byte[] signature = member(ApkSigner.ENTRY,
                    ApkSigner.of(blobs).sign(() -> new BufferedInputStream(Files.newInputStream(document))), false);
            Path archive = OwnerOnly.createTempFile("jenrepo-apkindex", ".tar.gz");
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(archive));
                 InputStream body = new BufferedInputStream(Files.newInputStream(document))) {
                out.write(signature);
                body.transferTo(out);
            } catch (IOException | RuntimeException failed) {
                Files.deleteIfExists(archive);
                throw failed;
            }
            return archive;
        } finally {
            Files.deleteIfExists(document);
        }
    }

    /** {@link #member(String, byte[], boolean)} over a body too large to hold, written to a file: the same output, with
     *  the padding and end-of-archive blocks cut by truncating the file and the modification time pinned. */
    private static Path member(String name, StoredListing.Body content, long size, boolean end) throws IOException {
        Path blocks = OwnerOnly.createTempFile("jenrepo-apkindex", ".tar");
        try {
            try (OutputStream raw = new BufferedOutputStream(Files.newOutputStream(blocks));
                 TarArchiveOutputStream tar = new TarArchiveOutputStream(raw, "UTF-8")) {
                TarArchiveEntry entry = new TarArchiveEntry(name);
                entry.setSize(size);
                entry.setModTime(0L);
                tar.putArchiveEntry(entry);
                try (InputStream body = content.open()) {
                    body.transferTo(tar);
                }
                tar.closeArchiveEntry();
            }
            try (FileChannel channel = FileChannel.open(blocks, StandardOpenOption.WRITE)) {
                channel.truncate(BLOCK + (size + BLOCK - 1) / BLOCK * BLOCK + (end ? 2L * BLOCK : 0));
            }
            Path compressed = OwnerOnly.createTempFile("jenrepo-apkindex", ".gz");
            try (InputStream in = new BufferedInputStream(Files.newInputStream(blocks));
                 OutputStream out = new BufferedOutputStream(Files.newOutputStream(compressed));
                 GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(out)) {
                in.transferTo(gzip);
            } catch (IOException | RuntimeException failed) {
                Files.deleteIfExists(compressed);
                throw failed;
            }
            return compressed;
        } finally {
            Files.deleteIfExists(blocks);
        }
    }

    /** One gzip member carrying one tar entry, cut to its block count: {@link TarArchiveOutputStream} pads to a 10 KiB
     *  record and writes end-of-archive blocks on close, so the result is truncated to the header and the entry's
     *  padded data, the two zero blocks kept only for the member ending the stream. */
    private static byte[] member(String name, byte[] content, boolean end) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream(content.length + 4 * BLOCK);
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(raw, "UTF-8")) {
            TarArchiveEntry entry = new TarArchiveEntry(name);
            entry.setSize(content.length);
            // Pinned, so two archives derived from one unchanged document are byte-identical, and so are their
            // validators.
            entry.setModTime(0L);
            tar.putArchiveEntry(entry);
            tar.write(content);
            tar.closeArchiveEntry();
        }
        int used = BLOCK + (content.length + BLOCK - 1) / BLOCK * BLOCK + (end ? 2 * BLOCK : 0);
        byte[] blocks = Arrays.copyOf(raw.toByteArray(), used);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream(used / 2 + 64);
        try (GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(compressed)) {
            gzip.write(blocks);
        }
        return compressed.toByteArray();
    }

    // ---- the write path ----

    /** A package was published: store its block, and list it if it is servable. */
    void published(String repo, String architecture, String file, String block) throws IOException {
        blobs.write(blockKey(repo, architecture, file), block.getBytes(StandardCharsets.UTF_8));
        refresh(repo, architecture, file, block);
    }

    /** Re-decide one package's membership from the store's current state. */
    void refresh(String repo, String architecture, String file) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        if (!blobs.read(blockKey(repo, architecture, file), buffer)) {
            // No stored block: never published here, or removed; its key is the file's stem.
            StoredListing.remove(store, indexSpec(repo, architecture), stem(file));
            return;
        }
        refresh(repo, architecture, file, buffer.toString(StandardCharsets.UTF_8));
    }

    private void refresh(String repo, String architecture, String file, String block) throws IOException {
        StoredListing.Spec spec = indexSpec(repo, architecture);
        if (servable(repo, architecture, file, block)) {
            StoredListing.put(store, spec, ApkIndex.keyOf(block), block.stripTrailing().getBytes(StandardCharsets.UTF_8));
        } else {
            StoredListing.remove(store, spec, ApkIndex.keyOf(block));
        }
    }

    /** Whether the package is served - pointer not withheld, version not yanked - the screen the download applies. */
    private boolean servable(String repo, String architecture, String file, String block) throws IOException {
        if (blobs.withheld(packageKey(repo, architecture, file))) {
            return false;
        }
        String name = ApkIndex.field(block, "P");
        String version = ApkIndex.field(block, "V");
        if (name == null || version == null) {
            return false;
        }
        return Lifecycle.read(store, name, version)
                .filter(flag -> flag.state() == Lifecycle.State.YANKED)
                .isEmpty();
    }

    /** The index as built from the stored blocks: the first materialisation and the repair path. It collects: the key
     *  is read from each block's contents, so scan order and key order are unrelated, and the sorted map is what orders
     *  the document. */
    private SortedMap<String, byte[]> generate(String repo, String architecture) throws IOException {
        SortedMap<String, byte[]> entries = new TreeMap<>();
        String prefix = "apk/" + repo + "/" + architecture + "/index";
        for (String file : blobs.list(prefix)) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            if (!blobs.read(prefix + "/" + file, buffer)) {
                continue;
            }
            String block = buffer.toString(StandardCharsets.UTF_8);
            if (servable(repo, architecture, file, block)) {
                entries.put(ApkIndex.keyOf(block), block.stripTrailing().getBytes(StandardCharsets.UTF_8));
            }
        }
        return entries;
    }

    /** Regenerate the listing at this key if it is an apk one; its archive twin regenerates with it. */
    boolean rebuild(String listing) throws IOException {
        String[] segments = listing.split("/");
        if (segments.length != 4 || !segments[0].equals("apk")) {
            return false;
        }
        if (segments[3].equals("APKINDEX")) {
            StoredListing.rebuild(store, indexSpec(segments[1], segments[2]));
            return true;
        }
        return segments[3].equals("APKINDEX.tar.gz");
    }

    /** A package file's stem, which is the key its block is listed under - {@code <name>-<version>}. */
    static String stem(String file) {
        return file.endsWith(".apk") ? file.substring(0, file.length() - ".apk".length()) : file;
    }
}
