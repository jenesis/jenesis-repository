package build.jenesis.repository.format.debian;

import module java.base;

import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.format.lifecycle.Lifecycle;
import build.jenesis.repository.format.LifecycleMark;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.StoredListing;
import build.jenesis.repository.walk.BoundedChildren;
import build.jenesis.repository.format.signing.OpenPgpSigner;
import build.jenesis.repository.store.OwnerOnly;

/**
 * The Debian suite's served indexes as stored listings: a {@code Packages} document per component and architecture, its
 * {@code Packages.gz} twin, and the suite's {@code Release} (with {@code InRelease} and {@code Release.gpg} when a
 * signing key is provisioned), each written when a push, hold, release, yank or removal changes it and streamed as
 * stored on every read.
 *
 * <p>A {@code Packages} write lands on the request; the twins, the suite manifest and the {@code Release} family are
 * {@linkplain StoredListing#later deferred}, one unit per suite, so a burst of pushes costs one derivation. The suite's
 * {@linkplain #touched stamp} records the newest {@code Packages} write and its {@linkplain #announced acknowledgement}
 * the newest one derived; a read of the {@code Release} family compares the two, a read of {@code Packages.gz} its
 * twin's sequence with the index's, and each derives on the spot inside the lag window, so a client never sees a twin
 * or {@code Release} older than its index.
 *
 * <p>A {@code Packages} entry is a package stanza keyed by its {@code Filename}'s file name, present exactly when the
 * package is servable - pool pointer not withheld, version not yanked. The {@code Release} names the digests of every
 * index with a servable package, derived from a per-suite manifest of those digests that every {@code Packages} write
 * updates, so two writers of different indexes serialise on the manifest.
 *
 * <p>The pool pointers and the stanzas under {@code debian/<suite>/index/...} are the durable truth a missing listing
 * is generated from, and the {@code by/} reverse index maps a coordinate to its pool keys without walking the pool.
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

    /** The {@code Packages} index of one component and architecture. Its write stamps the suite and defers the twins,
     *  the manifest and the {@code Release} family, coalesced per suite. */
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

    /** Bring the suite's announced state up to its indexes: every lagging twin, the manifest and the {@code Release}
     *  family. The deferred unit, and what a read inside the lag window runs. Acknowledges the stamp it started from,
     *  so a write landing meanwhile is folded by the next unit or read. */
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

    /** The {@code Packages} index with only its twin derived, which the manifest's generation reads the indexes
     *  through. */
    private StoredListing.Spec indexOnly(String suite, String component, String architecture) {
        return StoredListing.Spec.of(packages(suite, component, architecture), STANZAS,
                        sink -> generatePackages(suite, component, architecture, sink))
                .withMd5()   // the Release names the index's MD5 beside its SHA-256
                .deriving(document -> deriveCompressed(suite, component, architecture, document));
    }

    /** Derive this index's {@code .gz} twin from its newest stored {@code Packages}. */
    void compress(String suite, String component, String architecture) throws IOException {
        // Streamed, never read whole: an index is every package in the suite, and holding it exhausts a small heap.
        Optional<StoredListing.Served> served = StoredListing.open(store, indexOnly(suite, component, architecture));
        if (served.isPresent()) {
            try (StoredListing.Served document = served.get()) {
                deriveCompressed(suite, component, architecture, document.derived());
            }
        }
    }

    /** Derive the {@code .gz} twin; its header, digested here, is what the manifest names. Through a temporary file, so
     *  the peak is a buffer rather than two copies of the index - the document and its gzip - on every publish. */
    private StoredListing.Header deriveCompressed(String suite, String component, String architecture,
                                                  StoredListing.Derived document) throws IOException {
        Path compressed = OwnerOnly.createTempFile("jenrepo-packages", ".gz");
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

    /** Emit an entry per package in the scan's order, the store's lexicographic child order, which is the order the
     *  sink needs since the key is the child name itself. */
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
                    // Not materialised: rebuild it and its twin, without the manifest derivation this generation is
                    // producing.
                    plain = Optional.of(StoredListing.rebuild(store, indexOnly(suite, component, architecture)));
                }
                if (plain.isEmpty() || plain.get().size() == 0) {
                    continue;
                }
                Optional<StoredListing.Header> gz = StoredListing.header(store,
                        packages(suite, component, architecture) + ".gz");
                if (gz.isEmpty() || gz.get().seq() != plain.get().seq() || gz.get().md5().isEmpty()
                        || plain.get().md5().isEmpty()) {
                    // The twin is missing or lags, or a header lacks an MD5: re-derive from the stored body, streamed,
                    // rebuilding a body without an MD5 first, since the manifest names both digests.
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

    /** Re-derive the suite's {@code Release} family from the stored manifest, so a newly provisioned key signs it at
     *  once. */
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

    /** Whether the stanza's package is served: the pool pointer not withheld and the version not yanked, the screen the
     *  download applies. */
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
                .filter(flag -> flag.state() == LifecycleMark.YANKED)
                .isPresent();
    }

    /** The index containers of a suite carrying a stanza of this file: those a hold or mark on it must refresh. */
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

    /** The stride the repository-wide index is enumerated in. It drains: the index names every package, so neither
     *  names nor round-trips are capped - a cap would omit packages or throw and never materialise the document - and
     *  only the names in hand are bounded. */
    private static final BoundedChildren ENTRIES = BoundedChildren.draining();
}
