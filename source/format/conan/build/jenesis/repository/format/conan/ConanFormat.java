package build.jenesis.repository.format.conan;

import module java.base;
import module tools.jackson.databind;

import build.jenesis.repository.format.Listings;
import build.jenesis.repository.blobs.HostedMarker;
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
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.walk.BoundedChildren;
import build.jenesis.repository.walk.TraversalException;

/**
 * The Conan (C/C++) registry format (the Conan v2 REST protocol): {@code conan upload} and {@code conan install} over
 * the shared store, under {@code /conan/...}, the first segment a registry. {@code GET /conan/<repo>/v2/ping}
 * advertises {@code X-Conan-Server-Capabilities: revisions}, so a Conan 2 client uses the revisions API; a recipe file
 * is pushed with {@code PUT /conan/<repo>/v2/conans/<name>/<version>/<user>/<channel>/revisions/<rrev>/files/<file>} (a
 * package file under {@code .../packages/<package_id>/revisions/<prev>/files/<file>}) and served from the same path. A
 * reference without user and channel uses Conan's {@code _} placeholder.
 *
 * <p><b>Revision-addressed, indexed on write.</b> The client computes the recipe and package revisions itself and
 * uploads each file to its revision's path, so the coordinate is in the path and no archive is opened. The index a
 * client reads - a recipe's {@code latest} and {@code revisions}, a revision's {@code files}, and the same for a
 * package id - is a {@linkplain ConanListings stored listing} each upload updates by one entry. {@code latest} is the
 * most recently uploaded, so each upload stamps its revision's time by compare-and-set.
 *
 * <p><b>Streaming publish.</b> A file streams through {@link Blobs#write(String, InputStream)} into the
 * content-addressed store, so a {@code conan_package.tgz} of any size never lands in heap.
 *
 * <p>The ecosystem is {@code "Conan"}, and {@link #describe} maps a file path to its {@code <name>} coordinate and
 * version. OSV has no Conan feed, so vulnerability screening finds nothing while the coordinate drives licence and
 * malicious-package screening. Pointers live in the shared {@code Blobs} namespace, so {@link #paths} is empty and a
 * coordinate is reached through {@link #blobKeys} and {@link #servedPaths}.
 *
 * <p><b>Pull-through proxy.</b> A local {@code v2/conans/...} miss is served from an upstream Conan server, the alias
 * stripped and the path mapped through. A revision file is immutable: it streams into the store
 * ({@link ProxyRelay#fill}), stamps its revision's time and serves locally. The index ({@code latest},
 * {@code revisions}, {@code files}, {@code search}) is streamed fresh and carries no download URLs.
 * {@link #defaultUpstream()} is ConanCenter.
 */
public final class ConanFormat implements RepositoryFormat, ArtifactLayout, ProxyLeg, BlobLayout,
        RepositoryImporter.Delegating, RepositoryExporter {

    /** The ecosystem name this format's artifacts report, distinct from {@link #name()}, the routing id. */
    public static final String ECOSYSTEM = "Conan";

    /** Shared with the listing codec beside this, so one mapper configuration serves both. */
    static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String PREFIX = "/conan/";
    private static final String CONANS = "v2/conans/";

    /** The canonical public Conan registry mirrored when a deployment names no upstream, Conan 2's
     *  {@code conancenter}. */
    private static final URI CONAN_CENTER = URI.create("https://center2.conan.io");

    /** The capabilities a client reads from {@code /v2/ping}: {@code revisions} is required for the revisions API this
     *  format speaks; {@code complex_search} advertises pattern search. */
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
            // A traversal-shaped coordinate or version maps nowhere, since an eviction deletes these keys; judged part
            // by part.
            return List.of();
        }
        // Files are addressed by client-computed revisions and pushed names, so they are found by walking the version's
        // own subtree conan/<repo>/r/<name>/<version>: users, channels, recipe revisions and their files, and each
        // revision's pkg/<pid>/<prev>/files. Each pointer's body is the blob hash, from which the withhold set derives;
        // a hold marks those hashes and eviction deletes these keys. Every level is publisher-grown, so each is paged;
        // the depth is fixed.
        List<ConanFile> files = conanFiles(coordinate, version, store);
        List<String> keys = new ArrayList<>(files.size());
        for (ConanFile file : files) {
            keys.add(file.key());
        }
        return keys;
    }

    /** The request paths this version's files serve at - a recipe file at
     *  {@code /conan/<repo>/v2/conans/<name>/<version>/<user>/<channel>/revisions/<rrev>/files/<file>}, a package file
     *  at {@code .../revisions/<rrev>/packages/<pid>/revisions/<prev>/files/<file>} - where a retroactive hold links
     *  its {@code /quarantine} handles. Only pointers are read. */
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

    /** One stored file found by the hold discovery walk: its pointer key and the path it serves at. */
    private record ConanFile(String key, String path) {
    }

    /** The page size the hold discovery walk lists each level in. */
    private static final int WALK_PAGE = 1000;

    /** One level of the version subtree, a flat container: the walk is a fixed nest of these. It feeds {@code blobKeys}
     *  and {@code servedPaths}, where a short level would leave a held file serving, so the entry cap is off and the
     *  step budget (1000 pages) raises a {@link TraversalException} rather than dropping keys. */
    private static final BoundedChildren LEVEL =
            BoundedChildren.bounded().entries(Integer.MAX_VALUE).page(WALK_PAGE);

    /** Every recipe and package file stored for {@code (name, version)}, across all users, channels and revisions,
     *  since a hold covers every revision. The registry set is operator-configured, so a plain list suits it; every
     *  level below the version is publisher-grown and scanned through the bounded {@link #LEVEL}, at a fixed depth. */
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

    /** The request path a recipe file serves at, its {@code PUT}/{@code GET} route. */
    private static String recipePath(String repo, String name, String version, String user, String channel,
                                     String rrev, String file) {
        return PREFIX + repo + "/" + CONANS + name + "/" + version + "/" + user + "/" + channel
                + "/revisions/" + rrev + "/files/" + file;
    }

    /** The request path a package file serves at, its {@code PUT}/{@code GET} route. */
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
        // The capability and authentication handshake a client performs first.
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
            // A client exchanges its credentials for a bearer token. The security layer around this format reads a key
            // from a bearer token, so the token is the password the client logged in with, its repository key. Without
            // a Basic login the handshake completes with a placeholder read as no credential.
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

    /** Route a {@code /v2/conans/<name>/<version>/<user>/<channel>/...} request: the reference is always four segments,
     *  then a tail selecting {@code latest}, {@code revisions}, a revision's {@code files} or one file, of the recipe
     *  or a package. A file {@code PUT} streams into the store and updates the index, a {@code GET} streams it back, an
     *  index read streams the stored document. */
    private void conans(String repo, String path, FormatExchange exchange, ArtifactStore store) throws IOException {
        String[] t = path.split("/", -1);
        // <name>/<version>/<user>/<channel>/<tail...>: the reference and at least one tail token.
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

    /** Dispatch everything under a recipe revision: its {@code files} or one recipe file, or a package's
     *  {@code latest}, {@code revisions}, {@code files} or one package file. */
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

    /** The latest revision under {@code parent} (a recipe or a package id): the one with the greatest upload time among
     *  those still servable, derived from the stored {@code revisions} on every write, as
     *  {@code {"revision": "<rev>", "time": "<iso>"}}. A {@code 404} when nothing is published, which a proxy fills
     *  from upstream, and when a hold left nothing servable, since one revision has no empty form. */
    private void latest(String repo, String parent, FormatExchange exchange, ArtifactStore store) throws IOException {
        if (!hosted(repo, store)) {
            // A proxy registry misses locally, so pull-through answers the upstream's latest rather than a cached older
            // one.
            exchange.respond(404);
            return;
        }
        if (!StoredListing.present(store, ConanListings.revisions(parent)) && store.isEmpty(parent)) {
            exchange.respond(404);      // a structural emptiness probe: nothing published here, so there is no index
            return;
        }
        ConanListings listings = new ConanListings(new Blobs(store));
        // latest derives from the stored revisions; a parent read before they exist derives it once.
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

    /** Every revision under {@code parent} still servable, newest first, as the stored {@code revisions} document
     *  {@code {"revisions": [{"revision": "<rev>", "time": "<iso>"}, ...]}}, or a {@code 404} when nothing is
     *  published. The 404 is keyed on the raw revision set: the route is addressed by the recipe's own name, so an
     *  empty list discloses nothing the client did not supply, while a 404 would claim "no such recipe". The probe is
     *  paid only until the document exists. */
    private void revisions(String repo, String parent, FormatExchange exchange, ArtifactStore store)
            throws IOException {
        if (!hosted(repo, store)) {
            // A proxy registry misses locally, so pull-through answers the upstream's full revision list.
            exchange.respond(404);
            return;
        }
        if (!StoredListing.present(store, ConanListings.revisions(parent)) && store.isEmpty(parent)) {
            exchange.respond(404);      // a structural emptiness probe: nothing published here, so there is no index
            return;
        }
        serveListing(new ConanListings(new Blobs(store)).revisionsSpec(parent), exchange, store);
    }

    /** A revision's file listing as the stored {@code files} document, {@code {"files": {"conanfile.py": {}, ...}}}, or
     *  a {@code 404} when the revision holds no files. A withheld file is not listed, since a client could not fetch
     *  it. */
    private void files(String repo, String revBase, FormatExchange exchange, ArtifactStore store)
            throws IOException {
        if (!hosted(repo, store)) {
            // A proxy registry misses locally, so pull-through answers the upstream's full listing.
            exchange.respond(404);
            return;
        }
        if (!StoredListing.present(store, ConanListings.files(revBase)) && store.isEmpty(revBase + "/files")) {
            exchange.respond(404);      // a structural emptiness probe: the revision holds nothing, so there is no index
            return;
        }
        serveListing(new ConanListings(new Blobs(store)).filesSpec(revBase), exchange, store);
    }

    /** Stream a stored index document, materialising it once when the store has none. */
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
            // Nothing was read from it.
        }
    }

    /** A segment a client may not use: {@code @}-prefixed names hold a parent's stored index beside its raw revisions.
     *  Real revisions and package ids are hex hashes. */
    private static boolean reserved(String segment) {
        return segment.startsWith("@");
    }

    /** Serve a stored file ({@code GET}/{@code HEAD}) or stream an upload into the store ({@code PUT}), the upload
     *  stamping its revision's time so {@link #latest} and {@link #revisions} order correctly. */
    private void file(String repo, String revBase, String fileKey, String filename, FormatExchange exchange,
                      ArtifactStore store) throws IOException {
        if (Keys.unsafe(filename)) {
            exchange.respond(exchange.method().equals("PUT") ? 400 : 404);
            return;
        }
        Blobs blobs = new Blobs(store);
        switch (exchange.method()) {
            case "PUT" -> {
                // A revision is named for its content, so other bytes under it are refused rather than rewriting what a
                // lock file pins.
                String hash = blobs.store(exchange.requestStream());
                try {
                    blobs.linkRelease(fileKey, hash, -1L);
                } catch (Publication.RepublishConflict taken) {
                    exchange.respond(409, Blobs.alreadyPublished(revBase + "/" + filename));
                    return;
                }
                stampTime(store, revBase + "/time");
                indexed(revBase, filename, blobs);
                // The per-registry hosted marker switches on the local index; a proxy registry never writes it, so its
                // index reads fall through to the upstream's.
                HostedMarker.mark(store, hostedKey(repo));
                exchange.respond(201);
            }
            case "GET", "HEAD" -> {
                blobs.answer(fileKey, exchange, contentType(filename));
            }
            default -> exchange.respond(405);
        }
    }

    /** A file landed under {@code revBase}, by upload or proxy fill: its entry joins the revision's files and the
     *  revision's entry its parent's revisions, with {@code latest} derived. */
    private static void indexed(String revBase, String filename, Blobs blobs) throws IOException {
        int slash = revBase.lastIndexOf('/');
        new ConanListings(blobs).refresh(revBase.substring(0, slash), revBase.substring(slash + 1), filename);
    }

    /** Stamp a revision's upload time by compare-and-set through {@link Retries}: the stamp orders {@code latest}, and
     *  a revision's files arrive one after another and from several writers, so the stamp contends with its siblings
     *  and a lost race is retried. */
    private static void stampTime(ArtifactStore store, String key) throws IOException {
        Retries.update(store, key, _ -> Long.toString(System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8));
    }

    /** A registry's hosted marker, beside the {@code r/} recipe tree, so no listing surfaces it. */
    private static String hostedKey(String repo) {
        return "conan/" + repo + "/hosted";
    }

    /** Whether this registry has taken a hosted upload. The index gate keys on it, so a proxy registry's index reads
     *  relay the upstream's. */
    private static boolean hosted(String repo, ArtifactStore store) throws IOException {
        return store.readVersioned(hostedKey(repo)).isPresent();
    }

    @Override
    public Optional<URI> defaultUpstream() {
        return Optional.of(CONAN_CENTER);
    }

    /** Serve a local {@code v2/conans/...} miss from an upstream Conan server, {@code /conan/<repo>/v2/conans/<tail>}
     *  mapping to {@code <upstream>/v2/conans/<tail>}. A revision file is fetched once into the store under the key
     *  {@link #file} serves from, its revision time stamped, and served by re-dispatching through {@link #handle}. Any
     *  other read - the index or a {@code search} - is streamed fresh, never cached or rewritten. {@code ping} and
     *  authentication answer locally. {@code false} lets the local {@code 404} stand. */
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
            // An immutable, revision-pinned file: cached once as the upstream serves it, then served locally. The
            // revision's conanmanifest.txt, which lists each file's MD5, is the publisher's and is relayed like any
            // other file for the conan client to check; it is never held against the bytes here.
            try (ProxyFormat.Download download = fetcher.download(target, Map.of()).orElse(null)) {
                if (download == null || download.status() != 200) {
                    return false;
                }
                if (!ProxyRelay.fill(new Blobs(store), file.key(), target, download.body(), ProxyRelay.Declared.NONE)) {
                    return false;
                }
            }
            stampTime(store, file.timeKey());
            indexed(file.revBase(), file.filename(), new Blobs(store));
            handle(exchange, store);
            return true;
        }
        // The index is streamed fresh with validators forwarded both ways. Every shape answers what exists, an
        // ENUMERATION a conan install resolves against; the loop is this leg's own for the HEAD short-circuit and the
        // upstream Content-Type.
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

    /** The store and time-pointer keys of a proxied revision file, or {@code null} when {@code tail} is an index to
     *  stream fresh. The two file shapes {@link #describe} recognises, keyed as {@link #file} serves them, every
     *  segment traversal-guarded. */
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

    /** The stored file a request path names, or {@code null} for an index or another format's path. */
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

    /** One stored revision file: its parent, its revision and its name, from which its key and time pointer follow. */
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

    /** A recipe or package file is served from the pointer the version's files pair with its path. */
    @Override
    public Optional<String> servingKey(String requestPath, ArtifactStore store) throws IOException {
        Optional<ArtifactDescriptor> described = describedVersion(requestPath);
        if (described.isEmpty()) {
            return Optional.empty();
        }
        for (ConanFile file : conanFiles(described.get().coordinate(), described.get().version(), store)) {
            if (file.path().equals(requestPath)) {
                return Optional.of(file.key());
            }
        }
        return Optional.empty();
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
        // A download path ends .../files/<file>; an index path describes nothing.
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

    /** The recipe or package version a stored Conan pointer serves, from which the inventory back-fill rebuilds a lost
     *  {@code published} record. Name and version are segments at fixed indices of a fixed-depth tree - a recipe file
     *  is {@code conan/<repo>/r/<name>/<version>/<user>/<channel>/<rrev>/files/<file>}, a package file adds
     *  {@code pkg/<pid>/<prev>/} - so nothing is split. The shapes are told apart by length and the literal
     *  {@code pkg}, so the {@code time}, {@code commit} and {@code latest} markers outside {@code files/} are never
     *  claimed. */
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
        // Pointers live in the shared Blobs namespace, so the coordinate enumerates nothing in publish/.
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

    /** Answer a JSON document. */

    private static void respondJson(FormatExchange exchange, JsonNode node) throws IOException {
        exchange.setResponseHeader("Content-Type", "application/json");
        exchange.answer(MAPPER.writeValueAsBytes(node));
    }

    /** The migration-import capability, delegated to {@link ConanImporter}. */
    private final ConanImporter importer = new ConanImporter();

    @Override
    public RepositoryImporter importer() {
        return importer;
    }

    /** Every revision of the version is uploaded as {@code conan upload} does through the v2 API - each recipe file,
     *  then each file of every package - at the path it is served from, which names the client's revisions, so the
     *  target holds the same revisions. */
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
