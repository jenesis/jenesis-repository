package build.jenesis.repository.format.conda;

import module java.base;
import module org.apache.commons.compress;
import module tools.jackson.databind;

import build.jenesis.repository.format.Listings;
import build.jenesis.repository.blobs.BlobLayout;
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
import build.jenesis.repository.store.StoredListing;
import build.jenesis.repository.store.ServableNames;
import build.jenesis.repository.walk.BoundedChildren;
import build.jenesis.repository.walk.ScreenedNames;
import build.jenesis.repository.walk.Traversal;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import build.jenesis.repository.store.OwnerOnly;

/**
 * The Conda channel format, so {@code conda install} and {@code conda create} resolve packages over the shared store.
 * It owns {@code /conda/...}, where the first path segment is a channel (repository) and the second is a platform
 * {@code subdir} ({@code linux-64}, {@code noarch}, {@code osx-arm64}, ...). A package is pushed with
 * {@code PUT /conda/<repo>/<subdir>/<name>-<version>-<build>.conda} (the modern zip container) or the legacy
 * {@code .tar.bz2}, the raw archive as the body, and downloaded from the same path. The per-subdir index a client
 * reads ({@code GET /conda/<repo>/<subdir>/repodata.json}, and its {@code .bz2} variant) is a stored listing the
 * publish maintains.
 *
 * <p><b>Streaming publish.</b> The upload streams straight through {@link ArtifactStore#writeBlob} into the
 * content-addressed store, hashed on the way and never buffered; the SHA-256 the store returns is both the package
 * pointer's blob hash and the {@code sha256} the index records. Conda keeps a package's metadata (name, version,
 * build, {@code depends}, license, ...) in an {@code info/index.json} <i>inside</i> the archive, so - exactly as the
 * store-then-gate publish path reads a just-stored artifact back rather than buffering it from the network - the
 * stored blob is reopened ({@link ArtifactStore#open}) and only that small {@code info/index.json} is materialised:
 * a {@code .tar.bz2} is a bzip2 tar and a {@code .conda} is a zip whose {@code info-*.tar.zst} member is a
 * Zstandard tar, both walked with Commons Compress (bzip2 pure-Java, {@code .zst} via zstd) rather than hand-parsed.
 * The augmented record is stored per package (the Debian/RPM/Cargo per-package-stanza pattern), so the publish joins
 * the record into the stored {@code repodata.json} and a read streams it rather than reopening every archive
 * (read-first).
 *
 * <p>The record carries {@code sha256} and {@code size} but not {@code md5}: modern conda verifies a download against
 * {@code sha256} when present (for both {@code .conda} and {@code .tar.bz2}), so the content-addressed hash the store
 * already computed is the verification field, and a second full read of the blob to also compute an md5 is avoided.
 *
 * <p><b>Pull-through proxy.</b> The same layout is also a {@link ProxyFormat}: a local miss on a proxy channel is
 * served from an upstream conda channel, mapping {@code /conda/<repo>/<subdir>/<file>} to
 * {@code <upstream>/<subdir>/<file>} (the local channel name is a deployment alias, so it is stripped and only the
 * subdir and file map through). An immutable package ({@code .conda} / {@code .tar.bz2}) streams from upstream
 * straight into the CAS and is cached (a later read is a local hit that never touches the upstream), while the mutable
 * index a client reads ({@code repodata.json}, its {@code .bz2}/{@code .zst}, {@code current_repodata.json}, ...) is
 * streamed through fresh - a package record's location is its bare filename relative to the subdir root, which maps
 * onto this repository's {@code /conda/<repo>/<subdir>/} prefix, so the index needs no rewrite. Conda has no single
 * canonical upstream (conda-forge, bioconda, anaconda main, ...), so a deployment names one per repository
 * ({@link #defaultUpstream()} stays empty), and an empty subdir's local {@code repodata.json} is a {@code 404} so the
 * pull-through reaches the upstream index rather than serving an empty local one.
 *
 * <p>The layout declares its ecosystem ({@code "conda"}) so a compliance inspector, the console and download
 * tracking key on it; {@link #describe} resolves a package download path to its {@code name}/{@code version}
 * coordinate from the filename ({@code <name>-<version>-<build>.<ext>}, split from the right, since a conda version
 * carries no {@code -}). Package pointers live in the shared {@code Blobs} namespace like the other language formats,
 * so the {@code publish/}-namespace eviction ({@link #paths}) stays empty; coordinate-scoped enforcement runs through
 * the {@code BlobLayout} seam ({@link #blobKeys}/{@link #servedPaths}) instead.
 */
public final class CondaFormat implements RepositoryFormat, ArtifactLayout, ProxyLeg, BlobLayout, RepositoryImporter {

    /** The package-ecosystem name this format's artifacts report (distinct from {@link #name()}, the routing id). */
    public static final String ECOSYSTEM = "conda";

    static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String PREFIX = "/conda/";
    private static final String CONDA_EXT = ".conda";
    private static final String TARBZ2_EXT = ".tar.bz2";
    private static final String REPODATA = "repodata.json";
    private static final String REPODATA_BZ2 = "repodata.json.bz2";

    // A hostile package cannot force a large allocation: the info/index.json read is bounded by the product's one
    // archive-inflation ceiling, ArchiveInflation.largestEntry(), settable at jenreg.archive.largest-entry - not by a
    // private constant of this format's (RepositoryFormat contract clause 15 /). How far the WALK may run to
    // reach that member is the sibling bound one dimension over, ArchiveWalk.largestWalk(), settable at
    // jenreg.archive.largest-walk - also shared, and also not this format's to restate. What IS this format's is the
    // ratio below, which the legacy container needs and states at the call site that applies it.

    /** The legacy {@code .tar.bz2} is a single archive whose {@code info/index.json} may sit anywhere - even after a
     *  large payload - so it cannot use the flat {@link ArchiveWalk#largestWalk()} tier without refusing valid large
     *  packages. Instead its info scan is bounded to this multiple of the stored (compressed) blob size: a genuine package
     *  inflates at a normal ratio well under this, while a bzip2 bomb (tiny compressed, huge inflated) is stopped at
     *  {@code compressed * ratio}, capping the decompression a single publish can be forced to run to a bounded
     *  multiple of the upload the caller actually transferred. */
    private static final long MAX_INFO_INFLATION_RATIO = 100L;

    @Override
    public String name() {
        return "conda";
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
            // A traversal-shaped coordinate or version maps nowhere: these keys are what an eviction DELETES, and
            // ArtifactStore.delete is not screened. The shared per-part screen, so a legitimately
            // multi-segment coordinate still resolves.
            return List.of();
        }
        // A conda package is keyed on its filename (<name>-<version>-<build>.<ext>) under its <repo>/<subdir>; the
        // build/subdir are not derivable from the <name,version> coordinate, so discover them by walking each channel's
        // subdirs and keeping the package pointers whose coordinate(file) matches - this collects EVERY build of the
        // version (the correct retroactive-hold scope). The BlobLayout.blobHashes default resolves the withhold set from
        // these pointers (bare-hex bodies), so a retroactive KEV/license hold marks them and serving (serve + repodata
        // listing all gate on the withheld marker) retracts; an eviction deletes these exact keys. Before this the empty
        // return made a hold a silent no-op (no marker, no review handle) and a KEV-listed package kept serving. The
        // per-subdir pkgs listing is unbounded (attacker-publishable), so it is PAGED, never list()ed whole.
        List<Coordinate> kept = keptPackages(coordinate, version, store);
        List<String> keys = new ArrayList<>(kept.size());
        for (Coordinate found : kept) {
            keys.add(packageKey(found.repo(), found.subdir(), found.file()));
        }
        return keys;
    }

    /** The request paths this coordinate version's package(s) serve at ({@code /conda/<repo>/<subdir>/<file>}), one per
     *  build - the inverse of {@link #describe}, so a retroactive hold links a {@code /quarantine} review handle per
     *  served path exactly as {@code ArtifactLayout.paths} does for a publish/ layout. The per-package repodata record
     *  ({@code indexKey}) carries no download path and is regenerated (already screened on the package pointer), so it is
     *  not a served path. Reads only the tiny pointers, never a blob body. */
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

    /** One stored package file located by the retroactive-hold discovery walk: its channel, subdir and filename. */
    private record Coordinate(String repo, String subdir, String file) {
    }

    /** Every stored package file whose {@code coordinate(file)} equals {@code (coordinate, version)}, across all
     *  channels and subdirs - so a hold covers every build of the version. Channels and subdirs are operator/platform
     *  bounded (a bare list is right), but the per-subdir {@code pkgs} listing is attacker-publishable, so it is
     *  enumerated through the shared bounded {@link #PKGS} scan - never {@code list()}ed whole (the Audit-26 DoS
     *  lesson), and never a hand-rolled page loop either. */
    private static List<Coordinate> keptPackages(String coordinate, String version, ArtifactStore store)
            throws IOException {
        List<Coordinate> kept = new ArrayList<>();
        for (String repo : store.list("conda")) {
            for (String subdir : store.list("conda/" + repo)) {
                if (!store.isEmpty("conda/" + repo + "/" + subdir + "/by")) {
                    // The reverse index a publish writes answers without a scan; a subdir from before it is scanned
                    // as before, until the rebuild pass has backfilled it.
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

    /** The bounded {@code pkgs} page size the legacy retroactive-hold discovery walk streams through: the per-subdir
     *  package listing is walked a page at a time, never materialised whole. */
    private static final int PKGS_PAGE = 1000;

    /** One subdir's package container: a flat enumeration through the shared bounded primitive. This feeds
     *  {@code blobKeys}/{@code servedPaths}, so a listing that answered short would be a KEV-listed build that keeps
     *  serving after its hold - the entry cap is therefore OFF, and the binding bound is the primitive's step budget
     *  (1000 page round-trips, ~10^6 names), which raises a named
     *  {@link build.jenesis.repository.walk.TraversalException} rather than dropping keys. */
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

    /**
     * Stream a package upload into the CAS while materialising only its {@code info/index.json}, then record the
     * package pointer and its repodata record. The archive streams straight through {@link ArtifactStore#writeBlob},
     * so an arbitrarily large package never lands in heap; the just-stored blob is reopened to read its metadata.
     */
    private void publish(String repo, String subdir, String file, FormatExchange exchange, ArtifactStore store)
            throws IOException {
        if (Keys.unsafe(repo) || Keys.unsafe(subdir) || Keys.unsafe(file)) {
            // Validate the channel, subdir and filename BEFORE any store write, so a coordinate segment of ".." (or one
            // carrying a path separator or control character) cannot splice the package pointer key out of this
            // channel's namespace - the guard RpmFormat.publish documents, and which the package pointer's own
            // Blobs.link safe-key check would otherwise only enforce after the blob was already stored.
            exchange.respond(400);
            return;
        }
        String hash = store.writeBlob(exchange.requestStream());
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
            // The embedded info/index.json declares a different package name than the filename this package deploys
            // under: refuse rather than let it be screened under the filename name yet stored/served under the
            // index.json name (a screen-label bypass), the way Composer/CocoaPods refuse a manifest that disagrees with
            // the deploy path. The importer screens on the filename coordinate, so the two must agree.
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
        // Route the package pointer through Blobs.link (not a bare writeVersioned): besides the compare-and-set retry,
        // link clears any gc/condemned/<hash> marker a collector set, so republishing content byte-identical to a
        // condemned blob un-condemns it before the sweep deletes it - otherwise a 200/201 publish is GC-deleted to a
        // permanent 404. The blob hash is exactly what the pointer stores, so the marker key matches.
        Blobs blobs = new Blobs(store);
        blobs.link(packageKey(repo, subdir, file), hash);
        byte[] indexed = MAPPER.writeValueAsBytes(record);
        blobs.write(indexKey(repo, subdir, file), indexed);
        if (pathCoordinate != null) {
            // The reverse index a coordinate's package keys are found through without scanning every subdir.
            blobs.note(reverseKey(repo, subdir, pathCoordinate[0], pathCoordinate[1], file), file);
        }
        // The subdir's repodata is written here, on the publish, rather than generated on every read: the record
        // joins the stored document (if the package is servable), which re-derives its .bz2 twin.
        new CondaListings(blobs).published(repo, subdir, file, indexed);
        exchange.respond(201);
    }

    /** {@code conda/<repo>/<subdir>/by/<name>/<version>/<file>}: the reverse index a publish writes. */
    static String reverseKey(String repo, String subdir, String name, String version, String file) {
        return "conda/" + repo + "/" + subdir + "/by/" + name + "/" + version + "/" + file;
    }

    /** Read {@code info/index.json} from a just-stored package blob, decompressing only as far as that entry: a
     *  {@code .conda} is a zip whose {@code info-*.tar.zst} member is a Zstandard tar, a {@code .tar.bz2} a bzip2 tar.
     *  Only the small JSON is materialised; the (large) payload streams past or is skipped. Both the zip <em>walk</em>
     *  and the info member's own decompressed stream run under the product's shared archive-walk bound, so skipping
     *  past a hostile deflate-bombed member (or a package with no {@code info-*} member at all) cannot drive unbounded
     *  inflation on the publish thread; the {@code info-*} member sits near the front of a well-formed {@code .conda},
     *  so a legitimate package reaches it well within the bound. A walk the bound stopped yields no index, and the
     *  caller treats the package as unindexable (a {@code 400} publish) - the safe outcome for a member that will not
     *  yield its metadata cheaply. */
    private static ObjectNode readIndex(String file, InputStream blob, long compressed) throws IOException {
        if (file.endsWith(CONDA_EXT)) {
            return ArchiveWalk.walk(blob, CondaFormat::infoMember).orNull();
        }
        // Legacy .tar.bz2: bound the info scan to a multiple of the stored compressed size rather than the flat tier,
        // so a valid large package (whose info/ may follow a large payload) is never refused, while a bzip2 bomb (tiny
        // compressed, huge inflated) is stopped at compressed*ratio instead of decompressing unbounded on this thread.
        // Only the ratio is conda's; the floor is the shared bound's.
        return ArchiveWalk.walk(new BZip2CompressorInputStream(blob),
                ArchiveWalk.largestWalk(compressed, MAX_INFO_INFLATION_RATIO),
                CondaFormat::indexFromTar).orNull();
    }

    /** The {@code info/index.json} of a {@code .conda}'s {@code info-*} member, read from an already-bounded zip
     *  stream, or {@code null} when the package carries no info member. The member's own decompressed stream gets its
     *  own walk bound: the entry is deflate-compressed at the zip layer, so a small upload whose {@code info-*.tar}
     *  inflates to many GB (a deflate bomb with no {@code info/index.json} to stop the walk early) would otherwise
     *  drive unbounded inflation on the publish thread even though the compressed bytes it drew were bounded. */
    private static ObjectNode infoMember(InputStream archive) throws IOException {
        ZipInputStream zip = new ZipInputStream(archive);
        for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
            String name = entry.getName();
            if (name.startsWith("info-") && (name.endsWith(".tar.zst") || name.endsWith(".tar.zstd"))) {
                return ArchiveWalk.walk(new ZstdCompressorInputStream(zip), CondaFormat::indexFromTar).orNull();
            }
            if (name.startsWith("info-") && name.endsWith(".tar")) {
                return ArchiveWalk.walk(zip, CondaFormat::indexFromTar).orNull();
            }
        }
        return null;
    }

    /** The {@code info/index.json} object from a (decompressed) info tar, bounded so a hostile entry cannot force a
     *  large allocation. */
    private static ObjectNode indexFromTar(InputStream stream) throws IOException {
        TarArchiveInputStream tar = new TarArchiveInputStream(stream, "UTF-8");
        for (TarArchiveEntry entry = tar.getNextEntry(); entry != null; entry = tar.getNextEntry()) {
            String name = entry.getName();
            if (name.equals("info/index.json") || name.equals("./info/index.json")) {
                // The index is this publish's GUARD INPUT - publish() checks the declared name against the filename
                // this package deploys under - so a read the ceiling stopped fails closed rather than answering
                // "declares nothing". Bounding on entry.getSize() (what the tar header CLAIMS) and then reading that
                // many bytes was the older shape: a header may under-declare, and what came back would then be a
                // prefix of the real member that can still parse into a plausible index.
                byte[] json = ArchiveInflation.entry(tar).required("conda package", "info/index.json");
                return MAPPER.readTree(json) instanceof ObjectNode object ? object : null;
            }
        }
        return null;
    }

    /**
     * The channel's {@code channeldata.json}: its populated subdirs, the standard document a client - and an
     * enumeration, jenesis's own index walk included - discovers a channel's subdirs from without probing.
     *
     * <p><b>Screened: a subdir every package of which is held is not announced.</b> This is the half of the
     * rule that enumerates names the client did not supply - it is handed a channel and answers with platform
     * names - so a container with nothing servable is dropped, exactly as Composer's {@code list.json} drops a package
     * and PyPI's Simple root drops a project. <b>The call, stated rather than inherited</b>, because a
     * {@code subdir} is a platform name one level of abstraction above a published coordinate and the question is a
     * real one: <em>a container name is screened on the same rule as a coordinate.</em> Three reasons.
     * <ul>
     *   <li><b>The product has already answered it for the identical shape.</b> Debian's {@code dists/} autoindex
     *       screens the <em>suite</em>, and a suite is exactly as much an operator/platform container as
     *       {@code linux-64} is. Two formats may not answer one question differently (&sect;13).</li>
     *   <li><b>What {@code channeldata.json} is for is servability.</b> Its subdir list is a solver's answer to "which
     *       platforms can this channel resolve for". Announcing a platform whose every package is quarantined offers a
     *       view that is not merely stale but known-unservable - the &sect;5 "never serve a silently-incomplete view
     *       as if it were whole".</li>
     *   <li><b>the earlier own criterion.</b> The disclosure that matters is servability, not whether the token looks like
     *       a coordinate; a subdir with nothing servable is a container with nothing servable.</li>
     * </ul>
     * A subdir with no package at all stays listed - it names no withheld coordinate, and only a structural emptiness
     * probe over the raw container separates the two cases - which is the same carve-out {@code ComposerFormat.servable}
     * and {@code PyPiFormat.servable} draw for the same reason. The screen is the shared primitive short-circuiting at
     * the first surviving package, so a normal channel pays one probe per subdir.
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
        respondBody(exchange, MAPPER.writeValueAsBytes(root));
    }

    /** Whether {@code channeldata.json} may announce this subdir - it carries at least one package a client can
     *  actually download. The membership question the shared screened enumeration answers directly, short-circuiting
     *  at the first disclosable package and judging each enumerated index record by the package archive pointer key
     *  that carries its bytes - the same screen {@link #repodata} renders through, not a second private one. A subdir
     *  with no indexed package at all is left listed: it names no withheld coordinate, so only this structural
     *  emptiness probe over the raw container separates "nothing published here" from "everything here is held". */
    private static boolean servable(String repo, String subdir, Blobs blobs) throws IOException {
        if (ScreenedNames.keys(blobs.servableNames(), ServableNames.Policy.HIDE_WITHHELD,
                        file -> packageKey(repo, subdir, file))
                .any(blobs.store(), indexPrefix(repo, subdir))) {
            return true;
        }
        return blobs.isEmpty(indexPrefix(repo, subdir));
    }

    /** The subdir's {@code repodata.json} - the stored listing a publish maintains - streamed as is, or its
     *  {@code .bz2} twin. The ETag is the stored document's digest, so apt-style revalidation answers {@code 304}
     *  from the header alone. */
    private void repodata(String repo, String subdir, ArtifactStore store, FormatExchange exchange, boolean compressed)
            throws IOException {
        Blobs blobs = new Blobs(store);
        // The structural emptiness probe is paid only until the document exists: a present document proves the
        // subdir was published to, so a read never enumerates the records again.
        if (!StoredListing.present(store, CondaListings.repodata(repo, subdir))
                && blobs.isEmpty(indexPrefix(repo, subdir))) {
            // Nothing published under this subdir: a hosted channel has no repodata to serve, and a proxy channel
            // needs this local miss so the pull-through fetches the upstream repodata. Modern conda tolerates a 404
            // for a subdir it does not need. A structural emptiness probe over the raw container.
            exchange.respond(404);
            return;
        }
        StoredListing.Spec spec = new CondaListings(blobs).spec(repo, subdir);
        Optional<StoredListing.Served> served;
        if (compressed) {
            // The twin is derived off the publish's thread, so a read of it checks the source's sequence against its
            // own (one header read) and re-derives it here when it lags - the legacy variant pays for its
            // compression only when it is asked for before the deriver reached it.
            Optional<StoredListing.Header> source = StoredListing.header(store, spec.listing());
            served = StoredListing.openDerived(store, spec.listing() + ".bz2");
            if (served.isEmpty() || source.isEmpty() || served.get().header().seq() < source.get().seq()) {
                if (served.isPresent()) {
                    served.get().close();
                }
                // Streamed through two temporary files rather than held. This is a request thread and the
                // repodata is every package in the subdir, so the array form compressed the subdir while holding
                // it - the whole index twice, to answer one GET for the legacy variant.
                Optional<StoredListing.Served> source0 = StoredListing.open(store, spec);
                if (source0.isPresent()) {
                    Path plain = OwnerOnly.createTempFile("jenreg-repodata", ".json");
                    try {
                        long seq;
                        try (StoredListing.Served document = source0.get();
                             InputStream body = document.body()) {
                            seq = document.header().seq();
                            Files.copy(body, plain, StandardCopyOption.REPLACE_EXISTING);
                        }
                        Path twin = OwnerOnly.createTempFile("jenreg-repodata", ".bz2");
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
        Optional<Blobs.Located> located = blobs.locate(key);
        if (located.isEmpty()) {
            exchange.respond(404);
            return;
        }
        long size = located.get().size();
        exchange.setResponseHeader("Content-Type", "application/octet-stream");
        if (exchange.method().equals("HEAD")) {
            if (size >= 0) {
                exchange.setResponseHeader("Content-Length", Long.toString(size));
            }
            exchange.respond(200, -1L).close();
            return;
        }
        blobs.serve(located.get(), exchange);
    }

    /**
     * Proxy a Conda miss to an upstream conda channel. The request {@code /conda/<repo>/<subdir>/<file>} maps to
     * {@code <upstream>/<subdir>/<file>} - the local channel name is a deployment alias for the upstream channel, so it
     * is stripped and only the subdir and file map through. An immutable package ({@code .conda} / {@code .tar.bz2})
     * streams from upstream straight into the content-addressed store ({@link ProxyRelay#fill}, never
     * buffered) and is served, so a later read is a local hit that never touches the upstream. The mutable index a
     * client reads ({@code repodata.json}, its {@code .bz2}/{@code .zst}, {@code current_repodata.json}, ...) is
     * streamed through fresh on every read - a package record's location is its bare filename relative to the subdir
     * root, which maps onto this repository's {@code /conda/<repo>/<subdir>/} prefix, so the index needs no rewrite.
     * Conda has no single canonical upstream, so a deployment always names one per repository and
     * {@link #defaultUpstream()} stays empty.
     */
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
            // Point-integrity: the subdir's repodata.json publishes each package's SHA-256, so read that sibling and
            // verify the streamed archive against it, refusing a mismatch (the Maven proxy leg's checksum parity). The
            // index is a SEPARATE fetch from the archive below, so a repodata this repository could not read is not
            // "this channel declares no sha256 for the package" and must not become an unverified fill.
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
        // A mutable index (repodata) streamed fresh, never cached; the upstream's Content-Type is relayed when present.
        // ENUMERATION: repodata.json IS the subdir's package list, the document a solver reads to decide which packages
        // and versions exist there - an absent one is the answer "this channel subdir is empty", so only an upstream
        // that ANSWERED 404/410 may reach the client as a 404.
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

    /**
     * The package version a stored conda pointer serves - the backwards direction the inventory back-fill rebuilds a
     * lost {@code published/} row from.
     *
     * <p>The pair lives in a <em>filename</em>, {@code <name>-<version>-<build>.<ext>}, and what makes decoding it
     * safe is the ecosystem's own rule rather than this store's: a conda version contains no {@code -}, so
     * {@link #coordinate}'s right-to-left split is exact even for the many package names that carry hyphens. That
     * is the same guarantee Debian's underscore and RPM's hyphen-free version give, and the same reason npm's and
     * Cargo's filename shapes are deliberately left undecoded.
     *
     * <p>Only a {@code pkgs/} pointer is claimed, and the position is checked rather than searched for: a per-package
     * {@code index/} record is named after the same file and would otherwise decode to the same pair from a key that
     * is not a package pointer at all.
     */
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
        // Conda package pointers live in the shared Blobs namespace (like npm/pypi/go/rpm/cargo), not the Publication
        // namespace coordinate-based eviction walks, so nothing is enumerable from the coordinate alone here.
        return List.of();
    }

    /** Split a conda filename {@code <name>-<version>-<build>.<ext>} into {@code [name, version]} from the right (a
     *  conda version contains no {@code -}), or null when it does not carry two separators. */
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

    /** The SHA-256 the subdir's {@code repodata.json} records for a package, stream-parsed from the upstream index so
     *  a proxied archive can be verified against it. repodata is a mutable index that can be very large, so it is read
     *  through a streaming {@link JsonParser} - the {@code packages} / {@code packages.conda} sections are walked entry
     *  by entry and every non-matching record is {@linkplain JsonParser#skipChildren() skipped}, so the document is
     *  never materialised whole.
     *
     *  <p>{@link ProxyRelay.Declared#NONE} - cache without a point check, as Maven serves a jar whose {@code .sha1} is
     *  missing - when the index <em>answered</em> and declares nothing: a {@code 404}/{@code 410} (no repodata for this
     *  subdir), no record for this file, or a record with no 64-hex {@code sha256}.
     *  {@linkplain ProxyRelay.Declared#unreadable Unreadable} when the index could not be read - a transport failure, a
     *  refusing status, or a {@code 200} whose body is not a JSON object. It is read through the streaming
     *  {@code download} leg rather than the buffered one, so it takes {@link ProxyRelay#declaration} directly rather
     *  than {@code ProxyRelay.declaring}. */
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

    /** Walk a {@code packages} / {@code packages.conda} object entry by entry, returning the {@code sha256} of the
     *  record whose key is {@code file} (or {@code null} if it is not in this section), skipping every other record's
     *  subtree so a large section is never buffered. */
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

    /**
     * bzip2 {@code source} into {@code target}, digesting the compressed bytes as they are written, and answer the
     * header a derived twin carries.
     *
     * <p>The array form above holds the repodata and its compression at once, which is the whole subdir twice on a
     * path that is already the slowest part of a publish. This one holds a buffer. The digests come out of the same
     * pass because a twin's validator is what the read serves and reading it back to compute one would undo this.
     */
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

    /** Whether a channel/subdir/filename segment must not be spliced into a {@code conda/...} store key - empty, a dot
     *  segment, or carrying a path separator or control character. Mirrors the guard the sibling formats (rpm/cargo/…)
     *  apply to their coordinates, so a body- or path-supplied {@code ..} cannot escape the channel's namespace. */

    private static void respondBody(FormatExchange exchange, byte[] body) throws IOException {
        if (exchange.method().equals("HEAD")) {
            exchange.setResponseHeader("Content-Length", Integer.toString(body.length));
            exchange.respond(200, -1L).close();
        } else {
            exchange.respond(200, body);
        }
    }

    /** The migration-import capability (WSPI.2 (c)), delegated to the layout-only {@link CondaImporter} - the format IS the
     *  discovered importer now (an {@code instanceof} capability), and the importer class stays as its delegate. */
    private final CondaImporter importer = new CondaImporter();

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
}
