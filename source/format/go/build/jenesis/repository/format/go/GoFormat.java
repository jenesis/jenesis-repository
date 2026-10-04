package build.jenesis.repository.format.go;

import module java.base;
import module org.slf4j;
import build.jenesis.repository.format.LifecycleMark;
import build.jenesis.repository.format.Listings;
import build.jenesis.repository.blobs.HostedMarker;
import build.jenesis.repository.blobs.BlobExport;
import build.jenesis.repository.blobs.BlobLayout;
import build.jenesis.repository.blobs.ComposedLayout;
import build.jenesis.repository.format.ExportTarget;
import build.jenesis.repository.format.RepositoryExporter;
import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.blobs.Keys;
import build.jenesis.repository.blobs.ProxyLeg;
import build.jenesis.repository.blobs.ProxyRelay;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.icon.IconResource;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.StoredListing;
import build.jenesis.repository.walk.BoundedChildren;
import build.jenesis.repository.format.Semver;

/**
 * The Go module proxy format (the GOPROXY protocol): {@code go mod download} and {@code go get} resolve modules from
 * {@code /go/...}. A module version is the trio {@code <module>/@v/<version>.info}, {@code .mod} and {@code .zip},
 * stored under {@code go/<module>/@v/...}; {@code <module>/@v/list} lists the stored versions and
 * {@code <module>/@latest} the newest. A {@code PUT} to the same paths pushes a module in. The module path is used
 * verbatim, the client's {@code !lower} escaping kept through to the store key. As a proxy it also relays the checksum
 * database under {@code /go/sumdb/<name>/...}.
 *
 * <h2>Contract</h2>
 * The clauses of {@code RepositoryFormat} and {@code ProxyFormat} bind unchanged; this is what {@code ProxyFormat}
 * clause 5 (upstream integrity) resolves to for a protocol that advertises no digest.
 * <ol>
 *   <li><b>Where the digest comes from.</b> The checksum database ({@code GOSUMDB}, {@code sum.golang.org} by default),
 *       which publishes an {@code h1:} dirhash per module version, consulted by {@link GoChecksumDatabase} directly or
 *       through the upstream's {@code /sumdb/} mirror.</li>
 *   <li><b>What is verified.</b> A proxied {@code .zip} and {@code .mod} are stored content-addressed and held to that
 *       dirhash ({@link GoDirhash}) before any pointer is linked. A mismatch is refused: nothing serves, the local
 *       {@code 404} stands, the refusal is logged with both digests, and the blob is left for the collector.</li>
 *   <li><b>What is not, by request shape.</b> Each of these says so in its log line:
 * <ul>
 *   <li>a {@code .info}: the database publishes no dirhash for it, and it names no bytes a build compiles;</li>
 *   <li>a module the database does not carry - a private module - cached unverified, as Maven caches an artifact whose
 *       upstream publishes no {@code .sha1};</li>
 *   <li>{@code jenrepo.go.sumdb=off}: no digest is advertised at all;</li>
 *   <li>a {@code .zip} whose dirhash cannot be computed within the archive bounds, which is <em>not</em> cached: an
 *       uncomputable digest refuses like a mismatch.</li>
 * </ul>
 * {@code @v/list} and {@code @latest} are streamed fresh and never cached.</li>
 *   <li><b>The strength of the check.</b> The lookup record is compared, but its signature and inclusion proof are not
 *       verified: a digest check against the database, which defeats a corrupted GOPROXY since the database is another
 *       origin, not a transparency-log attestation. The end-to-end check stays the client's, hence clause 5.</li>
 *   <li><b>{@code /go/sumdb/<name>/...} is relayed.</b> A client pointed only here gets the protocol's four operations
 *       ({@code supported}, {@code latest}, {@code lookup/...}, {@code tile/...}) fresh and uncached, through the
 *       upstream's mirror or the configured database. Nothing else under {@code /go/sumdb/} is relayed.</li>
 *   <li><b>A version query that could not be asked is not an empty version list.</b> On {@code @v/list} and
 *       {@code @latest} a {@code 404} is the answer a build resolves against, so only an upstream
 *       {@code 404}/{@code 410} stands as one, while a transport failure or other status answers {@code 502} and is
 *       logged, through {@code ProxyRelay}: these two are {@code ENUMERATION}, the trio {@code PINNED}. The local list
 *       is served whole for the same reason.</li>
 * </ol>
 */
public final class GoFormat implements RepositoryFormat, ProxyLeg, ComposedLayout, RepositoryImporter.Delegating,
        RepositoryExporter {

    @Override
    public String name() {
        return "go";
    }

    /** The marks this format's clients see, each by its own word. */
    @Override
    public Map<LifecycleMark, String> lifecycleMarks() {
        return LifecycleMark.shown(LifecycleMark.YANKED);
    }

    @Override
    public String ecosystem() {
        return "Go";
    }

    @Override
    public List<String> blobRoots() {
        return List.of("go");
    }

    /** The module version a stored Go pointer serves, from which the inventory back-fill rebuilds a lost
     *  {@code published} record. A module path is multi-segment, so coordinate and version are split at {@code /@v/},
     *  the protocol's reserved separator no module path contains; the {@code .info}, {@code .mod} or {@code .zip}
     *  suffix is stripped, which no version ends in. The coordinate is the module path verbatim, escaping included, as
     *  {@link #blobKeys} composes it. */
    @Override
    public Optional<ArtifactDescriptor> describePointer(String key) {
        if (!key.startsWith("go/")) {
            return Optional.empty();
        }
        String rest = key.substring("go/".length());
        int marker = rest.lastIndexOf("/@v/");
        if (marker < 0) {
            return Optional.empty();
        }
        String coordinate = rest.substring(0, marker);
        String file = rest.substring(marker + "/@v/".length());
        String version = null;
        for (String suffix : VERSION_FILES) {
            if (file.endsWith(suffix)) {
                version = file.substring(0, file.length() - suffix.length());
                break;
            }
        }
        if (version == null || version.indexOf('/') >= 0 || !BlobLayout.addressable(coordinate, version)) {
            return Optional.empty();
        }
        return Optional.of(new ArtifactDescriptor(ecosystem(), coordinate, version, key,
                "application/octet-stream", version.contains("-"), null, 0L));
    }

    @Override
    public List<String> blobKeys(String coordinate, String version, ArtifactStore store) throws IOException {
        // The .info/.mod/.zip trio under go/<module>/@v/<version>, the module path verbatim.
        if (!BlobLayout.addressable(coordinate, version)) {
            // The multi-segment module path is screened part by part; a part that is . or .. maps nowhere, since an
            // eviction deletes these keys.
            return List.of();
        }
        List<String> keys = new ArrayList<>();
        String base = "go/" + coordinate + "/@v/" + version;
        for (String suffix : VERSION_FILES) {
            if (store.readVersioned(base + suffix).isPresent()) {
                keys.add(base + suffix);
            }
        }
        return keys;
    }

    /** The request path this version's archive serves at ({@code /go/<module>/@v/<version>.zip}), where a retroactive
     *  hold links its {@code /quarantine} handle; the {@code .info} and {@code .mod} are not downloads. */
    @Override
    public List<String> servedPaths(String coordinate, String version, ArtifactStore store) throws IOException {
        if (!BlobLayout.addressable(coordinate, version)) {
            return List.of();
        }
        String zip = "go/" + coordinate + "/@v/" + version + ".zip";
        return store.readVersioned(zip).isPresent() ? List.of("/" + zip) : List.of();
    }

    /** The coordinate a module-archive path carries ({@code /go/<module>/@v/<version>.zip}), the module path verbatim
     *  as {@link #blobKeys} keys it. The {@code .info}/{@code .mod} and the version queries name no archive and stay
     *  empty. A {@code -} in the version marks a prerelease, a pseudo-version included, as {@link Semver#compare} ranks
     *  it. */
    /** A version's {@code .info}, {@code .mod} and {@code .zip} are each served from the key their path names. */
    @Override
    public Optional<String> servingKey(String requestPath, ArtifactStore store) throws IOException {
        int dot = requestPath.lastIndexOf('.');
        String suffix = dot < 0 ? "" : requestPath.substring(dot);
        if (!VERSION_FILES.contains(suffix) || describedVersion(requestPath.substring(0, dot) + ".zip").isEmpty()) {
            return Optional.empty();
        }
        return BlobLayout.stored(requestPath.substring(1), store);
    }

    /** A module version is its {@code .info}, {@code .mod} and {@code .zip}, the archive last: the trio a go command is
     *  served, of which the archive alone is a download. */
    @Override
    public List<String> contents(String coordinate, String version, ArtifactStore store) throws IOException {
        if (servedPaths(coordinate, version, store).isEmpty()) {
            return List.of();
        }
        return blobKeys(coordinate, version, store).stream().map(key -> "/" + key).toList();
    }

    /** The files of a module version, in the order a version is laid down. */
    private static final List<String> VERSION_FILES = List.of(".info", ".mod", ".zip");

    @Override
    public Optional<ArtifactDescriptor> describe(String path) {
        if (!path.startsWith("/go/") || !path.endsWith(".zip")) {
            return Optional.empty();
        }
        String rest = path.substring("/go/".length());
        int at = rest.indexOf("/@v/");
        if (at <= 0) {
            return Optional.empty();
        }
        String modulePath = rest.substring(0, at);
        String version = rest.substring(at + "/@v/".length(), rest.length() - ".zip".length());
        if (version.isEmpty() || version.indexOf('/') >= 0) {
            return Optional.empty();
        }
        return Optional.of(new ArtifactDescriptor("Go", modulePath, version, path,
                "application/zip", version.contains("-"), null, -1L));
    }

    // An original CC0 line glyph (a rounded head with speed lines).
    private static final IconResource ICON = IconResource.svg("""
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round">
              <path d="M2 10h6M3 13h5"/><circle cx="15" cy="12" r="6"/><circle cx="16.5" cy="10.7" r="1"/>
            </svg>""");

    @Override
    public Optional<IconResource> icon() {
        return Optional.of(ICON);
    }

    @Override
    public Optional<URI> defaultUpstream() {
        return Optional.of(URI.create("https://proxy.golang.org/"));
    }

    @Override
    public boolean handles(String path) {
        return path.startsWith("/go/");
    }

    @Override
    public void serve(FormatExchange exchange, ArtifactStore store) throws IOException {
        Blobs blobs = new Blobs(store);
        String rest = exchange.path().substring("/go/".length());
        int at = rest.indexOf("/@");
        if (at < 0) {
            exchange.respond(404);
            return;
        }
        String modulePath = rest.substring(0, at);
        String suffix = rest.substring(at + 1);
        // Each module segment is screened as the siblings screen their coordinate, so a backslash or an empty file name
        // is a clean 400 rather than an exception at the store boundary.
        if (unsafeModule(modulePath)) {
            exchange.respond(400);
            return;
        }
        if (suffix.equals("@latest")) {
            latest(modulePath, blobs, exchange);
            return;
        }
        if (!suffix.startsWith("@v/")) {
            exchange.respond(404);
            return;
        }
        String file = suffix.substring("@v/".length());
        if (Keys.unsafe(file)) {
            exchange.respond(400);
            return;
        }
        if (exchange.method().equals("PUT")) {
            String key = "go/" + modulePath + "/@v/" + file;
            String hash = blobs.store(exchange.requestStream());
            if (file.endsWith(".zip") || file.endsWith(".mod")) {
                // A version's zip and go.mod are what go.sum pins, so the first bytes stay, decided at the pointer's
                // compare-and-set; the .info, the version's timestamp, may be stated afresh.
                try {
                    blobs.linkRelease(key, hash, -1L);
                } catch (Publication.RepublishConflict taken) {
                    exchange.respond(409, Blobs.alreadyPublished(modulePath + "@" + file));
                    return;
                }
            } else {
                blobs.link(key, hash);
            }
            // The per-module hosted marker switches on local version discovery; a pull-through proxy never writes it,
            // so its discovery falls through to the upstream's list.
            HostedMarker.mark(store, hostedKey(modulePath));
            // The @v/list (and @latest) are maintained on the publish.
            int dot = file.lastIndexOf('.');
            if (dot > 0) {
                new GoListings(blobs).refresh(modulePath, file.substring(0, dot));
            }
            exchange.respond(201);
        } else if (file.equals("list")) {
            list(modulePath, blobs, exchange);
        } else {
            serve(modulePath, file, blobs, exchange);
        }
    }

    /** Proxy a {@code /go/} miss to the upstream GOPROXY. A version's {@code .info}, {@code .mod} and {@code .zip} are
     *  immutable and cached; {@code @v/list} and {@code @latest} are streamed fresh; {@code /go/sumdb/...} relays the
     *  checksum database; a cached {@code .zip} or {@code .mod} is held to its dirhash. The class contract says which
     *  shapes stay unverified. */
    @Override
    public boolean pullThrough(FormatExchange exchange, ArtifactStore store, URI upstream,
                               ProxyFormat.Fetcher fetcher) throws IOException {
        String path = exchange.path();
        String rest = path.substring("/go/".length());
        if (rest.startsWith(SUMDB)) {
            // Relayed so a client whose only egress is this repository can run the GOSUMDB check (contract clause 5).
            return sumdb(rest.substring(SUMDB.length()), exchange, upstream, fetcher);
        }
        boolean immutable = rest.endsWith(".info") || rest.endsWith(".mod") || rest.endsWith(".zip");
        boolean query = rest.endsWith("/@latest") || rest.endsWith("/@v/list");
        if (!immutable && !query) {
            return false;
        }
        String root = upstream.toString();
        URI target = URI.create(root.endsWith("/") ? root + rest : root + "/" + rest);
        if (query) {
            // The version queries are relayed with validators forwarded both ways and remembered for the repository.
            // They are ENUMERATION: a 404 is an empty answer a build resolves against, so only an upstream 404/410
            // reaches the client as one.
            ProxyRelay.Answer answer = ProxyRelay.fetchRemembered(fetcher, target,
                    ProxyRelay.conditionalHeaders(exchange), exchange, ProxyRelay.Document.ENUMERATION, store);
            if (!answer.answered()) {
                return answer.served();
            }
            ProxyRelay.relayValidators(answer.document(), exchange);
            exchange.setResponseHeader("Content-Type", rest.endsWith("/@latest") ? "application/json" : "text/plain");
            exchange.respond(200, answer.document().body());
            return true;
        }
        // The advertised digest is read before the body, so the fill is one streamed pass. NONE for a .info, a module
        // the database answered it does not carry, or a database turned off (clause 3); a database neither route could
        // read is refused.
        ProxyRelay.Declared advertised = rest.endsWith(".info")
                ? ProxyRelay.Declared.NONE
                : advertisedDirhash(rest, upstream, fetcher, ProxyLeg.allowInternalTargets(exchange));
        if (!advertised.readable()) {
            return ProxyRelay.unverifiable(target, advertised);
        }
        String expected = advertised.text();
        // Streamed from the network into the content-addressed store, since a .zip is unbounded.
        try (ProxyFormat.Download download = fetcher.download(target, Map.of()).orElse(null)) {
            if (download == null || download.status() != 200) {
                return false;
            }
            Blobs blobs = new Blobs(store);
            String key = "go/" + rest;
            if (expected == null) {
                blobs.write(key, download.body());
                return serveProxied(exchange, store);
            }
            // Stored first, linked only once held to the dirhash (clause 8), so a failing body never serves. The
            // store's SHA-256 is the .mod's per-file digest, so a .mod costs no second read.
            String hash = blobs.store(download.body());
            String actual = rest.endsWith(".mod")
                    ? GoDirhash.ofGoMod(hash)
                    : zipDirhash(blobs, store, hash);
            if (!expected.equals(actual)) {
                // Refused visibly: nothing linked, the local 404 stands, and a mismatch is logged apart from an
                // uncomputable dirhash.
                LOGGER.warn("Refusing the proxied Go module {}: the checksum database advertises {} but the upstream "
                                + "body {}. Nothing was cached or served.", rest, expected,
                        actual == null
                                ? "could not be hashed within the archive-walk bound (jenrepo.archive.largest-walk)"
                                : "hashes to " + actual);
                return false;
            }
            blobs.link(key, hash);
        }
        return serveProxied(exchange, store);
    }

    /** The just-cached body served through this format's own read path. */
    private boolean serveProxied(FormatExchange exchange, ArtifactStore store) throws IOException {
        handle(exchange, store);
        return true;
    }

    /** The path prefix the GOPROXY protocol reserves for the checksum database. */
    private static final String SUMDB = "sumdb/";

    private static final Logger LOGGER = LoggerFactory.getLogger(GoFormat.class);

    /** Relay one checksum-database request, {@code <name>/<operation>}, fresh and uncached, through the upstream's
     *  mirror or the configured database. {@code false} (the local {@code 404}) when no protocol operation is named or
     *  neither route answers, which a {@code go} client reads as "not mirrored" and falls back on its own. */
    private boolean sumdb(String rest, FormatExchange exchange, URI upstream, ProxyFormat.Fetcher fetcher)
            throws IOException {
        int slash = rest.indexOf('/');
        if (slash <= 0) {
            return false;
        }
        String database = rest.substring(0, slash);
        String operation = rest.substring(slash + 1);
        if (Keys.unsafe(database) || !GoChecksumDatabase.relayable(operation)) {
            // The name is spliced into the upstream's path, so it is screened; an undefined operation is not relayed.
            return false;
        }
        Optional<ProxyFormat.Fetched> response = GoChecksumDatabase.read(fetcher, upstream, database, operation,
                ProxyLeg.allowInternalTargets(exchange));
        if (response.isEmpty()) {
            return false;
        }
        // Small and mutable, so not cached, and relayed verbatim, since a rewritten body would no longer verify.
        String contentType = response.get().header("Content-Type");
        exchange.setResponseHeader("Content-Type", contentType == null ? "text/plain; charset=utf-8" : contentType);
        exchange.respond(200, response.get().body());
        return true;
    }

    /** The dirhash the checksum database advertises for the version a {@code <module>/@v/<file>} request names.
     *  {@link ProxyRelay.Declared#NONE} when it advertises none for that shape (clause 3);
     *  {@linkplain ProxyRelay.Declared#unreadable unreadable} when neither route could be read. A composed string, so
     *  it rides as a {@link ProxyRelay.Declared#text} declaration compared as text. */
    private static ProxyRelay.Declared advertisedDirhash(String rest, URI upstream, ProxyFormat.Fetcher fetcher,
                                                         boolean allowInternal) throws IOException {
        int at = rest.indexOf("/@v/");
        if (at <= 0) {
            return ProxyRelay.Declared.NONE;
        }
        String module = rest.substring(0, at);
        String file = rest.substring(at + "/@v/".length());
        int dot = file.lastIndexOf('.');
        if (dot <= 0) {
            return ProxyRelay.Declared.NONE;
        }
        String version = file.substring(0, dot);
        GoChecksumDatabase.Advertised advertised =
                GoChecksumDatabase.lookup(fetcher.beside(), upstream, module, version, allowInternal);
        if (advertised.unreadable() != null) {
            return ProxyRelay.Declared.unreadable(advertised.unreadable());
        }
        String dirhash = advertised.dirhashes() == null
                ? null
                : file.endsWith(".mod") ? advertised.dirhashes().mod() : advertised.dirhashes().zip();
        if (dirhash == null) {
            LOGGER.debug("The checksum database advertises no dirhash for {}: caching it unverified.", rest);
            return ProxyRelay.Declared.NONE;
        }
        return ProxyRelay.Declared.text(GoDirhash.PREFIX, dirhash);
    }

    /** The dirhash of a module {@code .zip} stored under {@code hash}, walked from the store in one bounded pass;
     *  {@code null} when a bound stopped the walk, which the caller refuses. */
    private static String zipDirhash(Blobs blobs, ArtifactStore store, String hash) throws IOException {
        try (InputStream archive = blobs.open(hash)) {
            return GoDirhash.ofZip(archive, store.size("blobs/" + hash));
        }
    }

    /**
     * {@code GET /go/<module>/@v/list}: the module's disclosable versions, one per line.
     *
     * <p>A {@code 404} means "not hosted here, ask the upstream", keyed on the {@link #hosted} marker alone. A hosted
     * module whose every version is held answers {@code 200} with an empty list, so the emptiness probe is over the raw
     * {@code @v} container; answering {@code 404} would send a client to the upstream's list and disclose what the hold
     * withholds. {@code @latest} names one version and has no empty form, so it keeps its {@code 404}
     * ({@link #latest}).
     */
    private void list(String modulePath, Blobs blobs, FormatExchange exchange) throws IOException {
        if (!hosted(modulePath, blobs)) {
            exchange.respond(404);
            return;
        }
        Optional<StoredListing.Served> served = StoredListing.open(blobs.store(), new GoListings(blobs).spec(modulePath));
        if (served.isEmpty()) {
            exchange.respond(404);
            return;
        }
        try (StoredListing.Served document = served.get()) {
            if (document.header().size() == 0 && !stored(modulePath, blobs)) {
                exchange.respond(404);
                return;
            }
            Listings.serve(exchange, document, "text/plain");
        }
    }

    private void latest(String modulePath, Blobs blobs, FormatExchange exchange) throws IOException {
        if (!hosted(modulePath, blobs)) {
            exchange.respond(404);
            return;
        }
        // @latest is derived from the stored list; a module read before its list exists derives it once.
        Optional<StoredListing.Served> served = StoredListing.openDerived(blobs.store(), GoListings.latest(modulePath));
        if (served.isEmpty()) {
            StoredListing.open(blobs.store(), new GoListings(blobs).spec(modulePath)).ifPresent(GoFormat::closeQuietly);
            served = StoredListing.openDerived(blobs.store(), GoListings.latest(modulePath));
        }
        if (served.isEmpty()) {
            exchange.respond(404);
            return;
        }
        String latest;
        try (StoredListing.Served document = served.get()) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            document.copyTo(buffer);
            latest = buffer.toString(StandardCharsets.UTF_8).trim();
        }
        if (latest.isEmpty()) {
            exchange.respond(404);
            return;
        }
        serve(modulePath, latest + ".info", blobs, exchange);
    }

    private static void closeQuietly(StoredListing.Served served) {
        try {
            served.close();
        } catch (IOException ignored) {
            // nothing was read from it
        }
    }

    private void serve(String modulePath, String file, Blobs blobs, FormatExchange exchange) throws IOException {
        String key = "go/" + modulePath + "/@v/" + file;
        Optional<Blobs.Located> located = blobs.locate(key);
        if (located.isEmpty()) {
            exchange.respond(404);
            return;
        }
        long size = located.get().size();
        exchange.setResponseHeader("Content-Type", contentType(file));
        if (exchange.method().equals("HEAD")) {
            // HEAD answers from the stored size; the go client probes existence and size with it.
            if (size >= 0) {
                exchange.setResponseHeader("Content-Length", Long.toString(size));
            }
            exchange.respond(200, -1L).close();
            return;
        }
        if (file.endsWith(".zip")) {
            blobs.serve(located.get(), exchange);
            return;
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        blobs.stream(located.get(), buffer);
        exchange.respond(200, buffer.toByteArray());
    }

    /** Whether a module path has a segment unsafe for a blob key - empty, {@code .}/{@code ..}, or a backslash or
     *  control character, as {@link Keys#unsafe} judges a single segment - so a hostile path is a clean 400. A
     *  backslash in the whole request path is already a 404 by the shared request screen. */
    private static boolean unsafeModule(String modulePath) {
        for (String segment : modulePath.split("/", -1)) {
            if (Keys.unsafe(segment)) {
                return true;
            }
        }
        return false;
    }

    /** The per-module hosted-publish marker, beside the version files and no {@code .info}, so it never reads as a
     *  version; {@link GoImporter} stamps it too, an import being a hosted publish. */
    static String hostedKey(String modulePath) {
        return "go/" + modulePath + "/@v/.hosted";
    }

    /** Whether this module has taken a hosted publish. The version-discovery gate keys on it, so a proxy repository's
     *  discovery misses locally and relays the upstream's full version list. */
    private static boolean hosted(String modulePath, Blobs blobs) throws IOException {
        return blobs.exists(hostedKey(modulePath));
    }

    /** Whether the module's {@code @v} container holds any version, by an {@code .info} in the raw container rather
     *  than the screened list, so a hosted module whose versions are all held answers an empty list. Walked through the
     *  bounded children primitive at its default width - a thousand names, a request-path bound - so a module whose
     *  first thousand children carry no {@code .info} reads as not stored. */
    private static boolean stored(String modulePath, Blobs blobs) throws IOException {
        boolean[] any = {false};
        BoundedChildren.bounded().scan(blobs.store(), "go/" + modulePath + "/@v", name -> {
            if (name.endsWith(".info")) {
                any[0] = true;
            }
        });
        return any[0];
    }

    private static String contentType(String file) {
        if (file.endsWith(".info")) {
            return "application/json";
        }
        if (file.endsWith(".zip")) {
            return "application/zip";
        }
        return "text/plain";
    }

    /** The migration-import capability, delegated to {@link GoImporter}. */
    private final GoImporter importer = new GoImporter();

    @Override
    public RepositoryImporter importer() {
        return importer;
    }

    /** Each of the version's {@code .info}, {@code .mod} and {@code .zip} is put at its {@code @v/} path, as a GOPROXY
     *  that accepts uploads takes a module. */
    @Override
    public Exported export(ArtifactStore repository, String coordinate, String version, ExportTarget target)
            throws IOException {
        return BlobExport.put(repository, mount(), blobKeys(coordinate, version, repository), target);
    }
}
