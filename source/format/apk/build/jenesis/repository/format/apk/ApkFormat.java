package build.jenesis.repository.format.apk;

import module java.base;

import build.jenesis.repository.blobs.BlobLayout;
import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.blobs.Keys;
import build.jenesis.repository.format.ArtifactLayout;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.ArtifactSignatures;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.StoredListing;
import build.jenesis.repository.format.Listings;

/**
 * The Alpine {@code apk} repository - an {@code APKINDEX.tar.gz} beside {@code .apk} packages - so
 * {@code apk update} and {@code apk add} work over the shared store.
 *
 * <p>It owns {@code /apk/<repo>/<arch>/...}: the index at {@code APKINDEX.tar.gz} and a package at
 * {@code <name>-<version>.apk}. An operator points {@code /etc/apk/repositories} at {@code <base>/apk/<repo>} and
 * the client appends {@code /<arch>/APKINDEX.tar.gz} itself - which is why the architecture is a path segment here
 * rather than something this format chooses.
 *
 * <p><b>The coordinate comes from inside the package.</b> An {@code .apk} file name is {@code <name>-<version>.apk}
 * and both halves may contain hyphens - an Alpine version carries an {@code -rN} revision, so {@code musl-1.2.5-r3}
 * splits at neither the first hyphen nor the last. {@code .PKGINFO} states {@code pkgname} and {@code pkgver}
 * outright, so the publish reads them from there and refuses a package whose own metadata disagrees with the path
 * it was deployed to. That is the same screen-label rule the Debian, Composer and CocoaPods formats apply: a
 * package screened under one name must not be served under another.
 *
 * <p><b>What the index says is derived, not supplied.</b> Every field of an entry comes from the package's own
 * control segment or from the stored bytes, so a publisher cannot describe an artifact as something other than what
 * will be served. The derivation of each is recorded in {@link ApkIndex}, and both it and {@link ApkPackage} were
 * settled by rendering a real package and diffing the result against the block Alpine publishes for it.
 *
 * <p><b>The index is signed, and the key is the repository's own.</b> An {@code apk} client verifies
 * {@code APKINDEX.tar.gz} against a trusted RSA public key and reaches an unsigned repository only with
 * {@code --allow-untrusted}, which switches verification off for every repository that client uses. So the first
 * publish generates an RSA key pair, every derived archive carries a {@code .SIGN.RSA256.} member, and the public
 * half is served at {@code GET /apk/keys/jenesis.rsa.pub} for an operator to place in {@code /etc/apk/keys/}. See
 * {@link ApkSigner} for the scheme and how each part of it was measured.
 */
public final class ApkFormat implements RepositoryFormat, ArtifactLayout, BlobLayout, ArtifactSignatures {

    /** The package-ecosystem name apk coordinates report. */
    public static final String ECOSYSTEM = "Alpine";

    private static final String PREFIX = "/apk/";

    /** The document a client fetches - the archive. */
    private static final String ARCHIVE = "APKINDEX.tar.gz";

    /** The same index as plain text. No apk client asks for it; an operator diagnosing a repository does, and the
     *  Debian format serves its {@code Packages} beside {@code Packages.gz} for the same reason. It is the stored
     *  document itself, so the two can never disagree. */
    private static final String INDEX = "APKINDEX";

    private static final String APK = ".apk";

    /**
     * How much of a published package is read to find its control segment.
     *
     * <p>A control segment is a few kilobytes however large the payload - musl's is 523 compressed bytes behind a
     * 666-byte signature - so this bounds the metadata read rather than the artifact. The package itself streams
     * into the content-addressed store unbounded and is never held whole; only this prefix is.
     */
    private static final int CONTROL_PREFIX = 8 * 1024 * 1024;

    /** Where the public half of the repository's signing key is served, for {@code /etc/apk/keys/}. */
    private static final String KEYS = "keys";

    @Override
    public String name() {
        return "apk";
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
            // Two segments, checked ahead of the repository routes below. A repository really called "keys" is
            // unaffected: its own paths carry three segments and this one carries two.
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

    /**
     * Publish one package.
     *
     * <p>The bytes stream straight into the content-addressed store, and only then is the stored blob reopened to
     * read its control segment - the store-then-gate publish this product's packaged formats share. A package that
     * fails a check is left as an orphan blob for the collector to reclaim, and nothing is pointed at it or listed.
     */
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
            // A package whose members do not parse within the bounded prefix is a malformed upload rather than a
            // server error: nothing was pointed at, so answering 400 leaves the store exactly as it was.
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
            // The path is what a hold, a scan verdict and a licence screen name the artifact by; the index serves it
            // under its .PKGINFO name. They must agree, or a package screened under one coordinate is served under
            // another.
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
            // Blocks are separated by a blank line in the shared index, so a block carrying one would splice a
            // second, fully publisher-chosen entry into it - a phantom package whose own C: and P: an apk client
            // would trust. No .PKGINFO value can contain a newline (the file is parsed line by line), so this
            // cannot trip on a real package; it refuses the shape rather than reasoning about who could reach it.
            exchange.respond(400);
            return;
        }
        blobs.link(ApkListings.packageKey(repo, architecture, file), hash);
        new ApkListings(blobs).published(repo, architecture, file, block);
        exchange.respond(201);
    }

    // ---- the read path ----

    private void serveArchive(FormatExchange exchange, Blobs blobs, String repo, String architecture)
            throws IOException {
        ApkListings listings = new ApkListings(blobs);
        // The archive is derived off the index write; a read compares its sequence with the index's (one header
        // read) and derives it itself, once, when it arrived inside that window.
        Optional<StoredListing.Header> index = StoredListing.header(blobs.store(),
                ApkListings.index(repo, architecture));
        Optional<StoredListing.Served> served = StoredListing.openDerived(blobs.store(),
                ApkListings.archive(repo, architecture));
        if (served.isEmpty() || index.isEmpty() || served.get().header().seq() < index.get().seq()) {
            if (served.isPresent()) {
                served.get().close();
            }
            // rederive() reads the index through its spec, which materialises a listing that was never written -
            // so a repository whose packages predate the stored listing answers from a rebuilt one rather than a
            // 404.
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

    /**
     * The public half of the key this repository's indexes are signed with.
     *
     * <p>It answers {@code 404} until something has been published, because the key is generated by the first
     * publish - a repository that has served nothing has signed nothing, and inventing a key for it on a read
     * would be a write on a read path for no one's benefit.
     */
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

    /** Answer with a stored listing, with the revalidation {@code apk update} lives on: the ETag is the stored
     *  document's own digest, so a matching {@code If-None-Match} is answered from the header alone and a repeated
     *  {@code apk update} against an unchanged repository transfers nothing. */
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

    // ---- layout ----

    /**
     * An {@code .apk} may carry a bare RSA signature as its first member, over the control segment's compressed
     * bytes - optional here, since a package from a build with no key carries none, and what the client verifies
     * against its {@code /etc/apk/keys/} this deployment verifies against its trusted public keys.
     */
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
        // The file name is not authoritative - .PKGINFO is - but the publish has already refused a package whose
        // metadata disagrees with the path it was deployed to, so for a stored artifact the two are the same fact.
        String[] split = split(ApkListings.stem(segments[2]));
        if (split == null) {
            return Optional.of(ArtifactDescriptor.at(ECOSYSTEM, path));
        }
        return Optional.of(new ArtifactDescriptor(ECOSYSTEM, split[0], split[1], path,
                "application/octet-stream", false, null, -1L));
    }

    /**
     * Split {@code <name>-<version>} the way Alpine's own grammar does.
     *
     * <p>An Alpine version is {@code <upstream>-r<build>}, always, so the version is the <em>last two</em>
     * hyphen-separated segments when the final one is {@code r} and digits. Neither of the two rules that look
     * obvious works: splitting at the first {@code -<digit>} breaks a name like {@code libx11-6}, and splitting at
     * the last one puts the revision in the coordinate. A stem with no {@code -rN} tail is not an Alpine package
     * file name and gets no coordinate rather than a guessed one.
     */
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
        // An apk pointer lives in the blobs namespace rather than under publish/, so the coordinate seam this format
        // really has is BlobLayout's - see blobKeys below, which is what an eviction and a compliance read follow.
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
     * <p>This layout's pointer key <em>is</em> its served path without the leading slash - {@link #servedPaths}
     * composes one from the other - so the request-path describer is already the parse, and writing a second one
     * here would be two spellings of one grammar with nothing holding them together. The description is re-keyed to
     * the pointer, because what a repair rebuilding the inventory row holds is the key, not the request path.
     *
     * <p><b>Only when the description actually names a version.</b> The two describers have different contracts:
     * {@code describe} answers about any path this format serves and falls back to a coordinate-less descriptor for
     * the indexes and checksums beside the artifacts, while this one must answer <em>empty</em> for those - a
     * repair walking the blob root asks about every key it meets, and a present descriptor with no coordinate is
     * an absence dressed as a claim. The filter is what keeps the delegation honest.
     *
     * <p>{@code BlobLayoutCoordinateSeamTest} drives this over keys this layout really wrote and over the folders
     * above them, so both halves are checked rather than asserted: if the two shapes ever stop coinciding the round
     * trip names the wrong coordinate, and if the filter goes the parent of a pointer is claimed as one.
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
}
