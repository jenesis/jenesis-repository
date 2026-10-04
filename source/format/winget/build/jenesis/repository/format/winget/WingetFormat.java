package build.jenesis.repository.format.winget;

import module java.base;
import module tools.jackson.databind;

import build.jenesis.repository.format.LifecycleMark;
import build.jenesis.repository.blobs.BlobExport;
import build.jenesis.repository.blobs.BlobLayout;
import build.jenesis.repository.blobs.ComposedLayout;
import build.jenesis.repository.format.ExportTarget;
import build.jenesis.repository.format.RepositoryExporter;
import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.blobs.Keys;
import build.jenesis.repository.blobs.RequestBase;
import build.jenesis.repository.format.ArtifactLayout;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.format.lifecycle.Lifecycle;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.StoredListing;
import build.jenesis.repository.store.Publication;

/**
 * The Windows Package Manager REST source protocol, so {@code winget search} and {@code winget install} resolve against
 * this repository once a client has run {@code winget source add -n <name> -a <base>/winget/<repo> -t Microsoft.Rest}.
 *
 * <p><b>The three read routes are Microsoft's; the two write routes are ours.</b> {@code GET information} answers the
 * source identifier and protocol versions, {@code POST manifestSearch} a search, and
 * {@code GET packageManifests/<PackageIdentifier>} the manifest an install resolves. The specification defines no way
 * to put a package into a source, so this format adds {@code PUT manifests/<id>/<version>} for the manifest and
 * {@code PUT installers/<id>/<version>/<file>} for each installer, served back from the same path. The split keeps the
 * publish streaming: an installer can run to gigabytes and goes straight into the store, while only the small manifest
 * is held in memory, under a bound.
 *
 * <p><b>What is served is rewritten.</b> The client verifies a download against the manifest's {@code InstallerSha256}.
 * On read each installer entry's {@code InstallerUrl} is regenerated from the serving request (nothing host-specific is
 * stored) and its digest restated from the hash the store computed, so the client checks the bytes this server hands
 * it. An installer whose bytes were never uploaded is dropped from the served manifest.
 *
 * <p><b>Reads are bounded.</b> A search reads one stored document, the repository index each publish re-derives, and
 * filters it under an examined budget ({@link #EXAMINED_CAP}); a manifest read is a point lookup of the version list
 * plus a point read per version.
 *
 * <p>Pointers live in the shared {@code Blobs} namespace, so {@link #paths} is empty and coordinate-scoped enforcement
 * runs through {@link #blobKeys}/{@link #servedPaths}. OSV publishes no winget feed, so vulnerability screening finds
 * nothing while licence and malicious-package screening still apply.
 */
public final class WingetFormat implements RepositoryFormat, ArtifactLayout, ComposedLayout,
        RepositoryImporter.Delegating, RepositoryExporter {

    /** The ecosystem name this format's artifacts report, distinct from {@link #name()}, the routing id: the product's
     *  own spelling, as {@code CocoaPods} and {@code RubyGems} are. */
    public static final String ECOSYSTEM = "WinGet";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String PREFIX = "/winget/";

    private static final String INFORMATION = "information";
    private static final String SEARCH = "manifestSearch";
    private static final String PACKAGE_MANIFESTS = "packageManifests/";
    private static final String INSTALLERS = "installers/";
    private static final String MANIFESTS = "manifests/";

    /** The REST protocol versions this server implements; 1.1.0 added the {@code Inclusions}/{@code Filters} search
     *  shape. */
    private static final List<String> SUPPORTED_VERSIONS = List.of("1.0.0", "1.1.0");

    /** The largest manifest accepted. A manifest is the one publisher body this format holds in heap, so one over the
     *  bound is refused rather than risking an OutOfMemoryError (contract clause 15). */
    private static final int LARGEST_MANIFEST = 512 * 1024;

    /** A search body is smaller still: a query, a match type and some filters. */
    private static final int LARGEST_SEARCH = 64 * 1024;

    /** How many index entries one search examines before answering with what it has: filtering the stored index is a
     *  walk, so it has a budget. */
    private static final int EXAMINED_CAP = 5_000;

    /** The most results one response carries when the client asks for no limit of its own. */
    private static final int DEFAULT_RESULTS = 100;

    private final WingetImporter importer = new WingetImporter();

    @Override
    public RepositoryImporter importer() {
        return importer;
    }

    public WingetFormat() {
    }

    @Override
    public String name() {
        return "winget";
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
        String rest = exchange.path().substring(PREFIX.length());
        int slash = rest.indexOf('/');
        if (slash < 0) {
            exchange.respond(404);
            return;
        }
        String repo = rest.substring(0, slash);
        String sub = rest.substring(slash + 1);
        if (Keys.unsafe(repo)) {
            exchange.respond(404);
            return;
        }
        Blobs blobs = new Blobs(store);
        String method = exchange.method();
        switch (method) {
            case "PUT" -> {
                if (sub.startsWith(MANIFESTS)) {
                    publishManifest(repo, sub.substring(MANIFESTS.length()), exchange, blobs);
                } else if (sub.startsWith(INSTALLERS)) {
                    publishInstaller(repo, sub.substring(INSTALLERS.length()), exchange, blobs);
                } else {
                    exchange.respond(404);
                }
            }
            case "POST" -> {
                if (sub.equals(SEARCH)) {
                    search(repo, exchange, blobs);
                } else {
                    exchange.respond(404);
                }
            }
            case "GET", "HEAD" -> {
                if (sub.equals(INFORMATION)) {
                    information(repo, exchange);
                } else if (sub.startsWith(PACKAGE_MANIFESTS)) {
                    packageManifest(repo, sub.substring(PACKAGE_MANIFESTS.length()), exchange, blobs);
                } else if (sub.startsWith(INSTALLERS)) {
                    download(repo, sub.substring(INSTALLERS.length()), exchange, blobs);
                } else if (sub.startsWith(MANIFESTS)) {
                    manifest(repo, sub.substring(MANIFESTS.length()), exchange, blobs);
                } else {
                    exchange.respond(404);
                }
            }
            default -> exchange.respond(405);
        }
    }

    // ---- writes

    /** Store one version's manifest: the version object a client is served back ({@code PackageVersion},
     *  {@code DefaultLocale}, {@code Installers}), validated against the path so a package cannot publish under
     *  another's coordinate. */
    private void publishManifest(String repo, String tail, FormatExchange exchange, Blobs blobs) throws IOException {
        String[] parts = tail.split("/", -1);
        if (parts.length != 2) {
            exchange.respond(404);
            return;
        }
        String identifier = parts[0];
        String version = parts[1];
        if (Keys.unsafe(identifier) || Keys.unsafe(version)) {
            exchange.respond(400);
            return;
        }
        byte[] body = bounded(exchange, LARGEST_MANIFEST);
        if (body == null) {
            exchange.respond(413);
            return;
        }
        ObjectNode manifest;
        try {
            JsonNode parsed = MAPPER.readTree(body);
            if (!parsed.isObject()) {
                exchange.respond(400);
                return;
            }
            manifest = (ObjectNode) parsed;
        } catch (RuntimeException malformed) {
            exchange.respond(400);
            return;
        }
        String declaredIdentifier = text(manifest, "PackageIdentifier");
        String declaredVersion = text(manifest, "PackageVersion");
        if ((declaredIdentifier != null && !declaredIdentifier.equals(identifier))
                || (declaredVersion != null && !declaredVersion.equals(version))) {
            // The manifest names another identifier or version than its path: refuse rather than file it under a
            // coordinate its own contents disown.
            exchange.respond(400);
            return;
        }
        // Normalised onto the path, so the stored manifest is self-describing.
        manifest.put("PackageIdentifier", identifier);
        manifest.put("PackageVersion", version);
        blobs.write(manifestKey(repo, identifier, version), MAPPER.writeValueAsBytes(manifest));
        new WingetListings(blobs).refresh(repo, identifier, version);
        exchange.respond(201);
    }

    /** Stream one installer's bytes into the store and record the pointer the served manifest's {@code InstallerUrl}
     *  and {@code InstallerSha256} are generated from. Nothing is buffered. */
    private void publishInstaller(String repo, String tail, FormatExchange exchange, Blobs blobs) throws IOException {
        String[] parts = tail.split("/", -1);
        if (parts.length != 3) {
            exchange.respond(404);
            return;
        }
        String identifier = parts[0];
        String version = parts[1];
        String file = parts[2];
        if (Keys.unsafe(identifier) || Keys.unsafe(version) || Keys.unsafe(file)) {
            exchange.respond(400);
            return;
        }
        // An installer belongs to a version, which exists only once its manifest was accepted. The manifest carries
        // what the gate reads and an installer is opaque bytes, so without this a manifest refused or held for review
        // could be followed by an installer stored and served under a coordinate the manifest never established. A
        // point read before the body is consumed, so a refusal costs neither a blob nor a buffer.
        if (!blobs.exists(manifestKey(repo, identifier, version))) {
            exchange.respond(404);
            return;
        }
        String hash = blobs.store(exchange.requestStream());
        // linkRelease retries its compare-and-set and spares bytes a collector has condemned.
        try {
            blobs.linkRelease(installerKey(repo, identifier, version, file), hash, -1L);
        } catch (Publication.RepublishConflict taken) {
            exchange.respond(409, Blobs.alreadyPublished(identifier + " " + version + " " + file));
            return;
        }
        exchange.respond(201);
    }

    // ---- reads

    /** A version's manifest as it was published, at the path it was published to: what another deployment importing
     *  this one lays the version down from, before its installers. A withheld one is absent. */
    private void manifest(String repo, String rest, FormatExchange exchange, Blobs blobs) throws IOException {
        String[] parts = rest.split("/", -1);
        if (parts.length != 2 || Keys.unsafe(parts[0]) || Keys.unsafe(parts[1])) {
            exchange.respond(404);
            return;
        }
        blobs.answer(manifestKey(repo, parts[0], parts[1]), exchange, "application/json");
    }

    /** The source's own description: who it is, and which protocol versions it speaks. */
    private void information(String repo, FormatExchange exchange) throws IOException {
        ObjectNode data = MAPPER.createObjectNode();
        // Stable per repository: a client stores the identifier with the source, and a changed one reads as another
        // source.
        data.put("SourceIdentifier", "JenesisRepository." + repo);
        ArrayNode versions = data.putArray("ServerSupportedVersions");
        SUPPORTED_VERSIONS.forEach(versions::add);
        ObjectNode body = MAPPER.createObjectNode();
        body.set("Data", data);
        respondJson(exchange, 200, MAPPER.writeValueAsBytes(body));
    }

    /** Answer a {@code winget search} from the stored index. A package matches when every {@code Filters} entry matches
     *  and, where either is given, the {@code Query.KeyWord} or some {@code Inclusions} entry does - over the
     *  identifier, name and publisher the index line carries. */
    private void search(String repo, FormatExchange exchange, Blobs blobs) throws IOException {
        byte[] body = bounded(exchange, LARGEST_SEARCH);
        if (body == null) {
            exchange.respond(413);
            return;
        }
        ObjectNode request;
        try {
            JsonNode parsed = body.length == 0 ? MAPPER.createObjectNode() : MAPPER.readTree(body);
            request = parsed.isObject() ? (ObjectNode) parsed : MAPPER.createObjectNode();
        } catch (RuntimeException malformed) {
            exchange.respond(400);
            return;
        }
        int limit = request.path("MaximumResults").isIntegralNumber()
                ? Math.min(Math.max(request.path("MaximumResults").asInt(), 1), DEFAULT_RESULTS)
                : DEFAULT_RESULTS;
        String keyword = lower(text(request.path("Query"), "KeyWord"));
        List<String> inclusions = matchValues(request.path("Inclusions"));
        List<String> filters = matchValues(request.path("Filters"));
        // One streaming pass over the index, keeping only results and examining at most EXAMINED_CAP entries: a query
        // never holds the index, whose size is the repository's.
        Optional<StoredListing.Served> index = StoredListing.open(blobs.store(),
                new WingetListings(blobs).indexSpec(repo));
        ArrayNode data = MAPPER.createArrayNode();
        if (index.isPresent()) {
            int examined = 0;
            try (StoredListing.Served document = index.get();
                 StoredListing.Codec.Reader reader = WingetListings.INDEX.read(document.body(),
                         document.header().size())) {
                for (Optional<Map.Entry<String, byte[]>> entry = reader.next(); entry.isPresent();
                     entry = reader.next()) {
                    if (examined++ >= EXAMINED_CAP || data.size() >= limit) {
                        break;
                    }
                    String line = new String(entry.get().getValue(), StandardCharsets.UTF_8);
                    int tab = line.indexOf('\t');
                    if (tab < 0) {
                        continue;
                    }
                    JsonNode candidate;
                    try {
                        candidate = MAPPER.readTree(line.substring(tab + 1));
                    } catch (RuntimeException unreadable) {
                        continue;
                    }
                    if (matches(candidate, keyword, inclusions, filters)) {
                        data.add(candidate);
                    }
                }
            }
        }
        ObjectNode response = MAPPER.createObjectNode();
        response.set("Data", data);
        respondJson(exchange, 200, MAPPER.writeValueAsBytes(response));
    }

    /** Answer one package's manifests: every servable version, or the one {@code ?Version=} names, each with its
     *  installer entries rewritten onto this repository. */
    private void packageManifest(String repo, String identifier, FormatExchange exchange, Blobs blobs)
            throws IOException {
        if (identifier.isEmpty() || identifier.indexOf('/') >= 0 || Keys.unsafe(identifier)) {
            exchange.respond(404);
            return;
        }
        Optional<StoredListing.Document> listing = StoredListing.read(blobs.store(),
                new WingetListings(blobs).packageSpec(repo, identifier));
        if (listing.isEmpty()) {
            exchange.respond(404);
            return;
        }
        String requested = exchange.queryParameter("Version");
        ArrayNode versions = MAPPER.createArrayNode();
        String base = repoBase(repo, exchange);
        for (String version : WingetListings.VERSIONS.split(listing.get().body()).keySet()) {
            if (requested != null && !requested.isEmpty() && !requested.equals(version)) {
                continue;
            }
            Optional<byte[]> manifest = readManifest(blobs, repo, identifier, version);
            if (manifest.isEmpty()) {
                continue;
            }
            served(blobs, repo, identifier, version, manifest.get(), base).ifPresent(versions::add);
        }
        if (versions.isEmpty()) {
            exchange.respond(404);
            return;
        }
        ObjectNode data = MAPPER.createObjectNode();
        data.put("PackageIdentifier", identifier);
        data.set("Versions", versions);
        ObjectNode response = MAPPER.createObjectNode();
        response.set("Data", data);
        response.set("RequiredQueryParameters", MAPPER.createArrayNode());
        response.set("UnsupportedQueryParameters", MAPPER.createArrayNode());
        respondJson(exchange, 200, MAPPER.writeValueAsBytes(response));
    }

    /** Stream one installer's stored bytes: the pointer, the withheld marker and the length, then the body. */
    private void download(String repo, String tail, FormatExchange exchange, Blobs blobs) throws IOException {
        String[] parts = tail.split("/", -1);
        if (parts.length != 3 || Keys.unsafe(parts[0]) || Keys.unsafe(parts[1]) || Keys.unsafe(parts[2])) {
            exchange.respond(404);
            return;
        }
        blobs.answer(installerKey(repo, parts[0], parts[1], parts[2]), exchange, "application/octet-stream");
    }

    // ---- layout

    /** An installer, {@code /winget/<repo>/installers/<identifier>/<version>/<file>}, is served from its blob
     *  pointer, and a version's manifest, {@code /winget/<repo>/manifests/<identifier>/<version>}, from its own. */
    @Override
    public Optional<String> servingKey(String requestPath, ArtifactStore store) throws IOException {
        Optional<ArtifactDescriptor> described = describedVersion(requestPath);
        int slash = requestPath.indexOf('/', PREFIX.length());
        if (described.isEmpty() || slash < 0) {
            return Optional.empty();
        }
        String repo = requestPath.substring(PREFIX.length(), slash);
        String identifier = described.get().coordinate();
        String version = described.get().version();
        String file = requestPath.substring(requestPath.lastIndexOf('/') + 1);
        if (requestPath.equals(PREFIX + repo + "/" + MANIFESTS + identifier + "/" + version)) {
            return BlobLayout.stored(manifestKey(repo, identifier, version), store);
        }
        return requestPath.equals(PREFIX + repo + "/" + INSTALLERS + identifier + "/" + version + "/" + file)
                ? BlobLayout.stored(installerKey(repo, identifier, version, file), store)
                : Optional.empty();
    }

    /** A version is its manifest and its installers, the manifest first: an installer is accepted only under a version
     *  whose manifest is already there. */
    @Override
    public List<String> contents(String coordinate, String version, ArtifactStore store) throws IOException {
        List<String> served = servedPaths(coordinate, version, store);
        if (served.isEmpty()) {
            return List.of();
        }
        List<String> contents = new ArrayList<>();
        for (String repo : store.list("winget")) {
            if (store.exists(manifestKey(repo, coordinate, version))) {
                contents.add(PREFIX + repo + "/" + MANIFESTS + coordinate + "/" + version);
            }
        }
        contents.addAll(served);
        return List.copyOf(contents);
    }

    @Override
    public Optional<ArtifactDescriptor> describe(String path) {
        if (!path.startsWith(PREFIX)) {
            return Optional.empty();
        }
        String rest = path.substring(PREFIX.length());
        int slash = rest.indexOf('/');
        if (slash < 0) {
            return Optional.empty();
        }
        String sub = rest.substring(slash + 1);
        if (sub.startsWith(MANIFESTS)) {
            // The manifest publish path is where a version is created, so the gate links its review pointer there; it
            // carries the coordinate the installers under it do.
            String[] pushed = sub.substring(MANIFESTS.length()).split("/", -1);
            if (pushed.length == 2 && ArtifactLayout.addressable(pushed[0], pushed[1])) {
                return Optional.of(new ArtifactDescriptor(ECOSYSTEM, pushed[0], pushed[1], path,
                        "application/json", prerelease(pushed[1]), null, -1L));
            }
            return Optional.empty();
        }
        if (!sub.startsWith(INSTALLERS)) {
            return Optional.empty();
        }
        String[] parts = sub.substring(INSTALLERS.length()).split("/", -1);
        if (parts.length != 3) {
            return Optional.of(ArtifactDescriptor.at(ECOSYSTEM, path));
        }
        return Optional.of(new ArtifactDescriptor(ECOSYSTEM, parts[0], parts[1], path,
                "application/octet-stream", prerelease(parts[1]), null, -1L));
    }

    @Override
    public List<String> paths(String coordinate, String version, ArtifactStore store) {
        // Winget pointers live in the shared Blobs namespace; blobKeys/servedPaths carry the coordinate.
        return List.of();
    }

    @Override
    public List<String> blobRoots() {
        return List.of("winget");
    }

    @Override
    public List<String> blobKeys(String coordinate, String version, ArtifactStore store) throws IOException {
        if (!BlobLayout.addressable(coordinate, version)) {
            return List.of();
        }
        Blobs blobs = new Blobs(store);
        List<String> keys = new ArrayList<>();
        // The registry set is operator-configured and bounded; within one, the manifest key is a point lookup and the
        // installers are the version's own children.
        for (String repo : store.list("winget")) {
            String manifest = manifestKey(repo, coordinate, version);
            if (store.readVersioned(manifest).isPresent()) {
                keys.add(manifest);
            }
            for (String file : blobs.list(installerPrefix(repo, coordinate, version))) {
                keys.add(installerKey(repo, coordinate, version, file));
            }
        }
        return keys;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Both pointer shapes, since an eviction deletes both: the manifest at
     * {@code winget/<repo>/manifest/<id>/<version>} and each installer at
     * {@code winget/<repo>/blob/<id>/<version>/<file>}. Neither is a served path, so the segments are constants used in
     * both directions. An identifier is one dotted segment ({@code Publisher.Package}), so the split is positional: id,
     * version, then an installer's file name.
     */
    @Override
    public Optional<ArtifactDescriptor> describePointer(String key) {
        if (!key.startsWith("winget/")) {
            return Optional.empty();
        }
        int repo = key.indexOf('/', "winget/".length());
        if (repo < 0) {
            return Optional.empty();
        }
        String sub = key.substring(repo + 1);
        String[] parts;
        if (sub.startsWith(MANIFEST + "/")) {
            parts = sub.substring(MANIFEST.length() + 1).split("/");
            if (parts.length != 2) {
                return Optional.empty();
            }
        } else if (sub.startsWith(BLOB + "/")) {
            parts = sub.substring(BLOB.length() + 1).split("/");
            if (parts.length != 3) {
                return Optional.empty();   // the identifier's or the version's own folder, not an installer
            }
        } else {
            return Optional.empty();
        }
        String identifier = parts[0], version = parts[1];
        if (!BlobLayout.addressable(identifier, version)) {
            return Optional.empty();
        }
        return Optional.of(new ArtifactDescriptor(ECOSYSTEM, identifier, version, key,
                "application/octet-stream", version.indexOf('-') >= 0, null, -1L));
    }

    @Override
    public List<String> servedPaths(String coordinate, String version, ArtifactStore store) throws IOException {
        if (!BlobLayout.addressable(coordinate, version)) {
            return List.of();
        }
        Blobs blobs = new Blobs(store);
        List<String> paths = new ArrayList<>();
        for (String repo : store.list("winget")) {
            for (String file : blobs.list(installerPrefix(repo, coordinate, version))) {
                paths.add(PREFIX + repo + "/" + INSTALLERS + coordinate + "/" + version + "/" + file);
            }
        }
        return paths;
    }

    // ---- importer

    // ---- keys and helpers

    /** The two key segments under a registry, composed and parsed here. */
    private static final String MANIFEST = "manifest";

    private static final String BLOB = "blob";

    static String manifestPrefix(String repo) {
        return "winget/" + repo + "/" + MANIFEST;
    }

    static String manifestKey(String repo, String identifier, String version) {
        return manifestPrefix(repo) + "/" + identifier + "/" + version;
    }

    static String installerPrefix(String repo, String identifier, String version) {
        return "winget/" + repo + "/" + BLOB + "/" + identifier + "/" + version;
    }

    static String installerKey(String repo, String identifier, String version, String file) {
        return installerPrefix(repo, identifier, version) + "/" + file;
    }

    /** One version's stored manifest, or empty when it is absent or withheld. */
    static Optional<byte[]> readManifest(Blobs blobs, String repo, String identifier, String version)
            throws IOException {
        String key = manifestKey(repo, identifier, version);
        if (blobs.withheld(key)) {
            return Optional.empty();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        return blobs.read(key, out) ? Optional.of(out.toByteArray()) : Optional.empty();
    }

    /** The repository index line for one package: {@code <identifier>\t<compact JSON>}, the JSON being the object a
     *  search response carries, so a query filters stored lines rather than re-reading manifests. */
    static byte[] indexEntry(String identifier, byte[] manifest, List<String> versions) {
        ObjectNode entry = MAPPER.createObjectNode();
        entry.put("PackageIdentifier", identifier);
        JsonNode parsed;
        try {
            parsed = MAPPER.readTree(manifest);
        } catch (RuntimeException unreadable) {
            parsed = MAPPER.createObjectNode();
        }
        JsonNode locale = parsed.path("DefaultLocale");
        String name = text(locale, "PackageName");
        String publisher = text(locale, "Publisher");
        entry.put("PackageName", name == null ? identifier : name);
        entry.put("Publisher", publisher == null ? "" : publisher);
        ArrayNode listed = entry.putArray("Versions");
        for (String version : versions) {
            listed.addObject().put("PackageVersion", version);
        }
        String line = identifier + "\t" + entry.toString();
        return line.getBytes(StandardCharsets.UTF_8);
    }

    /** One version as served: each installer entry's {@code InstallerUrl} regenerated onto this repository and its
     *  {@code InstallerSha256} restated from the digest of the bytes that will be streamed. An entry whose bytes were
     *  never uploaded is dropped; the version is still served with the installers that resolve. */
    private static Optional<ObjectNode> served(Blobs blobs, String repo, String identifier, String version,
                                               byte[] manifest, String base) throws IOException {
        JsonNode parsed;
        try {
            parsed = MAPPER.readTree(manifest);
        } catch (RuntimeException unreadable) {
            return Optional.empty();
        }
        if (!parsed.isObject()) {
            return Optional.empty();
        }
        ObjectNode object = ((ObjectNode) parsed).deepCopy();
        ArrayNode rewritten = MAPPER.createArrayNode();
        JsonNode installers = object.path("Installers");
        if (installers.isArray()) {
            for (JsonNode installer : installers) {
                if (!installer.isObject()) {
                    continue;
                }
                String file = installerFile(installer);
                if (file == null) {
                    continue;
                }
                Optional<Blobs.Located> located = blobs.locate(installerKey(repo, identifier, version, file));
                if (located.isEmpty()) {
                    continue;
                }
                ObjectNode entry = ((ObjectNode) installer).deepCopy();
                entry.put("InstallerUrl", base + "/" + INSTALLERS + identifier + "/" + version + "/" + file);
                // Upper case, as the canonical manifests state it; clients compare case-insensitively.
                entry.put("InstallerSha256", located.get().hash().toUpperCase(Locale.ROOT));
                rewritten.add(entry);
            }
        }
        object.set("Installers", rewritten);
        return Optional.of(object);
    }

    /** The stored file name for an installer entry: the last segment of the publisher's {@code InstallerUrl}, the name
     *  its own {@code PUT} used. A URL with no usable last segment drops the entry. */
    private static String installerFile(JsonNode installer) {
        String url = text(installer, "InstallerUrl");
        if (url == null || url.isBlank()) {
            return null;
        }
        String path = url;
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        int slash = path.lastIndexOf('/');
        String file = slash < 0 ? path : path.substring(slash + 1);
        return file.isBlank() || Keys.unsafe(file) ? null : file;
    }

    /** Whether every stated filter matches, and the keyword or some inclusion does when either was given. */
    private static boolean matches(JsonNode candidate, String keyword, List<String> inclusions, List<String> filters) {
        for (String filter : filters) {
            if (!field(candidate, filter)) {
                return false;
            }
        }
        if (keyword == null && inclusions.isEmpty()) {
            return true;
        }
        if (keyword != null && field(candidate, keyword)) {
            return true;
        }
        return inclusions.stream().anyMatch(inclusion -> field(candidate, inclusion));
    }

    /** Whether a candidate's identifier, name or publisher contains the value, matched case-insensitively. */
    private static boolean field(JsonNode candidate, String value) {
        return contains(candidate, "PackageIdentifier", value)
                || contains(candidate, "PackageName", value)
                || contains(candidate, "Publisher", value);
    }

    private static boolean contains(JsonNode candidate, String field, String value) {
        String actual = lower(text(candidate, field));
        return actual != null && actual.contains(value);
    }

    /** The {@code RequestMatch.KeyWord} of each entry of a search request's {@code Inclusions}/{@code Filters}. */
    private static List<String> matchValues(JsonNode entries) {
        if (!entries.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode entry : entries) {
            String keyword = lower(text(entry.path("RequestMatch"), "KeyWord"));
            if (keyword != null) {
                values.add(keyword);
            }
        }
        return values;
    }

    /** Read the whole request body, or {@code null} when it exceeds the bound - a refusal, never a truncation. */
    private static byte[] bounded(FormatExchange exchange, int largest) throws IOException {
        try (InputStream in = exchange.requestStream()) {
            byte[] body = in.readNBytes(largest + 1);
            return body.length > largest ? null : body;
        }
    }

    private static void respondJson(FormatExchange exchange, int status, byte[] body) throws IOException {
        exchange.setResponseHeader("Content-Type", "application/json");
        if (exchange.method().equals("HEAD")) {
            exchange.setResponseHeader("Content-Length", Long.toString(body.length));
            exchange.respond(status, -1L).close();
            return;
        }
        exchange.respond(status, body);
    }

    /** The external base URL of this registry, generated from the serving request so a download routes back to it. */
    private static String repoBase(String repo, FormatExchange exchange) {
        return RequestBase.of(exchange) + exchange.external(PREFIX + repo);
    }

    /** A winget version is a dotted numeric string; a pre-release carries a hyphenated tail, as semver does. */
    private static boolean prerelease(String version) {
        return version.indexOf('-') >= 0;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.stringValue() : null;
    }

    private static String lower(String value) {
        return value == null || value.isBlank() ? null : value.toLowerCase(Locale.ROOT);
    }

    /** Each registry's manifest is put first, at {@code <repo>/manifests/<id>/<version>}, then each installer at its
     *  served path - the target refuses an installer whose version has no manifest. A manifest cannot be asked for back
     *  alone, so it is sent whenever an installer is. */
    @Override
    public Exported export(ArtifactStore repository, String coordinate, String version, ExportTarget target)
            throws IOException {
        if (!BlobLayout.addressable(coordinate, version)) {
            return Exported.WITHHELD;
        }
        Blobs blobs = new Blobs(repository);
        List<BlobExport.Pair> pairs = new ArrayList<>();
        for (String repo : repository.list("winget")) {
            if (repository.readVersioned(manifestKey(repo, coordinate, version)).isEmpty()) {
                continue;
            }
            pairs.add(new BlobExport.Pair(manifestKey(repo, coordinate, version),
                    repo + "/" + MANIFESTS + coordinate + "/" + version, Optional.empty()));
            for (String file : blobs.list(installerPrefix(repo, coordinate, version))) {
                pairs.add(new BlobExport.Pair(installerKey(repo, coordinate, version, file),
                        repo + "/" + INSTALLERS + coordinate + "/" + version + "/" + file));
            }
        }
        return BlobExport.put(repository, pairs, target);
    }
}
