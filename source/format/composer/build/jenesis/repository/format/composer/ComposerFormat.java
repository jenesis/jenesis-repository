package build.jenesis.repository.format.composer;

import module java.base;
import module tools.jackson.databind;

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
import build.jenesis.repository.store.ArchiveInflation;
import build.jenesis.repository.store.ArchiveWalk;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.StoredListing;
import build.jenesis.repository.store.ServableNames;
import build.jenesis.repository.walk.ScreenedNames;

/**
 * The Composer registry format (the Composer v2 metadata protocol), so {@code composer require} and
 * {@code composer install} resolve PHP packages over the shared store. It owns {@code /composer/...}, where the first
 * path segment is a registry: a package is pushed with {@code PUT /composer/<repo>/<vendor>/<package>/<version>} (the
 * package's zip archive as the raw body) and downloaded from {@code /composer/<repo>/dists/<vendor>/<package>/<version>.zip}.
 * The metadata a client reads ({@code /composer/<repo>/packages.json}, the root, and the per-package Composer-v2 file at
 * {@code /composer/<repo>/p2/<vendor>/<package>.json}, plus its {@code ~dev} companion for dev versions) is generated on
 * read ({@code packages.json}) or streamed from a stored listing the publish maintains, its download URLs completed
 * for the serving host on the way out.
 *
 * <p><b>Streaming publish.</b> The uploaded zip streams straight through {@link ArtifactStore#writeBlob} into the
 * content-addressed store, hashed on the way and never buffered; the SHA-256 the store returns is both the download
 * pointer's blob hash and the {@code dist.reference} the metadata records. Composer keeps a package's coordinate and
 * dependency metadata in a {@code composer.json} <i>inside</i> the archive, so - exactly as the store-then-gate publish
 * path reads a just-stored artifact back rather than buffering it from the network - the stored blob is reopened
 * ({@link ArtifactStore#open}) and only that small {@code composer.json} is materialised (the package's root
 * {@code composer.json}, at the archive root or one directory deep, walked with {@code java.util.zip} and parsed with
 * the Jackson databind on the server path; the large payload streams past or is skipped, bounded so a hostile archive
 * cannot force a large allocation or an unbounded inflate). The {@code composer.json} augmented with the {@code version}
 * (from the request path, since a library's {@code composer.json} usually omits it) and a {@code dist} pointing back
 * through this registry is stored per version (the Debian/RPM/Cargo/Conda per-package-stanza pattern), so the publish
 * joins the stored stanzas into the package's metadata file and a read streams it rather than reopening every archive
 * (read-first).
 *
 * <p>The stanza carries no {@code version_normalized}: Composer's {@code ArrayLoader} normalises the {@code version}
 * field itself when {@code version_normalized} is absent, so the ecosystem-specific normalisation (which no modular
 * library on the path provides) is left to the client rather than hand-rolled and risked getting wrong. Likewise the
 * {@code dist} carries no {@code shasum}: the content-addressed download is served from the store's own SHA-256, and an
 * empty/absent {@code shasum} simply skips Composer's optional dist check.
 *
 * <p>The layout declares its ecosystem ({@code "Packagist"}, the OSV package-ecosystem name for Composer/PHP packages)
 * so a compliance inspector, the console and download tracking key on it; {@link #describe} resolves a dist
 * download path to its {@code <vendor>/<package>} coordinate and version. Package pointers live in the shared
 * {@code Blobs} namespace like the other language formats, so the {@code publish/}-namespace eviction ({@link #paths})
 * stays empty; coordinate-scoped enforcement runs through the {@code BlobLayout} seam
 * ({@link #blobKeys}/{@link #servedPaths}) instead.
 *
 * <p><b>Pull-through proxy.</b> The same layout is also a {@link ProxyFormat}: a local miss on a proxy registry is
 * served from an upstream Composer-v2 repository (Packagist by default, {@link #defaultUpstream()}). The root
 * {@code packages.json} is always generated locally (its {@code metadata-url} points back through this registry, so a
 * client fetches every package's {@code p2} file through here) and is never proxied. A per-package
 * {@code p2/<vendor>/<package>.json} (and its {@code ~dev} companion) is mutable, so it is fetched fresh on every read
 * and never cached; unlike the Cargo sparse index (which carries no download URLs) and the Conda repodata (whose
 * locations are bare filenames under the local prefix), a Composer {@code p2} file carries an absolute upstream
 * {@code dist.url} per version, so each version's {@code dist.url} is <i>rewritten</i> to route the download back
 * through this registry - the client then fetches the archive through here and it is cached on first fetch. The
 * archive itself is immutable, so it streams from upstream straight into the CAS
 * ({@link Blobs#writeVerified}, never buffered) and is served, so a later read is a local hit that never
 * touches the upstream. On a package miss the upstream {@code dist.url} is resolved by re-reading the upstream
 * {@code p2} file (the version's {@code dist.url}) - a small bounded metadata read, only on the first download of a
 * version since the archive is then cached - so the proxy keeps no per-download state written on a metadata read (the
 * Cargo model). That same entry publishes the archive's {@code dist.shasum} (Composer's SHA-1 of the dist file, the
 * digest {@code composer install} itself verifies against), so the streamed archive is held to it and a mismatch is
 * refused - nothing linked, nothing served, the local {@code 404} standing so a later pull re-hits the upstream. An
 * entry with no (or an empty) {@code shasum} - Packagist's own answer for a VCS-sourced dist - caches unverified rather
 * than fabricating a check, exactly as Maven serves a proxied jar whose {@code .sha1} sibling is missing. An upstream
 * miss lets the local {@code 404} stand.
 *
 * <p>A {@link build.jenesis.repository.format.RepositoryImporter} ({@link ComposerImporter}) migrates a
 * Nexus/Artifactory {@code composer} repository by replaying each package archive through this format's own publish
 * path, and a compliance inspector screens the {@code "Packagist"} ecosystem (a sibling {@code compliance/composer}
 * module).
 */
public final class ComposerFormat implements RepositoryFormat, ArtifactLayout, ProxyLeg, BlobLayout, RepositoryImporter,
        RepositoryExporter {

    /** The OSV package-ecosystem name this format's artifacts report (distinct from {@link #name()}, the routing id). */
    public static final String ECOSYSTEM = "Packagist";

    static final ObjectMapper MAPPER = new ObjectMapper();

    /** The canonical public Composer-v2 registry this format mirrors when a deployment enables proxying without naming
     *  an upstream - Packagist's metadata host, where {@code packages.json} and the {@code /p2/%package%.json} files
     *  live (like crates.io for Cargo, registry.npmjs.org for npm). A deployment can always set one explicitly. */
    private static final URI PACKAGIST = URI.create("https://repo.packagist.org");

    private static final String PREFIX = "/composer/";
    private static final String PACKAGES = "packages.json";
    private static final String LIST = "list.json";
    private static final String P2 = "p2/";
    private static final String DISTS = "dists/";
    private static final String ZIP = ".zip";
    private static final String JSON = ".json";
    private static final String DEV = "~dev";

    // A hostile archive cannot force a large allocation: the composer.json read is bounded by the product's one
    // archive-inflation ceiling, ArchiveInflation.largestEntry(), settable at jenreg.archive.largest-entry - not by a
    // private constant of this format's (RepositoryFormat contract clause 15).

    // How far the walk for the root composer.json may run is the product's one archive-walk bound,
    // ArchiveWalk.largestWalk(), settable at jenreg.archive.largest-walk - not a private constant of this format's
    // (RepositoryFormat contract clause 15 /, one dimension over from the inflation ceiling). An archive that
    // will not yield its composer.json inside it is treated as unindexable (a 400 publish).

    @Override
    public String name() {
        return "composer";
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

    /**
     * The package version a stored Composer pointer serves - the backwards direction the inventory back-fill
     * rebuilds a lost {@code published/} row from.
     *
     * <p>Only the index entry is decoded, {@code composer/<repo>/index/<vendor>/<name>/<version>}. What makes that
     * unambiguous despite a two-segment coordinate is that a Composer coordinate is <em>always</em> exactly
     * {@code vendor/package} - so after the {@code index} marker there are exactly three segments, and counting
     * them is a fact about the ecosystem rather than a guess about this store. The {@code dist} archive beside it
     * would decode as well, and is left alone for the reason the whole clause is conservative: two ways to derive
     * one row are two ways for them to disagree.
     */
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
            // A traversal-shaped coordinate or version maps nowhere: these keys are what an eviction DELETES, and
            // ArtifactStore.delete is not screened. The shared per-part screen, so a legitimately
            // multi-segment coordinate still resolves.
            return List.of();
        }
        // The package zip and its precomputed Composer-v2 stanza, both keyed deterministically by
        // <vendor>/<package>/<version>; the <repo> registry segment is discovered by listing.
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

    /** The served download path the dist zip occupies for one coordinate version - the request that streams the
     *  {@code .zip} blob {@link #blobKeys} resolves, so a retroactive hold retracts it (a {@code /quarantine} review
     *  handle per path) exactly as {@code ArtifactLayout.paths} does for a {@code publish/}-namespace layout. The
     *  {@code <repo>} registry segment is discovered by listing, the same way {@link #blobKeys} finds the dist pointer;
     *  the served path is the {@code dists/} download route {@link #describe} reads (the store key uses {@code dist/}).
     *  Only the dist archive is a served artifact - the Composer-v2 index stanza is metadata and carries no
     *  {@code /quarantine} handle. */
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

    /**
     * Stream a package upload into the CAS while materialising only its {@code composer.json}, then record the
     * download pointer and its metadata stanza. The archive streams straight through {@link ArtifactStore#writeBlob},
     * so an arbitrarily large package never lands in heap; the just-stored blob is reopened to read its metadata.
     */
    private void publish(String repo, String sub, FormatExchange exchange, ArtifactStore store) throws IOException {
        // The publish coordinate is <vendor>/<package>/<version>; p2/ and dists/ are read-route names, not vendors.
        String[] parts = sub.split("/", -1);
        if (parts.length != 3 || parts[0].equals("p2") || parts[0].equals("dists")) {
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
        String hash = store.writeBlob(exchange.requestStream());
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
            // The archive claims a different coordinate than the deploy path: refuse rather than let a package publish
            // itself under another's <vendor>/<package> name (a metadata-poisoning route the store's tenant root check
            // does not close within this format's namespace).
            exchange.respond(400);
            return;
        }
        ObjectNode stanza = composer.deepCopy();
        stanza.put("name", coordinate);
        stanza.put("version", version);
        stanza.set("dist", dist(hash));
        // Route the download pointer through Blobs.link (not a bare writeVersioned): besides the compare-and-set retry,
        // link clears any gc/condemned/<hash> marker a collector set, so republishing content byte-identical to a
        // condemned archive un-condemns it before the sweep deletes it - otherwise a 201 publish is GC-deleted to a
        // permanent 404. The pointer stores exactly that blob hash, so the marker key matches.
        Blobs blobs = new Blobs(store);
        blobs.link(distKey(repo, vendor, pkg, version), hash);
        byte[] indexed = MAPPER.writeValueAsBytes(stanza);
        blobs.write(indexKey(repo, vendor, pkg, version), indexed);
        // The served p2 file and the package list are written here, on the publish, rather than generated on read.
        new ComposerListings(blobs).published(repo, vendor, pkg, version, indexed);
        exchange.respond(201);
    }

    /** The {@code dist} block for a version, as stored: a {@code zip} keyed by the stored SHA-256 (no {@code shasum} -
     *  the content-addressed serve is the integrity guarantee). The download {@code url} is not baked in here; it is
     *  generated on read from the serving request (see {@link #metadata}), so a package points at whatever host serves
     *  it - the host-portable, read-first convention every other format's download URL follows, and what lets an
     *  imported package (stored with no request host) still carry a correct download URL. */
    private ObjectNode dist(String hash) {
        ObjectNode dist = MAPPER.createObjectNode();
        dist.put("type", "zip");
        dist.put("reference", hash);
        return dist;
    }

    /** The root {@code packages.json}: the Composer-v2 {@code metadata-url} template a client expands per package. It is
     *  host-relative (leading {@code /}), which Composer resolves against the repository's host, so it carries the full
     *  routing path back to this registry's {@code p2/} files. No {@code available-packages} listing is emitted: a
     *  Composer-v2 client treats a present {@code available-packages} as the <i>complete</i> set of packages, so a proxy
     *  whose local index holds only what it has cached would shadow the upstream - a required package absent from the
     *  local index is never looked up, its {@code p2} file never fetched, and pull-through never fires. Omitting it makes
     *  the root a pure lazy provider: the client fetches each required package's {@code p2} file through the
     *  {@code metadata-url}, which resolves locally on a hosted registry and triggers pull-through on a proxy. Emitting
     *  it also cost a full vendor/package index scan on <i>every</i> root read; the enumeration a migration needs is
     *  moved to the explicit {@code list} endpoint ({@link #list}), off the hot resolve path (read-first). The root
     *  itself is a constant document - no scan, no store read. */
    private void root(String repo, FormatExchange exchange) throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        root.set("packages", MAPPER.createObjectNode());
        root.put("metadata-url", repoPath(repo, exchange) + "/" + P2 + "%package%" + JSON);
        // The Composer-v2 list endpoint (Packagist's list.json): the package-name enumeration a migration walk reads,
        // named here but computed only when that endpoint is actually fetched - not on this resolve-path read.
        root.put("list", repoPath(repo, exchange) + "/" + LIST);
        exchange.setResponseHeader("Content-Type", "application/json");
        exchange.answer(MAPPER.writeValueAsBytes(root));
    }

    /** The Composer-v2 {@code list} endpoint: {@code {"packageNames": [...]}}, every {@code <vendor>/<package>} this
     *  registry has indexed. This is the one read that walks the index (a migration/enumeration reads it), so the
     *  per-root scan the resolve path used to pay is confined here - a normal {@code composer require} reads
     *  {@code packages.json} (a constant) then the per-package {@code p2} files, and never touches this endpoint. On a
     *  proxy registry it lists only the locally cached packages, which is honest for enumeration and does not affect
     *  resolution (the resolver drives off the lazy {@code metadata-url}, not this list). */
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
            // A repository with nothing to list answers 404, not 200 with an empty array. This route is addressed by
            // the REPOSITORY's own name rather than a package's, which is the half the container fix left open: there, an empty
            // document discloses nothing a client did not already supply, but here it asserts "this repository exists
            // and is barren" - and on a proxy that assertion is load-bearing the wrong way, because it is the 404
            // that sends the pull-through to the upstream root. A 200 would shadow Packagist rather than proxy it.
            //
            // Read AFTER opening, deliberately: open() materialises an absent document, so a repository that has never
            // been published to arrives here with a freshly generated empty one rather than with nothing. Probing the
            // header first therefore answers "no document" for exactly the case this must catch. The count covers both
            // shapes - never published, and everything since withheld - which is the single rule the ruling asked for.
            // An UNKNOWN count (a document predating the count) is not zero and is left serving as before.
            if (document.header().count().orElse(-1L) == 0L) {
                exchange.respond(404);
                return;
            }
            respondListing(document, exchange, null);
        }
    }

    /** The per-package Composer-v2 metadata file: the stored listing the publish maintains, completed with this
     *  registry's external base on the way out. {@code <vendor>/<package>.json} carries the release versions;
     *  {@code <vendor>/<package>~dev.json} carries the dev versions. A bucket with nothing STORED is a {@code 404} so
     *  a proxy registry fills it from upstream; a bucket whose every stored version a hold has withheld answers
     *  {@code 200} with an empty version array, because the 404 is keyed on the raw container and never on the
     *  screened one. */
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
        // The structural bucket probe is paid only until the document exists: a present document proves the bucket
        // was published to, so a read never enumerates the stored versions again.
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
            respondListing(document, exchange, repoBase(repo, exchange));
        }
    }

    /** Stream a stored listing as JSON; with a base, the stored placeholder is completed on the way out (so the length
     *  is not known in advance and the body is sent without a {@code Content-Length}). The ETag is the stored document's
     *  digest, folded with the base it is completed for. */
    private static void respondListing(StoredListing.Served document, FormatExchange exchange, String base)
            throws IOException {
        String etag = '"' + document.header().sha256() + (base == null ? "" : "-" + Integer.toHexString(base.hashCode()))
                + '"';
        exchange.setResponseHeader("ETag", etag);
        if (etag.equals(exchange.requestHeader("If-None-Match"))) {
            exchange.respond(304);
            return;
        }
        exchange.setResponseHeader("Content-Type", "application/json");
        if (exchange.method().equals("HEAD")) {
            if (base == null) {
                exchange.setResponseHeader("Content-Length", Long.toString(document.header().size()));
            }
            exchange.respond(200, -1L).close();
            return;
        }
        if (base == null) {
            // Streamed rather than materialised: the document is the size of what it lists, so handing
            // it over whole put the whole listing in heap on the request path.
            try (OutputStream out = exchange.respond(200, document.header().size())) {
                document.body().transferTo(out);
            }
        } else {
            // Streamed with the rewrite folded in, never as one byte array: a package's document is every version
            // of it, and answering it whole held the packument the publish had just streamed into - the
            // shape the npm-packument canary showed as a 500 at fifty thousand versions under 512 MiB, an OutOfMemoryError on the read
            // after the write was fixed. The length is not declared, since the rewrite changes it.
            try (OutputStream out = exchange.respond(200, -1L)) {
                document.copyTo(out, ComposerListings.BASE, base);
            }
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
        Optional<Blobs.Located> located = blobs.locate(key);
        if (located.isEmpty()) {
            exchange.respond(404);
            return;
        }
        long size = located.get().size();
        exchange.setResponseHeader("Content-Type", "application/zip");
        if (exchange.method().equals("HEAD")) {
            if (size >= 0) {
                exchange.setResponseHeader("Content-Length", Long.toString(size));
            }
            exchange.respond(200, -1L).close();
            return;
        }
        blobs.serve(located.get(), exchange);
    }

    /** The canonical public Composer-v2 registry this format mirrors when a deployment enables proxying without naming
     *  one: Packagist. A deployment can always set a different upstream per repository. */
    @Override
    public Optional<URI> defaultUpstream() {
        return Optional.of(PACKAGIST);
    }

    /**
     * Proxy a Composer miss to an upstream Composer-v2 repository. The root {@code packages.json} is always generated
     * locally (never proxied); a mutable {@code p2/<vendor>/<package>.json} is fetched fresh and its per-version
     * {@code dist.url} rewritten to route the download back through this registry; and an immutable package archive is
     * fetched, cached into the CAS and served, with its upstream URL resolved from the upstream {@code p2} file. The
     * request path {@code /composer/<repo>/<sub>} maps to {@code <upstream>/<sub>} - a Composer-v2 upstream serves its
     * metadata under the same {@code p2/%package%.json} layout this registry exposes, so no path rewrite is needed
     * beyond the {@code dist.url} routing. Returns {@code false} to let the local {@code 404} stand.
     */
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
            // The local packages.json is always generated (its metadata-url points back through here), so it never
            // misses and is never proxied; declining lets the local response stand.
            return false;
        }
        if (sub.startsWith(P2) && sub.endsWith(JSON)) {
            return proxyMetadata(repo, sub, root, exchange, fetcher);
        }
        if (sub.startsWith(DISTS) && sub.endsWith(ZIP)) {
            return proxyDist(repo, sub.substring(DISTS.length()), root, exchange, store, fetcher);
        }
        return false;
    }

    /**
     * Fetch an upstream per-package {@code p2} metadata file, rewrite each version's {@code dist.url} to route the
     * download back through this registry, and stream it through fresh (never cached - a {@code p2} file is mutable).
     * The {@code p2} file is a bounded metadata document (a package's version list), not an artifact, so it may be
     * materialised to rewrite. A version whose value would not form a safe download path segment is left pointing
     * upstream rather than rewritten to a path that could not be served back (a rare dev branch carrying a {@code /}).
     */
    private boolean proxyMetadata(String repo, String sub, String root, FormatExchange exchange,
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
        // ENUMERATION: a p2 file is the package's version list, the document Composer's resolver reads to decide which
        // versions exist, so its absence is an answer ("no such package") and not "not cached here". Only an upstream
        // that ANSWERED 404/410 may reach the client as one; an upstream that could not be asked, or that answered a
        // 429/5xx/auth challenge, refuses visibly rather than being rendered as an empty version list.
        ProxyRelay.Answer answer = ProxyRelay.fetchFresh(fetcher, URI.create(root + "/" + sub), Map.of(), exchange,
                ProxyRelay.Document.ENUMERATION);
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

    /**
     * Fetch, cache and serve an immutable package archive: resolve the upstream {@code dist.url} from the upstream
     * {@code p2} file (the version's dist), stream the archive into the CAS ({@link ProxyRelay#fill},
     * never buffered), then serve it from the local hit. Only reached on a miss, and the archive is then cached, so a
     * later read never re-resolves.
     */
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
        // Point-integrity: the same p2 entry this leg just read to resolve the download URL also publishes the
        // archive's `dist.shasum` - Composer's own SHA-1 of the dist file, the digest a `composer install` verifies the
        // download against - so hold the streamed archive to it and refuse a mismatch (the Maven proxy leg's checksum
        // parity, and ProxyFormat contract clause 5: the declaring document is addressable from the request path
        // because this leg already has to fetch it).
        //
        // This leg has no split to make and that is a property of its protocol, not an omission: the p2 entry is
        // the SAME document that resolves the download URL, so a p2 file this repository could not read leaves nothing
        // to fetch and distUrl already declines the whole fill above. The only fall-back reachable here is the
        // documented one - an entry that answered and carries no (or an empty/malformed) shasum, which is what
        // Packagist itself publishes for a VCS-sourced dist.
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

    /** A resolved upstream dist: the archive URL and, when the upstream {@code p2} entry declared one, the
     *  {@code dist.shasum} (Composer's SHA-1 of the archive) the fetched bytes must hash to. */
    private record Dist(URI url, byte[] shasum) {
    }

    /** Resolve the upstream download for a version by reading the upstream {@code p2} file (the {@code ~dev}
     *  companion for a dev version, the release file otherwise) and taking the version's {@code dist.url} - and, where
     *  the entry declares one, its {@code dist.shasum} so the fetched archive can be verified against it. A small
     *  bounded metadata read, only on a package miss (once per version, since the archive is then cached). */
    private static Dist distUrl(String root, String vendor, String pkg, String version, ProxyFormat.Fetcher fetcher,
                                boolean allowInternal) throws IOException {
        String file = P2 + vendor + "/" + pkg + (isDev(version) ? DEV : "") + JSON;
        Optional<ProxyFormat.Fetched> fetched = fetcher.fetch(URI.create(root + "/" + file), Map.of());
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
                    // The dist.url comes from untrusted upstream package metadata (an attacker can publish a package
                    // to the public upstream whose dist points at an internal service, or at a plaintext host of
                    // their choosing), so an unguarded fetch would be an SSRF and an unguarded http one would put the
                    // archive and any per-host upstream credential in front of every observer on the path. The one
                    // shared screen decides it; a refused target is declined so the miss falls through
                    // to a 404, never a throw (ProxyLeg clause 2).
                    return OutboundTargets.mayFollow(target, URI.create(root), allowInternal)
                            ? new Dist(target, hex(text(entry.path("dist"), "shasum"), 20))
                            : null;
                } catch (IllegalArgumentException e) {
                    return null;
                }
            }
        }
        return null;
    }

    /** Decode a hex digest of exactly {@code bytes} bytes to its raw bytes, or {@code null} when it is absent, empty or
     *  malformed - so an upstream that publishes {@code "shasum": ""} (Packagist's own answer for a VCS-sourced dist)
     *  falls back to plain caching rather than refusing every fetch. */
    private static byte[] hex(String value, int bytes) {
        if (value == null || value.length() != bytes * 2) {
            return null;
        }
        try {
            return HexFormat.of().parseHex(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Parse an upstream metadata document, or {@code null} when it is malformed (Jackson signals a parse failure with
     *  an unchecked exception) - a malformed upstream metadata is treated as a miss rather than a {@code 500}. */
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
            // The publish path, <vendor>/<name>/<version>: what a package is pushed at, and so the path the gate
            // links a review pointer at when it holds one - which a release's cross-alias guard then asks to be
            // placed. The dist path below carries the same coordinate.
            String[] pushed = sub.split("/", -1);
            if (pushed.length == 3 && ArtifactLayout.addressable(pushed[0], pushed[1], pushed[2])) {
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
        // Composer package pointers live in the shared Blobs namespace (like npm/pypi/go/rpm/cargo/conda), not the
        // Publication namespace coordinate-based eviction walks, so nothing is enumerable from the coordinate alone.
        return List.of();
    }

    /** Read the package's root {@code composer.json} from a just-stored archive, inflating only as far as it: the root
     *  ({@code composer.json}) is preferred, else the first one directory deep ({@code <prefix>/composer.json}, as a
     *  VCS-exported archive files it); a {@code composer.json} deeper than that belongs to a bundled dependency and is
     *  ignored. Only the small JSON is materialised (bounded), and the whole scan is bounded so a hostile archive
     *  cannot drive an unbounded inflate. Returns {@code null} when no usable {@code composer.json} is found. */
    private static ObjectNode readComposerJson(InputStream blob) throws IOException {
        return ArchiveWalk.walk(blob, ComposerFormat::declaredComposerJson).orNull();
    }

    /** The {@code composer.json} inside an already-bounded archive stream, or {@code null} when it carries none. */
    private static ObjectNode declaredComposerJson(InputStream archive) throws IOException {
        ZipInputStream zip = new ZipInputStream(archive);
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

    /**
     * Parse the current zip entry as a JSON object under the shared archive-inflation ceiling, or {@code null} when it
     * is not a JSON object (an unusable package manifest).
     *
     * <p>The manifest is this publish's <b>guard input</b> - {@link #publish} checks the declared {@code name} against
     * the deploy path so a package cannot publish itself under another's coordinate - so a read the ceiling stopped
     * takes {@code required(...)} and fails closed rather than answering "this entry declares nothing". Degrading it
     * would be worse here than a lost declaration: the walk would fall through to the fallback one directory deep,
     * which belongs to a <em>bundled dependency</em>, and the package would be checked against that manifest instead.
     */
    private static ObjectNode parse(InputStream entry) throws IOException {
        byte[] json = ArchiveInflation.entry(entry).required("Composer package", "composer.json");
        try {
            return MAPPER.readTree(json) instanceof ObjectNode object ? object : null;
        } catch (RuntimeException e) {
            // A malformed composer.json (Jackson signals a parse failure with an unchecked exception) is not a usable
            // package manifest: treat it as absent rather than let the 500 escape.
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

    /** Whether a Composer version string denotes a dev version (a branch alias {@code dev-<branch>} or an {@code -dev}
     *  suffix), which Composer serves from the {@code ~dev} metadata file rather than the release one. */
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

    /** The host-relative path to this registry ({@code <external-prefix>/composer/<repo>}), for the {@code metadata-url}
     *  Composer resolves against the host. */
    private static String repoPath(String repo, FormatExchange exchange) {
        return exchange.external(PREFIX + repo);
    }

    /**
     * A {@code vendor}, {@code package} or {@code version} becomes store-key path segments (the download pointer
     * {@link #distKey} and the metadata stanza {@link #indexKey}) and a routed request path, so a value that is empty,
     * carries a path separator or control character, or is a {@code .}/{@code ..} traversal segment could steer a write
     * or read outside the package's key space and is refused. Real Composer vendor/package names are
     * {@code [a-z0-9]([_.-]?[a-z0-9]+)*} and versions carry no {@code /}, so no legitimate value is rejected.
     */

    static String distKey(String repo, String vendor, String pkg, String version) {
        return "composer/" + repo + "/dist/" + vendor + "/" + pkg + "/" + version + ZIP;
    }

    static String indexPrefix(String repo, String vendor, String pkg) {
        return "composer/" + repo + "/index/" + vendor + "/" + pkg;
    }

    /** Whether the name enumeration may list this package - it has at least one version whose archive a client can
     *  actually download. A package every one of whose versions a compliance hold has withheld is screened out, the
     *  same disclosure the per-package {@link #metadata} screen closes; the first servable version short-circuits the
     *  scan. A package with no indexed versions is left listed (it names no withheld coordinate). */
    static boolean servable(String repo, String vendor, String pkg, Blobs blobs) throws IOException {
        // The membership question the shared screened enumeration answers directly, short-circuiting at the first
        // disclosable version - the same screen metadata() renders through, not a second private one.
        if (ScreenedNames.keys(blobs.servableNames(), ServableNames.Policy.HIDE_WITHHELD,
                        version -> distKey(repo, vendor, pkg, version))
                .any(blobs.store(), indexPrefix(repo, vendor, pkg))) {
            return true;
        }
        // Provably no disclosable version. A package with no indexed version at all is still listed - it names no
        // withheld coordinate - so only this structural emptiness probe separates the two cases.
        return blobs.list(indexPrefix(repo, vendor, pkg)).isEmpty();
    }

    static String indexKey(String repo, String vendor, String pkg, String version) {
        return indexPrefix(repo, vendor, pkg) + "/" + version;
    }

    /** The migration-import capability, delegated to the layout-only {@link ComposerImporter} - the format IS the
     *  discovered importer now (an {@code instanceof} capability), and the importer class stays as its delegate. */
    private final ComposerImporter importer = new ComposerImporter();

    @Override
    public boolean imports(String sourceFormat) {
        return importer.imports(sourceFormat);
    }

    @Override
    public Optional<ArtifactDescriptor> importTarget(String sourcePath) {
        return importer.importTarget(sourcePath);
    }

    @Override
    public void importArtifact(String path, InputStream content, ArtifactStore store) throws IOException {
        importer.importArtifact(path, content, store);
    }

    /** Each registry's zip of the version is put where a Composer upload goes -
     *  {@code <repo>/<vendor>/<package>/<version>} - and asked for back at the dist path it is served from; the target
     *  derives its own {@code p2} stanza. */
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
