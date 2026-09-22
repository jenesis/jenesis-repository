package build.jenesis.repository.format.debian;

import module java.base;

import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.format.lifecycle.Lifecycle;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.StoredListing;
import build.jenesis.repository.walk.BoundedChildren;
import build.jenesis.repository.format.signing.OpenPgpSigner;
import build.jenesis.repository.store.OwnerOnly;

/**
 * The Debian suite's served indexes as stored listings: one {@code Packages} document per component and
 * architecture, its {@code Packages.gz} twin, and the suite's {@code Release} (with {@code InRelease} and
 * {@code Release.gpg} when a signing key is provisioned) - every one of them written when a push, a hold, a release
 * from hold, a yank or a removal changes it, and streamed as stored bytes on every read.
 *
 * <p>A {@code Packages} write lands on the request; what follows it - the gzip of the twins, the suite manifest, the
 * {@code Release} and its two signatures - is {@linkplain StoredListing#later deferred} off the request, one unit
 * per suite, so a burst of pushes costs one derivation rather than one per push. The suite's {@linkplain #touched
 * stamp} records the newest {@code Packages} write and its {@linkplain #announced acknowledgement} the newest one
 * the derivation has folded; a read of the {@code Release} family compares the two (two small reads), a read of
 * {@code Packages.gz} its twin's sequence with the index's, and each derives on the spot only when it arrives
 * inside the lag window - so a client never sees a twin or a {@code Release} older than the index.
 *
 * <p>A {@code Packages} document's entries are the package stanzas, keyed by the {@code .deb} file name the stanza's
 * {@code Filename} ends in; an entry exists exactly when the package is servable - its pool pointer not withheld, its
 * version not yanked - which is the screen the on-read generation applied per stanza and now the write path applies
 * to the one stanza it touches. The suite's {@code Release} lists the digests of every component/architecture index
 * that carries at least one servable package; it is derived from a per-suite <em>manifest</em> listing whose entries
 * are those indexes' digests, updated by every {@code Packages} write, so two writers of different indexes serialise
 * on the manifest and the last one's {@code Release} names both their documents.
 *
 * <p>The pool pointers and the per-package stanzas under {@code debian/<suite>/index/...} stay the durable truth the
 * listings are generated from when absent (a repository from before stored listings, or a document forgotten for
 * repair), and the {@code by/} reverse index written beside them maps a package coordinate back to its pool keys
 * without walking the pool.
 */
final class DebianListings {

    /** A {@code Packages} file: stanzas separated by a blank line, each keyed by its {@code Filename}'s file name. */
    static final StoredListing.Codec STANZAS = StoredListing.Codec.delimited("\n\n", DebianListings::fileOf);

    /** The suite manifest: one line per announced index, {@code <component>/binary-<arch>/Packages} first. */
    static final StoredListing.Codec MANIFEST = StoredListing.Codec.delimited("\n",
            line -> line.substring(0, line.indexOf(' ')));

    private final Blobs blobs;
    private final ArtifactStore store;
    private final Function<Blobs, OpenPgpSigner> signer;

    DebianListings(Blobs blobs, Function<Blobs, OpenPgpSigner> signer) {
        this.blobs = blobs;
        this.store = blobs.store();
        this.signer = signer;
    }

    // ---- names ----

    static String packages(String suite, String component, String architecture) {
        return "debian/" + suite + "/" + component + "/binary-" + architecture + "/Packages";
    }

    static String manifest(String suite) {
        return "debian/" + suite + "/manifest";
    }

    static String release(String suite, String name) {
        return "debian/" + suite + "/" + name;
    }

    /** The suite's stamp: a derived document whose sequence is that of the suite's newest {@code Packages} write. */
    static String touched(String suite) {
        return "debian/" + suite + "/@touched";
    }

    /** The suite's acknowledgement: a derived document whose sequence is the stamp the last completed derivation of
     *  the {@code Release} family had caught up to. */
    static String announced(String suite) {
        return "debian/" + suite + "/@announced";
    }

    static String stanzaKey(String suite, String component, String architecture, String file) {
        return "debian/" + suite + "/index/" + component + "/" + architecture + "/" + file;
    }

    /** The reverse index a push writes: {@code debian/<suite>/by/<package>/<version>/<file>} naming the pool key. */
    static String reverseKey(String suite, String coordinate, String version, String file) {
        return "debian/" + suite + "/by/" + coordinate + "/" + version + "/" + file;
    }

    static String fileOf(String stanza) {
        String filename = field(stanza, "Filename");
        if (filename == null) {
            return "";
        }
        return filename.substring(filename.lastIndexOf('/') + 1);
    }

    static String field(String stanza, String name) {
        for (String line : stanza.split("\n")) {
            if (line.startsWith(name + ":")) {
                return line.substring(name.length() + 1).trim();
            }
        }
        return null;
    }

    // ---- specs ----

    /** The {@code Packages} index of one component/architecture. Its write stamps the suite and defers the rest -
     *  the {@code .gz} twins, the suite manifest and through it the {@code Release} family - off the request,
     *  coalesced per suite. */
    StoredListing.Spec packagesSpec(String suite, String component, String architecture) {
        return StoredListing.Spec.of(packages(suite, component, architecture), STANZAS,
                        sink -> generatePackages(suite, component, architecture, sink))
                .withMd5()   // the Release names the index's MD5 beside its SHA-256
                .deriving(document -> {
                    StoredListing.derive(store, touched(suite), document.header().seq(), new byte[0]);
                    StoredListing.later(manifest(suite), () -> {
                        try {
                            announce(suite);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
                });
    }

    /** Bring the suite's announced state up to its indexes: every lagging {@code .gz} twin, the manifest and
     *  through it the {@code Release} family - the deferred unit, and what a read inside the lag window runs
     *  itself. Acknowledges the stamp it started from; a write landing meanwhile advances the stamp past it and
     *  the next unit (or the next read) folds that one. */
    void announce(String suite) throws IOException {
        Optional<StoredListing.Header> stamp = StoredListing.header(store, touched(suite));
        StoredListing.rebuild(store, manifestSpec(suite));
        if (stamp.isPresent()) {
            StoredListing.derive(store, announced(suite), stamp.get().seq(), new byte[0]);
        }
    }

    /** Whether the suite's {@code Release} family is current: the last derivation acknowledged the newest
     *  {@code Packages} write. Two small reads. */
    boolean current(String suite) throws IOException {
        Optional<StoredListing.Header> stamp = StoredListing.header(store, touched(suite));
        if (stamp.isEmpty()) {
            return true;
        }
        Optional<StoredListing.Header> acknowledged = StoredListing.header(store, announced(suite));
        return acknowledged.isPresent() && acknowledged.get().seq() >= stamp.get().seq();
    }

    /** The {@code Packages} index with only its {@code .gz} twin derived - what the manifest's own generation reads
     *  the indexes through, since it is computing the manifest those writes would otherwise update. */
    private StoredListing.Spec indexOnly(String suite, String component, String architecture) {
        return StoredListing.Spec.of(packages(suite, component, architecture), STANZAS,
                        sink -> generatePackages(suite, component, architecture, sink))
                .withMd5()   // the Release names the index's MD5 beside its SHA-256
                .deriving(document -> deriveCompressed(suite, component, architecture, document));
    }

    /** Derive this index's {@code .gz} twin from its newest stored {@code Packages} - what a twin read inside the
     *  lag window runs itself. */
    void compress(String suite, String component, String architecture) throws IOException {
        // Streamed from the stored index, never read whole: a Packages index is every package in the suite, and
        // holding it to compress it was the whole heap of a server that sets none - the debian-gzip canary's
        // finding at three hundred thousand stanzas under 512 MiB, on the read path of a Packages.gz that arrived
        // inside the derivation's lag window.
        Optional<StoredListing.Served> served = StoredListing.open(store, indexOnly(suite, component, architecture));
        if (served.isPresent()) {
            try (StoredListing.Served document = served.get()) {
                deriveCompressed(suite, component, architecture, document.derived());
            }
        }
    }

    /**
     * Derive the {@code .gz} twin; its header, digested once here, is what the manifest line names.
     *
     * <p>Through a temporary file, because a {@code Packages} index is every package in the suite and this used to
     * hold <b>two</b> copies of it at once - the document, and the gzip of the document - on the write path of
     * every publish. The bytes are compressed straight out of the source stream and digested as they are written,
     * so the peak is a buffer and the twin's digests still come from one pass.
     */
    private StoredListing.Header deriveCompressed(String suite, String component, String architecture,
                                                  StoredListing.Derived document) throws IOException {
        Path compressed = OwnerOnly.createTempFile("jenreg-packages", ".gz");
        try {
            MessageDigest md5 = digest("MD5");
            MessageDigest sha256 = digest("SHA-256");
            try (InputStream body = document.open();
                 OutputStream file = new BufferedOutputStream(Files.newOutputStream(compressed));
                 OutputStream digesting = new DigestOutputStream(new DigestOutputStream(file, sha256), md5);
                 OutputStream gz = new GZIPOutputStream(digesting)) {
                body.transferTo(gz);
            }
            long size = Files.size(compressed);
            StoredListing.Header gz = StoredListing.Header.of(document.header().seq(), size,
                    HexFormat.of().formatHex(md5.digest()), HexFormat.of().formatHex(sha256.digest()), 0L);
            StoredListing.derive(store, packages(suite, component, architecture) + ".gz", gz, size,
                    () -> new BufferedInputStream(Files.newInputStream(compressed)));
            return gz;
        } finally {
            Files.deleteIfExists(compressed);
        }
    }

    private static MessageDigest digest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(algorithm + " is required of every JVM", e);
        }
    }

    private static String manifestLine(String index, StoredListing.Header plain, StoredListing.Header gz) {
        return index + " " + plain.size() + " " + plain.md5() + " " + plain.sha256()
                + " " + gz.size() + " " + gz.md5() + " " + gz.sha256();
    }

    /** The suite manifest, deriving {@code Release}, {@code InRelease} and {@code Release.gpg} after every write. */
    StoredListing.Spec manifestSpec(String suite) {
        return StoredListing.Spec.materialising(manifest(suite), MANIFEST, () -> generateManifest(suite))
                .deriving(document -> deriveRelease(suite, document));
    }

    // ---- generation (first materialisation and repair) ----

    /**
     * Emit an entry per package, in the order the scan yields them.
     *
     * <p>The index names every package it covers, so collecting them into a sorted map held that whole set. The
     * scan's order is the sink's order - the store's lexicographic child order, which is where the sorted map's
     * ordering came from and is what now supplies it. The key here is the child name itself, which is what makes
     * that substitution sound: a key composed across nested scans would not be in scan order.
     */
    private void generatePackages(String suite, String component, String architecture,
                                  StoredListing.Generator.Sink sink) throws IOException {
        String prefix = "debian/" + suite + "/index/" + component + "/" + architecture;
        ENTRIES.scan(store, prefix, file -> {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            if (!blobs.read(prefix + "/" + file, buffer)) {
                return;
            }
            String stanza = buffer.toString(StandardCharsets.UTF_8);
            if (servable(stanza)) {
                sink.accept(file, stanza.stripTrailing().getBytes(StandardCharsets.UTF_8));
            }
        });
    }

    private SortedMap<String, byte[]> generateManifest(String suite) throws IOException {
        SortedMap<String, byte[]> entries = new TreeMap<>();
        List<String> components = new ArrayList<>(blobs.list("debian/" + suite + "/index"));
        Collections.sort(components);
        for (String component : components) {
            List<String> architectures = new ArrayList<>(blobs.list("debian/" + suite + "/index/" + component));
            Collections.sort(architectures);
            for (String architecture : architectures) {
                Optional<StoredListing.Header> plain = StoredListing.header(store,
                        packages(suite, component, architecture));
                if (plain.isEmpty()) {
                    // Not materialised yet: generate it (and its .gz) now, without the manifest derivation this very
                    // generation is producing - through the rebuild, which writes without holding the document and
                    // hands back the header, rather than a whole read of what it just wrote.
                    plain = Optional.of(StoredListing.rebuild(store, indexOnly(suite, component, architecture)));
                }
                if (plain.isEmpty() || plain.get().size() == 0) {
                    continue;
                }
                Optional<StoredListing.Header> gz = StoredListing.header(store,
                        packages(suite, component, architecture) + ".gz");
                if (gz.isEmpty() || gz.get().seq() != plain.get().seq() || gz.get().md5().isEmpty()
                        || plain.get().md5().isEmpty()) {
                    // The twin is missing, lags, or either header carries no MD5 (a document stored through a
                    // spec without one): re-derive from the stored body before naming it - streamed, never held,
                    // for the reason compress() gives; a body without an MD5 is rebuilt first, since the manifest
                    // names both digests and the rebuild's header carries them.
                    if (plain.get().md5().isEmpty()) {
                        StoredListing.rebuild(store, indexOnly(suite, component, architecture));
                    }
                    try (StoredListing.Served document = StoredListing.open(store,
                            indexOnly(suite, component, architecture)).orElseThrow()) {
                        gz = Optional.of(deriveCompressed(suite, component, architecture, document.derived()));
                        plain = Optional.of(document.header());
                    }
                }
                String index = component + "/binary-" + architecture + "/Packages";
                entries.put(index, manifestLine(index, plain.get(), gz.get()).getBytes(StandardCharsets.UTF_8));
            }
        }
        return entries;
    }

    // ---- the Release family ----

    private void deriveRelease(String suite, StoredListing.Derived manifest) throws IOException {
        long seq = manifest.header().seq();
        SortedMap<String, byte[]> entries = MANIFEST.split(manifest.body());
        byte[] release = render(suite, seq, entries);
        StoredListing.derive(store, release(suite, "Release"), seq, release);
        OpenPgpSigner signing = signer.apply(blobs);
        if (signing != null) {
            StoredListing.derive(store, release(suite, "InRelease"), seq, signing.clearSigned(release));
            StoredListing.derive(store, release(suite, "Release.gpg"), seq, signing.detachedSignature(release, OpenPgpSigner.Encoding.ARMOURED));
        }
    }

    /** Re-derive the suite's {@code Release} family from the stored manifest - after a signing key is provisioned,
     *  so the signed twins appear without waiting for the next push. */
    void rederiveRelease(String suite) throws IOException {
        Optional<StoredListing.Document> manifest = StoredListing.read(store, manifestSpec(suite));
        if (manifest.isPresent()) {
            deriveRelease(suite, manifest.get());
        }
    }

    private static byte[] render(String suite, long seq, SortedMap<String, byte[]> entries) {
        SequencedSet<String> architectures = new TreeSet<>();
        SequencedSet<String> components = new TreeSet<>();
        StringBuilder md5 = new StringBuilder("MD5Sum:\n");
        StringBuilder sha256 = new StringBuilder("SHA256:\n");
        for (byte[] entry : entries.values()) {
            String[] fields = new String(entry, StandardCharsets.UTF_8).split(" ");
            String index = fields[0];
            String component = index.substring(0, index.indexOf('/'));
            String architecture = index.substring(index.indexOf("/binary-") + "/binary-".length(),
                    index.lastIndexOf('/'));
            components.add(component);
            architectures.add(architecture);
            md5.append(' ').append(fields[2]).append(' ').append(fields[1]).append(' ').append(index).append('\n');
            md5.append(' ').append(fields[5]).append(' ').append(fields[4]).append(' ').append(index).append(".gz\n");
            sha256.append(' ').append(fields[3]).append(' ').append(fields[1]).append(' ').append(index).append('\n');
            sha256.append(' ').append(fields[6]).append(' ').append(fields[4]).append(' ').append(index)
                    .append(".gz\n");
        }
        String date = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(seq).atZone(ZoneId.of("GMT")));
        return new StringBuilder()
                .append("Origin: Jenesis\n")
                .append("Label: Jenesis\n")
                .append("Suite: ").append(suite).append('\n')
                .append("Codename: ").append(suite).append('\n')
                .append("Date: ").append(date).append('\n')
                .append("Architectures: ").append(String.join(" ", architectures)).append('\n')
                .append("Components: ").append(String.join(" ", components)).append('\n')
                .append("Description: Jenesis Debian repository\n")
                .append(md5).append(sha256).toString().getBytes(StandardCharsets.UTF_8);
    }

    // ---- the write path ----

    /** A package was pushed (or imported): its stanza is stored; list it if it is servable, drop it otherwise. */
    void published(String suite, String component, String architecture, String file, String stanza)
            throws IOException {
        refresh(suite, component, architecture, file, stanza);
    }

    /** Re-decide one stanza's membership from the store's current state - after a hold, a release or a mark. */
    void refresh(String suite, String component, String architecture, String file) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        if (!blobs.read(stanzaKey(suite, component, architecture, file), buffer)) {
            StoredListing.remove(store, packagesSpec(suite, component, architecture), file);
            return;
        }
        refresh(suite, component, architecture, file, buffer.toString(StandardCharsets.UTF_8));
    }

    private void refresh(String suite, String component, String architecture, String file, String stanza)
            throws IOException {
        StoredListing.Spec spec = packagesSpec(suite, component, architecture);
        if (servable(stanza)) {
            StoredListing.put(store, spec, file, stanza.stripTrailing().getBytes(StandardCharsets.UTF_8));
        } else {
            StoredListing.remove(store, spec, file);
        }
    }

    /** Whether the stanza's package is served: its pool pointer is not withheld and its version is not yanked - the
     *  same screen the pool download applies, so the index and the download agree. */
    boolean servable(String stanza) throws IOException {
        String filename = field(stanza, "Filename");
        if (filename == null || blobs.withheld("debian/" + filename)) {
            return false;
        }
        return !yanked(field(stanza, "Package"), field(stanza, "Version"));
    }

    private boolean yanked(String name, String version) throws IOException {
        if (name == null || version == null) {
            return false;
        }
        String withoutEpoch = version.indexOf(':') < 0 ? version : version.substring(version.indexOf(':') + 1);
        return Lifecycle.read(store, name, withoutEpoch)
                .filter(flag -> flag.state() == Lifecycle.State.YANKED)
                .isPresent();
    }

    /** The index containers (component, architecture) of a suite that carry a stanza of this file - the ones a hold or
     *  a mark on the package must refresh. */
    List<String[]> indexesOf(String suite, String component, String file) throws IOException {
        List<String[]> indexes = new ArrayList<>();
        for (String architecture : blobs.list("debian/" + suite + "/index/" + component)) {
            if (blobs.exists(stanzaKey(suite, component, architecture, file))) {
                indexes.add(new String[]{component, architecture});
            }
        }
        return indexes;
    }

    /** Regenerate the listing at this key if it is a Debian one: an index, or the suite manifest (whose derived
     *  Release family and the index twins regenerate with their sources). */
    boolean rebuild(String listing) throws IOException {
        String[] segments = listing.split("/");
        if (!segments[0].equals("debian") || segments.length < 3) {
            return false;
        }
        String suite = segments[1];
        if (segments.length == 3) {
            if (segments[2].equals("manifest")) {
                StoredListing.rebuild(store, manifestSpec(suite));
                return true;
            }
            return segments[2].equals("Release") || segments[2].equals("InRelease") || segments[2].equals("Release.gpg");
        }
        if (segments.length == 5 && segments[3].startsWith("binary-")) {
            if (segments[4].equals("Packages")) {
                StoredListing.rebuild(store, packagesSpec(suite, segments[2], segments[3].substring("binary-".length())));
                return true;
            }
            return segments[4].equals("Packages.gz");
        }
        return false;
    }

    static byte[] gzip(byte[] content) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(content);
        }
        return out.toByteArray();
    }

    /** The stride the repository-wide index is enumerated in. It <b>drains</b>: the index names every package by
     *  definition, so neither the names nor the round-trips that fetch them may cap it, and what is bounded is how
     *  many names are in hand. Capping either one silently omits packages - or, once the entry cap alone was lifted, stopped
     *  omitting them and started throwing instead, at exactly {@code steps x page} names. That is the ceiling the
     *  OCI tag canary hit at a million: a generator that raises {@code TraversalException} does not answer short,
     *  it never materialises the document at all. */
    private static final BoundedChildren ENTRIES = BoundedChildren.draining();
}
