package build.jenesis.repository.format.swift;

import module java.base;

import build.jenesis.repository.format.LifecycleMark;
import build.jenesis.repository.blobs.BlobLayout;
import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.format.ArtifactSignatures;
import build.jenesis.repository.blobs.Keys;
import build.jenesis.repository.blobs.ProxyLeg;
import build.jenesis.repository.blobs.ProxyRelay;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.ArtifactLayout;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.multipart.MultipartBody;
import build.jenesis.repository.multipart.MultipartForm;
import build.jenesis.repository.format.ExportTarget;
import build.jenesis.repository.format.PublishedExport;
import build.jenesis.repository.format.RepositoryExporter;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.PublishInterceptor;
import build.jenesis.repository.store.StoredListing;
import build.jenesis.repository.store.Withheld;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Swift Package Registry (SE-0292), hosted and proxied.
 *
 * <p>Six endpoints under {@code /swift/<repo>/}: list a package's releases, fetch a release's metadata, its
 * {@code Package.swift} and its source archive, look a package up by repository URL, and publish. A client is pointed
 * here with {@code swift package-registry set <base>/swift/<repo>}.
 *
 * <p>The API version is negotiated - {@code Accept: application/vnd.swift.registry.v1+json}, answered with
 * {@code Content-Version: 1} - and the URL space is unversioned, so these paths carry no version.
 *
 * <p>The {@code checksum} a client verifies an archive against is the SHA-256 the store computed as the bytes streamed
 * in, recorded in the release document at publish - it describes the bytes this repository serves, not a publisher's
 * claim.
 *
 * <p>A withheld release leaves the release list; a yanked one stays with the specification's {@code problem} object
 * (see {@link SwiftListings}).
 */
public final class SwiftFormat implements RepositoryFormat, ArtifactLayout, BlobLayout, ArtifactSignatures,
        RepositoryExporter, RepositoryImporter, ProxyLeg {

    /** The archive signature's sidecar suffix under the archive key and path: {@code <version>.zip.sig}. */
    private static final String SIGNATURE = ".sig";

    /** The one signature format the specification defines (SE-0305): a detached CMS structure over the source archive,
     *  declared by the {@code X-Swift-Package-Signature-Format} header on publish and download. */
    private static final String SIGNATURE_FORMAT = "cms-1.0.0";

    private static boolean signable(String path) {
        return archivePath(path) != null;
    }

    /** {@code {repo, scope, name, version}} for a source-archive path
     *  ({@code /swift/<repo>/<scope>/<name>/<version>.zip}) or its signature sidecar's ({@code ...zip.sig}), else
     *  {@code null}. */
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

    /** A source archive's or its signature sidecar's serving key, when the pointer exists - for the compliance screen's
     *  sibling read and the completion observer. */
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

    /** Reads a publisher's metadata part as one JSON value with nothing after it, so a second value behind the first is
     *  refused rather than ignored. */
    private static final ObjectReader METADATA = MAPPER.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** The package-ecosystem name Swift coordinates report. */
    public static final String ECOSYSTEM = "Swift";

    private static final String PREFIX = "/swift/";

    /** A source archive's signature is its {@code .sig} sidecar: optional, PKCS#7 detached over the archive bytes, and
     *  never asked of the sidecar itself. It arrives in the publish's multipart form ({@code source-archive-signature})
     *  and is announced as its own publish once stored, so the completion observer re-derives the verdict over the
     *  stored archive. */
    private static final ArtifactSignatures SIGNATURES = ArtifactSignatures.detachedSidecar(ECOSYSTEM, SIGNATURE,
            ArtifactSignatures.Scheme.PKCS7, SwiftFormat::signable, ArtifactSignatures.Coverage.OPTIONAL);

    /** The version header the specification's examples send, and what this answers with. */
    private static final String CONTENT_VERSION = "Content-Version";

    private static final String API_VERSION = "1";

    private static final int METADATA_LIMIT = 1024 * 1024;

    private static final int MANIFEST_LIMIT = 1024 * 1024;

    @Override
    public String name() {
        return "swift";
    }

    private final SwiftImporter importer = new SwiftImporter();

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

    /** The marks this format's clients see, each by its own word. */
    @Override
    public Map<LifecycleMark, String> lifecycleMarks() {
        return LifecycleMark.shown(LifecycleMark.YANKED);
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

    // ---- proxy ----

    /** The media type each SE-0391 document is asked for with; a registry may refuse a request that names none. */
    private static final String ACCEPT = "application/vnd.swift.registry.v1+";

    /**
     * Proxy a miss to an upstream SE-0391 registry (there is no public one). The local repository name aliases it, so
     * {@code /swift/<repo>/<rest>} maps to {@code <upstream>/<rest>}; nothing an upstream advertises is followed.
     *
     * <p>The release list (4.1) and the identifier lookup (4.5) are ENUMERATIONS, fetched fresh, and only an upstream
     * that answered 404/410 reaches the client as a 404. Each release's upstream {@code url} is dropped from the list,
     * as this repository's own list omits it, so a client fetches every release through this repository's cache and
     * gate.
     *
     * <p>A release's metadata (4.2) and manifest (4.3) are PINNED and relayed fresh. Its source archive (4.4) is held
     * to the {@code checksum} the release metadata declares: metadata that could not be read declines the fill, and a
     * mismatch is refused.
     */
    @Override
    public boolean pullThrough(FormatExchange exchange, ArtifactStore store, URI upstream,
                               ProxyFormat.Fetcher fetcher) throws IOException {
        String[] segments = exchange.path().substring(PREFIX.length()).split("/");
        if (segments.length < 2) {
            return false;
        }
        String repo = segments[0];
        String[] rest = Arrays.copyOfRange(segments, 1, segments.length);
        String root = upstream.toString().endsWith("/") ? upstream.toString() : upstream + "/";
        if (rest.length == 1 && rest[0].equals("identifiers")) {
            String url = exchange.queryParameter("url");
            if (url == null || url.isBlank()) {
                return false;
            }
            return relay(exchange, fetcher, URI.create(root + "identifiers?url="
                    + URLEncoder.encode(url, StandardCharsets.UTF_8)), "json", ProxyRelay.Document.ENUMERATION);
        }
        if (rest.length == 2) {
            URI list = URI.create(root + rest[0] + "/" + strip(rest[1]));
            ProxyRelay.Answer answer = ProxyRelay.fetchFresh(fetcher, list, Map.of("Accept", ACCEPT + "json"),
                    exchange, ProxyRelay.Document.ENUMERATION);
            if (!answer.answered()) {
                return answer.served();
            }
            JsonNode document;
            try {
                document = MAPPER.readTree(answer.document().body());
            } catch (RuntimeException unreadable) {
                document = null;
            }
            if (document == null || !(document.get("releases") instanceof ObjectNode releases)) {
                return ProxyRelay.unanswered(list, exchange, ProxyRelay.Document.ENUMERATION,
                        "the upstream answered a release list that is not one");
            }
            for (JsonNode release : releases) {
                if (release instanceof ObjectNode entry) {
                    entry.remove("url");
                }
            }
            respondBytes(exchange, MAPPER.writeValueAsBytes(document), "application/json");
            return true;
        }
        if (rest.length == 3 && rest[2].endsWith(".zip")) {
            String scope = rest[0], name = rest[1], version = rest[2].substring(0, rest[2].length() - ".zip".length());
            if (Keys.unsafe(scope) || Keys.unsafe(name) || Keys.unsafe(version)) {
                return false;
            }
            URI target = URI.create(root + scope + "/" + name + "/" + rest[2]);
            ProxyRelay.Declared declared = archiveChecksum(fetcher, URI.create(root + scope + "/" + name + "/"
                    + version));
            if (!declared.readable()) {
                return ProxyRelay.unverifiable(target, declared);
            }
            Blobs blobs = new Blobs(store);
            try (ProxyFormat.Download download = fetcher.download(target, Map.of("Accept", ACCEPT + "zip"))
                    .orElse(null)) {
                if (download == null || download.status() != 200 || !ProxyRelay.fill(blobs,
                        SwiftListings.archiveKey(repo, scope, name, version), target, download.body(), declared)) {
                    return false;
                }
            }
            archive(exchange, blobs, repo, scope, name, version);
            return true;
        }
        if (rest.length == 3) {
            return relay(exchange, fetcher, URI.create(root + rest[0] + "/" + rest[1] + "/" + strip(rest[2])), "json",
                    ProxyRelay.Document.PINNED);
        }
        if (rest.length == 4 && rest[3].equals("Package.swift")) {
            String swiftVersion = exchange.queryParameter("swift-version");
            return relay(exchange, fetcher, URI.create(root + rest[0] + "/" + rest[1] + "/" + rest[2]
                    + "/Package.swift" + (swiftVersion == null ? ""
                    : "?swift-version=" + URLEncoder.encode(swiftVersion, StandardCharsets.UTF_8))), "swift",
                    ProxyRelay.Document.PINNED);
        }
        return false;
    }

    /** Relay one document fresh, asked for with the media type the specification gives it. */
    private static boolean relay(FormatExchange exchange, ProxyFormat.Fetcher fetcher, URI url, String kind,
                                 ProxyRelay.Document document) throws IOException {
        ProxyRelay.Answer answer = ProxyRelay.fetchFresh(fetcher, url, Map.of("Accept", ACCEPT + kind), exchange,
                document);
        if (!answer.answered()) {
            return answer.served();
        }
        respondBytes(exchange, answer.document().body(), kind.equals("swift") ? "text/x-swift" : "application/json");
        return true;
    }

    /** The {@code checksum} a release's metadata declares for its {@code source-archive}: a SHA-256 in hex. */
    private static ProxyRelay.Declared archiveChecksum(ProxyFormat.Fetcher fetcher, URI release) throws IOException {
        ProxyRelay.Sidecar sidecar = ProxyRelay.declaring(fetcher, release, Map.of("Accept", ACCEPT + "json"));
        if (!sidecar.answered()) {
            return sidecar.verdict();
        }
        JsonNode document;
        try {
            document = MAPPER.readTree(sidecar.document().body());
        } catch (RuntimeException unreadable) {
            return ProxyRelay.Declared.unreadable("the release metadata at " + release + " is not JSON");
        }
        for (JsonNode resource : document.path("resources")) {
            if (resource.path("name").asString("").equals("source-archive")) {
                String checksum = resource.path("checksum").asString("");
                return checksum.matches("[0-9a-fA-F]{64}")
                        ? ProxyRelay.Declared.of("SHA-256", HexFormat.of().parseHex(checksum))
                        : ProxyRelay.Declared.NONE;
            }
        }
        return ProxyRelay.Declared.NONE;
    }

    // ---- the read path ----

    /** 4.1, the release list. The {@code 404} is keyed on the raw package folder: a package never held is absent, while
     *  one whose every release is withheld answers an empty {@code releases} object. */
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
            // A tool-version-specific manifest falls back to the unversioned one, as the ecosystem's layout intends.
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
            // A signed archive's signature rides the download in the headers 4.4 names, so a verifying client needs no
            // second read.
            byte[] bytes;
            try (InputStream in = blobs.open(sidecar.get().hash())) {
                bytes = in.readNBytes(ArtifactSignatures.Material.LARGEST_SIGNATURE);
            }
            exchange.setResponseHeader("X-Swift-Package-Signature-Format", SIGNATURE_FORMAT);
            exchange.setResponseHeader("X-Swift-Package-Signature", Base64.getEncoder().encodeToString(bytes));
        }
        // The Digest header takes RFC 3230's base64, not the store's hex.
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
     * 4.5, the reverse lookup from a source-repository URL to package identifiers, answered by point read from an index
     * the publish writes.
     *
     * <p><b>Screened.</b> A package whose every release is withheld is not named: an identifier is still a statement
     * that the repository holds it. The screen is the package's own release list, which a hold has emptied - one header
     * read per candidate.
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

    /** The republish policy: {@code OVERWRITE}, since a release already standing at other bytes is refused at the
     *  archive's link ({@link Blobs#linkRelease}), inside the pointer's compare-and-set, where two racing first
     *  publishes are told apart. */
    private static final Publication.Republish REPUBLISH = Publication.Republish.overwrite();

    /** A publish is a multipart form around the source archive, so an edge screening the body would assess the form
     *  while clients download the archive - {@code RepositoryFormat}'s envelope clause. This format screens at
     *  {@link #publish} over the archive's own bytes. */
    @Override
    public boolean screened() {
        return false;
    }

    /**
     * 4.6, publish: a multipart body carrying the source archive and, optionally, the release metadata, the
     * {@code Package.swift} and the archive's signature.
     *
     * <p><b>The archive is what is screened.</b> The archive streams into the store through the shared multipart
     * reader, which bounds the form fields, and the small parts are read against their limits. Only a form that is a
     * release is screened: the archive is read back into the shared hosted-publish operation with the discovered
     * interceptor chain and observers, under its own download path and coordinate, so the chain assesses the bytes a
     * client downloads. The store's digest becomes the release document's {@code checksum}.
     *
     * <p><b>The signature is judged with the archive.</b> It is stored as the archive's sidecar before the screen runs,
     * so an untrusted or invalid signature decides the archive's own verdict. A release already standing at other bytes
     * is refused before the sidecar is written.
     *
     * <p><b>The commit point is the archive's pointer</b>, linked through {@link Blobs#linkRelease}: of two first
     * publishes racing with different archives one lands and the other gets {@code 409}, and a re-publish of the same
     * form converges. The documents follow, the release list last; a re-publish with other metadata, manifest or
     * signature is refused with {@code 409} too. Two racing first publishes can each write their signature before
     * either links, so the winner may carry the loser's signature - which fails to verify over the winner's archive,
     * and so reads as invalid, never valid.
     *
     * <p>A held release is laid out behind its withhold marker ({@link #held}); a rejected one is answered {@code 422}
     * with nothing linked. Metadata that is not one JSON object is refused with {@code 400}, so a publisher cannot
     * place a field beside the store's {@code checksum}.
     */
    private void publish(FormatExchange exchange, Blobs blobs, String repo, String scope, String name,
                         String version) throws IOException {
        if (Keys.unsafe(scope) || Keys.unsafe(name) || Keys.unsafe(version) || version.endsWith(".zip")) {
            exchange.respond(400);
            return;
        }
        Optional<String> boundary = MultipartBody.boundary(exchange.requestHeader("Content-Type"));
        if (boundary.isEmpty()) {
            exchange.respond(400);
            return;
        }
        Blobs.Stored archive = null;
        byte[] metadata = "{}".getBytes(StandardCharsets.UTF_8);
        byte[] manifest = null;
        byte[] signature = null;
        boolean signed = false;
        MultipartBody body = MultipartBody.over(exchange.requestStream(), boundary.get());
        for (Optional<MultipartBody.Part> part = body.next(); part.isPresent(); part = body.next()) {
            switch (part.get().name()) {
                case "source-archive" -> archive = blobs.stored(part.get().stream());
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
        if (archive == null) {
            exchange.respond(400);
            return;
        }
        String format = exchange.requestHeader("X-Swift-Package-Signature-Format");
        if (signature != null && format != null && !format.strip().equalsIgnoreCase(SIGNATURE_FORMAT)) {
            // One format is defined; a signature in another could never be checked, so it is not stored.
            exchange.respond(400);
            return;
        }
        Optional<ObjectNode> declared = metadata(metadata);
        if (declared.isEmpty()) {
            problem(exchange, 400, "the release metadata is not a JSON object");
            return;
        }
        Release release = new Release(repo, scope, name, version, archive, declared.get(), manifest, signature);
        Publication.Commit commit = null;
        try {
            blobs.refuseReplacement(release.archiveKey(), archive.hash());
            release.sign(blobs);
            try (InputStream stored = blobs.open(archive.hash())) {
                commit = new Publication(blobs.store()).commit(release.described(), stored, REPUBLISH,
                        _ -> Publication.Visibility
                                // The serving pointer, in this format's namespace rather than publish/, so declared as
                                // a Serving step. It goes first: it is where a release standing at other bytes refuses
                                // this one.
                                .through((hash, size, _) -> blobs.linkRelease(release.archiveKey(), hash, size))
                                .andThrough((_, _, _) -> release.lay(blobs)));
            }
            if (commit.disposition() == PublishInterceptor.Disposition.QUARANTINE) {
                held(release, blobs, commit.hash());
            }
        } catch (Publication.RepublishConflict taken) {
            if (commit != null) {
                // A held re-publish was refused before anything was marked: its review handle goes with it.
                new Publication(blobs.store(), List.of(), List.of()).unpublish("/quarantine" + release.path());
            }
            problem(exchange, 409, new String(Blobs.alreadyPublished(scope + "." + name + " " + version),
                    StandardCharsets.UTF_8));
            return;
        }
        switch (commit.disposition()) {
            case ACCEPT -> {
                release.announceSignature(blobs);
                exchange.setResponseHeader("Location", exchange.requestUri());
                exchange.respond(201);
            }
            // Held for review: stored, laid out and withheld - the release list leaves it out until it is released.
            case QUARANTINE -> {
                release.announceSignature(blobs);
                explain(exchange, 202, commit.explanation());
            }
            // Refused: nothing is linked, and the stored archive is collected.
            case REJECT -> explain(exchange, 422, commit.explanation());
        }
    }

    /** Lay a held release out behind its withhold marker, so its review release is the same marker clear any hold's
     *  release is. The shared commit runs its layout only on {@code ACCEPT}, so without this a screen-time
     *  {@code QUARANTINE} would link nothing and a release would materialise no version. The marker retracts the
     *  archive's hash before its pointer is linked, so the held archive is never downloadable, readable or listed. */
    private static void held(Release release, Blobs blobs, String hash) throws IOException {
        // A hold never replaces a released archive or what its release says: refused before the mark.
        blobs.refuseReplacement(release.archiveKey(), hash);
        release.refuseReplacement(blobs);
        Withheld.mark(blobs.store(), hash, release.described());
        blobs.linkRelease(release.archiveKey(), hash, release.archive().size());
        release.lay(blobs);   // held: the release list keeps it out
    }

    /** One release as its publish form named it: the stored archive and the parts beside it. The accepted and held legs
     *  both lay it out through {@link #lay}, so they cannot write different releases for one form. */
    private record Release(String repo, String scope, String name, String version, Blobs.Stored archive,
                           ObjectNode metadata, byte[] manifest, byte[] signature) {

        String archiveKey() {
            return SwiftListings.archiveKey(repo, scope, name, version);
        }

        /** The archive's own download path, which the screen assesses and holds it under. */
        String path() {
            return PREFIX + repo + "/" + scope + "/" + name + "/" + version + ".zip";
        }

        ArtifactDescriptor described() {
            return new ArtifactDescriptor(ECOSYSTEM, scope + "." + name, version, path(), "application/zip", false,
                    null, -1L);
        }

        /** Store the archive's signature as its sidecar, where the screen's sibling read finds it. Beside an archive
         *  that already stands it is that release's and kept ({@link Blobs#writeRelease}); with none standing it
         *  replaces whatever a refused or held-off publish left. */
        void sign(Blobs blobs) throws IOException {
            if (signature == null) {
                return;
            }
            if (blobs.hash(archiveKey()).isPresent()) {
                blobs.writeRelease(archiveKey() + SIGNATURE, signature);
            } else {
                blobs.write(archiveKey() + SIGNATURE, signature);
            }
        }

        /** Everything the release serves beside its archive, written once the archive's pointer stands: the manifest,
         *  the release document, the repository-URL index and, last, the release list. The manifest and document are
         *  the release's own, so a re-publish that would change either is refused ({@link Blobs#writeRelease}). */
        void lay(Blobs blobs) throws IOException {
            if (manifest != null) {
                blobs.writeRelease(SwiftListings.manifestKey(repo, scope, name, version, ""), manifest);
            }
            blobs.writeRelease(SwiftListings.metadataKey(repo, scope, name, version), document());
            indexRepositoryUrls(blobs, repo, scope, name, metadata);
            new SwiftListings(blobs).refresh(repo, scope, name, version);
        }

        /** Refuse, before anything of this publish is written, a manifest or document other than the standing release's
         *  - what {@link #lay} would refuse. */
        void refuseReplacement(Blobs blobs) throws IOException {
            if (manifest != null) {
                blobs.refuseReplacement(SwiftListings.manifestKey(repo, scope, name, version, ""), manifest);
            }
            blobs.refuseReplacement(SwiftListings.metadataKey(repo, scope, name, version), document());
        }

        /** The release document endpoint 4.2 answers. */
        byte[] document() {
            return release(scope, name, version, archive.hash(), metadata);
        }

        /** Announce the stored signature as its own publish once the archive is laid out, so the signature dimension
         *  records the verdict on the version - a held one's too. */
        void announceSignature(Blobs blobs) {
            if (signature != null) {
                new Publication(blobs.store()).published(ArtifactDescriptor.at(ECOSYSTEM, path() + SIGNATURE));
            }
        }
    }

    /** The release document endpoint 4.2 answers, assembled at publish from the store's digest. */
    private static byte[] release(String scope, String name, String version, String hash, ObjectNode metadata) {
        ObjectNode release = MAPPER.createObjectNode();
        release.put("id", scope + "." + name);
        release.put("version", version);
        ObjectNode archive = release.putArray("resources").addObject();
        archive.put("name", "source-archive");
        archive.put("type", "application/zip");
        archive.put("checksum", hash);
        release.set("metadata", metadata);
        return MAPPER.writeValueAsBytes(release);
    }

    /** The publisher's metadata part as one JSON object - an absent or blank part is an empty one - or empty when it is
     *  not JSON, another kind of value, or a value with more after it. */
    private static Optional<ObjectNode> metadata(byte[] metadata) {
        if (new String(metadata, StandardCharsets.UTF_8).isBlank()) {
            return Optional.of(MAPPER.createObjectNode());
        }
        try {
            return METADATA.readTree(metadata) instanceof ObjectNode object ? Optional.of(object) : Optional.empty();
        } catch (JacksonException malformed) {
            return Optional.empty();
        }
    }

    /** Refuse with the specification's problem details (RFC 7807), the body a client prints a refusal from. */
    private static void problem(FormatExchange exchange, int status, String detail) throws IOException {
        ObjectNode problem = MAPPER.createObjectNode();
        problem.put("status", status);
        problem.put("detail", detail);
        exchange.setResponseHeader(CONTENT_VERSION, API_VERSION);
        exchange.setResponseHeader("Content-Type", "application/problem+json");
        exchange.respond(status, MAPPER.writeValueAsBytes(problem));
    }

    /** Whether a package still offers anything, the screen {@link #identifiers} applies: a held release leaves the
     *  list, so a package with no entries is one nothing may name. */
    private static boolean servable(Blobs blobs, SwiftListings listings, String repo, String scope, String name)
            throws IOException {
        Optional<StoredListing.Header> header = StoredListing.header(blobs.store(),
                SwiftListings.releases(repo, scope, name));
        if (header.isEmpty()) {
            // Never materialised: read through its spec, which generates it with the write path's screen.
            return StoredListing.read(blobs.store(), listings.releasesSpec(repo, scope, name))
                    .map(document -> document.header().entries() > 0)
                    .orElse(false);
        }
        return header.get().entries() > 0;
    }

    /** Note this package under every repository URL its metadata declares, so 4.5 is a point read. */
    private static void indexRepositoryUrls(Blobs blobs, String repo, String scope, String name, JsonNode metadata)
            throws IOException {
        for (JsonNode element : metadata.path("repositoryURLs")) {
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
        // Streamed: the document is the size of what it lists.
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
            // The release path - which a publish PUTs and the release document is read from - describes that version,
            // and the descriptor's path names the archive it releases.
            String version = strip(segments[3]);
            return Optional.of(new ArtifactDescriptor(ECOSYSTEM, segments[1] + "." + segments[2], version,
                    PREFIX + segments[0] + "/" + segments[1] + "/" + segments[2] + "/" + version + ".zip",
                    "application/zip", false, null, -1L));
        }
        return Optional.of(ArtifactDescriptor.at(ECOSYSTEM, path));
    }

    @Override
    public List<String> paths(String coordinate, String version, ArtifactStore store) {
        // A Swift release's pointer lives in the blobs namespace, so its coordinate seam is BlobLayout's (blobKeys
        // below).
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
     * <p>This layout's pointer key is its served path without the leading slash, so the request-path describer is the
     * parse, re-keyed to the pointer. It answers only when the description names a version: {@code describe} falls back
     * to a coordinate-less descriptor for the documents beside an artifact, while a repair walking the blob root needs
     * empty for those. {@code BlobLayoutCoordinateSeamTest} drives both halves over keys this layout wrote.
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

    /** Each registry's release is published as a client publishes one: a multipart {@code PUT} to
     *  {@code <repo>/<scope>/<name>/<version>} with the source archive, its metadata, its {@code Package.swift} where
     *  sent, and its signature with the format header where signed. Asked for at the archive's path first, so a release
     *  already there is not sent again. */
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
