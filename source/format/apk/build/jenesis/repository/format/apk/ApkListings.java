package build.jenesis.repository.format.apk;

import module java.base;
import module org.apache.commons.compress;

import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.format.lifecycle.Lifecycle;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.StoredListing;
import build.jenesis.repository.store.OwnerOnly;

/**
 * {@code APKINDEX} as a stored listing, and the {@code APKINDEX.tar.gz} an apk client actually fetches.
 *
 * <p>The index is one block per package version, blocks separated by a blank line - which is exactly the shape
 * {@link StoredListing.Codec#delimited} models. A publish rewrites the one block; nothing folds over the
 * repository.
 *
 * <p><b>The archive is a derived twin, not the document.</b> What a client downloads is a gzipped tar carrying an
 * {@code APKINDEX} member, so the served file wraps the stored text. Keeping the text as the document is what makes
 * an incremental write possible at all - a block can be replaced in a text file and cannot be replaced inside a
 * compressed archive without rewriting it - and the twin is derived from the document's own sequence, so the
 * archive a client reads never predates the index it was built from.
 *
 * <p><b>The per-package block is the durable truth.</b> Each publish writes its rendered block to
 * {@code apk/<repo>/<arch>/index/<file>} beside the pool pointer, the same shape the Debian format stores a stanza
 * in. That is what lets a hold, a release or a yank re-decide one package's membership without reopening the
 * {@code .apk} - and what the generator reads when the document has to be rebuilt.
 *
 * <p>Alpine's own index also carries a {@code DESCRIPTION} member, naming the branch it was built for. This one
 * does not: the description is Alpine's own release metadata and a repository that is not Alpine has nothing true
 * to put there. The signature member it also carries <em>is</em> written - see {@link ApkSigner}.
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

    /** The index text of one repository and architecture - the client asks per architecture, so this is keyed so. */
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

    /** Where a package's rendered block is stored, so a later transition can re-decide it without the archive. */
    static String blockKey(String repo, String architecture, String file) {
        return "apk/" + repo + "/" + architecture + "/index/" + file;
    }

    StoredListing.Spec indexSpec(String repo, String architecture) {
        return StoredListing.Spec.materialising(index(repo, architecture), INDEX, () -> generate(repo, architecture))
                .deriving(document -> derive(repo, architecture, document));
    }

    // ---- reading ----

    /**
     * Derive the archive from the index document, without holding either.
     *
     * <p>An {@code APKINDEX} is every package of one architecture, and the archive is a gzip of it - so the array
     * form held the index, its tar blocks, its gzip and the assembled archive, four sizes of the same thing, on
     * the write path of every publish. Each stage is a file now and the digests fall out of the assembly pass.
     */
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

    /**
     * Re-derive the archive from the newest stored index - what a read inside the derivation's own window runs
     * itself, so a client never fetches an archive older than the index beside it.
     *
     * <p>That matters more here than for a compressed twin elsewhere: an apk client verifies each downloaded
     * package against the {@code C:} in the index it read, so an index older than the packages beside it is not a
     * cosmetic staleness but a failed install.
     */
    void rederive(String repo, String architecture) throws IOException {
        // Streamed from the stored index, never read whole: the same shape the Debian twin had on its rebuild leg,
        // which the debian-gzip canary showed failing at three hundred thousand stanzas under 512 MiB.
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
     * <h2>The container, as an apk client reads it rather than as it looks</h2>
     *
     * <p>It is <b>one tar stream split across gzip members</b>, not two tars concatenated. The signature covers
     * every byte after the first member, so the boundary has to be a gzip member boundary - but the tar inside runs
     * straight through it, which means <b>only the last member carries the tar end-of-archive blocks</b> and none
     * of them carries the 10 KiB record padding a tar writer adds by default.
     *
     * <p>Both were measured, and both are silent when wrong: an end-of-archive marker in the signature member makes
     * a real {@code apk update} answer {@code BAD archive} while every Java tar reader parses the file happily,
     * because a reader that opens each member separately never sees the seam. Alpine's own published index is two
     * members of exactly 1024 and 2191872 uncompressed bytes - two blocks for the signature, and the index member
     * ending in the two zero blocks - which is what this reproduces.
     */
    byte[] wrap(byte[] index) throws IOException {
        Path assembled = wrap(() -> new ByteArrayInputStream(index), index.length);
        try {
            return Files.readAllBytes(assembled);
        } finally {
            Files.deleteIfExists(assembled);
        }
    }

    /**
     * The signed archive as a file: the signature member, then the document member.
     *
     * <p>The signature is over the document member's bytes, so the document member is built first and then
     * scanned by the signer - two passes over a file rather than one pass over four copies in heap. The signature
     * member itself is a few hundred bytes and stays an array, which is what it is.
     */
    private Path wrap(StoredListing.Body index, long size) throws IOException {
        Path document = member(APKINDEX, index, size, true);
        try {
            byte[] signature = member(ApkSigner.ENTRY,
                    ApkSigner.of(blobs).sign(() -> new BufferedInputStream(Files.newInputStream(document))), false);
            Path archive = OwnerOnly.createTempFile("jenreg-apkindex", ".tar.gz");
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

    /**
     * {@link #member(String, byte[], boolean)} over a body too large to hold, written to a file.
     *
     * <p>Identical output, and the same two corrections to {@link TarArchiveOutputStream}: the record padding and
     * the end-of-archive blocks are cut by truncating the file where the array form truncated the array, and the
     * entry's modification time is pinned so two archives derived from one unchanged document are byte-identical.
     */
    private static Path member(String name, StoredListing.Body content, long size, boolean end) throws IOException {
        Path blocks = OwnerOnly.createTempFile("jenreg-apkindex", ".tar");
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
            Path compressed = OwnerOnly.createTempFile("jenreg-apkindex", ".gz");
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

    /**
     * One gzip member carrying one tar entry, cut to the exact block count it uses.
     *
     * <p>{@link TarArchiveOutputStream} pads its output to a 10 KiB record and writes the end-of-archive blocks on
     * close, both of which are correct for a standalone tar and wrong for a member of this container - so the
     * result is truncated to the header block plus the entry's own padded data, and the two zero blocks are kept
     * only for the member that ends the stream.
     */
    private static byte[] member(String name, byte[] content, boolean end) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream(content.length + 4 * BLOCK);
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(raw, "UTF-8")) {
            TarArchiveEntry entry = new TarArchiveEntry(name);
            entry.setSize(content.length);
            // A tar entry's modification time defaults to now, which would make two archives derived from one
            // unchanged document differ byte for byte - and every validator computed over them with it.
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

    /** Re-decide one package's membership from the store's current state - after a hold, a release or a mark. */
    void refresh(String repo, String architecture, String file) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        if (!blobs.read(blockKey(repo, architecture, file), buffer)) {
            // No stored block: the package was never published through this format, or has been removed. Either way
            // there is nothing to decide, and the key it would have been listed under is the file's own stem.
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

    /** Whether the package is served: its pointer is not withheld and its version is not yanked - the same screen
     *  the download applies, so the index cannot advertise what the fetch refuses. */
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

    /**
     * The index as it would be built from the stored blocks - the first materialisation and the repair path.
     *
     * <p><b>It collects deliberately.</b> Every other repository-wide generator streams into a {@code Sink},
     * which owes ascending key order and takes that order from the scan. That does not hold here: the key is
     * {@code ApkIndex.keyOf(block)}, read out of each file's <em>contents</em>, so the scan order and the key
     * order are not two different orders - they are unrelated. The sorted map is what orders this document,
     * and removing it would write a misordered one that nothing would report.
     */
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
