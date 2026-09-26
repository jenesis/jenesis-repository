package build.jenesis.repository.format.conan;

import module java.base;
import module tools.jackson.databind;

import build.jenesis.repository.format.Listings;
import build.jenesis.repository.blobs.BlobExport;
import build.jenesis.repository.blobs.BlobLayout;
import build.jenesis.repository.format.ExportTarget;
import build.jenesis.repository.format.RepositoryExporter;
import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.blobs.Keys;
import build.jenesis.repository.blobs.ProxyLeg;
import build.jenesis.repository.blobs.ProxyRelay;
import build.jenesis.repository.format.ArtifactLayout;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Retries;
import build.jenesis.repository.store.StoredListing;
import build.jenesis.repository.walk.BoundedChildren;
import build.jenesis.repository.walk.TraversalException;

/**
 * The Conan (C/C++) registry format (the Conan v2 REST protocol), so {@code conan upload} and {@code conan install}
 * resolve C/C++ packages over the shared store. It owns {@code /conan/...}, where the first path segment is a registry:
 * a capability {@code GET /conan/<repo>/v2/ping} advertises {@code X-Conan-Server-Capabilities: revisions} (so a Conan 2
 * client uses the revisions API), a recipe or package <i>file</i> is pushed with
 * {@code PUT /conan/<repo>/v2/conans/<name>/<version>/<user>/<channel>/revisions/<rrev>/files/<file>} (and its package
 * equivalent under {@code .../packages/<package_id>/revisions/<prev>/files/<file>}), and the same path serves the file
 * back. A reference without a user/channel uses Conan's {@code _} placeholder for both.
 *
 * <p><b>Revision-addressed, indexed on write.</b> A Conan client computes the recipe revision ({@code rrev}) and the
 * package revision ({@code prev}) itself - a hash of the exported sources / the built binary - and uploads each file to
 * that revision's path, so this format is a streaming, revision-addressed file store: it stores whatever the client
 * pushes at the client's revision and never has to open an archive (the coordinate is in the request path, not inside
 * the bytes). The revision index a client reads - the recipe's {@code latest} and {@code revisions}, a revision's
 * {@code files} listing, and the same three for a package_id's revisions - is a {@linkplain ConanListings stored
 * listing} each upload updates with its one entry, and a read streams as it is. The {@code latest} revision is the
 * most recently uploaded, so each file upload stamps its revision's time (a small compare-and-set pointer through the
 * store), and {@code latest} / {@code revisions} order by it.
 *
 * <p><b>Streaming publish.</b> An uploaded file streams straight through {@link Blobs#write(String, InputStream)} into
 * the content-addressed store, hashed on the way and never buffered, so an arbitrarily large {@code conan_package.tgz}
 * never lands in heap; a download streams the blob back out. Files dedupe in the shared {@code blobs/} namespace like
 * every other language format.
 *
 * <p>The layout declares its ecosystem ({@code "Conan"}) so the console and download tracking key on it;
 * {@link #describe} resolves a recipe/package file download path to its {@code <name>} coordinate and version. OSV
 * carries no dedicated Conan advisory feed today, so vulnerability screening is a graceful no-op while a coordinate
 * still drives license and malicious-package screening (the sibling {@code compliance/conan} inspector).
 * File pointers live in the shared {@code Blobs} namespace, so the {@code publish/}-namespace eviction ({@link #paths})
 * stays empty; coordinate-scoped enforcement runs through the {@code BlobLayout} seam
 * ({@link #blobKeys}/{@link #servedPaths}) instead.
 *
 * <p><b>Pull-through proxy.</b> The same layout is also a {@link ProxyFormat}: a local miss on a {@code v2/conans/...}
 * read is served from an upstream Conan server, mapping {@code /conan/<repo>/v2/conans/...} to
 * {@code <upstream>/v2/conans/...} (the local registry name is a deployment alias, so it is stripped and the rest of the
 * v2 path maps through). An immutable revision file ({@code .../revisions/<rrev>/files/<file>} and its package
 * equivalent - a content-addressed, revision-pinned blob) streams from upstream straight into the CAS
 * ({@link ProxyRelay#fill}, never buffered), stamps its revision's time, and is served locally, so a
 * later read is a local hit that never touches the upstream. The mutable index a client reads ({@code latest},
 * {@code revisions}, a revision's {@code files} listing, a {@code search}) is streamed through fresh on every read -
 * these documents carry no download URLs (a client builds a file URL from the request path, which already roots at this
 * repository), so the index needs no rewrite. {@link #defaultUpstream()} is ConanCenter (the canonical public Conan
 * registry); a deployment can name a different upstream per repository.
 */
public final class ConanFormat implements RepositoryFormat, ArtifactLayout, ProxyLeg, BlobLayout, RepositoryImporter,
        RepositoryExporter {

    /** The package-ecosystem name this format's artifacts report (distinct from {@link #name()}, the routing id). OSV
     *  has no dedicated Conan feed, so vulnerability lookups on it simply find nothing; the coordinate still drives
     *  license and malicious-package screening. */
    public static final String ECOSYSTEM = "Conan";

    /** Package-private like its peers in the other formats: the listing codec beside this parses with it too,
     *  and a second mapper for one reader would be a second configuration to keep in step. */
    static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String PREFIX = "/conan/";
    private static final String CONANS = "v2/conans/";

    /** The canonical public Conan registry a proxy repository mirrors when a deployment enables proxying without naming
     *  an upstream (Conan 2's {@code conancenter} remote), the Conan analogue of npm's registry.npmjs.org. */
    private static final URI CONAN_CENTER = URI.create("https://center2.conan.io");

    /** The Conan 2 server capabilities a client reads from {@code /v2/ping}: {@code revisions} is required for the
     *  revisions REST API this format speaks; {@code complex_search} advertises pattern search (a follow-on). */
    private static final String CAPABILITIES = "revisions,complex_search";

    @Override
    public String name() {
        return "conan";
    }

    @Override
    public String ecosystem() {
        return ECOSYSTEM;
    }

    @Override
    public List<String> blobRoots() {
        return List.of("conan");
    }

    @Override
    public List<String> blobKeys(String coordinate, String version, ArtifactStore store) throws IOException {
        if (!BlobLayout.addressable(coordinate, version)) {
            // A traversal-shaped coordinate or version maps nowhere: these keys are what an eviction DELETES, and
            // ArtifactStore.delete is not screened. The shared per-part screen, so a legitimately
            // multi-segment coordinate still resolves.
            return List.of();
        }
        // A Conan recipe/package file is addressed by client-computed revisions (rrev/prev) and a pushed filename, none
        // derivable from the <name,version> coordinate alone, so discover them by walking the version's own subtree
        // conan/<repo>/r/<name>/<version> (recipeBase without the user/channel) - users -> channels -> rrevs -> the
        // recipe files/*, plus each rrev's pkg/<pid>/<prev>/files/* package files. Every file under a revision's files/
        // directory is a Blobs.write pointer with a bare-hex body, so the BlobLayout.blobHashes default resolves the
        // withhold set from them (the time/commit/latest markers live OUTSIDE files/ and are never collected). A
        // retroactive KEV/license hold marks those hashes - the file serve and the revision's files listing both gate on
        // the marker - and an eviction deletes these exact keys; before this the empty return made a hold a silent no-op
        // (no marker, no review handle) and a KEV-listed recipe/package kept serving. Every level under the version is
        // attacker-publishable, so it is PAGED a page at a time (store.page), never list()ed whole (a whole
        // listing there is a denial-of-service lever); the tree depth is fixed
        // (user/channel/rrev[/pkg/pid/prev]/files), so the walk is bounded, not recursive over an attacker-controlled
        // depth.
        List<ConanFile> files = conanFiles(coordinate, version, store);
        List<String> keys = new ArrayList<>(files.size());
        for (ConanFile file : files) {
            keys.add(file.key());
        }
        return keys;
    }

    /** The request paths this coordinate version's recipe and package files serve at - a recipe file at
     *  {@code /conan/<repo>/v2/conans/<name>/<version>/<user>/<channel>/revisions/<rrev>/files/<file>} and a package
     *  file at {@code .../revisions/<rrev>/packages/<pid>/revisions/<prev>/files/<file>}, the inverse of
     *  {@link #describe}, so a retroactive hold links a {@code /quarantine} review handle per served path exactly as
     *  {@code ArtifactLayout.paths} does for a publish/ layout. Every collected file pointer is live (it was found by
     *  listing), so each maps to a served path. Reads only the tiny pointers, never a blob body. */
    @Override
    public List<String> servedPaths(String coordinate, String version, ArtifactStore store) throws IOException {
        if (!BlobLayout.addressable(coordinate, version)) {
            return List.of();
        }
        List<ConanFile> files = conanFiles(coordinate, version, store);
        List<String> paths = new ArrayList<>(files.size());
        for (ConanFile file : files) {
            paths.add(file.path());
        }
        return paths;
    }

    /** One stored Conan file located by the retroactive-hold discovery walk: its store pointer key and the request path
     *  it serves at. */
    private record ConanFile(String key, String path) {
    }

    /** The bounded page size the retroactive-hold discovery walk streams each subtree level through. */
    private static final int WALK_PAGE = 1000;

    /** One level of the version subtree: a flat container enumerated through the shared bounded primitive,
     *  which is what these six statically nested, fixed-depth loops actually are - a generic subtree walk would buy an
     *  {@code exists} probe per name that none of them needs. This feeds {@code blobKeys}/{@code servedPaths}, so a
     *  level that answered short would be a KEV-listed recipe or package file that keeps serving after its hold: the
     *  entry cap is therefore OFF, and the binding bound is the primitive's step budget (1000 page round-trips,
     *  ~10^6 names per level), which raises a named {@link TraversalException} rather than dropping keys. */
    private static final BoundedChildren LEVEL =
            BoundedChildren.bounded().entries(Integer.MAX_VALUE).page(WALK_PAGE);

    /** Every recipe and package file stored for {@code (name, version)}, across all users, channels and revisions -
     *  the correct retroactive-hold scope (a hold covers every revision of the version). The registry set is
     *  operator-configured (bounded), so a bare {@code store.list("conan")} is right for it; every level beneath the
     *  version root is attacker-publishable, so each is enumerated through the shared bounded {@link #LEVEL} scan,
     *  never {@code list()}ed whole. The tree depth is fixed (user/channel/rrev[/pkg/pid/prev]/files), so this is a
     *  fixed nest of flat enumerations rather than a recursion over an attacker-controlled depth. */
    private static List<ConanFile> conanFiles(String name, String version, ArtifactStore store) throws IOException {
        if (Keys.unsafe(name) || Keys.unsafe(version)) {
            return List.of();
        }
        List<ConanFile> files = new ArrayList<>();
        for (String repo : store.list("conan")) {
            String versionRoot = "conan/" + repo + "/r/" + name + "/" + version;
            LEVEL.scan(store, versionRoot, user -> {
                String userBase = versionRoot + "/" + user;
                LEVEL.scan(store, userBase, channel -> {
                    String recipeBase = userBase + "/" + channel;
                    LEVEL.scan(store, recipeBase, rrev -> {
                        String revBase = recipeBase + "/" + rrev;
                        // Recipe files: <revBase>/files/<file>.
                        LEVEL.scan(store, revBase + "/files", file -> files.add(new ConanFile(
                                revBase + "/files/" + file,
                                recipePath(repo, name, version, user, channel, rrev, file))));
                        // Package files: <revBase>/pkg/<pid>/<prev>/files/<file>.
                        LEVEL.scan(store, revBase + "/pkg", pid -> {
                            String pkgBase = revBase + "/pkg/" + pid;
                            LEVEL.scan(store, pkgBase, prev -> LEVEL.scan(store, pkgBase + "/" + prev + "/files",
                                    file -> files.add(new ConanFile(
                                            pkgBase + "/" + prev + "/files/" + file,
                                            packagePath(repo, name, version, user, channel, rrev, pid, prev, file)))));
                        });
                    });
                });
            });
        }
        return files;
    }

    /** The request path a recipe file serves at, the mirror of the {@code PUT}/{@code GET}
     *  {@code /conan/<repo>/v2/conans/<name>/<version>/<user>/<channel>/revisions/<rrev>/files/<file>} route. */
    private static String recipePath(String repo, String name, String version, String user, String channel,
                                     String rrev, String file) {
        return PREFIX + repo + "/" + CONANS + name + "/" + version + "/" + user + "/" + channel
                + "/revisions/" + rrev + "/files/" + file;
    }

    /** The request path a package file serves at, the mirror of the
     *  {@code .../revisions/<rrev>/packages/<pid>/revisions/<prev>/files/<file>} route. */
    private static String packagePath(String repo, String name, String version, String user, String channel,
                                      String rrev, String pid, String prev, String file) {
        return PREFIX + repo + "/" + CONANS + name + "/" + version + "/" + user + "/" + channel
                + "/revisions/" + rrev + "/packages/" + pid + "/revisions/" + prev + "/files/" + file;
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
        // The capability and authentication handshake a Conan client performs before it reads or uploads.
        if (sub.equals("v2/ping") || sub.equals("v1/ping")) {
            if (!method.equals("GET") && !method.equals("HEAD")) {
                exchange.respond(405);
                return;
            }
            exchange.setResponseHeader("X-Conan-Server-Capabilities", CAPABILITIES);
            exchange.respond(200, -1L).close();
            return;
        }
        if (sub.equals("v2/users/authenticate") || sub.equals("v1/users/authenticate")) {
            // A Conan client exchanges its credentials here for a bearer token it sends on subsequent requests. The
            // real authorization is enforced by the server's security layer around this format, which reads a key
            // out of a bearer token - so the token handed back IS the password the client logged in with (its
            // repository key), and every later request carries that key. Without a Basic login the handshake still
            // completes, with a placeholder the security layer treats as no credential.
            exchange.setResponseHeader("Content-Type", "text/plain");
            exchange.answer(basicPassword(exchange.requestHeader("Authorization"))
                    .orElse("jenesis").getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (sub.equals("v2/users/check_credentials") || sub.equals("v1/users/check_credentials")) {
            exchange.setResponseHeader("Content-Type", "text/plain");
            exchange.answer("anonymous".getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (sub.startsWith(CONANS)) {
            conans(repo, sub.substring(CONANS.length()), exchange, store);
            return;
        }
        exchange.respond(404);
    }

    /** The password of a {@code Basic} credential, which a Conan client presents to the authenticate endpoint. */
    private static Optional<String> basicPassword(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
            return Optional.empty();
        }
        String credential;
        try {
            credential = new String(Base64.getDecoder().decode(authorization.substring(6).strip()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
        int colon = credential.indexOf(':');
        return colon < 0 || colon == credential.length() - 1 ? Optional.empty() : Optional.of(credential.substring(colon + 1));
    }

    /**
     * Route a {@code /v2/conans/<name>/<version>/<user>/<channel>/...} request to the recipe/package index or a file.
     * The reference is always four segments (a missing user/channel is the {@code _} placeholder), then a tail that
     * selects {@code latest}, {@code revisions}, a revision's {@code files} listing or a single file, for the recipe or
     * one of its packages. A file {@code PUT} streams into the CAS and updates the stored index; a file {@code GET}
     * streams it back; an index read streams the stored document.
     */
    private void conans(String repo, String path, FormatExchange exchange, ArtifactStore store) throws IOException {
        String[] t = path.split("/", -1);
        // <name>/<version>/<user>/<channel>/<tail...> - at least the reference plus one tail token.
        if (t.length < 5) {
            exchange.respond(404);
            return;
        }
        String name = t[0], version = t[1], user = t[2], channel = t[3];
        if (Keys.unsafe(name) || Keys.unsafe(version) || Keys.unsafe(user) || Keys.unsafe(channel)) {
            exchange.respond(404);
            return;
        }
        String recipe = recipeBase(repo, name, version, user, channel);
        // recipe latest / revisions: .../<user>/<channel>/latest | .../revisions
        if (t.length == 5 && t[4].equals("latest")) {
            latest(repo, recipe, exchange, store);
        } else if (t.length == 5 && t[4].equals("revisions")) {
            revisions(repo, recipe, exchange, store);
        } else if (t.length >= 6 && t[4].equals("revisions")) {
            recipeRevision(repo, recipe, t, exchange, store);
        } else {
            exchange.respond(404);
        }
    }

    /** Dispatch everything under a recipe revision: its {@code files} listing / a single recipe file, or a package's
     *  {@code latest} / {@code revisions} / {@code files} / a single package file. */
    private void recipeRevision(String repo, String recipe, String[] t, FormatExchange exchange, ArtifactStore store)
            throws IOException {
        String rrev = t[5];
        if (Keys.unsafe(rrev) || reserved(rrev)) {
            exchange.respond(404);
            return;
        }
        String revBase = recipe + "/" + rrev;
        // .../revisions/<rrev>/files            -> recipe files listing
        if (t.length == 7 && t[6].equals("files")) {
            files(repo, revBase, exchange, store);
        } else if (t.length == 8 && t[6].equals("files")) {
            // .../revisions/<rrev>/files/<file> -> recipe file GET/PUT
            file(repo, revBase, revBase + "/files/" + t[7], t[7], exchange, store);
        } else if (t.length >= 8 && t[6].equals("packages")) {
            packages(repo, revBase, t, exchange, store);
        } else {
            exchange.respond(404);
        }
    }

    /** Dispatch a package_id's index and files under a recipe revision. */
    private void packages(String repo, String revBase, String[] t, FormatExchange exchange, ArtifactStore store)
            throws IOException {
        String pid = t[7];
        if (Keys.unsafe(pid) || reserved(pid)) {
            exchange.respond(404);
            return;
        }
        String pkg = revBase + "/pkg/" + pid;
        // .../packages/<pid>/latest | .../packages/<pid>/revisions
        if (t.length == 9 && t[8].equals("latest")) {
            latest(repo, pkg, exchange, store);
        } else if (t.length == 9 && t[8].equals("revisions")) {
            revisions(repo, pkg, exchange, store);
        } else if (t.length >= 10 && t[8].equals("revisions")) {
            String prev = t[9];
            if (Keys.unsafe(prev) || reserved(prev)) {
                exchange.respond(404);
                return;
            }
            String prevBase = pkg + "/" + prev;
            if (t.length == 11 && t[10].equals("files")) {
                files(repo, prevBase, exchange, store);
            } else if (t.length == 12 && t[10].equals("files")) {
                file(repo, prevBase, prevBase + "/files/" + t[11], t[11], exchange, store);
            } else {
                exchange.respond(404);
            }
        } else {
            exchange.respond(404);
        }
    }

    /**
     * The latest revision under {@code parent} (a recipe base or a package_id base): the revision with the greatest
     * stored upload time <b>among the revisions that still have something servable</b>, derived from the stored
     * {@code revisions} document on every write. {@code {"revision": "<rev>", "time": "<iso>"}}, or a {@code 404}
     * when nothing is published there (so a proxy registry can later fill it from upstream) - and equally when a hold
     * has left no revision servable, because this route answers a single revision and so has no empty form to render
     * (the shape Go's {@code @latest} takes).
     */
    private void latest(String repo, String parent, FormatExchange exchange, ArtifactStore store) throws IOException {
        if (!hosted(repo, store)) {
            // A proxy repo needs this local miss so the pull-through streams the authoritative upstream latest
            // revision rather than answering from a locally cached (and possibly no-longer-newest) revision.
            exchange.respond(404);
            return;
        }
        if (!StoredListing.present(store, ConanListings.revisions(parent)) && store.isEmpty(parent)) {
            exchange.respond(404);      // a structural emptiness probe: nothing published here, so there is no index
            return;
        }
        ConanListings listings = new ConanListings(new Blobs(store));
        // latest is derived from the stored revisions on every write; a parent read before its revisions were
        // materialised derives it now, once.
        Optional<StoredListing.Served> served = StoredListing.openDerived(store, ConanListings.latest(parent));
        if (served.isEmpty()) {
            StoredListing.open(store, listings.revisionsSpec(parent)).ifPresent(ConanFormat::closeQuietly);
            served = StoredListing.openDerived(store, ConanListings.latest(parent));
        }
        if (served.isEmpty()) {
            exchange.respond(404);
            return;
        }
        try (StoredListing.Served document = served.get()) {
            if (document.header().size() == 0) {
                exchange.respond(404);
                return;
            }
            exchange.setResponseHeader("Content-Type", "application/json");
            Listings.serve(exchange, document, null);
        }
    }

    /**
     * All revisions under {@code parent} that still have something servable, newest first, as the stored
     * {@code revisions} document: {@code {"revisions": [{"revision": "<rev>", "time": "<iso>"}, ...]}}, or a
     * {@code 404} when nothing is published there.
     *
     * <p><b>Screened, and empty rather than absent when a hold takes everything.</b> The 404 is keyed on the
     * <em>raw</em> revision set, never on the screened one: this route is addressed by the recipe's own name, which
     * the client already had, so answering {@code {"revisions": []}} discloses nothing it did not supply while a 404
     * would assert "no such recipe" - a different fact, and one a client caches. That is the rule PyPI's per-project
     * index, npm's packument, Cargo's sparse index, Conda's repodata and this format's own {@code files} listing all
     * follow; the listing routes that enumerate names a client did NOT supply (PyPI's Simple root, Composer's
     * list.json, CocoaPods' shard) drop the container instead, which is the same rule on the other shape. The probe
     * is paid only until the document exists.
     */
    private void revisions(String repo, String parent, FormatExchange exchange, ArtifactStore store)
            throws IOException {
        if (!hosted(repo, store)) {
            // A proxy repo needs this local miss so the pull-through streams the authoritative upstream revision list
            // (every revision) rather than shadowing it with only the locally cached revisions.
            exchange.respond(404);
            return;
        }
        if (!StoredListing.present(store, ConanListings.revisions(parent)) && store.isEmpty(parent)) {
            exchange.respond(404);      // a structural emptiness probe: nothing published here, so there is no index
            return;
        }
        serveListing(new ConanListings(new Blobs(store)).revisionsSpec(parent), exchange, store);
    }

    /**
     * A revision's file listing as the stored {@code files} document: {@code {"files": {"conanfile.py": {},
     * "conanmanifest.txt": {}, ...}}}, or a {@code 404} when the revision holds no files. A withheld file is not
     * listed - a compliance hold withholds the file's blob (its download 404s), so listing it would disclose a
     * quarantined file a client then cannot fetch - the way OCI screens a held image out of its tags; the write that
     * holds or releases the file re-decides its entry.
     */
    private void files(String repo, String revBase, FormatExchange exchange, ArtifactStore store)
            throws IOException {
        if (!hosted(repo, store)) {
            // A proxy repo needs this local miss so the pull-through streams the authoritative upstream files listing
            // (every file of the revision) rather than shadowing it with only the files cached so far.
            exchange.respond(404);
            return;
        }
        if (!StoredListing.present(store, ConanListings.files(revBase)) && store.isEmpty(revBase + "/files")) {
            exchange.respond(404);      // a structural emptiness probe: the revision holds nothing, so there is no index
            return;
        }
        serveListing(new ConanListings(new Blobs(store)).filesSpec(revBase), exchange, store);
    }

    /** Stream a stored index document, materialising it once when a store from before the layout has none. */
    private static void serveListing(StoredListing.Spec spec, FormatExchange exchange, ArtifactStore store)
            throws IOException {
        Optional<StoredListing.Served> served = StoredListing.open(store, spec);
        if (served.isEmpty()) {
            exchange.respond(404);
            return;
        }
        try (StoredListing.Served document = served.get()) {
            exchange.setResponseHeader("Content-Type", "application/json");
            Listings.serve(exchange, document, null);
        }
    }

    private static void closeQuietly(StoredListing.Served served) {
        try {
            served.close();
        } catch (IOException ignored) {
            // nothing was read from it
        }
    }

    /** A revision, package id or file segment a client may not use: the {@code @}-prefixed names are where the
     *  stored index of a parent lives, beside its raw revisions. Real revisions and package ids are hex hashes. */
    private static boolean reserved(String segment) {
        return segment.startsWith("@");
    }

    /** Serve a stored file ({@code GET}/{@code HEAD}) or stream an upload into the CAS ({@code PUT}). The upload is
     *  content-addressed while it streams, so a large package archive never lands in heap; the upload also stamps its
     *  revision's time so {@link #latest} / {@link #revisions} order correctly. */
    private void file(String repo, String revBase, String fileKey, String filename, FormatExchange exchange,
                      ArtifactStore store) throws IOException {
        if (Keys.unsafe(filename)) {
            exchange.respond(exchange.method().equals("PUT") ? 400 : 404);
            return;
        }
        Blobs blobs = new Blobs(store);
        switch (exchange.method()) {
            case "PUT" -> {
                blobs.write(fileKey, exchange.requestStream());
                stampTime(store, revBase + "/time");
                indexed(revBase, filename, blobs);
                // Stamp the per-registry hosted-publish marker, so a later latest/revisions/files read serves the
                // local index. A pull-through proxy repository (whose files are cached by proxy(), which stamps only
                // the revision time, never this marker) never writes it, so its index reads miss locally and reproxy
                // the authoritative upstream index for an uncached revision rather than shadowing it (the RPM gate).
                markHosted(store, hostedKey(repo));
                exchange.respond(201);
            }
            case "GET", "HEAD" -> {
                Optional<Blobs.Located> located = blobs.locate(fileKey);
                if (located.isEmpty()) {
                    exchange.respond(404);
                    return;
                }
                long size = located.get().size();
                exchange.setResponseHeader("Content-Type", contentType(filename));
                if (exchange.method().equals("HEAD")) {
                    if (size >= 0) {
                        exchange.setResponseHeader("Content-Length", Long.toString(size));
                    }
                    exchange.respond(200, -1L).close();
                    return;
                }
                blobs.serve(located.get(), exchange);
            }
            default -> exchange.respond(405);
        }
    }

    /** A file landed under {@code revBase} (an upload or a proxy fill): its entry joins the revision's stored files
     *  listing and the revision's entry its parent's stored revisions, with {@code latest} derived. */
    private static void indexed(String revBase, String filename, Blobs blobs) throws IOException {
        int slash = revBase.lastIndexOf('/');
        new ConanListings(blobs).refresh(revBase.substring(0, slash), revBase.substring(slash + 1), filename);
    }

    /** Stamp a revision's upload time to now, a small compare-and-set pointer through the store (never a raw file). The
     *  stamp is load-bearing - it drives latest-revision ordering - so a lost compare-and-set re-reads the token and
     *  retries (the shared {@link Retries} policy: a revision's files are uploaded one after the other and, from
     *  several writers, at the same moment, so the stamp contends with its own siblings) rather than discarding the
     *  returned boolean, which would leave a concurrently-uploaded revision unstamped and mis-ordered. */
    private static void stampTime(ArtifactStore store, String key) throws IOException {
        Retries.update(store, key, _ -> Long.toString(System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8));
    }

    private static final byte[] HOSTED = "1".getBytes(StandardCharsets.UTF_8);

    /** The reserved store key of a registry's hosted-publish marker - a child of {@code conan/<repo>}, a sibling of the
     *  {@code r/} recipe tree, so it is never surfaced by any revision/file listing. */
    private static String hostedKey(String repo) {
        return "conan/" + repo + "/hosted";
    }

    /** Whether this registry has ever taken a hosted upload - it then carries {@link #hostedKey}, which a pull-through
     *  proxy never writes (its file cache stamps only the revision time). The {@code latest}/{@code revisions}/{@code
     *  files} index gate keys on it so a proxy registry's index reads always miss locally and reproxy the upstream
     *  index (every revision/file) for an uncached revision rather than shadowing it. */
    private static boolean hosted(String repo, ArtifactStore store) throws IOException {
        return store.readVersioned(hostedKey(repo)).isPresent();
    }

    /** Stamp the hosted-publish marker once, idempotently - a compare-and-set against an absent pointer, so a
     *  concurrent upload's lost race simply means a peer already set it. */
    private static void markHosted(ArtifactStore store, String key) throws IOException {
        if (store.readVersioned(key).isEmpty()) {
            store.writeVersioned(key, HOSTED, null);
        }
    }

    @Override
    public Optional<URI> defaultUpstream() {
        return Optional.of(CONAN_CENTER);
    }

    /**
     * Serve a local {@code v2/conans/...} miss from an upstream Conan server. The request
     * {@code /conan/<repo>/v2/conans/<tail>} maps to {@code <upstream>/v2/conans/<tail>} - the local registry name is a
     * deployment alias, so it is stripped and the rest of the v2 path maps through unchanged. An immutable revision file
     * ({@code .../revisions/<rrev>/files/<file>} or its package equivalent) is fetched once, streamed straight into the
     * content-addressed store ({@link ProxyRelay#fill}, never buffered) under the same key
     * {@link #file} serves from, its revision time stamped, and then served locally by re-dispatching through
     * {@link #handle} - so a later read is a local hit that never touches the upstream. Any other read (the mutable
     * {@code latest} / {@code revisions} / {@code files} index or a {@code search}) is streamed through fresh on every
     * read, never cached and never rewritten (these documents carry no download URLs). The {@code ping} and
     * authentication handshake are answered locally and so never miss to here. Returns {@code false} - letting the local
     * {@code 404} stand - for a non-{@code v2/conans} path, a transport failure or an upstream miss.
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
        if (!sub.startsWith(CONANS)) {
            return false;
        }
        String root = upstream.toString();
        if (root.endsWith("/")) {
            root = root.substring(0, root.length() - 1);
        }
        URI target = URI.create(root + "/" + sub);
        FileRef file = fileRef(repo, sub.substring(CONANS.length()));
        if (file != null) {
            // Point-integrity: the revision's conanmanifest.txt lists each file's MD5, so read that sibling and verify
            // the streamed file against it, refusing a mismatch - the checksum parity the Maven proxy leg has. The
            // manifest is a SEPARATE fetch from the file below, so a manifest this repository could not read is not
            // "this revision declares no MD5 for the file" and must not become an unverified fill.
            String name = sub.substring(sub.lastIndexOf('/') + 1);
            ProxyRelay.Declared expected = manifestChecksum(target, name, fetcher);
            if (!expected.readable()) {
                return ProxyRelay.unverifiable(target, expected);
            }
            // An immutable, revision-pinned file: fetch once, cache into the CAS, then serve locally.
            try (ProxyFormat.Download download = fetcher.download(target, Map.of()).orElse(null)) {
                if (download == null || download.status() != 200) {
                    return false;
                }
                if (!ProxyRelay.fill(new Blobs(store), file.key(), target, download.body(), expected)) {
                    return false;
                }
            }
            stampTime(store, file.timeKey());
            indexed(file.revBase(), file.filename(), new Blobs(store));
            handle(exchange, store);
            return true;
        }
        // A mutable index (latest / revisions / files listing / search): stream fresh, never cache, no rewrite. Forward
        // the client's conditional-request validators so a 304-capable client's revalidation reaches the upstream, and
        // relay the upstream's validators back so its next read can revalidate rather than re-streaming the index.
        // ENUMERATION: every one of these shapes answers "what exists" - which recipe revisions, which package ids,
        // which files a revision carries - so an absent one is an answer a `conan install` resolves against, not a
        // "not cached here". The verdict is ProxyRelay's; this leg keeps its own loop only for the HEAD short-circuit
        // and the upstream Content-Type below, which streamFresh deliberately does not fold in.
        try (ProxyFormat.Download download =
                     fetcher.download(target, ProxyRelay.conditionalHeaders(exchange)).orElse(null)) {
            if (download == null) {
                return ProxyRelay.unanswered(target, exchange, ProxyRelay.Document.ENUMERATION,
                        "the upstream could not be reached");
            }
            if (download.status() == 304) {
                ProxyRelay.relayValidators(download, exchange);
                exchange.respond(304);
                return true;
            }
            if (ProxyRelay.upstreamMiss(download.status())) {
                return false;
            }
            if (download.status() != 200) {
                return ProxyRelay.unanswered(target, exchange, ProxyRelay.Document.ENUMERATION,
                        "the upstream answered " + download.status());
            }
            String contentType = download.header("Content-Type");
            exchange.setResponseHeader("Content-Type", contentType != null ? contentType : "application/json");
            ProxyRelay.relayValidators(download, exchange);
            if (exchange.method().equals("HEAD")) {
                exchange.respond(200, -1L).close();
                return true;
            }
            try (OutputStream out = exchange.respond(200, ProxyRelay.length(download.header("Content-Length")))) {
                download.body().transferTo(out);
            }
        }
        return true;
    }

    /**
     * The store key and time-pointer key of a proxied immutable revision file, or {@code null} when {@code tail} (the
     * part after {@code v2/conans/}) is instead an index a proxy must stream fresh. It recognises exactly the two
     * file-download shapes {@link #describe} does - a recipe file {@code .../revisions/<rrev>/files/<file>} and a
     * package file {@code .../packages/<pid>/revisions/<prev>/files/<file>} - and computes the same store keys
     * {@link #file} serves from, so a cached blob is a local hit on the next read. Every path segment is
     * traversal-guarded before it becomes a store key.
     */
    private static FileRef fileRef(String repo, String tail) {
        String[] t = tail.split("/", -1);
        boolean recipeFile = t.length == 8 && t[4].equals("revisions") && t[6].equals("files");
        boolean packageFile = t.length == 12 && t[4].equals("revisions") && t[6].equals("packages")
                && t[8].equals("revisions") && t[10].equals("files");
        if (!recipeFile && !packageFile) {
            return null;
        }
        for (String segment : t) {
            if (Keys.unsafe(segment)) {
                return null;
            }
        }
        String recipe = recipeBase(repo, t[0], t[1], t[2], t[3]);
        if (recipeFile) {
            return reserved(t[5]) ? null : new FileRef(recipe, t[5], t[7]);
        }
        if (reserved(t[5]) || reserved(t[7]) || reserved(t[9])) {
            return null;
        }
        return new FileRef(recipe + "/" + t[5] + "/pkg/" + t[7], t[9], t[11]);
    }

    /** The stored file a request path names - the same two shapes {@link #describe} recognises - or {@code null}
     *  when the path is an index or not this format's. */
    static FileRef locate(String path) {
        if (!path.startsWith(PREFIX)) {
            return null;
        }
        String rest = path.substring(PREFIX.length());
        int slash = rest.indexOf('/');
        if (slash < 0 || !rest.substring(slash + 1).startsWith(CONANS)) {
            return null;
        }
        return fileRef(rest.substring(0, slash), rest.substring(slash + 1 + CONANS.length()));
    }

    /** One stored revision file: its parent (a recipe reference or a package id base), its revision and its name -
     *  from which the CAS key it serves from and the revision-time pointer an upload stamps follow. */
    record FileRef(String parent, String revision, String filename) {

        String revBase() {
            return parent + "/" + revision;
        }

        String key() {
            return revBase() + "/files/" + filename;
        }

        String timeKey() {
            return revBase() + "/time";
        }
    }

    /** The MD5 the revision's {@code conanmanifest.txt} records for {@code file} (a Conan manifest is a timestamp line
     *  followed by {@code <path>: <md5>} lines, the {@code md5} being of that file's bytes), read from the sibling in
     *  the same {@code files/} directory so a proxied revision file can be verified against it. The manifest is a small
     *  bounded metadata document, fetched buffered and only on a file miss.
     *
     *  <p>{@link ProxyRelay.Declared#NONE} - cache without a point check, as Maven serves a jar whose {@code .sha1}
     *  sibling is missing - for the manifest itself (it carries no self-checksum) and when the manifest
     *  <em>answered</em> and declares nothing: a {@code 404}/{@code 410}, or a manifest listing no 16-byte {@code md5}
     *  for this file. {@linkplain ProxyRelay.Declared#unreadable Unreadable} when the manifest could not be read at
     *  all, which is not the revision declaring anything. */
    private static ProxyRelay.Declared manifestChecksum(URI target, String file, ProxyFormat.Fetcher fetcher)
            throws IOException {
        if (file.equals("conanmanifest.txt")) {
            return ProxyRelay.Declared.NONE;
        }
        String url = target.toString();
        int slash = url.lastIndexOf('/');
        if (slash < 0) {
            return ProxyRelay.Declared.NONE;
        }
        ProxyRelay.Sidecar sidecar = ProxyRelay.declaring(fetcher,
                URI.create(url.substring(0, slash + 1) + "conanmanifest.txt"), Map.of());
        if (!sidecar.answered()) {
            return sidecar.verdict();
        }
        for (String line : new String(sidecar.document().body(), StandardCharsets.UTF_8).split("\n")) {
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            if (line.substring(0, colon).strip().equals(file)) {
                byte[] md5 = hex(line.substring(colon + 1).strip(), 16);
                return md5 == null ? ProxyRelay.Declared.NONE : ProxyRelay.Declared.of("MD5", md5);
            }
        }
        return ProxyRelay.Declared.NONE;
    }

    /** Decode a hex digest of exactly {@code bytes} bytes to its raw bytes, or {@code null} when absent or malformed. */
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

    @Override
    public Optional<ArtifactDescriptor> describe(String path) {
        if (!path.startsWith(PREFIX)) {
            return Optional.empty();
        }
        String rest = path.substring(PREFIX.length());
        int slash = rest.indexOf('/');
        if (slash < 0 || !rest.substring(slash + 1).startsWith(CONANS)) {
            return Optional.empty();
        }
        String[] t = rest.substring(slash + 1 + CONANS.length()).split("/", -1);
        // A file download path ends .../files/<file>; a listing/index path carries no artifact to describe.
        String name = t[0];
        String version = t.length > 1 ? t[1] : null;
        boolean recipeFile = t.length == 8 && t[4].equals("revisions") && t[6].equals("files");
        boolean packageFile = t.length == 12 && t[4].equals("revisions") && t[6].equals("packages")
                && t[8].equals("revisions") && t[10].equals("files");
        if (!recipeFile && !packageFile) {
            return Optional.empty();
        }
        if (Keys.unsafe(name) || version == null || Keys.unsafe(version)) {
            return Optional.of(ArtifactDescriptor.at(ECOSYSTEM, path));
        }
        String filename = t[t.length - 1];
        return Optional.of(new ArtifactDescriptor(ECOSYSTEM, name, version, path,
                contentType(filename), prerelease(version), null, -1L));
    }

    /**
     * The recipe or package version a stored Conan pointer serves - the backwards direction the inventory back-fill
     * rebuilds a lost {@code published/} row from.
     *
     * <p>Conan is the easy shape: the pair is two <em>path segments</em> near the root of a tree whose depth is
     * fixed. A recipe file is {@code conan/<repo>/r/<name>/<version>/<user>/<channel>/<rrev>/files/<file>} and a
     * package file adds {@code pkg/<pid>/<prev>/} before its own {@code files/}, so a name and a version are read
     * at fixed indices and nothing is split on a character either may contain. Both are composed one segment at a
     * time by the same guard {@code blobKeys} resolves through, so a multi-segment value cannot arrive here.
     *
     * <p>The two shapes are distinguished by length and by the literal {@code pkg} rather than by searching for
     * {@code files}, because the markers this format keeps beside a revision - {@code time}, {@code commit},
     * {@code latest} - live outside {@code files/} and must not be claimed: they are not the version's content and
     * a row rebuilt from one would age by a marker's key rather than by an artifact's.
     */
    @Override
    public Optional<ArtifactDescriptor> describePointer(String key) {
        String[] parts = key.split("/", -1);
        boolean recipeFile = parts.length == 10 && parts[8].equals("files");
        boolean packageFile = parts.length == 13 && parts[8].equals("pkg") && parts[11].equals("files");
        if (!parts[0].equals("conan") || parts.length < 3 || !parts[2].equals("r")
                || (!recipeFile && !packageFile)) {
            return Optional.empty();
        }
        String name = parts[3], version = parts[4];
        if (!BlobLayout.addressable(name, version)) {
            return Optional.empty();
        }
        return Optional.of(new ArtifactDescriptor(ECOSYSTEM, name, version, key,
                contentType(parts[parts.length - 1]), prerelease(version), null, 0L));
    }

    @Override
    public List<String> paths(String coordinate, String version, ArtifactStore store) {
        // Conan file pointers live in the shared Blobs namespace (like npm/pypi/go/rpm/cargo/conda/composer/cocoapods),
        // not the Publication namespace coordinate-based eviction walks, so nothing is enumerable from the coordinate.
        return List.of();
    }

    /** The content type for a Conan file by extension - archives are gzip tarballs, the rest are text metadata. */
    private static String contentType(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".tgz") || lower.endsWith(".gz")) {
            return "application/gzip";
        }
        if (lower.endsWith(".txt") || lower.endsWith(".py")) {
            return "text/plain";
        }
        if (lower.endsWith(".yml") || lower.endsWith(".yaml")) {
            return "application/yaml";
        }
        return "application/octet-stream";
    }

    /** Whether a Conan version denotes a prerelease (a semantic-version {@code -} pre-release suffix). */
    private static boolean prerelease(String version) {
        return version.indexOf('-') >= 0;
    }

    private static String recipeBase(String repo, String name, String version, String user, String channel) {
        return "conan/" + repo + "/r/" + name + "/" + version + "/" + user + "/" + channel;
    }

    /**
     * A path segment ({@code name}, {@code version}, {@code user}, {@code channel}, {@code rrev}, {@code package_id},
     * {@code prev}, {@code filename}) becomes store-key segments, so a value that is empty, carries a path separator or
     * control character, or is a {@code .}/{@code ..} traversal segment could steer a write or read outside the
     * package's key space and is refused. Real Conan references are identifiers, revisions are hex hashes, filenames are
     * flat names, and a missing user/channel is the {@code _} placeholder - so no legitimate value is rejected.
     */

    private static void respondJson(FormatExchange exchange, JsonNode node) throws IOException {
        exchange.setResponseHeader("Content-Type", "application/json");
        exchange.answer(MAPPER.writeValueAsBytes(node));
    }

    /** The migration-import capability, delegated to the layout-only {@link ConanImporter} - the format IS the
     *  discovered importer now (an {@code instanceof} capability), and the importer class stays as its delegate. */
    private final ConanImporter importer = new ConanImporter();

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

    /**
     * Every revision of the version is uploaded as {@code conan upload} uploads it through the v2 API: each recipe
     * file of a revision, then each file of every package built from it, put at the path it is served from - which
     * names the revisions the client computed, so the target holds the same revisions rather than new ones.
     */
    @Override
    public Exported export(ArtifactStore repository, String coordinate, String version, ExportTarget target)
            throws IOException {
        if (!BlobLayout.addressable(coordinate, version)) {
            return Exported.WITHHELD;
        }
        List<BlobExport.Pair> pairs = new ArrayList<>();
        for (ConanFile file : conanFiles(coordinate, version, repository)) {
            pairs.add(new BlobExport.Pair(file.key(), file.path().substring(PREFIX.length())));
        }
        return BlobExport.put(repository, pairs, target);
    }
}
