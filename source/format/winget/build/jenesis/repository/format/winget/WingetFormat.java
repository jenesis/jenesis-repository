package build.jenesis.repository.format.winget;

import module java.base;
import module tools.jackson.databind;

import build.jenesis.repository.blobs.BlobLayout;
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

/**
 * The Windows Package Manager REST source protocol, so {@code winget search} and {@code winget install} resolve
 * against this repository once a client has added it with
 * {@code winget source add -n <name> -a <base>/winget/<repo> -t Microsoft.Rest}.
 *
 * <p><b>The three read routes are Microsoft's; the two write routes are ours.</b> {@code GET information} answers the
 * source identifier and the protocol versions spoken, {@code POST manifestSearch} answers a search, and
 * {@code GET packageManifests/<PackageIdentifier>} answers the manifest an install resolves. The specification defines
 * no way to put a package <i>into</i> a source - the community source is built from a git repository of YAML by pull
 * request - so this format adds {@code PUT manifests/<id>/<version>} for the manifest and
 * {@code PUT installers/<id>/<version>/<file>} for each installer's bytes, and serves those bytes back from the same
 * path. Splitting the two is what keeps the publish streaming: an installer is an {@code .exe}, {@code .msi} or
 * {@code .msix} that can run to gigabytes and goes straight into the content-addressed store, while only the small
 * JSON manifest is ever held in memory, under an explicit bound.
 *
 * <p><b>What is served is not quite what was published, and that is the point.</b> A winget manifest names each
 * installer by {@code InstallerUrl} and {@code InstallerSha256}, and the client verifies its download against that
 * digest. On read, each installer entry is rewritten: the URL is regenerated from the serving request so it routes
 * back to whatever host answers (nothing host-specific is stored, which is what lets an imported package resolve),
 * and the digest is restated from the hash the content-addressed store computed when the bytes landed. So the number
 * a client checks is the hash of the bytes this server will actually hand it, rather than one a publisher typed. An
 * installer entry whose bytes were never uploaded is dropped from the served manifest rather than advertising a
 * download that would 404.
 *
 * <p><b>Reads are bounded.</b> A search answers from one stored document - the repository index that each publish
 * re-derives - so it costs one read rather than a fold over every package. The index is filtered in memory, which is
 * a walk, so it carries an examined budget ({@link #EXAMINED_CAP}) and stops rather than running long on a repository
 * with a very large package count; a manifest read is a point lookup of the package's version list followed by a
 * point read per version served.
 *
 * <p>Pointers live in the shared {@code Blobs} namespace as the other language formats do, so the {@code publish/}
 * namespace eviction ({@link #paths}) stays empty and coordinate-scoped enforcement runs through the
 * {@link BlobLayout} seam ({@link #blobKeys}/{@link #servedPaths}) instead. OSV publishes no winget advisory feed, so
 * vulnerability screening finds nothing while license and malicious-package screening still key on the coordinate.
 */
public final class WingetFormat implements RepositoryFormat, ArtifactLayout, BlobLayout, RepositoryImporter {

    /** The package-ecosystem name this format's artifacts report, distinct from {@link #name()}, the routing id.
     *  Microsoft styles the product "WinGet" and the command {@code winget}; the ecosystem takes the product's
     *  spelling, as {@code CocoaPods} and {@code RubyGems} do. */
    public static final String ECOSYSTEM = "WinGet";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String PREFIX = "/winget/";

    private static final String INFORMATION = "information";
    private static final String SEARCH = "manifestSearch";
    private static final String PACKAGE_MANIFESTS = "packageManifests/";
    private static final String INSTALLERS = "installers/";
    private static final String MANIFESTS = "manifests/";

    /** The REST protocol versions this server implements, answered by {@code information}. A client picks the highest
     *  it also speaks; 1.1.0 is the version that added the {@code Inclusions}/{@code Filters} search shape below. */
    private static final List<String> SUPPORTED_VERSIONS = List.of("1.0.0", "1.1.0");

    /** A manifest is a small JSON document describing one version. The bound is explicit because this body is
     *  materialised rather than streamed - it is the one place in this format where a publisher's bytes reach heap -
     *  and a publisher who exceeds it is refused rather than served an OutOfMemoryError (contract clause 15). */
    private static final int LARGEST_MANIFEST = 512 * 1024;

    /** A search body is smaller still: a query, a match type and some filters. */
    private static final int LARGEST_SEARCH = 64 * 1024;

    /** How many index entries one search may examine before answering with what it has. The index is a single stored
     *  document and filtering it is a walk, so it gets a budget rather than an unbounded fold; a repository large
     *  enough to hit this wants a real search index, which is a different change. */
    private static final int EXAMINED_CAP = 5_000;

    /** The most results one response carries when the client asks for no limit of its own. */
    private static final int DEFAULT_RESULTS = 100;

    private final WingetImporter importer = new WingetImporter();

    public WingetFormat() {
    }

    @Override
    public String name() {
        return "winget";
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
                } else {
                    exchange.respond(404);
                }
            }
            default -> exchange.respond(405);
        }
    }

    // ------------------------------------------------------------------ writes

    /**
     * Store one version's manifest. The body is the version object a client will be served back - its
     * {@code PackageVersion}, its {@code DefaultLocale} and its {@code Installers} - and it is validated against the
     * path so a package cannot publish itself under another's coordinate.
     */
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
            // The manifest claims a different identifier or version than the path it was deployed to: refuse rather
            // than file it under a coordinate its own contents disown, the way the CocoaPods publish refuses a
            // podspec that disagrees with its deploy path.
            exchange.respond(400);
            return;
        }
        // Normalised onto the path, so the stored stanza is self-describing even when the publisher omitted either
        // field, and so a later read never has to reconcile the two.
        manifest.put("PackageIdentifier", identifier);
        manifest.put("PackageVersion", version);
        blobs.write(manifestKey(repo, identifier, version), MAPPER.writeValueAsBytes(manifest));
        new WingetListings(blobs).refresh(repo, identifier, version);
        exchange.respond(201);
    }

    /**
     * Stream one installer's bytes into the content-addressed store and record the pointer the served manifest's
     * rewritten {@code InstallerUrl} and {@code InstallerSha256} are generated from. Nothing is buffered: an installer
     * is the large half of a winget package.
     */
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
        // An installer belongs to a version, and a version exists only once its manifest has been accepted. Without
        // this the two halves of a publish are screened independently and only one of them can be: the manifest
        // carries the licence and the metadata a gate reads, an installer is opaque bytes. So a manifest refused
        // for its licence, or held for review, was followed by an installer PUT that answered 201 and stored the
        // bytes anyway - at a path this format serves back, so they were fetchable under a coordinate the manifest
        // never established. Measured by the soak: 232 anomalies in a quarter of an hour, every one this.
        //
        // A point read, taken BEFORE the body is consumed, so a refusal costs neither a stored blob nor a buffer.
        // It does not undo the two-request split - that split is what lets an installer stream instead of reaching
        // heap, and it stays exactly as it was.
        if (!blobs.exists(manifestKey(repo, identifier, version))) {
            exchange.respond(404);
            return;
        }
        String hash = blobs.store(exchange.requestStream());
        // Through Blobs.link rather than a bare write: besides the compare-and-set retry, link clears any
        // gc/condemned/<hash> marker a collector set, so re-publishing bytes identical to a condemned blob
        // un-condemns them before the sweep runs.
        blobs.link(installerKey(repo, identifier, version, file), hash);
        exchange.respond(201);
    }

    // ------------------------------------------------------------------ reads

    /** The source's own description: who it is, and which versions of the protocol it speaks. */
    private void information(String repo, FormatExchange exchange) throws IOException {
        ObjectNode data = MAPPER.createObjectNode();
        // Stable per repository and derived from its name: a client stores the identifier with the source it added,
        // and one that changed between reads would read as a different source.
        data.put("SourceIdentifier", "JenesisRepository." + repo);
        ArrayNode versions = data.putArray("ServerSupportedVersions");
        SUPPORTED_VERSIONS.forEach(versions::add);
        ObjectNode body = MAPPER.createObjectNode();
        body.set("Data", data);
        respondJson(exchange, 200, MAPPER.writeValueAsBytes(body));
    }

    /**
     * Answer a {@code winget search} from the repository's stored index. The client sends a free-text
     * {@code Query.KeyWord} and/or field-scoped {@code Inclusions}/{@code Filters}; a package matches when every
     * {@code Filters} entry matches and, where either is present, the keyword or some inclusion matches. Matching is
     * over the identifier, the package name and the publisher, which are the fields the index line carries.
     */
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
        // One sequential pass over the index through the codec's streaming reader, keeping only the results and
        // examining at most EXAMINED_CAP entries: the query never holds the index, whose size is the repository's.
        // It used to read the index whole and split every line into a map first - the shape the nuget-search-memory
        // canary showed as an OutOfMemoryError on NuGet's twin of this document.
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

    /**
     * Answer one package's manifests: every servable version, or the single one a {@code ?Version=} names. Each
     * version is served with its installer entries rewritten onto this repository.
     */
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
        Optional<Blobs.Located> located = blobs.locate(installerKey(repo, parts[0], parts[1], parts[2]));
        if (located.isEmpty()) {
            exchange.respond(404);
            return;
        }
        long size = located.get().size();
        exchange.setResponseHeader("Content-Type", "application/octet-stream");
        if (exchange.method().equals("HEAD")) {
            exchange.setResponseHeader("Content-Length", Long.toString(size));
            exchange.respond(200, -1L).close();
            return;
        }
        blobs.serve(located.get(), exchange);
    }

    // ------------------------------------------------------------------ layout

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
            // The manifest publish path, manifests/<id>/<version>: what a version is created at, and so the path
            // the gate links a review pointer at when it holds one - which a release's cross-alias guard then asks
            // to be placed. It carries the same coordinate the installers under it do.
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
        // Winget pointers live in the shared Blobs namespace, not the Publication namespace a coordinate-based
        // eviction walks, so nothing is enumerable from the coordinate alone; blobKeys/servedPaths carry it.
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
        // The registry set is operator-configured and therefore bounded; within one, the manifest key is an exact
        // lookup and the installer pointers are the one version's own children, never a listing of the pool.
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
     * <p>Both pointer shapes, because an eviction deletes both: the version's manifest at
     * {@code winget/<repo>/manifest/<id>/<version>} and each installer at
     * {@code winget/<repo>/blob/<id>/<version>/<file>}. Neither is the served path - an installer is served from
     * {@code /winget/<repo>/installers/} - so the request-path describer is not the parse, and the segments above
     * are constants used in both directions rather than two spellings of one grammar.
     *
     * <p>An identifier is a single segment here (a WinGet {@code Publisher.Package}, dotted rather than slashed),
     * so the split is positional and needs no name-versus-version guessing: what follows the segment is the id,
     * what follows that is the version, and anything after that is one installer's file name.
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

    // ------------------------------------------------------------------ importer

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

    // ------------------------------------------------------------------ keys and helpers

    /** The two key segments under a registry, named once because a pointer is now composed AND parsed here. */
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

    /**
     * The repository index line for one package: {@code <identifier>\t<compact JSON>}, the JSON being exactly the
     * object a search response carries, so answering a query is a filter over stored lines rather than a re-read of
     * each matched package's manifest.
     */
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

    /**
     * One version as it is served: the stored manifest with each installer entry's {@code InstallerUrl} regenerated
     * onto this repository and its {@code InstallerSha256} restated from the digest of the bytes that will actually be
     * streamed. An entry whose bytes were never uploaded is dropped, so the manifest never advertises a download that
     * would 404; the version itself is still served, with the installers that do resolve.
     */
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
                // Upper case: the canonical winget manifests state the digest that way, and a client compares
                // case-insensitively, so this is presentation rather than a second encoding.
                entry.put("InstallerSha256", located.get().hash().toUpperCase(Locale.ROOT));
                rewritten.add(entry);
            }
        }
        object.set("Installers", rewritten);
        return Optional.of(object);
    }

    /**
     * The stored file name for an installer entry: the last segment of the {@code InstallerUrl} the publisher
     * declared, which is the name the installer's own {@code PUT} used. A URL with no usable last segment names no
     * file and its entry is dropped.
     */
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

    /** Read the whole request body, or {@code null} when it exceeds the bound - which is a refusal, never a truncation. */
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
        String uri = exchange.requestUri();
        String path = exchange.path();
        String external = uri.length() >= path.length() && uri.endsWith(path)
                ? uri.substring(0, uri.length() - path.length()) : "";
        return RequestBase.of(exchange) + external + PREFIX.substring(0, PREFIX.length() - 1) + "/" + repo;
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
}
