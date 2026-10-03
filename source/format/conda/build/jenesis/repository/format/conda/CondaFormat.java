package build.jenesis.repository.format.conda;

import module java.base;
import module org.apache.commons.compress;
import module tools.jackson.databind;

import io.airlift.compress.v3.zstd.ZstdInputStream;
import build.jenesis.repository.format.LifecycleMark;
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
import build.jenesis.repository.format.lifecycle.Lifecycle;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.store.ArchiveInflation;
import build.jenesis.repository.store.ArchiveWalk;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.StoredListing;
import build.jenesis.repository.store.ServableNames;
import build.jenesis.repository.walk.BoundedChildren;
import build.jenesis.repository.walk.ScreenedNames;
import build.jenesis.repository.walk.Traversal;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import build.jenesis.repository.store.OwnerOnly;

/**
 * The Conda channel format: {@code conda install} and {@code conda create} over the shared store, under
 * {@code /conda/...}, the first segment a channel and the second a platform {@code subdir}. A package is pushed with
 * {@code PUT /conda/<repo>/<subdir>/<name>-<version>-<build>.conda} or the legacy {@code .tar.bz2}, and downloaded from
 * the same path; the per-subdir {@code repodata.json} (and {@code .bz2}) is a stored listing the publish maintains.
 *
 * <p><b>Streaming publish.</b> The archive streams through {@link ArtifactStore#writeBlob} into the content-addressed
 * store, and the SHA-256 returned is both the pointer's hash and the index's {@code sha256}. The metadata lives in an
 * {@code info/index.json} inside the archive, so the stored blob is reopened ({@link ArtifactStore#open}) and only that
 * file read: a {@code .tar.bz2} is a bzip2 tar, a {@code .conda} a zip whose {@code info-*.tar.zst} member is a
 * Zstandard tar. The record is stored per package and joined into the stored {@code repodata.json}.
 *
 * <p>The record carries {@code sha256} and {@code size} but no {@code md5}: conda verifies against {@code sha256} when
 * present, so a second read of the blob for an md5 is avoided.
 *
 * <p><b>Pull-through proxy.</b> A local miss is served from an upstream channel, {@code /conda/<repo>/<subdir>/<file>}
 * mapping to {@code <upstream>/<subdir>/<file>}. A package is immutable, streamed into the store and cached; the index
 * ({@code repodata.json}, its compressed forms, {@code current_repodata.json}) is streamed fresh and needs no rewrite,
 * locations being bare filenames. Conda has no canonical upstream, so {@link #defaultUpstream()} is empty, and an empty
 * subdir's local {@code repodata.json} is a {@code 404} so pull-through reaches the upstream's.
 *
 * <p>The ecosystem is {@code "conda"}, and {@link #describe} maps a package path to {@code name}/{@code version} from
 * the filename, split from the right since a conda version has no {@code -}. Pointers live in the shared {@code Blobs}
 * namespace, so {@link #paths} is empty and a coordinate is reached through {@link #blobKeys} and {@link #servedPaths}.
 */
public final class CondaFormat implements RepositoryFormat, ArtifactLayout, ProxyLeg, BlobLayout,
        RepositoryImporter.Delegating, RepositoryExporter {

    /** The ecosystem name this format's artifacts report, distinct from {@link #name()}, the routing id. */
    public static final String ECOSYSTEM = "conda";

    static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String PREFIX = "/conda/";
    private static final String CONDA_EXT = ".conda";
    private static final String TARBZ2_EXT = ".tar.bz2";
    private static final String REPODATA = "repodata.json";
    private static final String REPODATA_BZ2 = "repodata.json.bz2";

    /** How far the legacy {@code .tar.bz2}'s info scan may inflate, as a multiple of the stored compressed size: its
     *  {@code info/index.json} may follow a large payload, so the flat walk tier would refuse valid packages, while a
     *  bzip2 bomb is stopped at {@code compressed * ratio}. The ratio is conda's; the floor and the {@code index.json}
     *  read are the shared archive bounds (RepositoryFormat clause 15). */
    private static final long MAX_INFO_INFLATION_RATIO = 100L;

    @Override
    public String name() {
        return "conda";
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
    public List<String> blobRoots() {
        return List.of("conda");
    }

    @Override
    public List<String> blobKeys(String coordinate, String version, ArtifactStore store) throws IOException {
        if (!BlobLayout.addressable(coordinate, version)) {
            // A traversal-shaped coordinate or version maps nowhere, since an eviction deletes these keys; judged part
            // by part.
            return List.of();
        }
        // A package is keyed on <name>-<version>-<build>.<ext> under <repo>/<subdir>; build and subdir are not
        // derivable, so every channel's subdirs are walked for pointers whose filename matches, collecting every build
        // of the version. Each pointer's body is the blob hash, from which the withhold set derives; a hold marks those
        // hashes and eviction deletes these keys. The pkgs listing is publisher-grown, so it is paged.
        List<Coordinate> kept = keptPackages(coordinate, version, store);
        List<String> keys = new ArrayList<>(kept.size());
        for (Coordinate found : kept) {
            keys.add(packageKey(found.repo(), found.subdir(), found.file()));
        }
        return keys;
    }

    /** The request paths this version's packages serve at ({@code /conda/<repo>/<subdir>/<file>}), one per build, where
     *  a retroactive hold links its {@code /quarantine} handles. The repodata record is not a download. Only pointers
     *  are read. */
    @Override
    public List<String> servedPaths(String coordinate, String version, ArtifactStore store) throws IOException {
        if (!BlobLayout.addressable(coordinate, version)) {
            return List.of();
        }
        List<String> paths = new ArrayList<>();
        for (Coordinate found : keptPackages(coordinate, version, store)) {
            paths.add(PREFIX + found.repo() + "/" + found.subdir() + "/" + found.file());
        }
        return paths;
    }

    /** One stored package found by the hold discovery walk: its channel, subdir and filename. */
    private record Coordinate(String repo, String subdir, String file) {
    }

    /** Every stored package whose filename names {@code (coordinate, version)}, across channels and subdirs, so a hold
     *  covers every build. Channels and subdirs are bounded by operator and platform, so a plain list suits them; each
     *  subdir's {@code pkgs} listing is publisher-grown and scanned through the bounded {@link #PKGS}. */
    private static List<Coordinate> keptPackages(String coordinate, String version, ArtifactStore store)
            throws IOException {
        List<Coordinate> kept = new ArrayList<>();
        for (String repo : store.list("conda")) {
            for (String subdir : store.list("conda/" + repo)) {
                if (!store.isEmpty("conda/" + repo + "/" + subdir + "/by")) {
                    // The reverse index a publish writes answers without a scan; a subdir without one is scanned until
                    // the rebuild pass has written it.
                    for (String file : store.list("conda/" + repo + "/" + subdir + "/by/" + coordinate + "/"
                            + version)) {
                        if (!isPackage(file)) {
                            continue;
                        }
                        if (store.readVersioned(packageKey(repo, subdir, file)).isPresent()) {
                            kept.add(new Coordinate(repo, subdir, file));
                        } else {
                            store.delete(reverseKey(repo, subdir, coordinate, version, file));   // evicted: stale note
                        }
                    }
                    continue;
                }
                PKGS.scan(store, "conda/" + repo + "/" + subdir + "/pkgs", file -> {
                    if (!isPackage(file)) {
                        return;
                    }
                    String[] found = coordinate(file);
                    if (found != null && found[0].equals(coordinate) && found[1].equals(version)) {
                        kept.add(new Coordinate(repo, subdir, file));
                    }
                });
            }
        }
        return kept;
    }

    /** The page size the hold discovery walk lists a subdir's packages in. */
    private static final int PKGS_PAGE = 1000;

    /** One subdir's package container, a flat enumeration. It feeds {@code blobKeys} and {@code servedPaths}, where a
     *  short listing would leave a held build serving, so the entry cap is off and the step budget (1000 pages) raises
     *  a {@link build.jenesis.repository.walk.TraversalException} rather than dropping keys. */
    private static final BoundedChildren PKGS =
            BoundedChildren.bounded().entries(Integer.MAX_VALUE).page(PKGS_PAGE);

    @Override
    public boolean handles(String path) {
        return path.startsWith(PREFIX);
    }

    @Override
    public void serve(FormatExchange exchange, ArtifactStore store) throws IOException {
        String rest = exchange.path().substring(PREFIX.length());
        int firstSlash = rest.indexOf('/');
        if (firstSlash < 0) {
            exchange.respond(404);
            return;
        }
        String repo = rest.substring(0, firstSlash);
        String after = rest.substring(firstSlash + 1);
        if (after.equals("channeldata.json") && (exchange.method().equals("GET") || exchange.method().equals("HEAD"))) {
            channeldata(repo, new Blobs(store), exchange);
            return;
        }
        int secondSlash = after.indexOf('/');
        if (secondSlash < 0) {
            exchange.respond(404);
            return;
        }
        String subdir = after.substring(0, secondSlash);
        String tail = after.substring(secondSlash + 1);
        String method = exchange.method();
        if (tail.indexOf('/') >= 0 || subdir.isEmpty()) {
            exchange.respond(404);
        } else if (method.equals("PUT") && isPackage(tail)) {
            publish(repo, subdir, tail, exchange, store);
        } else if (!method.equals("GET") && !method.equals("HEAD")) {
            exchange.respond(405);
        } else if (tail.equals(REPODATA)) {
            repodata(repo, subdir, store, exchange, false);
        } else if (tail.equals(REPODATA_BZ2)) {
            repodata(repo, subdir, store, exchange, true);
        } else if (isPackage(tail)) {
            serve(repo, subdir, tail, new Blobs(store), exchange);
        } else {
            exchange.respond(404);
        }
    }

    /** Stream a package upload into the store while reading only its {@code info/index.json}, then record the pointer
     *  and its repodata record. */
    private void publish(String repo, String subdir, String file, FormatExchange exchange, ArtifactStore store)
            throws IOException {
        if (Keys.unsafe(repo) || Keys.unsafe(subdir) || Keys.unsafe(file)) {
            // Validated before any store write, so a segment cannot splice the pointer key out of the channel's
            // namespace.
            exchange.respond(400);
            return;
        }
        String hash = new Blobs(store).store(exchange.requestStream());
        long size = store.size("blobs/" + hash);
        ObjectNode index;
        try (InputStream blob = store.open("blobs/" + hash)) {
            index = readIndex(file, blob, size);
        } catch (IOException e) {
            index = null;
        }
        if (index == null || text(index, "name") == null || text(index, "version") == null) {
            exchange.respond(400);
            return;
        }
        String[] pathCoordinate = coordinate(file);
        if (pathCoordinate != null && !pathCoordinate[0].equals(text(index, "name"))) {
            // The embedded name must match the filename it deploys under, or a package screened under one name would
            // serve under another.
            exchange.respond(400);
            return;
        }
        ObjectNode record = MAPPER.createObjectNode();
        record.setAll(index);
        if (!record.has("subdir")) {
            record.put("subdir", subdir);
        }
        record.put("sha256", hash);
        record.put("size", size);
        // Blobs.link spares a byte-identical blob a collector condemned, so it is not swept after a 201.
        Blobs blobs = new Blobs(store);
        try {
            // A package file never changes under its name, since a lock file pins its sha256: the first bytes stay,
            // decided at the pointer's compare-and-set, and a second upload is answered as anaconda.org does.
            blobs.linkRelease(packageKey(repo, subdir, file), hash, -1L);
        } catch (Publication.RepublishConflict taken) {
            exchange.respond(409, ("Conflict: the file " + subdir + "/" + file + " already exists")
                    .getBytes(StandardCharsets.UTF_8));
            return;
        }
        byte[] indexed = MAPPER.writeValueAsBytes(record);
        blobs.write(indexKey(repo, subdir, file), indexed);
        if (pathCoordinate != null) {
            // The reverse index from a coordinate to its package keys.
            blobs.note(reverseKey(repo, subdir, pathCoordinate[0], pathCoordinate[1], file), file);
        }
        // The repodata is maintained on the publish: the record joins it if servable, re-deriving the .bz2 twin.
        new CondaListings(blobs).published(repo, subdir, file, indexed);
        exchange.respond(201);
    }

    /** {@code conda/<repo>/<subdir>/by/<name>/<version>/<file>}: the reverse index a publish writes. */
    static String reverseKey(String repo, String subdir, String name, String version, String file) {
        return "conda/" + repo + "/" + subdir + "/by/" + name + "/" + version + "/" + file;
    }

    /** Read {@code info/index.json} from a stored package, decompressing only as far as it: a {@code .conda} is a zip
     *  whose {@code info-*.tar.zst} member is a Zstandard tar, a {@code .tar.bz2} a bzip2 tar. The zip walk and the
     *  info member's own stream run under the shared archive-walk bound, so a deflate-bombed member or a package
     *  without an {@code info-*} member cannot drive unbounded inflation; a stopped walk yields no index, and the
     *  publish answers {@code 400}. */
    private static ObjectNode readIndex(String file, InputStream blob, long compressed) throws IOException {
        if (file.endsWith(CONDA_EXT)) {
            return ArchiveWalk.walk(blob, CondaFormat::infoMember).orNull();
        }
        // The legacy container's scan is bounded to a multiple of its compressed size (MAX_INFO_INFLATION_RATIO).
        return ArchiveWalk.walk(new BZip2CompressorInputStream(blob),
                ArchiveWalk.largestWalk(compressed, MAX_INFO_INFLATION_RATIO),
                CondaFormat::indexFromTar).orNull();
    }

    /** The {@code info/index.json} of a {@code .conda}'s {@code info-*} member, or {@code null} when there is none. The
     *  member's decompressed stream gets its own walk bound, since it is deflated at the zip layer and could inflate to
     *  gigabytes from a small upload. */
    private static ObjectNode infoMember(InputStream archive) throws IOException {
        ZipInputStream zip = ArchiveWalk.zip(archive);
        for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
            String name = entry.getName();
            if (name.startsWith("info-") && (name.endsWith(".tar.zst") || name.endsWith(".tar.zstd"))) {
                return ArchiveWalk.walk(new ZstdInputStream(zip), CondaFormat::indexFromTar).orNull();
            }
            if (name.startsWith("info-") && name.endsWith(".tar")) {
                return ArchiveWalk.walk(zip, CondaFormat::indexFromTar).orNull();
            }
        }
        return null;
    }

    /** The {@code info/index.json} object from a decompressed info tar, bounded. */
    private static ObjectNode indexFromTar(InputStream stream) throws IOException {
        TarArchiveInputStream tar = new TarArchiveInputStream(stream, "UTF-8");
        for (TarArchiveEntry entry = tar.getNextEntry(); entry != null; entry = tar.getNextEntry()) {
            String name = entry.getName();
            if (name.equals("info/index.json") || name.equals("./info/index.json")) {
                // The index is the publish's guard input, checked against the filename, so a read the ceiling stopped
                // fails closed; the tar header's declared size is not trusted, since a prefix could still parse into a
                // plausible index.
                byte[] json = ArchiveInflation.entry(tar).required("conda package", "info/index.json");
                return MAPPER.readTree(json) instanceof ObjectNode object ? object : null;
            }
        }
        return null;
    }

    /**
     * The channel's {@code channeldata.json}: its populated subdirs, from which a client or an enumeration discovers
     * them without probing.
     *
     * <p><b>A subdir every package of which is held is not announced.</b> This route answers with names the client did
     * not supply, so a container with nothing servable is dropped, as Debian's {@code dists/} autoindex drops a suite,
     * Composer's {@code list.json} a package and PyPI's root a project. A subdir is a platform name, a container like a
     * suite, and the document's list is a solver's answer to "which platforms can this channel resolve for", so
     * announcing an unservable one would offer a known-unservable view. A subdir with no package at all stays listed,
     * naming no withheld coordinate; only a structural probe tells the two apart. The screen short-circuits at the
     * first surviving package.
     */
    private void channeldata(String repo, Blobs blobs, FormatExchange exchange) throws IOException {
        List<String> subdirs = new ArrayList<>();
        for (String subdir : blobs.list("conda/" + repo)) {
            if (servable(repo, subdir, blobs)) {
                subdirs.add(subdir);
            }
        }
        if (subdirs.isEmpty()) {
            exchange.respond(404);
            return;
        }
        Collections.sort(subdirs);
        ArrayNode listed = MAPPER.createArrayNode();
        subdirs.forEach(listed::add);
        ObjectNode root = MAPPER.createObjectNode();
        root.put("channeldata_version", 1);
        root.set("packages", MAPPER.createObjectNode());
        root.set("subdirs", listed);
        exchange.setResponseHeader("Content-Type", "application/json");
        exchange.answer(MAPPER.writeValueAsBytes(root));
    }

    /** Whether {@code channeldata.json} may announce this subdir: a package a client can download, judged by the
     *  screened enumeration {@link #repodata} renders through, short-circuiting at the first. A subdir with no indexed
     *  package stays listed, told apart by a structural emptiness probe. */
    private static boolean servable(String repo, String subdir, Blobs blobs) throws IOException {
        if (ScreenedNames.keys(blobs.servableNames(), ServableNames.Policy.HIDE_WITHHELD,
                        file -> packageKey(repo, subdir, file))
                .any(blobs.store(), indexPrefix(repo, subdir))) {
            return true;
        }
        return blobs.isEmpty(indexPrefix(repo, subdir));
    }

    /** The subdir's {@code repodata.json}, the stored listing, streamed as is, or its {@code .bz2} twin; the ETag is
     *  the document's digest, so revalidation answers {@code 304} from the header. */
    private void repodata(String repo, String subdir, ArtifactStore store, FormatExchange exchange, boolean compressed)
            throws IOException {
        Blobs blobs = new Blobs(store);
        // The structural probe is paid only until the document exists.
        if (!StoredListing.present(store, CondaListings.repodata(repo, subdir))
                && blobs.isEmpty(indexPrefix(repo, subdir))) {
            // Nothing published here: a 404, so a proxy channel's pull-through fetches the upstream repodata; conda
            // tolerates a 404 for a subdir it does not need.
            exchange.respond(404);
            return;
        }
        StoredListing.Spec spec = new CondaListings(blobs).spec(repo, subdir);
        Optional<StoredListing.Served> served;
        if (compressed) {
            // The twin is derived off the publish's thread, so a read compares sequences and re-derives a lagging one
            // here.
            Optional<StoredListing.Header> source = StoredListing.header(store, spec.listing());
            served = StoredListing.openDerived(store, spec.listing() + ".bz2");
            if (served.isEmpty() || source.isEmpty() || served.get().header().seq() < source.get().seq()) {
                if (served.isPresent()) {
                    served.get().close();
                }
                // Streamed through two temporary files: the repodata is every package in the subdir, on a request
                // thread.
                Optional<StoredListing.Served> source0 = StoredListing.open(store, spec);
                if (source0.isPresent()) {
                    Path plain = OwnerOnly.createTempFile("jenrepo-repodata", ".json");
                    try {
                        long seq;
                        try (StoredListing.Served document = source0.get();
                             InputStream body = document.body()) {
                            seq = document.header().seq();
                            Files.copy(body, plain, StandardCopyOption.REPLACE_EXISTING);
                        }
                        Path twin = OwnerOnly.createTempFile("jenrepo-repodata", ".bz2");
                        try {
                            StoredListing.Header header = bzip2(plain, twin, seq);
                            StoredListing.derive(store, spec.listing() + ".bz2", header, header.size(),
                                    () -> new BufferedInputStream(Files.newInputStream(twin)));
                        } finally {
                            Files.deleteIfExists(twin);
                        }
                    } finally {
                        Files.deleteIfExists(plain);
                    }
                }
                served = StoredListing.openDerived(store, spec.listing() + ".bz2");
            }
        } else {
            served = StoredListing.open(store, spec);
        }
        if (served.isEmpty()) {
            exchange.respond(404);
            return;
        }
        try (StoredListing.Served document = served.get()) {
            Listings.serve(exchange, document, compressed ? "application/x-bzip2" : "application/json");
        }
    }

    /** Serve a package archive from the CAS. */
    private void serve(String repo, String subdir, String file, Blobs blobs, FormatExchange exchange)
            throws IOException {
        String key = packageKey(repo, subdir, file);
        blobs.answer(key, exchange, "application/octet-stream");
    }

    /** Proxy a conda miss to an upstream channel: {@code /conda/<repo>/<subdir>/<file>} maps to
     *  {@code <upstream>/<subdir>/<file>}, the alias stripped. A package streams into the store
     *  ({@link ProxyRelay#fill}) and is cached; the index is streamed fresh, needing no rewrite. A deployment names one
     *  upstream per repository. */
    @Override
    public boolean pullThrough(FormatExchange exchange, ArtifactStore store, URI upstream,
                               ProxyFormat.Fetcher fetcher) throws IOException {
        String path = exchange.path();
        String rest = path.substring(PREFIX.length());
        int firstSlash = rest.indexOf('/');
        if (firstSlash < 0) {
            return false;
        }
        String repo = rest.substring(0, firstSlash);
        String after = rest.substring(firstSlash + 1);
        int secondSlash = after.indexOf('/');
        if (secondSlash < 0) {
            return false;
        }
        String subdir = after.substring(0, secondSlash);
        String file = after.substring(secondSlash + 1);
        if (file.indexOf('/') >= 0 || subdir.isEmpty()) {
            return false;
        }
        String root = upstream.toString();
        if (!root.endsWith("/")) {
            root += "/";
        }
        URI target = URI.create(root + subdir + "/" + file);
        if (isPackage(file)) {
            // The subdir's repodata.json publishes each package's SHA-256, and the streamed archive is held to it. The
            // index is a separate fetch, and one that could not be read must not become an unverified fill.
            ProxyRelay.Declared expected = repodataChecksum(root, subdir, file, fetcher);
            if (!expected.readable()) {
                return ProxyRelay.unverifiable(target, expected);
            }
            try (ProxyFormat.Download download = fetcher.download(target, Map.of()).orElse(null)) {
                if (download == null || download.status() != 200) {
                    return false;
                }
                if (!ProxyRelay.fill(new Blobs(store), packageKey(repo, subdir, file), target, download.body(),
                        expected)) {
                    return false;
                }
            }
            serve(repo, subdir, file, new Blobs(store), exchange);
            return true;
        }
        // The index streamed fresh with the upstream's Content-Type. repodata.json is the subdir's package list a
        // solver reads, an ENUMERATION, so only an upstream 404/410 reaches the client as one.
        return ProxyRelay.streamFresh(fetcher, target, null, exchange, ProxyRelay.Document.ENUMERATION);
    }

    @Override
    public Optional<ArtifactDescriptor> describe(String path) {
        if (!path.startsWith(PREFIX)) {
            return Optional.empty();
        }
        String file = path.substring(path.lastIndexOf('/') + 1);
        if (!isPackage(file)) {
            return Optional.empty();
        }
        String[] coordinate = coordinate(file);
        if (coordinate == null) {
            return Optional.of(ArtifactDescriptor.at(ECOSYSTEM, path));
        }
        return Optional.of(new ArtifactDescriptor(ECOSYSTEM, coordinate[0], coordinate[1], path,
                "application/octet-stream", false, null, -1L));
    }

    /** The package version a stored conda pointer serves, from which the inventory back-fill rebuilds a lost
     *  {@code published} record. A conda version contains no {@code -}, so {@link #coordinate}'s right-to-left split of
     *  {@code <name>-<version>-<build>.<ext>} is exact for hyphenated names. Only a {@code pkgs/} pointer is claimed,
     *  checked by position, since an {@code index/} record named after the same file is no package pointer. */
    @Override
    public Optional<ArtifactDescriptor> describePointer(String key) {
        String[] parts = key.split("/", -1);
        if (parts.length != 5 || !parts[0].equals("conda") || !parts[3].equals("pkgs") || !isPackage(parts[4])) {
            return Optional.empty();
        }
        String[] coordinate = coordinate(parts[4]);
        if (coordinate == null || !BlobLayout.addressable(coordinate[0], coordinate[1])) {
            return Optional.empty();
        }
        return Optional.of(new ArtifactDescriptor(ECOSYSTEM, coordinate[0], coordinate[1], key,
                "application/octet-stream", false, null, 0L));
    }

    @Override
    public List<String> paths(String coordinate, String version, ArtifactStore store) {
        // Pointers live in the shared Blobs namespace, so the coordinate enumerates nothing in publish/.
        return List.of();
    }

    /** Split {@code <name>-<version>-<build>.<ext>} into {@code [name, version]} from the right, or null without two
     *  separators. */
    static String[] coordinate(String file) {
        String stem = file.endsWith(CONDA_EXT)
                ? file.substring(0, file.length() - CONDA_EXT.length())
                : file.substring(0, file.length() - TARBZ2_EXT.length());
        int build = stem.lastIndexOf('-');
        if (build <= 0) {
            return null;
        }
        int version = stem.lastIndexOf('-', build - 1);
        if (version <= 0) {
            return null;
        }
        return new String[]{stem.substring(0, version), stem.substring(version + 1, build)};
    }

    private static boolean isPackage(String file) {
        return file.endsWith(CONDA_EXT) || file.endsWith(TARBZ2_EXT);
    }

    /**
     * The SHA-256 the upstream subdir's {@code repodata.json} records for a package, stream-parsed: the
     * {@code packages} sections are walked entry by entry and other records skipped, so the large document is never
     * held.
     *
     * <p>{@link ProxyRelay.Declared#NONE}, cached without a check, when the index answered and declares nothing: a
     * {@code 404}/{@code 410}, no record, or no 64-hex {@code sha256}.
     * {@linkplain ProxyRelay.Declared#unreadable Unreadable} on a transport failure, a refusing status, or a body that
     * is no JSON object. Read through the streaming {@code download} leg, so it takes {@link ProxyRelay#declaration}
     * directly.
     */
    private static ProxyRelay.Declared repodataChecksum(String root, String subdir, String file,
            ProxyFormat.Fetcher fetcher) throws IOException {
        URI index = URI.create(root + subdir + "/repodata.json");
        try (ProxyFormat.Download repodata = fetcher.download(index, Map.of()).orElse(null)) {
            if (repodata == null) {
                return ProxyRelay.Declared.unreachable(index);
            }
            if (repodata.status() != 200) {
                return ProxyRelay.declaration(index, repodata.status());
            }
            try (JsonParser parser = MAPPER.createParser(repodata.body())) {
                if (parser.nextToken() != JsonToken.START_OBJECT) {
                    return ProxyRelay.Declared.unreadable("the repodata at " + index
                            + " answered 200 with a body that is not a repodata document");
                }
                while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
                    String section = parser.currentName();
                    parser.nextToken();   // advance onto the section's value
                    if ((section.equals("packages") || section.equals("packages.conda"))
                            && parser.currentToken() == JsonToken.START_OBJECT) {
                        String sha256 = scanRepodataSection(parser, file);
                        if (sha256 != null) {
                            byte[] raw = hex(sha256, 32);
                            return raw == null ? ProxyRelay.Declared.NONE : ProxyRelay.Declared.of("SHA-256", raw);
                        }
                    } else {
                        parser.skipChildren();
                    }
                }
            }
        }
        return ProxyRelay.Declared.NONE;
    }

    /** Walk a {@code packages} section entry by entry for the {@code sha256} of {@code file}'s record, or {@code null},
     *  skipping every other record's subtree. */
    private static String scanRepodataSection(JsonParser parser, String file) throws IOException {
        while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
            boolean match = file.equals(parser.currentName());
            parser.nextToken();   // advance onto the record object
            if (!match) {
                parser.skipChildren();
                continue;
            }
            String sha256 = null;
            if (parser.currentToken() == JsonToken.START_OBJECT) {
                while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
                    boolean isSha = "sha256".equals(parser.currentName());
                    parser.nextToken();
                    if (isSha && parser.currentToken() == JsonToken.VALUE_STRING) {
                        sha256 = parser.getString();
                    } else {
                        parser.skipChildren();
                    }
                }
            }
            return sha256;
        }
        return null;
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

    static String packageKey(String repo, String subdir, String file) {
        return "conda/" + repo + "/" + subdir + "/pkgs/" + file;
    }

    static String indexPrefix(String repo, String subdir) {
        return "conda/" + repo + "/" + subdir + "/index";
    }

    static String indexKey(String repo, String subdir, String file) {
        return indexPrefix(repo, subdir) + "/" + file;
    }

    static String text(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = node.path(field).asString(null);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    static byte[] bzip2(byte[] content) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (BZip2CompressorOutputStream bzip2 = new BZip2CompressorOutputStream(out)) {
            bzip2.write(content);
        }
        return out.toByteArray();
    }

    /** bzip2 {@code source} into {@code target}, digesting the compressed bytes as they are written, and answer the
     *  twin's header. Holds a buffer, not the subdir; the digests come from the same pass, since the twin's validator
     *  is what a read serves. */
    static StoredListing.Header bzip2(Path source, Path target, long seq) throws IOException {
        MessageDigest sha256 = digest("SHA-256");
        try (InputStream content = new BufferedInputStream(Files.newInputStream(source));
             OutputStream file = new BufferedOutputStream(Files.newOutputStream(target));
             OutputStream digesting = new DigestOutputStream(file, sha256);
             OutputStream bzip2 = new BZip2CompressorOutputStream(digesting)) {
            content.transferTo(bzip2);
        }
        return StoredListing.Header.of(seq, Files.size(target), "", HexFormat.of().formatHex(sha256.digest()), 0L);
    }

    private static MessageDigest digest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(algorithm + " is required of every JVM", e);
        }
    }

    /** The migration-import capability, delegated to {@link CondaImporter}. */
    private final CondaImporter importer = new CondaImporter();

    @Override
    public RepositoryImporter importer() {
        return importer;
    }

    /** Each build of the version is put where conda's own upload puts it, {@code <channel>/<subdir>/<file>}: the path
     *  it is served at, not the key it is stored under. */
    @Override
    public Exported export(ArtifactStore repository, String coordinate, String version, ExportTarget target)
            throws IOException {
        if (!BlobLayout.addressable(coordinate, version)) {
            return Exported.WITHHELD;
        }
        List<BlobExport.Pair> pairs = new ArrayList<>();
        for (Coordinate found : keptPackages(coordinate, version, repository)) {
            pairs.add(new BlobExport.Pair(packageKey(found.repo(), found.subdir(), found.file()),
                    found.repo() + "/" + found.subdir() + "/" + found.file()));
        }
        return BlobExport.put(repository, pairs, target);
    }
}
