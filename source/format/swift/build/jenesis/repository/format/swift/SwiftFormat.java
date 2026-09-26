package build.jenesis.repository.format.swift;

import module java.base;

import build.jenesis.repository.blobs.BlobLayout;
import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.format.ArtifactSignatures;
import build.jenesis.repository.blobs.Keys;
import build.jenesis.repository.format.ArtifactLayout;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.multipart.MultipartBody;
import build.jenesis.repository.multipart.MultipartForm;
import build.jenesis.repository.format.ExportTarget;
import build.jenesis.repository.format.PublishedExport;
import build.jenesis.repository.format.RepositoryExporter;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.StoredListing;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The Swift Package Registry (SE-0292), hosted.
 *
 * <p>Six endpoints, under {@code /swift/<repo>/}: list a package's releases, fetch a release's metadata, fetch its
 * {@code Package.swift}, download its source archive, look a package up by repository URL, and publish. A client
 * is pointed here with {@code swift package-registry set <base>/swift/<repo>}.
 *
 * <h2>The version is in a header, not the path</h2>
 *
 * <p>Unusually among the formats here, this specification puts its API version in content negotiation -
 * {@code Accept: application/vnd.swift.registry.v1+json}, answered with {@code Content-Version: 1} - and leaves
 * the URL space unversioned. So there is no foreign {@code /vN} prefix to adopt, and this format's paths carry no
 * version of ours either, which is the rule this product already keeps everywhere it is free to.
 *
 * <h2>What the archive's checksum is</h2>
 *
 * <p>A client verifies a downloaded archive against the {@code checksum} in the release metadata. That value is the
 * SHA-256 the content-addressed store computed as the bytes streamed in, so it describes the bytes this repository
 * will actually serve rather than anything a publisher asserted beside them - and it is recorded in the metadata
 * document at publish, when the store has just told us what it was.
 *
 * <h2>Two ways to be unavailable</h2>
 *
 * <p>A withheld release leaves the release list; a yanked one stays, carrying the specification's own
 * {@code problem} object. See {@link SwiftListings} for why those are different rather than two spellings of one
 * thing.
 */
public final class SwiftFormat implements RepositoryFormat, ArtifactLayout, BlobLayout, ArtifactSignatures,
        RepositoryExporter {

    /** The archive signature's sidecar suffix under the archive key and path: {@code <version>.zip.sig}. */
    private static final String SIGNATURE = ".sig";

    /** The one signature format the registry specification defines (SE-0305): a detached CMS structure over the
     *  source archive, declared by the {@code X-Swift-Package-Signature-Format} header on publish and download. */
    private static final String SIGNATURE_FORMAT = "cms-1.0.0";

    private static boolean signable(String path) {
        return archivePath(path) != null;
    }

    /** {@code {repo, scope, name, version}} for a source-archive path ({@code /swift/<repo>/<scope>/<name>/<version>.zip})
     *  or a signature sidecar's ({@code ...zip.sig}), else {@code null}. */
    private static String[] archivePath(String path) {
        if (!path.startsWith(PREFIX)) {
            return null;
        }
        String[] segments = path.substring(PREFIX.length()).split("/");
        if (segments.length != 4) {
            return null;
        }
        String last = segments[3];
        if (last.endsWith(SIGNATURE)) {
            last = last.substring(0, last.length() - SIGNATURE.length());
        }
        if (!last.endsWith(".zip") || last.length() == ".zip".length()) {
            return null;
        }
        String version = last.substring(0, last.length() - ".zip".length());
        for (String segment : new String[] {segments[0], segments[1], segments[2], version}) {
            if (Keys.unsafe(segment)) {
                return null;
            }
        }
        return new String[] {segments[0], segments[1], segments[2], version};
    }

    @Override
    public List<ArtifactSignatures.Expectation> expects(String path) {
        return SIGNATURES.expects(path);
    }

    @Override
    public Optional<String> covers(String path) {
        return SIGNATURES.covers(path);
    }

    @Override
    public List<ArtifactSignatures.Evidence> evidence(String path, ArtifactSignatures.Material material)
            throws IOException {
        return SIGNATURES.evidence(path, material);
    }

    /** A source archive's or its signature sidecar's serving key, when the pointer exists - for the compliance
     *  screen's sibling read and the completion observer's re-derivation, which resolve through the layout. */
    @Override
    public Optional<String> servingKey(String requestPath, ArtifactStore store) throws IOException {
        String[] parts = archivePath(requestPath);
        if (parts == null) {
            return Optional.empty();
        }
        String key = SwiftListings.archiveKey(parts[0], parts[1], parts[2], parts[3])
                + (requestPath.endsWith(SIGNATURE) ? SIGNATURE : "");
        return store.readVersioned(key).isPresent() ? Optional.of(key) : Optional.empty();
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The package-ecosystem name Swift coordinates report. */
    public static final String ECOSYSTEM = "Swift";

    private static final String PREFIX = "/swift/";

    /**
     * A source archive's signature is its {@code .sig} sidecar: optional, since most registries' packages carry none,
     * PKCS#7 detached over the archive bytes as the specification defines it, and never asked of the sidecar itself.
     * The sidecar arrives in the same multipart request as the archive ({@code source-archive-signature}) and is
     * announced as its own publish once stored, so the completion observer re-derives the verdict over the stored
     * archive exactly as it does for a Maven {@code .asc} that lands after its jar.
     */
    private static final ArtifactSignatures SIGNATURES = ArtifactSignatures.detachedSidecar(ECOSYSTEM, SIGNATURE,
            ArtifactSignatures.Scheme.PKCS7, SwiftFormat::signable, ArtifactSignatures.Coverage.OPTIONAL);

    /** What the specification's own examples send, and what this answers with. */
    private static final String CONTENT_VERSION = "Content-Version";

    private static final String API_VERSION = "1";

    private static final int METADATA_LIMIT = 1024 * 1024;

    private static final int MANIFEST_LIMIT = 1024 * 1024;

    @Override
    public String name() {
        return "swift";
    }

    /** A lifecycle mark surfaces in the metadata this format's clients read, so marks are accepted here. */
    @Override
    public boolean surfacesLifecycleMarks() {
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
        String[] segments = exchange.path().substring(PREFIX.length()).split("/");
        if (segments.length < 2 || Keys.unsafe(segments[0])) {
            exchange.respond(404);
            return;
        }
        String repo = segments[0];
        String[] rest = Arrays.copyOfRange(segments, 1, segments.length);
        String method = exchange.method();
        if (method.equals("PUT")) {
            if (rest.length == 3) {
                publish(exchange, blobs, repo, rest[0], rest[1], rest[2]);
            } else {
                exchange.respond(400);
            }
            return;
        }
        if (!method.equals("GET") && !method.equals("HEAD")) {
            exchange.respond(405);
            return;
        }
        switch (rest.length) {
            case 1 -> {
                if (rest[0].equals("identifiers")) {
                    identifiers(exchange, blobs, repo);
                } else {
                    exchange.respond(404);
                }
            }
            case 2 -> releases(exchange, blobs, repo, rest[0], strip(rest[1]));
            case 3 -> {
                if (rest[2].endsWith(".zip")) {
                    archive(exchange, blobs, repo, rest[0], rest[1],
                            rest[2].substring(0, rest[2].length() - ".zip".length()));
                } else {
                    metadata(exchange, blobs, repo, rest[0], rest[1], strip(rest[2]));
                }
            }
            case 4 -> {
                if (rest[3].equals("Package.swift")) {
                    manifest(exchange, blobs, repo, rest[0], rest[1], rest[2]);
                } else {
                    exchange.respond(404);
                }
            }
            default -> exchange.respond(404);
        }
    }

    /** The specification lets a client append {@code .json} to a document request; both name one resource. */
    private static String strip(String segment) {
        return segment.endsWith(".json") ? segment.substring(0, segment.length() - ".json".length()) : segment;
    }

    // ---- the read path ----

    /**
     * 4.1, the release list.
     *
     * <p>The {@code 404} is keyed on the raw package folder rather than on the servable subset: a package this
     * repository has never held is absent, while one whose every release is withheld is present with nothing to
     * offer and answers an empty {@code releases} object. Collapsing those would assert "no such package" about
     * something this repository does hold.
     */
    private void releases(FormatExchange exchange, Blobs blobs, String repo, String scope, String name)
            throws IOException {
        if (Keys.unsafe(scope) || Keys.unsafe(name)
                || blobs.list(SwiftListings.packagePrefix(repo, scope, name)).isEmpty()) {
            exchange.respond(404);
            return;
        }
        Optional<StoredListing.Served> served = StoredListing.open(blobs.store(),
                new SwiftListings(blobs).releasesSpec(repo, scope, name));
        if (served.isEmpty()) {
            exchange.respond(404);
            return;
        }
        try (StoredListing.Served document = served.get()) {
            respondDocument(exchange, document, "application/json");
        }
    }

    /** 4.2, one release's metadata - written by the publish that knew the archive's digest. */
    private void metadata(FormatExchange exchange, Blobs blobs, String repo, String scope, String name,
                          String version) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        String key = SwiftListings.metadataKey(repo, scope, name, version);
        if (blobs.withheld(SwiftListings.archiveKey(repo, scope, name, version))
                || !blobs.read(key, buffer)) {
            exchange.respond(404);
            return;
        }
        respondBytes(exchange, buffer.toByteArray(), "application/json");
    }

    /** 4.3, the manifest. A tool-version-specific manifest is a separate stored file, as the ecosystem ships it. */
    private void manifest(FormatExchange exchange, Blobs blobs, String repo, String scope, String name,
                          String version) throws IOException {
        String swiftVersion = Optional.ofNullable(exchange.queryParameter("swift-version")).orElse("");
        if (Keys.unsafe(version) || (!swiftVersion.isEmpty() && Keys.unsafe(swiftVersion))) {
            exchange.respond(404);
            return;
        }
        if (blobs.withheld(SwiftListings.archiveKey(repo, scope, name, version))) {
            exchange.respond(404);
            return;
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        if (!blobs.read(SwiftListings.manifestKey(repo, scope, name, version, swiftVersion), buffer)
                && (swiftVersion.isEmpty()
                    || !blobs.read(SwiftListings.manifestKey(repo, scope, name, version, ""), buffer))) {
            // A request for a tool-version-specific manifest falls back to the unversioned one, which is what the
            // ecosystem's own layout means by their coexisting.
            exchange.respond(404);
            return;
        }
        respondBytes(exchange, buffer.toByteArray(), "text/x-swift");
    }

    /** 4.4, the source archive. */
    private void archive(FormatExchange exchange, Blobs blobs, String repo, String scope, String name,
                         String version) throws IOException {
        Optional<Blobs.Located> located = blobs.locate(SwiftListings.archiveKey(repo, scope, name, version));
        if (located.isEmpty()) {
            exchange.respond(404);
            return;
        }
        exchange.setResponseHeader("Content-Type", "application/zip");
        exchange.setResponseHeader("Content-Disposition",
                "attachment; filename=\"" + name + "-" + version + ".zip\"");
        Optional<Blobs.Located> sidecar = blobs.locate(SwiftListings.archiveKey(repo, scope, name, version) + SIGNATURE);
        if (sidecar.isPresent()) {
            // 4.4: a signed archive's signature rides the download in the headers the specification names, so a
            // client that verifies (swift package-registry with a trust configuration) has it without a second read.
            byte[] bytes;
            try (InputStream in = blobs.open(sidecar.get().hash())) {
                bytes = in.readNBytes(ArtifactSignatures.Material.LARGEST_SIGNATURE);
            }
            exchange.setResponseHeader("X-Swift-Package-Signature-Format", SIGNATURE_FORMAT);
            exchange.setResponseHeader("X-Swift-Package-Signature", Base64.getEncoder().encodeToString(bytes));
        }
        // The specification's Digest header takes RFC 3230's base64 form, which is not the hex the store speaks.
        exchange.setResponseHeader("Digest", "sha-256=" + Base64.getEncoder()
                .encodeToString(HexFormat.of().parseHex(located.get().hash())));
        exchange.setResponseHeader("Cache-Control", "public, immutable");
        if (exchange.method().equals("HEAD")) {
            if (located.get().size() >= 0) {
                exchange.setResponseHeader("Content-Length", Long.toString(located.get().size()));
            }
            exchange.respond(200, -1L).close();
            return;
        }
        blobs.serve(located.get(), exchange);
    }

    /**
     * 4.5, the reverse lookup from a source-repository URL to package identifiers.
     *
     * <p>Answered from an index the publish writes, by point read - never by walking the packages and reading each
     * one's metadata, which is the shape that would make this endpoint cost more the more the repository holds.
     *
     * <p><b>And it is screened.</b> A package whose every release is withheld is one this repository has decided
     * not to serve, so naming it here would disclose it through the back door - the endpoint answers identifiers
     * rather than releases, but an identifier nobody can resolve is still a statement that the repository holds
     * it. The screen is the package's own release list, which a hold has already emptied: one header read per
     * candidate, and a URL has few.
     */
    private void identifiers(FormatExchange exchange, Blobs blobs, String repo) throws IOException {
        String url = exchange.queryParameter("url");
        if (url == null || url.isBlank()) {
            exchange.respond(400);
            return;
        }
        SwiftListings listings = new SwiftListings(blobs);
        List<String> found = new ArrayList<>();
        for (String scope : blobs.list(urlIndex(repo, url))) {
            for (String name : blobs.list(urlIndex(repo, url) + "/" + scope)) {
                if (servable(blobs, listings, repo, scope, name)) {
                    found.add(MAPPER.writeValueAsString(scope + "." + name));
                }
            }
        }
        if (found.isEmpty()) {
            exchange.respond(404);
            return;
        }
        respondBytes(exchange, ("{\"identifiers\":[" + String.join(",", found) + "]}")
                .getBytes(StandardCharsets.UTF_8), "application/json");
    }

    // ---- the write path ----

    /**
     * 4.6, publish. A multipart body carrying the source archive and, optionally, the release metadata.
     *
     * <p>The archive streams into the content-addressed store through the shared multipart reader - which bounds
     * the form fields, so a body declaring a gigabyte-long field cannot be buffered whole - and the digest the
     * store returns becomes the {@code checksum} the release document publishes. A release that already exists is
     * refused with {@code 409}, which is what the specification says a registry does: a published release is
     * immutable.
     */
    private void publish(FormatExchange exchange, Blobs blobs, String repo, String scope, String name,
                         String version) throws IOException {
        if (Keys.unsafe(scope) || Keys.unsafe(name) || Keys.unsafe(version) || version.endsWith(".zip")) {
            exchange.respond(400);
            return;
        }
        String archiveKey = SwiftListings.archiveKey(repo, scope, name, version);
        if (blobs.exists(archiveKey)) {
            exchange.respond(409);   // a published release is immutable
            return;
        }
        Optional<String> boundary = MultipartBody.boundary(exchange.requestHeader("Content-Type"));
        if (boundary.isEmpty()) {
            exchange.respond(400);
            return;
        }
        String hash = null;
        byte[] metadata = "{}".getBytes(StandardCharsets.UTF_8);
        byte[] manifest = null;
        byte[] signature = null;
        boolean signed = false;
        MultipartBody body = MultipartBody.over(exchange.requestStream(), boundary.get());
        for (Optional<MultipartBody.Part> part = body.next(); part.isPresent(); part = body.next()) {
            switch (part.get().name()) {
                case "source-archive" -> hash = blobs.store(part.get().stream());
                case "metadata" -> metadata = part.get().bytes(METADATA_LIMIT).orElse(null);
                case "package-manifest" -> manifest = part.get().bytes(MANIFEST_LIMIT).orElse(null);
                case "source-archive-signature" -> {
                    signed = true;
                    signature = part.get().bytes(ArtifactSignatures.Material.LARGEST_SIGNATURE).orElse(null);
                }
                default -> { }
            }
            if (metadata == null || manifest == null && part.get().name().equals("package-manifest")
                    || signed && signature == null) {
                exchange.respond(413);   // an over-long field is a refusal, never a truncated value
                return;
            }
        }
        if (hash == null) {
            exchange.respond(400);
            return;
        }
        String format = exchange.requestHeader("X-Swift-Package-Signature-Format");
        if (signature != null && format != null && !format.strip().equalsIgnoreCase(SIGNATURE_FORMAT)) {
            // The specification defines one format; a signature in another is one nothing here could read, and
            // storing it unread would let a package claim a signature no client or screen ever checked.
            exchange.respond(400);
            return;
        }
        blobs.link(archiveKey, hash);
        if (signature != null) {
            blobs.write(archiveKey + SIGNATURE, signature);
            // The sidecar is announced as its own publish so the signature dimension re-derives the verdict over the
            // stored archive - the gate screened this request by its path before anything was stored, and the
            // archive's own screening could not have read a sidecar that did not yet exist.
            new Publication(blobs.store()).published(ArtifactDescriptor.at(ECOSYSTEM,
                    PREFIX + repo + "/" + scope + "/" + name + "/" + version + ".zip" + SIGNATURE));
        }
        if (manifest != null) {
            blobs.write(SwiftListings.manifestKey(repo, scope, name, version, ""), manifest);
        }
        blobs.write(SwiftListings.metadataKey(repo, scope, name, version),
                release(scope, name, version, hash, new String(metadata, StandardCharsets.UTF_8)));
        indexRepositoryUrls(blobs, repo, scope, name, metadata);
        new SwiftListings(blobs).refresh(repo, scope, name, version);
        exchange.setResponseHeader("Location", exchange.requestUri());
        exchange.respond(201);
    }

    /** The release document endpoint 4.2 answers, assembled once at publish from what the store just told us. */
    private static byte[] release(String scope, String name, String version, String hash, String metadata) {
        return ("{\"id\":" + MAPPER.writeValueAsString(scope + "." + name)
                + ",\"version\":" + MAPPER.writeValueAsString(version)
                + ",\"resources\":[{\"name\":\"source-archive\",\"type\":\"application/zip\",\"checksum\":"
                + MAPPER.writeValueAsString(hash) + "}]"
                + ",\"metadata\":" + (metadata.isBlank() ? "{}" : metadata)
                + "}").getBytes(StandardCharsets.UTF_8);
    }

    /** Whether a package still offers anything - the screen {@link #identifiers} applies. A held release leaves
     *  the release list, so a package with no entries left is one nothing may name. */
    private static boolean servable(Blobs blobs, SwiftListings listings, String repo, String scope, String name)
            throws IOException {
        Optional<StoredListing.Header> header = StoredListing.header(blobs.store(),
                SwiftListings.releases(repo, scope, name));
        if (header.isEmpty()) {
            // Never materialised: read it through its spec, which generates it from the store and applies the same
            // screen the write path does.
            return StoredListing.read(blobs.store(), listings.releasesSpec(repo, scope, name))
                    .map(document -> document.header().entries() > 0)
                    .orElse(false);
        }
        return header.get().entries() > 0;
    }

    /** Note this package under every repository URL its metadata declares, so 4.5 is a point read. */
    private static void indexRepositoryUrls(Blobs blobs, String repo, String scope, String name, byte[] metadata)
            throws IOException {
        // Parsed, not split on commas. The previous form stripped the brackets and split the text on ",", which
        // is right until a URL contains one - and a repositoryURLs entry is publisher-supplied.
        for (JsonNode element : MAPPER.readTree(metadata).path("repositoryURLs")) {
            String url = element.asString("");
            if (!url.isBlank()) {
                blobs.note(urlIndex(repo, url) + "/" + scope + "/" + name, scope + "." + name);
            }
        }
    }

    /** The reverse index's folder for one URL - digested so any URL is one safe key segment. */
    private static String urlIndex(String repo, String url) {
        try {
            return "swift/" + repo + "/by-url/" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(url.strip().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    // ---- responses ----

    private static void respondDocument(FormatExchange exchange, StoredListing.Served served, String contentType)
            throws IOException {
        String etag = '"' + served.header().sha256() + '"';
        exchange.setResponseHeader("ETag", etag);
        exchange.setResponseHeader(CONTENT_VERSION, API_VERSION);
        if (etag.equals(exchange.requestHeader("If-None-Match"))) {
            exchange.respond(304);
            return;
        }
        exchange.setResponseHeader("Content-Type", contentType);
        if (exchange.method().equals("HEAD")) {
            exchange.setResponseHeader("Content-Length", Long.toString(served.header().size()));
            exchange.respond(200, -1L).close();
            return;
        }
        // Streamed rather than materialised: the document is the size of what it lists, so handing
        // it over whole put the whole listing in heap on the request path.
        try (OutputStream out = exchange.respond(200, served.header().size())) {
            served.body().transferTo(out);
        }
    }

    private static void respondBytes(FormatExchange exchange, byte[] body, String contentType) throws IOException {
        exchange.setResponseHeader(CONTENT_VERSION, API_VERSION);
        exchange.setResponseHeader("Content-Type", contentType);
        if (exchange.method().equals("HEAD")) {
            exchange.setResponseHeader("Content-Length", Long.toString(body.length));
            exchange.respond(200, -1L).close();
            return;
        }
        exchange.respond(200, body);
    }

    // ---- layout ----

    @Override
    public Optional<ArtifactDescriptor> describe(String path) {
        if (!path.startsWith(PREFIX)) {
            return Optional.empty();
        }
        String[] segments = path.substring(PREFIX.length()).split("/");
        if (segments.length == 4 && segments[3].endsWith(".zip" + SIGNATURE)) {
            return Optional.of(ArtifactDescriptor.at(ECOSYSTEM, path));   // an archive's signature: material, not a release
        }
        if (segments.length == 4 && segments[3].endsWith(".zip")) {
            return Optional.of(new ArtifactDescriptor(ECOSYSTEM, segments[1] + "." + segments[2],
                    segments[3].substring(0, segments[3].length() - ".zip".length()),
                    path, "application/zip", false, null, -1L));
        }
        if (segments.length == 4 && !segments[3].isEmpty()) {
            // The release itself - the path a publish PUTs and the release document is read from - is that version, and
            // what it releases is the archive: so a publish is screened, held and forwarded under the version's
            // coordinate, while the descriptor's path names the archive rather than claiming the document is one.
            String version = strip(segments[3]);
            return Optional.of(new ArtifactDescriptor(ECOSYSTEM, segments[1] + "." + segments[2], version,
                    PREFIX + segments[0] + "/" + segments[1] + "/" + segments[2] + "/" + version + ".zip",
                    "application/zip", false, null, -1L));
        }
        return Optional.of(ArtifactDescriptor.at(ECOSYSTEM, path));
    }

    @Override
    public List<String> paths(String coordinate, String version, ArtifactStore store) {
        // A Swift release's pointer lives in the blobs namespace rather than under publish/, so the coordinate
        // seam this format really has is BlobLayout's - see blobKeys below.
        return List.of();
    }

    @Override
    public List<String> blobRoots() {
        return List.of("swift");
    }

    @Override
    public List<String> blobKeys(String coordinate, String version, ArtifactStore store) throws IOException {
        if (!BlobLayout.addressable(coordinate, version)) {
            return List.of();   // a traversal-shaped coordinate maps nowhere - these keys are what an eviction DELETES
        }
        int dot = coordinate.indexOf('.');
        if (dot <= 0 || dot == coordinate.length() - 1) {
            return List.of();
        }
        String scope = coordinate.substring(0, dot), name = coordinate.substring(dot + 1);
        List<String> keys = new ArrayList<>();
        for (String repo : store.list("swift")) {
            String archive = SwiftListings.archiveKey(repo, scope, name, version);
            if (store.readVersioned(archive).isPresent()) {
                keys.add(archive);
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

    /**
     * Each registry's release is published as SE-0391 has a client publish one: a multipart {@code PUT} to
     * {@code <repo>/<scope>/<name>/<version>} carrying the source archive, the metadata it was published with, its
     * {@code Package.swift} where one was sent, and its signature with the format header where it was signed. Asked for
     * back at the archive's path, so a release already there - a registry answers a second publish {@code 409} - is
     * not sent again.
     */
    @Override
    public Exported export(ArtifactStore repository, String coordinate, String version, ExportTarget target)
            throws IOException {
        int dot = coordinate.indexOf('.');
        if (!BlobLayout.addressable(coordinate, version) || dot <= 0 || dot == coordinate.length() - 1) {
            return Exported.WITHHELD;
        }
        String scope = coordinate.substring(0, dot), name = coordinate.substring(dot + 1);
        Blobs blobs = new Blobs(repository);
        List<PublishedExport.File> files = new ArrayList<>();
        for (String repo : repository.list("swift")) {
            String archiveKey = SwiftListings.archiveKey(repo, scope, name, version);
            Optional<Blobs.Located> located = blobs.locate(archiveKey);
            if (located.isEmpty()) {
                continue;
            }
            String hash = located.get().hash();
            MultipartForm form = MultipartForm.create().file("source-archive", name + "-" + version + ".zip",
                    "application/zip", located.get().size(), () -> blobs.open(hash));
            ByteArrayOutputStream release = new ByteArrayOutputStream();
            if (blobs.read(SwiftListings.metadataKey(repo, scope, name, version), release)) {
                form.field("metadata", "application/json",
                        MAPPER.writeValueAsBytes(MAPPER.readTree(release.toByteArray()).path("metadata")));
            }
            ByteArrayOutputStream manifest = new ByteArrayOutputStream();
            if (blobs.read(SwiftListings.manifestKey(repo, scope, name, version, ""), manifest)) {
                form.field("package-manifest", "text/x-swift", manifest.toByteArray());
            }
            Map<String, String> headers = new LinkedHashMap<>();
            ByteArrayOutputStream signature = new ByteArrayOutputStream();
            if (blobs.read(archiveKey + SIGNATURE, signature)) {
                byte[] signed = signature.toByteArray();
                form.file("source-archive-signature", "source-archive.sig", "application/octet-stream", signed.length,
                        () -> new ByteArrayInputStream(signed));
                headers.put("X-Swift-Package-Signature-Format", SIGNATURE_FORMAT);
            }
            headers.put("Content-Type", form.contentType());
            headers.put("Accept", "application/vnd.swift.registry.v1+json");
            files.add(new PublishedExport.File(new ExportTarget.Request("PUT",
                    repo + "/" + scope + "/" + name + "/" + version, headers,
                    ExportTarget.Body.of(form.length(), form::open)),
                    Optional.of(repo + "/" + scope + "/" + name + "/" + version + ".zip"), hash));
        }
        return PublishedExport.send(files, target);
    }
}
