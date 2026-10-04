package build.jenesis.repository.format.composer;

import module java.base;
import module tools.jackson.databind;

import build.jenesis.repository.format.Listings;
import build.jenesis.repository.format.LifecycleMark;
import build.jenesis.repository.blobs.RequestBase;
import build.jenesis.repository.blobs.BlobExport;
import build.jenesis.repository.blobs.BlobLayout;
import build.jenesis.repository.format.ExportTarget;
import build.jenesis.repository.format.RepositoryExporter;
import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.blobs.Keys;
import build.jenesis.repository.blobs.OutboundTargets;
import build.jenesis.repository.blobs.ProxyLeg;
import build.jenesis.repository.blobs.ProxyRelay;
import build.jenesis.repository.format.ArtifactLayout;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.store.Checksums;
import build.jenesis.repository.store.ArchiveInflation;
import build.jenesis.repository.store.ArchiveWalk;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.StoredListing;
import build.jenesis.repository.store.ServableNames;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.walk.ScreenedNames;

/**
 * The Composer registry format (the Composer v2 metadata protocol): {@code composer require} and
 * {@code composer install} resolve PHP packages from {@code /composer/...}, where the first segment is a registry. A
 * package is pushed with {@code PUT /composer/<repo>/<vendor>/<package>/<version>} (its zip as the body) and downloaded
 * from {@code /composer/<repo>/dists/<vendor>/<package>/<version>.zip}. The root {@code packages.json} is generated on
 * read; the per-package {@code p2/<vendor>/<package>.json} and its {@code ~dev} companion are stored listings the
 * publish maintains, their download URLs completed for the serving host on the way out.
 *
 * <p><b>Streaming publish.</b> The zip streams through {@link ArtifactStore#writeBlob} into the content-addressed
 * store, and the SHA-256 returned is both the pointer's hash and the {@code dist.reference}. The coordinate and
 * dependencies live in a {@code composer.json} inside the archive, so the stored blob is reopened
 * ({@link ArtifactStore#open}) and only the root {@code composer.json} - at the archive root or one directory deep - is
 * read, bounded against a hostile archive. It is stored per version, with the {@code version} from the request path and
 * a {@code dist} pointing back here, and the publish joins the stanzas into the package's metadata file.
 *
 * <p>The stanza carries no {@code version_normalized}, which Composer's {@code ArrayLoader} computes itself when
 * absent, and the {@code dist} no {@code shasum}, which Composer's optional check then skips; the content-addressed
 * serve is the integrity guarantee.
 *
 * <p>The ecosystem is {@code "Packagist"}, and {@link #describe} maps a dist path to its {@code <vendor>/<package>}
 * coordinate and version. Pointers live in the shared {@code Blobs} namespace, so {@link #paths} is empty and a
 * coordinate is reached through {@link #blobKeys} and {@link #servedPaths}.
 *
 * <p><b>Pull-through proxy.</b> A local miss is served from an upstream Composer-v2 repository, Packagist by default.
 * The root {@code packages.json} is always local, its {@code metadata-url} pointing back here. A {@code p2} file is
 * mutable, fetched fresh and never cached, and since it carries an absolute upstream {@code dist.url} per version, each
 * is rewritten to route the download back through this registry. An archive is immutable: on a miss its
 * {@code dist.url} is resolved by re-reading the upstream {@code p2} file, keeping no per-download state, and it
 * streams into the store ({@link Blobs#writeVerified}) held to the entry's {@code dist.shasum}, the SHA-1
 * {@code composer install} itself checks; a mismatch is refused and the local {@code 404} stands. An entry with no
 * {@code shasum}, as Packagist gives a VCS-sourced dist, caches unverified.
 *
 * <p>{@link ComposerImporter} migrates a {@code composer} repository by replaying each archive through this format's
 * publish path.
 */
public final class ComposerFormat implements RepositoryFormat, ArtifactLayout, ProxyLeg, BlobLayout,
        RepositoryImporter.Delegating, RepositoryExporter {

    /** The ecosystem name this format's artifacts report, distinct from {@link #name()}, the routing id. */
    public static final String ECOSYSTEM = "Packagist";

    static final ObjectMapper MAPPER = new ObjectMapper();

    /** The canonical public Composer-v2 registry mirrored when a deployment names no upstream: Packagist's metadata
     *  host, where {@code packages.json} and {@code /p2/%package%.json} live. */
    private static final URI PACKAGIST = URI.create("https://repo.packagist.org");

    private static final String PREFIX = "/composer/";
    private static final String PACKAGES = "packages.json";
    private static final String LIST = "list.json";
    private static final String P2 = "p2/";
    private static final String DISTS = "dists/";
    private static final String ZIP = ".zip";
    private static final String JSON = ".json";
    private static final String DEV = "~dev";

    @Override
    public String name() {
        return "composer";
    }

    /** The marks this format's clients see, each by its own word. */
    @Override
    public Map<LifecycleMark, String> lifecycleMarks() {
        return LifecycleMark.shown(LifecycleMark.DEPRECATED);
    }

    @Override
    public String ecosystem() {
        return ECOSYSTEM;
    }

    /** The package version a stored Composer pointer serves, from which the inventory back-fill rebuilds a lost
     *  {@code published} record. Only the index entry {@code composer/<repo>/index/<vendor>/<name>/<version>} is
     *  decoded: a Composer coordinate is always {@code vendor/package}, so three segments follow the marker. The
     *  {@code dist} archive is left alone, so one row has one derivation. */
    @Override
    public Optional<ArtifactDescriptor> describePointer(String key) {
        String marker = "/index/";
        int at = key.indexOf(marker);
        if (!key.startsWith("composer/") || at < 0) {
            return Optional.empty();
        }
        String[] parts = key.substring(at + marker.length()).split("/");
        if (parts.length != 3) {
            return Optional.empty();
        }
        String coordinate = parts[0] + "/" + parts[1], version = parts[2];
        if (!BlobLayout.addressable(coordinate, version)) {
            return Optional.empty();
        }
        return Optional.of(new ArtifactDescriptor(ECOSYSTEM, coordinate, version, key,
                "application/octet-stream", version.contains("-"), null, 0L));
    }

    @Override
    public List<String> blobRoots() {
        return List.of("composer");
    }

    @Override
    public List<String> blobKeys(String coordinate, String version, ArtifactStore store) throws IOException {
        if (!BlobLayout.addressable(coordinate, version)) {
            // A traversal-shaped coordinate or version maps nowhere, since an eviction deletes these keys; judged part
            // by part.
            return List.of();
        }
        // The zip and its stanza, keyed by <vendor>/<package>/<version>; the registry segment is found by listing.
        List<String> keys = new ArrayList<>();
        for (String repo : store.list("composer")) {
            String dist = "composer/" + repo + "/dist/" + coordinate + "/" + version + ".zip";
            if (store.readVersioned(dist).isPresent()) {
                keys.add(dist);
            }
            String index = "composer/" + repo + "/index/" + coordinate + "/" + version;
            if (store.readVersioned(index).isPresent()) {
                keys.add(index);
            }
        }
        return keys;
    }

    /** The served download paths of one version's dist zip ({@code dists/<vendor>/<package>/<version>.zip}, while the
     *  store key uses {@code dist/}), where a retroactive hold links its {@code /quarantine} handles; the registry is
     *  found by listing. The index stanza is metadata and has no handle. */
    @Override
    public List<String> servedPaths(String coordinate, String version, ArtifactStore store) throws IOException {
        if (!BlobLayout.addressable(coordinate, version)) {
            return List.of();
        }
        List<String> paths = new ArrayList<>();
        for (String repo : store.list("composer")) {
            String dist = "composer/" + repo + "/dist/" + coordinate + "/" + version + ZIP;
            if (store.readVersioned(dist).isPresent()) {
                paths.add(PREFIX + repo + "/" + DISTS + coordinate + "/" + version + ZIP);
            }
        }
        return paths;
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
        String method = exchange.method();
        if (method.equals("PUT")) {
            publish(repo, sub, exchange, store);
        } else if (!method.equals("GET") && !method.equals("HEAD")) {
            exchange.respond(405);
        } else if (sub.equals(PACKAGES)) {
            root(repo, exchange);
        } else if (sub.equals(LIST)) {
            list(repo, new Blobs(store), exchange);
        } else if (sub.startsWith(P2)) {
            metadata(repo, sub.substring(P2.length()), new Blobs(store), exchange);
        } else if (sub.startsWith(DISTS)) {
            download(repo, sub.substring(DISTS.length()), new Blobs(store), exchange);
        } else {
            exchange.respond(404);
        }
    }

    /** Whether a path under a repository is the publish coordinate {@code <vendor>/<package>/<version>}: {@code p2}
     *  and {@code dists} are read-route names, not vendors, so a metadata document is never a version. */
    private static boolean publishShape(String[] parts) {
        return parts.length == 3 && !parts[0].equals("p2") && !parts[0].equals("dists");
    }

    /** Stream a package upload into the store while reading only its {@code composer.json}, then record the download
     *  pointer and its stanza. */
    private void publish(String repo, String sub, FormatExchange exchange, ArtifactStore store) throws IOException {
        String[] parts = sub.split("/", -1);
        if (!publishShape(parts)) {
            exchange.respond(404);
            return;
        }
        String vendor = parts[0];
        String pkg = parts[1];
        String version = parts[2];
        if (Keys.unsafe(vendor) || Keys.unsafe(pkg) || Keys.unsafe(version)) {
            exchange.respond(400);
            return;
        }
        String hash = new Blobs(store).store(exchange.requestStream());
        ObjectNode composer;
        try (InputStream blob = store.open("blobs/" + hash)) {
            composer = readComposerJson(blob);
        } catch (IOException e) {
            composer = null;
        }
        if (composer == null) {
            exchange.respond(400);
            return;
        }
        String coordinate = vendor + "/" + pkg;
        String declared = text(composer, "name");
        if (declared != null && !declared.equals(coordinate)) {
            // The archive's declared name must match the deploy path, or a package could publish under another's name.
            exchange.respond(400);
            return;
        }
        ObjectNode stanza = composer.deepCopy();
        stanza.put("name", coordinate);
        stanza.put("version", version);
        stanza.set("dist", dist(hash));
        // Blobs.link retries the compare-and-set and clears any gc/condemned marker on a byte-identical blob, so a
        // republish is not collected after its 201.
        Blobs blobs = new Blobs(store);
        try {
            blobs.linkRelease(distKey(repo, vendor, pkg, version), hash, -1L);
        } catch (Publication.RepublishConflict taken) {
            exchange.respond(409, Blobs.alreadyPublished(vendor + "/" + pkg + " " + version));
            return;
        }
        byte[] indexed = MAPPER.writeValueAsBytes(stanza);
        blobs.write(indexKey(repo, vendor, pkg, version), indexed);
        // The p2 file and the package list are maintained on the publish.
        new ComposerListings(blobs).published(repo, vendor, pkg, version, indexed);
        exchange.respond(201);
    }

    /** The {@code dist} block as stored: a {@code zip} keyed by the stored SHA-256, with no {@code shasum}. The
     *  {@code url} is completed from the serving request on read ({@link #metadata}), so a package points at whatever
     *  host serves it, an imported one included. */
    private ObjectNode dist(String hash) {
        ObjectNode dist = MAPPER.createObjectNode();
        dist.put("type", "zip");
        dist.put("reference", hash);
        return dist;
    }

    /** The root {@code packages.json}: the {@code metadata-url} template a client expands per package, host-relative so
     *  Composer resolves it against the repository's host. No {@code available-packages}: a client reads it as the
     *  complete set, so a proxy listing only what it cached would hide every other upstream package. The client fetches
     *  each required package's {@code p2} file instead, locally or through pull-through. A constant document, no store
     *  read. */
    private void root(String repo, FormatExchange exchange) throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        root.set("packages", MAPPER.createObjectNode());
        root.put("metadata-url", repoPath(repo, exchange) + "/" + P2 + "%package%" + JSON);
        // The list endpoint names the enumeration a migration reads, computed only when fetched.
        root.put("list", repoPath(repo, exchange) + "/" + LIST);
        exchange.setResponseHeader("Content-Type", "application/json");
        exchange.answer(MAPPER.writeValueAsBytes(root));
    }

    /** The Composer-v2 {@code list} endpoint, {@code {"packageNames": [...]}}: the one read that walks the index, read
     *  by a migration and never by a resolve. On a proxy it lists only cached packages, which resolution does not
     *  depend on. */
    private void list(String repo, Blobs blobs, FormatExchange exchange) throws IOException {
        if (Keys.unsafe(repo)) {
            exchange.respond(404);
            return;
        }
        StoredListing.Spec spec = new ComposerListings(blobs).listSpec(repo);
        Optional<StoredListing.Served> served = StoredListing.open(blobs.store(), spec);
        if (served.isEmpty()) {
            exchange.respond(404);
            return;
        }
        try (StoredListing.Served document = served.get()) {
            // A repository with nothing to list answers 404, not an empty array: the route is addressed by the
            // repository's own name, and on a proxy the 404 is what sends pull-through to the upstream root. Read after
            // opening, since open() materialises an absent document; the count covers never-published and
            // everything-withheld alike. An unknown count is not zero.
            if (document.header().count().orElse(-1L) == 0L) {
                exchange.respond(404);
                return;
            }
            Listings.serve(exchange, document, "application/json", ComposerListings.BASE, null);
        }
    }

    /** The per-package Composer-v2 file: the stored listing, completed with this registry's base on the way out;
     *  {@code <vendor>/<package>.json} carries releases, {@code ~dev.json} dev versions. A bucket with nothing stored
     *  is a {@code 404}, which a proxy fills from upstream; one whose every version is held answers {@code 200} with an
     *  empty array, the 404 being keyed on the raw container. */
    private void metadata(String repo, String tail, Blobs blobs, FormatExchange exchange) throws IOException {
        if (!tail.endsWith(JSON)) {
            exchange.respond(404);
            return;
        }
        String name = tail.substring(0, tail.length() - JSON.length());
        boolean dev = name.endsWith(DEV);
        if (dev) {
            name = name.substring(0, name.length() - DEV.length());
        }
        int slash = name.indexOf('/');
        if (slash < 0 || name.indexOf('/', slash + 1) >= 0) {
            exchange.respond(404);
            return;
        }
        String vendor = name.substring(0, slash);
        String pkg = name.substring(slash + 1);
        if (Keys.unsafe(repo) || Keys.unsafe(vendor) || Keys.unsafe(pkg)) {
            exchange.respond(404);
            return;
        }
        // The structural probe is paid only until the document exists.
        boolean bucket = StoredListing.present(blobs.store(), ComposerListings.metadata(repo, vendor, pkg, dev));
        if (!bucket) {
            for (String stored : blobs.list(indexPrefix(repo, vendor, pkg))) {
                if (isDev(stored) == dev) {
                    bucket = true;
                    break;
                }
            }
        }
        if (!bucket) {
            exchange.respond(404);
            return;
        }
        Optional<StoredListing.Served> served = StoredListing.open(blobs.store(),
                new ComposerListings(blobs).metadataSpec(repo, vendor, pkg, dev));
        if (served.isEmpty()) {
            exchange.respond(404);
            return;
        }
        try (StoredListing.Served document = served.get()) {
            Listings.serve(exchange, document, "application/json", ComposerListings.BASE, repoBase(repo, exchange));
        }
    }

    /** Serve a package archive from the CAS. The path is {@code <vendor>/<package>/<version>.zip}. */
    private void download(String repo, String tail, Blobs blobs, FormatExchange exchange) throws IOException {
        if (!tail.endsWith(ZIP)) {
            exchange.respond(404);
            return;
        }
        String[] parts = tail.substring(0, tail.length() - ZIP.length()).split("/", -1);
        if (parts.length != 3 || Keys.unsafe(parts[0]) || Keys.unsafe(parts[1]) || Keys.unsafe(parts[2])) {
            exchange.respond(404);
            return;
        }
        String key = distKey(repo, parts[0], parts[1], parts[2]);
        blobs.answer(key, exchange, "application/zip");
    }

    /** Packagist, mirrored when a deployment names no upstream; a repository can set another. */
    @Override
    public Optional<URI> defaultUpstream() {
        return Optional.of(PACKAGIST);
    }

    /** Proxy a Composer miss to an upstream Composer-v2 repository: {@code packages.json} is always local; a {@code p2}
     *  file is fetched fresh with each {@code dist.url} rewritten through this registry; an archive is fetched, cached
     *  and served, its URL resolved from the upstream {@code p2} file. {@code /composer/<repo>/<sub>} maps to
     *  {@code <upstream>/<sub>}. {@code false} lets the local {@code 404} stand. */
    @Override
    public boolean pullThrough(FormatExchange exchange, ArtifactStore store, URI upstream,
                               ProxyFormat.Fetcher fetcher) throws IOException {
        String path = exchange.path();
        String rest = path.substring(PREFIX.length());
        int slash = rest.indexOf('/');
        if (slash < 0) {
            return false;
        }
        String repo = rest.substring(0, slash);
        String sub = rest.substring(slash + 1);
        String root = upstream.toString();
        while (root.endsWith("/")) {
            root = root.substring(0, root.length() - 1);
        }
        if (sub.equals(PACKAGES)) {
            // The local packages.json never misses, so it is never proxied.
            return false;
        }
        if (sub.startsWith(P2) && sub.endsWith(JSON)) {
            return proxyMetadata(repo, sub, root, exchange, store, fetcher);
        }
        if (sub.startsWith(DISTS) && sub.endsWith(ZIP)) {
            return proxyDist(repo, sub.substring(DISTS.length()), root, exchange, store, fetcher);
        }
        return false;
    }

    /** Fetch an upstream {@code p2} file, rewrite each version's {@code dist.url} through this registry, and stream it
     *  fresh, never cached; it is bounded metadata, so it may be held to rewrite. A version that would not form a safe
     *  path segment keeps its upstream URL. */
    private boolean proxyMetadata(String repo, String sub, String root, FormatExchange exchange, ArtifactStore store,
                                  ProxyFormat.Fetcher fetcher) throws IOException {
        String name = sub.substring(P2.length(), sub.length() - JSON.length());
        if (name.endsWith(DEV)) {
            name = name.substring(0, name.length() - DEV.length());
        }
        int s = name.indexOf('/');
        if (s < 0 || name.indexOf('/', s + 1) >= 0) {
            return false;
        }
        String vendor = name.substring(0, s);
        String pkg = name.substring(s + 1);
        if (Keys.unsafe(vendor) || Keys.unsafe(pkg)) {
            return false;
        }
        // A p2 file is the version list Composer resolves against, an ENUMERATION: only an upstream that answered
        // 404/410 reaches the client as one, and anything else refuses visibly.
        ProxyRelay.Answer answer = ProxyRelay.fetchRemembered(fetcher, URI.create(root + "/" + sub), Map.of(), exchange,
                ProxyRelay.Document.ENUMERATION, store);
        if (!answer.answered()) {
            return answer.served();
        }
        JsonNode document = parse(answer.document().body());
        if (document == null) {
            return false;
        }
        String coordinate = vendor + "/" + pkg;
        if (document.path("packages").get(coordinate) instanceof ArrayNode versions) {
            for (JsonNode version : versions) {
                if (version.path("dist") instanceof ObjectNode dist && dist.has("url")) {
                    String v = text(version, "version");
                    if (v != null && !Keys.unsafe(v)) {
                        dist.put("url", repoBase(repo, exchange) + "/" + DISTS + vendor + "/" + pkg + "/" + v + ZIP);
                    }
                }
            }
        }
        exchange.setResponseHeader("Content-Type", "application/json");
        exchange.answer(MAPPER.writeValueAsBytes(document));
        return true;
    }

    /** Fetch, cache and serve an immutable archive: its {@code dist.url} resolved from the upstream {@code p2} file,
     *  the bytes streamed into the store ({@link ProxyRelay#fill}), then served as a local hit. */
    private boolean proxyDist(String repo, String tail, String root, FormatExchange exchange, ArtifactStore store,
                              ProxyFormat.Fetcher fetcher) throws IOException {
        String[] parts = tail.substring(0, tail.length() - ZIP.length()).split("/", -1);
        if (parts.length != 3 || Keys.unsafe(parts[0]) || Keys.unsafe(parts[1]) || Keys.unsafe(parts[2])) {
            return false;
        }
        String vendor = parts[0];
        String pkg = parts[1];
        String version = parts[2];
        Dist dist = distUrl(root, vendor, pkg, version, fetcher, ProxyLeg.allowInternalTargets(exchange));
        if (dist == null) {
            return false;
        }
        // The p2 entry that resolved the URL also publishes dist.shasum, Composer's SHA-1 of the archive, so the stream
        // is held to it (ProxyFormat clause 5). That entry is the same document that locates the download, so an
        // unreadable one already declined above; only an entry without a shasum, as Packagist gives a VCS dist, falls
        // back to unverified.
        try (ProxyFormat.Download download = fetcher.download(dist.url(), Map.of()).orElse(null)) {
            if (download == null || download.status() != 200) {
                return false;
            }
            if (!ProxyRelay.fill(new Blobs(store), distKey(repo, vendor, pkg, version), dist.url(), download.body(),
                    dist.shasum() == null
                            ? ProxyRelay.Declared.NONE
                            : ProxyRelay.Declared.of("SHA-1", dist.shasum()))) {
                return false;
            }
        }
        download(repo, tail, new Blobs(store), exchange);
        return true;
    }

    /** A resolved upstream dist: the archive URL and, when declared, the {@code dist.shasum} the bytes must hash to. */
    private record Dist(URI url, byte[] shasum) {
    }

    /** Resolve a version's upstream download from the upstream {@code p2} file ({@code ~dev} for a dev version): its
     *  {@code dist.url} and, where declared, {@code dist.shasum}. A bounded read, once per version miss. */
    private static Dist distUrl(String root, String vendor, String pkg, String version, ProxyFormat.Fetcher fetcher,
                                boolean allowInternal) throws IOException {
        String file = P2 + vendor + "/" + pkg + (isDev(version) ? DEV : "") + JSON;
        Optional<ProxyFormat.Fetched> fetched = fetcher.beside().fetch(URI.create(root + "/" + file), Map.of());
        if (fetched.isEmpty() || fetched.get().status() != 200) {
            return null;
        }
        JsonNode document = parse(fetched.get().body());
        if (document == null || !(document.path("packages").get(vendor + "/" + pkg) instanceof ArrayNode versions)) {
            return null;
        }
        for (JsonNode entry : versions) {
            if (version.equals(text(entry, "version"))) {
                String url = text(entry.path("dist"), "url");
                if (url == null || url.isEmpty()) {
                    return null;
                }
                try {
                    URI target = URI.create(url);
                    // The dist.url is untrusted upstream metadata - a publisher could point it at an internal or
                    // plaintext host - so it is screened, and a refused target falls through to a 404 (ProxyLeg clause
                    // 2).
                    return OutboundTargets.mayFollow(target, URI.create(root), allowInternal)
                            ? new Dist(target, Checksums.parse(text(entry.path("dist"), "shasum"), 20))
                            : null;
                } catch (IllegalArgumentException e) {
                    return null;
                }
            }
        }
        return null;
    }

    /** Parse an upstream metadata document, or {@code null} when malformed, read as a miss rather than a
     *  {@code 500}. */
    private static JsonNode parse(byte[] body) {
        try {
            return MAPPER.readTree(body);
        } catch (RuntimeException e) {
            return null;
        }
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
        if (!sub.startsWith(DISTS) || !sub.endsWith(ZIP)) {
            // The publish path <vendor>/<name>/<version> is where the gate links a review pointer when it holds one, so
            // a release's cross-alias guard asks for it; the dist path carries the same coordinate.
            String[] pushed = sub.split("/", -1);
            if (publishShape(pushed) && ArtifactLayout.addressable(pushed[0], pushed[1], pushed[2])) {
                return Optional.of(new ArtifactDescriptor(ECOSYSTEM, pushed[0] + "/" + pushed[1], pushed[2], path,
                        "application/zip", isDev(pushed[2]), null, -1L));
            }
            return Optional.empty();
        }
        String[] parts = sub.substring(DISTS.length(), sub.length() - ZIP.length()).split("/", -1);
        if (parts.length != 3) {
            return Optional.of(ArtifactDescriptor.at(ECOSYSTEM, path));
        }
        String coordinate = parts[0] + "/" + parts[1];
        String version = parts[2];
        return Optional.of(new ArtifactDescriptor(ECOSYSTEM, coordinate, version, path,
                "application/zip", isDev(version), null, -1L));
    }

    @Override
    public List<String> paths(String coordinate, String version, ArtifactStore store) {
        // Pointers live in the shared Blobs namespace, so the coordinate enumerates nothing in publish/.
        return List.of();
    }

    /** Read the root {@code composer.json} of a stored archive, inflating only as far as it: at the archive root, else
     *  one directory deep, as a VCS export files it; one deeper belongs to a bundled dependency. Bounded; {@code null}
     *  when none is usable. */
    private static ObjectNode readComposerJson(InputStream blob) throws IOException {
        return ArchiveWalk.walk(blob, ComposerFormat::declaredComposerJson).orNull();
    }

    /** The {@code composer.json} inside an already-bounded archive stream, or {@code null} when it carries none. */
    private static ObjectNode declaredComposerJson(InputStream archive) throws IOException {
        ZipInputStream zip = ArchiveWalk.zip(archive);
        ObjectNode nested = null;
        for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
            if (entry.isDirectory()) {
                continue;
            }
            String entryName = entry.getName();
            int depth = depth(entryName);
            if (entryName.equals("composer.json")) {
                ObjectNode root = parse(zip);
                if (root != null) {
                    return root;
                }
            } else if (depth == 1 && nested == null && entryName.endsWith("/composer.json")) {
                nested = parse(zip);
            }
        }
        return nested;
    }

    /** Parse the current zip entry as a JSON object under the shared inflation ceiling, or {@code null} when it is
     *  none. The manifest is the publish's guard input, so a read the ceiling stopped fails closed: degrading it would
     *  fall through to a bundled dependency's manifest one directory deeper. */
    private static ObjectNode parse(InputStream entry) throws IOException {
        byte[] json = ArchiveInflation.entry(entry).required("Composer package", "composer.json");
        try {
            return MAPPER.readTree(json) instanceof ObjectNode object ? object : null;
        } catch (RuntimeException e) {
            // A malformed composer.json is no usable manifest: treated as absent rather than a 500.
            return null;
        }
    }

    /** The number of {@code /} separators in a zip entry name (its directory depth). */
    private static int depth(String name) {
        int depth = 0;
        for (int i = 0; i < name.length(); i++) {
            if (name.charAt(i) == '/') {
                depth++;
            }
        }
        return depth;
    }

    /** Whether a version is a dev version ({@code dev-<branch>} or an {@code -dev} suffix), served from the
     *  {@code ~dev} file. */
    static boolean isDev(String version) {
        return version.startsWith("dev-") || version.endsWith("-dev");
    }

    private static String text(JsonNode node, String field) {
        return node == null ? null : node.path(field).asString(null);
    }

    /** The external base URL of this registry ({@code <scheme>://<host><prefix>/composer/<repo>}), for the dist URLs. */
    private static String repoBase(String repo, FormatExchange exchange) {
        return RequestBase.of(exchange) + repoPath(repo, exchange);
    }

    /** The host-relative path to this registry, for the {@code metadata-url}. */
    private static String repoPath(String repo, FormatExchange exchange) {
        return exchange.external(PREFIX + repo);
    }

    /** The download pointer key of a version's archive. */

    static String distKey(String repo, String vendor, String pkg, String version) {
        return "composer/" + repo + "/dist/" + vendor + "/" + pkg + "/" + version + ZIP;
    }

    static String indexPrefix(String repo, String vendor, String pkg) {
        return "composer/" + repo + "/index/" + vendor + "/" + pkg;
    }

    /** Whether the name enumeration may list this package: a version a client can download, or no indexed version at
     *  all. A package whose every version is held is screened out, as {@link #metadata} screens; the first servable
     *  version short-circuits. */
    static boolean servable(String repo, String vendor, String pkg, Blobs blobs) throws IOException {
        // The shared screened enumeration, the screen metadata() renders through.
        if (ScreenedNames.keys(blobs.servableNames(), ServableNames.Policy.HIDE_WITHHELD,
                        version -> distKey(repo, vendor, pkg, version))
                .any(blobs.store(), indexPrefix(repo, vendor, pkg))) {
            return true;
        }
        // No disclosable version: still listed when it has no indexed version at all, a structural probe.
        return blobs.list(indexPrefix(repo, vendor, pkg)).isEmpty();
    }

    static String indexKey(String repo, String vendor, String pkg, String version) {
        return indexPrefix(repo, vendor, pkg) + "/" + version;
    }

    /** The migration-import capability, delegated to {@link ComposerImporter}. */
    private final ComposerImporter importer = new ComposerImporter();

    @Override
    public RepositoryImporter importer() {
        return importer;
    }

    /** Each registry's zip of the version is put where an upload goes, {@code <repo>/<vendor>/<package>/<version>},
     *  unless its dist path already answers; the target derives its own stanza. */
    @Override
    public Exported export(ArtifactStore repository, String coordinate, String version, ExportTarget target)
            throws IOException {
        if (!BlobLayout.addressable(coordinate, version)) {
            return Exported.WITHHELD;
        }
        List<BlobExport.Pair> pairs = new ArrayList<>();
        for (String repo : repository.list("composer")) {
            pairs.add(new BlobExport.Pair("composer/" + repo + "/dist/" + coordinate + "/" + version + ZIP,
                    repo + "/" + coordinate + "/" + version, repo + "/" + DISTS + coordinate + "/" + version + ZIP));
        }
        return BlobExport.put(repository, pairs, target);
    }
}
