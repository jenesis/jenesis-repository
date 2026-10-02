package build.jenesis.repository.format.apk;

import module java.base;

import build.jenesis.repository.blobs.BlobExport;
import build.jenesis.repository.blobs.BlobLayout;
import build.jenesis.repository.format.ExportTarget;
import build.jenesis.repository.format.RepositoryExporter;
import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.blobs.Keys;
import build.jenesis.repository.blobs.ProxyLeg;
import build.jenesis.repository.blobs.ProxyRelay;
import build.jenesis.repository.format.ProxyFormat;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import build.jenesis.repository.format.ArtifactLayout;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.ArtifactSignatures;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.StoredListing;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.format.Listings;

/**
 * The Alpine {@code apk} repository - an {@code APKINDEX.tar.gz} beside {@code .apk} packages - so {@code apk update}
 * and {@code apk add} work over the shared store. It owns {@code /apk/<repo>/<arch>/...}: the index at
 * {@code APKINDEX.tar.gz} and a package at {@code <name>-<version>.apk}. {@code /etc/apk/repositories} names
 * {@code <base>/apk/<repo>} and the client appends {@code /<arch>/APKINDEX.tar.gz}, which is why the architecture is a
 * path segment.
 *
 * <p><b>The coordinate comes from inside the package.</b> Name and version may both contain hyphens
 * ({@code musl-1.2.5-r3} splits at neither the first nor the last), so the publish reads {@code pkgname} and
 * {@code pkgver} from {@code .PKGINFO} and refuses a package whose metadata disagrees with its deploy path: a package
 * screened under one name must not be served under another.
 *
 * <p><b>The index is derived, not supplied.</b> Every field of an entry comes from the package's control segment or the
 * stored bytes ({@link ApkIndex}, {@link ApkPackage}), so a publisher cannot describe an artifact as something other
 * than what will be served.
 *
 * <p><b>The index is signed with the repository's own key.</b> A client reaches an unsigned repository only with
 * {@code --allow-untrusted}, which disables verification for every repository it uses, so the first publish generates
 * an RSA key pair, every archive carries a {@code .SIGN.RSA256.} member, and the public half is served at
 * {@code GET /apk/keys/jenesis.rsa.pub} for {@code /etc/apk/keys/} ({@link ApkSigner}).
 */
public final class ApkFormat implements RepositoryFormat, ArtifactLayout, BlobLayout, ArtifactSignatures, RepositoryExporter,
        RepositoryImporter, ProxyLeg {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(ApkFormat.class);

    /** The package-ecosystem name apk coordinates report. */
    public static final String ECOSYSTEM = "Alpine";

    private static final String PREFIX = "/apk/";

    /** The document a client fetches - the archive. */
    private static final String ARCHIVE = "APKINDEX.tar.gz";

    /** The same index as plain text: no client asks for it, an operator diagnosing a repository does. It is the stored
     *  document itself. */
    private static final String INDEX = "APKINDEX";

    private static final String APK = ".apk";

    /** How much of a published package is read to find its control segment, a few kilobytes whatever the payload: this
     *  bounds the metadata read, while the package streams into the store unbounded. */
    private static final int CONTROL_PREFIX = 8 * 1024 * 1024;

    /** Where the public half of the repository's signing key is served, for {@code /etc/apk/keys/}. */
    private static final String KEYS = "keys";

    @Override
    public String name() {
        return "apk";
    }

    private final ApkImporter importer = new ApkImporter();

    @Override
    public boolean imports(String format) {
        return importer.imports(format);
    }

    @Override
    public Optional<ArtifactDescriptor> importTarget(String path) {
        return importer.importTarget(path);
    }

    @Override
    public void importArtifact(String path, InputStream content, ArtifactStore store) throws IOException {
        importer.importArtifact(path, content, store);
    }

    /** A yank surfaces in the metadata this format's clients read, so one is accepted here. */
    @Override
    public boolean surfacesYank() {
        return true;
    }

    @Override
    public String ecosystem() {
        return ECOSYSTEM;
    }

    @Override
    public boolean handles(String path) {
        return path.startsWith(PREFIX);
    }

    @Override
    public void serve(FormatExchange exchange, ArtifactStore store) throws IOException {
        Blobs blobs = new Blobs(store);
        String rest = exchange.path().substring(PREFIX.length());
        String[] segments = rest.split("/");
        if (segments.length == 2 && segments[0].equals(KEYS) && segments[1].equals(ApkSigner.PUBLIC_KEY)) {
            // Two segments, checked first; a repository called "keys" has three-segment paths and is unaffected.
            servePublicKey(exchange, blobs);
            return;
        }
        if (segments.length != 3 || segments[0].isEmpty() || segments[1].isEmpty()) {
            exchange.respond(404);
            return;
        }
        String repo = segments[0], architecture = segments[1], file = segments[2];
        String method = exchange.method();
        if (method.equals("PUT") && file.endsWith(APK)) {
            push(exchange, blobs, repo, architecture, file);
        } else if (!method.equals("GET") && !method.equals("HEAD")) {
            exchange.respond(405);
        } else if (file.equals(ARCHIVE)) {
            serveArchive(exchange, blobs, repo, architecture);
        } else if (file.equals(INDEX)) {
            serveIndex(exchange, blobs, repo, architecture);
        } else if (file.endsWith(APK)) {
            servePackage(exchange, blobs, repo, architecture, file);
        } else {
            exchange.respond(404);
        }
    }

    // ---- the write path ----

    /** Publish one package: the bytes stream into the content-addressed store, then the stored blob is reopened to read
     *  its control segment. A package failing a check is an orphan blob for the collector, pointed at and listed by
     *  nothing. */
    private void push(FormatExchange exchange, Blobs blobs, String repo, String architecture, String file)
            throws IOException {
        if (Keys.unsafe(repo) || Keys.unsafe(architecture) || Keys.unsafe(file)) {
            exchange.respond(400);
            return;
        }
        String hash = blobs.store(exchange.requestStream());
        Optional<ApkPackage> read;
        try (InputStream stored = blobs.open(hash)) {
            read = ApkPackage.of(stored.readNBytes(CONTROL_PREFIX));
        } catch (RuntimeException | IOException malformed) {
            // Members that do not parse within the bounded prefix are a malformed upload: a 400, with nothing pointed
            // at.
            read = Optional.empty();
        }
        if (read.isEmpty()) {
            exchange.respond(400);
            return;
        }
        ApkPackage pkg = read.get();
        Optional<String> name = pkg.field("pkgname"), version = pkg.field("pkgver");
        if (name.isEmpty() || version.isEmpty()) {
            exchange.respond(400);
            return;
        }
        if (!file.equals(name.get() + "-" + version.get() + APK)) {
            // The path names the artifact to holds, scans and licence screens, and the index serves the .PKGINFO name;
            // they must agree.
            exchange.respond(400);
            return;
        }
        Optional<String> declared = pkg.field("arch");
        if (declared.isPresent() && !declared.get().equals(architecture) && !declared.get().equals("noarch")) {
            exchange.respond(400);
            return;
        }
        long size = blobs.store().size("blobs/" + hash);
        String block = ApkIndex.entry(pkg, size);
        if (block.contains("\n\n")) {
            // A blank line separates blocks in the shared index, so a block carrying one would splice in a
            // publisher-chosen entry. No .PKGINFO value can contain a newline, so a real package never trips this.
            exchange.respond(400);
            return;
        }
        try {
            blobs.linkRelease(ApkListings.packageKey(repo, architecture, file), hash, -1L);
        } catch (Publication.RepublishConflict taken) {
            exchange.respond(409, Blobs.alreadyPublished(architecture + "/" + file));
            return;
        }
        new ApkListings(blobs).published(repo, architecture, file, block);
        exchange.respond(201);
    }

    // ---- the read path ----

    /** Whether nothing was published under this repository and architecture, so an index read is a local miss a proxy
     *  repository fills from upstream rather than an empty document. The probe is paid only until the index exists. */
    private static boolean unpublished(Blobs blobs, String repo, String architecture) throws IOException {
        return !StoredListing.present(blobs.store(), ApkListings.index(repo, architecture))
                && blobs.isEmpty(ApkListings.blocks(repo, architecture));
    }

    private void serveArchive(FormatExchange exchange, Blobs blobs, String repo, String architecture)
            throws IOException {
        if (unpublished(blobs, repo, architecture)) {
            exchange.respond(404);
            return;
        }
        ApkListings listings = new ApkListings(blobs);
        // A read inside the derivation's window derives the archive once.
        Optional<StoredListing.Header> index = StoredListing.header(blobs.store(),
                ApkListings.index(repo, architecture));
        Optional<StoredListing.Served> served = StoredListing.openDerived(blobs.store(),
                ApkListings.archive(repo, architecture));
        if (served.isEmpty() || index.isEmpty() || served.get().header().seq() < index.get().seq()) {
            if (served.isPresent()) {
                served.get().close();
            }
            // rederive() materialises a listing never written, so a repository whose packages predate it answers from a
            // rebuilt one.
            listings.rederive(repo, architecture);
            served = StoredListing.openDerived(blobs.store(), ApkListings.archive(repo, architecture));
        }
        if (served.isEmpty()) {
            exchange.respond(404);
            return;
        }
        try (StoredListing.Served document = served.get()) {
            respondListing(exchange, document, "application/gzip");
        }
    }

    /** The public half of the key this repository's indexes are signed with; a {@code 404} until the first publish
     *  generates it, since inventing one on a read would be a write on a read path. */
    private void servePublicKey(FormatExchange exchange, Blobs blobs) throws IOException {
        if (!exchange.method().equals("GET") && !exchange.method().equals("HEAD")) {
            exchange.respond(405);
            return;
        }
        Optional<byte[]> key = ApkSigner.publicKey(blobs);
        if (key.isEmpty()) {
            exchange.respond(404);
            return;
        }
        exchange.setResponseHeader("Content-Type", "application/x-pem-file");
        if (exchange.method().equals("HEAD")) {
            exchange.setResponseHeader("Content-Length", Long.toString(key.get().length));
            exchange.respond(200, -1L).close();
            return;
        }
        exchange.respond(200, key.get());
    }

    /** The index as plain text, streamed from the stored document the archive is derived from. */
    private void serveIndex(FormatExchange exchange, Blobs blobs, String repo, String architecture)
            throws IOException {
        if (unpublished(blobs, repo, architecture)) {
            exchange.respond(404);
            return;
        }
        Optional<StoredListing.Served> served =
                StoredListing.open(blobs.store(), new ApkListings(blobs).indexSpec(repo, architecture));
        if (served.isEmpty()) {
            exchange.respond(404);
            return;
        }
        try (StoredListing.Served document = served.get()) {
            respondListing(exchange, document, "text/plain; charset=utf-8");
        }
    }

    /** Answer with a stored listing, with the revalidation {@code apk update} relies on: the ETag is the document's
     *  digest, so a matching {@code If-None-Match} is answered from the header. */
    private static void respondListing(FormatExchange exchange, StoredListing.Served served, String contentType)
            throws IOException {
        Listings.serve(exchange, served, contentType);
    }

    /** A package's bytes, streamed from the pointer the publish wrote - never re-read from the archive. */
    private void servePackage(FormatExchange exchange, Blobs blobs, String repo, String architecture, String file)
            throws IOException {
        Optional<Blobs.Located> located = blobs.locate(ApkListings.packageKey(repo, architecture, file));
        if (located.isEmpty()) {
            exchange.respond(404);
            return;
        }
        exchange.setResponseHeader("Content-Type", "application/octet-stream");
        if (exchange.method().equals("HEAD")) {
            if (located.get().size() >= 0) {
                exchange.setResponseHeader("Content-Length", Long.toString(located.get().size()));
            }
            exchange.respond(200, -1L).close();
            return;
        }
        blobs.serve(located.get(), exchange);
    }


    // ---- proxy ----

    /**
     * Proxy a miss to an upstream Alpine repository, the directory one {@code /etc/apk/repositories} line names; the
     * local repository name is an alias for it, so {@code /apk/<repo>/<architecture>/<file>} maps to
     * {@code <upstream>/<architecture>/<file>} and nothing an upstream advertises is followed.
     *
     * <p>{@code APKINDEX.tar.gz} is an ENUMERATION, fetched fresh, and only an upstream 404/410 reaches the client as a
     * 404. It is relayed as is, signed with the upstream's key.
     *
     * <p>A package is PINNED and held to that index: its control member against {@code C:}, and its data member against
     * the {@code datahash} the control member carries, together covering every installed byte. An unreadable index
     * declines the fill; one that does not list the package leaves it unverified, no client resolving to it; a mismatch
     * is refused. The bytes are stored as they stream and linked only once they pass. A proxied package does not join
     * this repository's index.
     */
    @Override
    public boolean pullThrough(FormatExchange exchange, ArtifactStore store, URI upstream,
                               ProxyFormat.Fetcher fetcher) throws IOException {
        String[] segments = exchange.path().substring(PREFIX.length()).split("/");
        if (segments.length != 3) {
            return false;
        }
        String repo = segments[0], architecture = segments[1], file = segments[2];
        String root = upstream.toString().endsWith("/") ? upstream.toString() : upstream + "/";
        URI index = URI.create(root + architecture + "/" + ARCHIVE);
        if (file.equals(ARCHIVE)) {
            return ProxyRelay.streamFresh(fetcher, index, "application/gzip", exchange,
                    ProxyRelay.Document.ENUMERATION);
        }
        if (!file.endsWith(APK)) {
            return false;
        }
        URI target = URI.create(root + architecture + "/" + file);
        Optional<String> declared;
        try (ProxyFormat.Download document = fetcher.download(index, Map.of()).orElse(null)) {
            if (document == null) {
                return ProxyRelay.unverifiable(target, ProxyRelay.Declared.unreachable(index));
            }
            if (document.status() != 200) {
                ProxyRelay.Declared verdict = ProxyRelay.declaration(index, document.status());
                if (!verdict.readable()) {
                    return ProxyRelay.unverifiable(target, verdict);
                }
                declared = Optional.empty();
            } else {
                try {
                    declared = indexChecksum(document.body(), file);
                } catch (IOException | RuntimeException unreadable) {
                    return ProxyRelay.unverifiable(target, ProxyRelay.Declared.unreadable(
                            "the index at " + index + " is not an APKINDEX archive"));
                }
            }
        }
        Blobs blobs = new Blobs(store);
        String hash;
        try (ProxyFormat.Download download = fetcher.download(target, Map.of()).orElse(null)) {
            if (download == null || download.status() != 200) {
                return false;
            }
            hash = blobs.store(download.body());
        }
        if (declared.isPresent() && !intact(blobs, hash, declared.get())) {
            LOGGER.warn("Refusing to cache the proxied Alpine package {}: it does not match the checksum {} the "
                    + "upstream index declares for it, or its data does not match its own datahash. Nothing was "
                    + "cached or served.", target, declared.get());
            return false;
        }
        blobs.link(ApkListings.packageKey(repo, architecture, file), hash);
        servePackage(exchange, blobs, repo, architecture, file);
        return true;
    }

    /** The {@code C:} an upstream index declares for {@code file}, or empty when it lists no such package. */
    private static Optional<String> indexChecksum(InputStream archive, String file) throws IOException {
        try (GzipCompressorInputStream gzip = new GzipCompressorInputStream(archive, true);
             TarArchiveInputStream tar = new TarArchiveInputStream(gzip, "UTF-8")) {
            for (TarArchiveEntry entry = tar.getNextEntry(); entry != null; entry = tar.getNextEntry()) {
                if (!entry.getName().equals(INDEX)) {
                    continue;
                }
                BufferedReader lines = new BufferedReader(new InputStreamReader(tar, StandardCharsets.UTF_8));
                String checksum = null, name = null, version = null;
                for (String line = lines.readLine(); ; line = lines.readLine()) {
                    if (line == null || line.isEmpty()) {
                        if (checksum != null && file.equals(name + "-" + version + APK)) {
                            return Optional.of(checksum);
                        }
                        if (line == null) {
                            return Optional.empty();
                        }
                        checksum = name = version = null;
                    } else if (line.startsWith("C:")) {
                        checksum = line.substring(2);
                    } else if (line.startsWith("P:")) {
                        name = line.substring(2);
                    } else if (line.startsWith("V:")) {
                        version = line.substring(2);
                    }
                }
            }
        }
        throw new IOException("no APKINDEX member");
    }

    /** Whether a stored package's control member is the one {@code checksum} names and its data member the one the
     *  control member's {@code datahash} names. */
    private static boolean intact(Blobs blobs, String hash, String checksum) throws IOException {
        Optional<ApkPackage> read;
        try (InputStream stored = blobs.open(hash)) {
            read = ApkPackage.of(stored.readNBytes(CONTROL_PREFIX));
        } catch (RuntimeException malformed) {
            return false;
        }
        if (read.isEmpty() || !read.get().checksum().equals(checksum)) {
            return false;
        }
        Optional<String> datahash = read.get().field("datahash");
        if (datahash.isEmpty()) {
            return false;
        }
        MessageDigest sha256;
        try {
            sha256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JVM", e);
        }
        try (InputStream stored = blobs.open(hash)) {
            stored.skipNBytes(read.get().dataOffset());
            stored.transferTo(new DigestOutputStream(OutputStream.nullOutputStream(), sha256));
        }
        return HexFormat.of().formatHex(sha256.digest()).equalsIgnoreCase(datahash.get());
    }

    // ---- layout ----

    /** An {@code .apk} may carry a bare RSA signature as its first member, over the control segment's compressed bytes:
     *  optional, and verified against this deployment's trusted public keys as a client verifies it against
     *  {@code /etc/apk/keys/}. */
    @Override
    public List<ArtifactSignatures.Expectation> expects(String path) {
        return describe(path).map(described -> described.coordinate() != null)
                .orElse(false)
                ? List.of(ArtifactSignatures.Expectation.optional(ArtifactSignatures.Scheme.RSA_DETACHED))
                : List.of();
    }

    @Override
    public List<ArtifactSignatures.Evidence> evidence(String path, ArtifactSignatures.Material material)
            throws IOException {
        if (expects(path).isEmpty()) {
            return List.of();
        }
        Optional<ArtifactSignatures.Signed> body = material.body();
        if (body.isEmpty()) {
            return List.of();
        }
        byte[] prefix;
        try (InputStream in = body.get().open()) {
            prefix = in.readNBytes(CONTROL_PREFIX);
        }
        Optional<ApkPackage.Signature> signature = ApkPackage.signature(prefix);
        if (signature.isEmpty()) {
            return List.of();
        }
        byte[] control = Arrays.copyOfRange(prefix, signature.get().controlOffset(),
                signature.get().controlOffset() + signature.get().controlLength());
        return List.of(new ArtifactSignatures.Evidence(ArtifactSignatures.Scheme.RSA_DETACHED,
                signature.get().bytes(), () -> new ByteArrayInputStream(control),
                path + "!" + signature.get().member()));
    }

    @Override
    public Optional<ArtifactDescriptor> describe(String path) {
        if (!path.startsWith(PREFIX) || !path.endsWith(APK)) {
            return Optional.empty();
        }
        String[] segments = path.substring(PREFIX.length()).split("/");
        if (segments.length != 3) {
            return Optional.empty();
        }
        // .PKGINFO is authoritative, but the publish refused a package disagreeing with its path, so the name is the
        // same fact.
        String[] split = split(ApkListings.stem(segments[2]));
        if (split == null) {
            return Optional.of(ArtifactDescriptor.at(ECOSYSTEM, path));
        }
        return Optional.of(new ArtifactDescriptor(ECOSYSTEM, split[0], split[1], path,
                "application/octet-stream", false, null, -1L));
    }

    /** Split {@code <name>-<version>} by Alpine's grammar: a version is always {@code <upstream>-r<build>}, so it is
     *  the last two hyphen-separated segments when the final one is {@code r} and digits. Splitting at the first
     *  {@code -<digit>} breaks {@code libx11-6}, at the last puts the revision in the coordinate; a stem with no
     *  {@code -rN} tail gets no coordinate. */
    private static String[] split(String stem) {
        int revision = stem.lastIndexOf('-');
        if (revision <= 0 || revision + 2 >= stem.length() || stem.charAt(revision + 1) != 'r') {
            return null;
        }
        for (int at = revision + 2; at < stem.length(); at++) {
            if (!Character.isDigit(stem.charAt(at))) {
                return null;
            }
        }
        int boundary = stem.lastIndexOf('-', revision - 1);
        if (boundary <= 0) {
            return null;
        }
        return new String[]{stem.substring(0, boundary), stem.substring(boundary + 1)};
    }

    @Override
    public List<String> paths(String coordinate, String version, ArtifactStore store) {
        // An apk pointer lives in the blobs namespace, so the coordinate seam is BlobLayout's (blobKeys).
        return List.of();
    }

    @Override
    public List<String> blobRoots() {
        return List.of("apk");
    }

    @Override
    public List<String> blobKeys(String coordinate, String version, ArtifactStore store) throws IOException {
        if (!BlobLayout.addressable(coordinate, version)) {
            return List.of();   // a traversal-shaped coordinate maps nowhere - these keys are what an eviction DELETES
        }
        List<String> keys = new ArrayList<>();
        String file = coordinate + "-" + version + APK;
        for (String repo : store.list("apk")) {
            for (String architecture : store.list("apk/" + repo)) {
                String key = ApkListings.packageKey(repo, architecture, file);
                if (store.readVersioned(key).isPresent()) {
                    keys.add(key);
                }
            }
        }
        return keys;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The pointer key is the served path without its leading slash, so the request-path parse is reused and the
     * description re-keyed to the pointer. Only when it names a version: {@code describe} falls back to a
     * coordinate-less descriptor for indexes and checksums, which a repair walking the blob root must read as empty.
     */
    @Override
    public Optional<ArtifactDescriptor> describePointer(String key) {
        return describe("/" + key)
                .filter(described -> described.coordinate() != null && described.version() != null)
                .map(described -> described.withPath(key));
    }

    @Override
    public List<String> servedPaths(String coordinate, String version, ArtifactStore store) throws IOException {
        List<String> paths = new ArrayList<>();
        for (String key : blobKeys(coordinate, version, store)) {
            paths.add("/" + key);
        }
        return paths;
    }

    /** Each {@code .apk} of the version is put at {@code <repo>/<arch>/<file>}; the target derives and signs its own
     *  {@code APKINDEX}. */
    @Override
    public Exported export(ArtifactStore repository, String coordinate, String version, ExportTarget target)
            throws IOException {
        return BlobExport.put(repository, mount(), blobKeys(coordinate, version, repository), target);
    }
}
