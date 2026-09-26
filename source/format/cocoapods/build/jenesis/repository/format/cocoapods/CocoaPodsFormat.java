package build.jenesis.repository.format.cocoapods;

import module java.base;
import module tools.jackson.databind;

import build.jenesis.repository.format.Listings;
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

/**
 * The CocoaPods registry format (the CocoaPods CDN protocol), so {@code pod install} and {@code pod update} resolve
 * Swift/Objective-C pods over the shared store. It owns {@code /cocoapods/...}, where the first path segment is a
 * registry: a pod is pushed with {@code PUT /cocoapods/<repo>/<name>/<version>} (the pod's zip archive as the raw body)
 * and downloaded from {@code /cocoapods/<repo>/pods/<name>/<version>/<name>.zip}. The CDN metadata a client reads - the
 * root {@code CocoaPods-version.yml}, the sharded {@code all_pods_versions_<a>_<b>_<c>.txt} listings, and each version's
 * {@code Specs/<a>/<b>/<c>/<name>/<version>/<name>.podspec.json} - is generated on read, except the sharded
 * listings, which stream from the stored listings every publish maintains.
 *
 * <p><b>The CDN shard.</b> The CocoaPods CDN buckets a pod into a directory shard derived from the pod name: the
 * first three hex characters of {@code MD5(name)} (the default {@code prefix_lengths: [1, 1, 1]} this format advertises
 * in {@code CocoaPods-version.yml}). A client computes the same shard to find a pod's version listing and its podspecs,
 * so this format stores each podspec under that shard ({@code cocoapods/<repo>/spec/<a>/<b>/<c>/<name>/<version>}) and a
 * read of the shard's version file or a podspec is a direct prefix listing / key lookup, never a scan of every pod
 * (read-first). {@code MD5} here is the CDN's shard function, mandated by the protocol - not a security digest.
 *
 * <p><b>Streaming publish.</b> The uploaded zip streams straight through {@link ArtifactStore#writeBlob} into the
 * content-addressed store, hashed on the way and never buffered; the SHA-256 the store returns is the download
 * pointer's blob hash. A CocoaPods package keeps its coordinate, license and dependency metadata in a
 * {@code <name>.podspec.json} <i>inside</i> the archive, so - exactly as the store-then-gate publish path reads a
 * just-stored artifact back rather than buffering it from the network - the stored blob is reopened
 * ({@link ArtifactStore#open}) and only that small podspec is materialised (at the archive root or one directory deep
 * as a VCS export files it, walked with {@code java.util.zip} and parsed with the Jackson databind on the server's
 * module path; the large payload streams past or is skipped, bounded so a hostile archive cannot force a large
 * allocation or an unbounded inflate). The podspec, with its {@code name}/{@code version} normalised to the deploy path
 * and its original {@code source} removed, is stored per version; a read serves it back with a fresh {@code source}
 * pointing the download at this registry, so the stored stanza carries no request host and an imported pod (stored with
 * no request host) still resolves to a reachable download.
 *
 * <p>The layout declares its ecosystem ({@code "CocoaPods"}) so a compliance inspector, the console and download
 * tracking key on it; {@link #describe} resolves a pod download path to its {@code <name>} coordinate and version. OSV
 * carries no dedicated CocoaPods advisory feed today, so vulnerability screening is a graceful no-op while license and
 * malicious-package screening still key on the coordinate. Pod pointers live in the shared {@code Blobs} namespace like
 * the other language formats, so the {@code publish/}-namespace eviction ({@link #paths}) stays empty; coordinate-scoped
 * enforcement runs through the {@code BlobLayout} seam ({@link #blobKeys}/{@link #servedPaths}) instead.
 *
 * <p><b>Pull-through proxy.</b> The same layout is also a {@link ProxyFormat}: a local miss on a proxy registry is
 * served from an upstream CocoaPods CDN (the trunk CDN at {@code cdn.cocoapods.org} by default,
 * {@link #defaultUpstream()}). The root {@code CocoaPods-version.yml} is always generated locally (it advertises the
 * sharding) and never proxied. A sharded {@code all_pods_versions_<a>_<b>_<c>.txt} listing is mutable, so it streams
 * through fresh on every read (never cached) - it carries only {@code <name>/<version>...} lines and no URLs, so it
 * needs no rewrite. A per-version {@code Specs/.../<name>.podspec.json} is mutable, so it is fetched fresh and streamed
 * through; when its {@code source} is an {@code :http} zip (a stable URL this registry can cache and re-serve as a
 * {@code .zip}) the {@code source} is rewritten to route the download back through this registry, and any other source
 * (a git checkout, an http tarball) is passed through unchanged so the client fetches it directly - the CocoaPods CDN
 * hosts only metadata, not artifacts, so only an http-zip source is one this registry can cache. A pod archive
 * ({@code pods/<name>/<version>/<name>.zip}) is immutable, so on the first download its upstream http-zip URL is
 * resolved from the upstream podspec (at the pod's CDN shard), the archive is streamed into the CAS
 * ({@link ProxyRelay#fill}, never buffered) and served, so a later read is a local hit that never
 * touches the upstream. The request path {@code /cocoapods/<repo>/<sub>} maps to {@code <upstream>/<sub>} (the local
 * repo name is a deployment alias, stripped), which is the CDN's own layout, so no path rewrite is needed beyond the
 * {@code source} routing. An upstream miss lets the local {@code 404} stand.
 */
public final class CocoaPodsFormat implements RepositoryFormat, ArtifactLayout, ProxyLeg, BlobLayout, RepositoryImporter,
        RepositoryExporter {

    /** The package-ecosystem name this format's artifacts report (distinct from {@link #name()}, the routing id). OSV
     *  has no dedicated CocoaPods feed, so vulnerability lookups on it simply find nothing; the coordinate still drives
     *  license and malicious-package screening. */
    public static final String ECOSYSTEM = "CocoaPods";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The canonical public CocoaPods CDN this format mirrors when a deployment enables proxying without naming one:
     *  the trunk CDN at {@code cdn.cocoapods.org}, where {@code CocoaPods-version.yml}, the sharded
     *  {@code all_pods_versions_*} listings and the {@code Specs/.../<name>.podspec.json} files live (like crates.io
     *  for Cargo, Packagist for Composer). A deployment can always set a different upstream per repository. */
    private static final URI CDN = URI.create("https://cdn.cocoapods.org");

    private static final String PREFIX = "/cocoapods/";
    private static final String VERSION_FILE = "CocoaPods-version.yml";
    private static final String ALL_PODS = "all_pods_versions_";
    private static final String TXT = ".txt";
    private static final String SPECS = "Specs/";
    private static final String PODS = "pods/";
    private static final String PODSPEC_SUFFIX = ".podspec.json";
    private static final String ZIP = ".zip";

    /** The CDN version document, advertising the default {@code [1, 1, 1]} sharding a client uses to locate a pod. The
     *  {@code min}/{@code last} are the CocoaPods client versions the CDN reports; a fixed, tiny document, so it is
     *  emitted literally rather than through a YAML library. */
    private static final byte[] VERSION_YML = ("---\nmin: 1.0.0\nlast: 1.16.2\nprefix_lengths:\n- 1\n- 1\n- 1\n")
            .getBytes(StandardCharsets.UTF_8);

    // A hostile archive cannot force a large allocation: the podspec read is bounded by the product's one
    // archive-inflation ceiling, ArchiveInflation.largestEntry(), settable at jenreg.archive.largest-entry - not by a
    // private constant of this format's (RepositoryFormat contract clause 15).

    // How far the walk for the podspec may run is the product's one archive-walk bound, ArchiveWalk.largestWalk(),
    // settable at jenreg.archive.largest-walk - not a private constant of this format's (RepositoryFormat contract
    // clause 15 /, one dimension over from the inflation ceiling). An archive that will not yield its podspec
    // inside it is treated as unindexable (a 400 publish).

    @Override
    public String name() {
        return "cocoapods";
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

    @Override
    public List<String> blobRoots() {
        return List.of("cocoapods");
    }

    @Override
    public List<String> blobKeys(String coordinate, String version, ArtifactStore store) throws IOException {
        if (!BlobLayout.addressable(coordinate, version)) {
            // A traversal-shaped coordinate or version maps nowhere: these keys are what an eviction DELETES, and
            // ArtifactStore.delete is not screened. The shared per-part screen, so a legitimately
            // multi-segment coordinate still resolves.
            return List.of();
        }
        // A pod version's served content is its download pointer (cocoapods/<repo>/blob/<name>/<version>) plus its
        // podspec stanza (under the MD5(name) CDN shard, cocoapods/<repo>/spec/<a>/<b>/<c>/<name>/<version>); both are
        // Blobs.write pointers with bare-hex bodies, so the BlobLayout.blobHashes default resolves the withhold set from
        // them. Discovered per registry: the coordinate names the pod but not which registries hold it, so probe each.
        // A retroactive hold marks the resolved hashes (the download serve + versions listing + podspec read all gate on
        // that marker) and an eviction deletes these exact pointer keys - closing the silent no-op where a KEV-listed pod
        // kept serving. The registry set is operator-configured (bounded), so a bare store.list is right here; the pod's
        // two pointer keys are exact lookups, never a large child listing.
        String[] shard = shard(coordinate);
        List<String> keys = new ArrayList<>();
        for (String repo : store.list("cocoapods")) {
            String blob = blobKey(repo, coordinate, version);
            if (store.readVersioned(blob).isPresent()) {
                keys.add(blob);
            }
            String spec = specKey(repo, shard, coordinate, version);
            if (store.readVersioned(spec).isPresent()) {
                keys.add(spec);
            }
        }
        return keys;
    }

    /** The request paths this pod version's download serves at ({@code /cocoapods/<repo>/pods/<name>/<version>/<name>.zip})
     *  for each registry whose download pointer is live - the inverse of {@link #describe}, so a retroactive hold links a
     *  {@code /quarantine} review handle per served path exactly as {@code ArtifactLayout.paths} does for a publish/
     *  layout. The podspec stanza pointer is withheld (it is served content) but carries no {@code pods/} download path,
     *  so only the archive maps to a served path. Reads only the tiny pointers, never a blob body. */
    @Override
    public List<String> servedPaths(String coordinate, String version, ArtifactStore store) throws IOException {
        if (!BlobLayout.addressable(coordinate, version)) {
            return List.of();
        }
        List<String> paths = new ArrayList<>();
        for (String repo : store.list("cocoapods")) {
            if (store.readVersioned(blobKey(repo, coordinate, version)).isPresent()) {
                paths.add(PREFIX + repo + "/" + PODS + coordinate + "/" + version + "/" + coordinate + ZIP);
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
        } else if (sub.equals(VERSION_FILE)) {
            exchange.setResponseHeader("Content-Type", "text/yaml");
            exchange.answer(VERSION_YML);
        } else if (sub.startsWith(ALL_PODS) && sub.endsWith(TXT)) {
            allPodsVersions(repo, sub.substring(ALL_PODS.length(), sub.length() - TXT.length()),
                    new Blobs(store), exchange);
        } else if (sub.startsWith(SPECS)) {
            spec(repo, sub, new Blobs(store), exchange);
        } else if (sub.startsWith(PODS)) {
            download(repo, sub.substring(PODS.length()), new Blobs(store), exchange);
        } else {
            exchange.respond(404);
        }
    }

    /**
     * Stream a pod upload into the CAS while materialising only its {@code .podspec.json}, then record the download
     * pointer and its podspec stanza under the pod's CDN shard. The archive streams straight through
     * {@link ArtifactStore#writeBlob}, so an arbitrarily large pod never lands in heap; the just-stored blob is reopened
     * to read its metadata.
     */
    private void publish(String repo, String sub, FormatExchange exchange, ArtifactStore store) throws IOException {
        // A publish coordinate is <name>/<version>; the CDN read routes (all_pods_versions_*, Specs/, pods/,
        // CocoaPods-version.yml) are never exactly two segments, so a two-segment PUT is unambiguous.
        String[] parts = sub.split("/", -1);
        if (parts.length != 2) {
            exchange.respond(404);
            return;
        }
        String name = parts[0];
        String version = parts[1];
        if (Keys.unsafe(name) || Keys.unsafe(version)) {
            exchange.respond(400);
            return;
        }
        String hash = store.writeBlob(exchange.requestStream());
        ObjectNode podspec;
        try (InputStream blob = store.open("blobs/" + hash)) {
            podspec = readPodspec(blob);
        } catch (IOException e) {
            podspec = null;
        }
        if (podspec == null) {
            exchange.respond(400);
            return;
        }
        String declaredName = text(podspec, "name");
        String declaredVersion = text(podspec, "version");
        if ((declaredName != null && !declaredName.equals(name))
                || (declaredVersion != null && !declaredVersion.equals(version))) {
            // The archive's podspec claims a different name/version than the deploy path: refuse rather than let a pod
            // publish itself under another's coordinate (a metadata-poisoning route the store's tenant root check does
            // not close within this format's namespace).
            exchange.respond(400);
            return;
        }
        ObjectNode stanza = podspec.deepCopy();
        stanza.put("name", name);
        stanza.put("version", version);
        // The download source is generated on read from the serving request (host-portable), so nothing host-specific
        // is baked into the stored stanza - what lets an imported pod, stored with no request host, resolve correctly.
        stanza.remove("source");
        String[] shard = shard(name);
        // Route the download pointer through Blobs.link (not a bare writeVersioned): besides the compare-and-set retry,
        // link clears any gc/condemned/<hash> marker a collector set, so republishing a pod byte-identical to a
        // condemned one un-condemns it before the sweep deletes it - otherwise a 201 publish is GC-deleted to a
        // permanent 404. The pointer stores exactly that blob hash, so the marker key matches.
        Blobs blobs = new Blobs(store);
        blobs.link(blobKey(repo, name, version), hash);
        blobs.write(specKey(repo, shard, name, version), MAPPER.writeValueAsBytes(stanza));
        // The served shard listing is written here, on the publish: the version joins the pod's stored list, which
        // re-derives the pod's line in its shard rather than scanning the shard on every read.
        new CocoaPodsListings(blobs).refresh(repo, name, version);
        exchange.respond(201);
    }

    /**
     * The sharded pod-versions listing: the stored listing every publish maintains. {@code <a>_<b>_<c>} is a CDN
     * shard (three hex characters of {@code MD5(pod)}); the file lists one line per pod in that shard,
     * {@code <name>/<v1>/<v2>/...}. An empty shard is a {@code 404} (the pod is not here), so a proxy registry can
     * later fill it from upstream.
     */
    private void allPodsVersions(String repo, String suffix, Blobs blobs, FormatExchange exchange) throws IOException {
        String[] shard = suffix.split("_", -1);
        if (shard.length != 3 || !hex(shard[0]) || !hex(shard[1]) || !hex(shard[2]) || Keys.unsafe(repo)) {
            exchange.respond(404);
            return;
        }
        Optional<StoredListing.Served> served = StoredListing.open(blobs.store(),
                new CocoaPodsListings(blobs).shardSpec(repo, shard));
        if (served.isEmpty()) {
            exchange.respond(404);
            return;
        }
        try (StoredListing.Served document = served.get()) {
            if (document.header().size() == 0) {
                exchange.respond(404);
                return;
            }
            Listings.serve(exchange, document, "text/plain");
        }
    }

    /**
     * Serve a version's podspec, generated on read from the stored stanza with a fresh {@code source} pointing the
     * download at this registry. The path is {@code Specs/<a>/<b>/<c>/<name>/<version>/<name>.podspec.json}; the stanza
     * key is built from the same {@code <a>/<b>/<c>/<name>/<version>}, so a client's own shard (computed from the pod
     * name) resolves to exactly where the pod was stored, or a {@code 404} if nothing is published there.
     */
    private void spec(String repo, String sub, Blobs blobs, FormatExchange exchange) throws IOException {
        String[] parts = sub.split("/", -1);
        if (parts.length != 7) {
            exchange.respond(404);
            return;
        }
        String[] shard = {parts[1], parts[2], parts[3]};
        String name = parts[4];
        String version = parts[5];
        if (!hex(shard[0]) || !hex(shard[1]) || !hex(shard[2]) || Keys.unsafe(name) || Keys.unsafe(version)
                || !parts[6].equals(name + PODSPEC_SUFFIX)) {
            exchange.respond(404);
            return;
        }
        // Screen a withheld version's podspec, the way OCI screens a held image out of its tags: the pod archive a
        // compliance hold withholds 404s on download, so serving its podspec (source, license, dependencies) would
        // disclose a quarantined pod a client then cannot fetch. The same marker check the download serve makes.
        if (blobs.withheld(blobKey(repo, name, version))) {
            exchange.respond(404);
            return;
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        if (!blobs.read(specKey(repo, shard, name, version), buffer)) {
            exchange.respond(404);
            return;
        }
        JsonNode stored = MAPPER.readTree(buffer.toByteArray());
        if (!(stored instanceof ObjectNode podspec)) {
            exchange.respond(404);
            return;
        }
        // Generate the download source from the serving request (host + routing prefix), not a value baked in at write
        // time, so the pod resolves to whatever host serves this registry.
        ObjectNode source = MAPPER.createObjectNode();
        source.put("http", downloadUrl(repo, name, version, exchange));
        podspec.set("source", source);
        // An operator's DEPRECATED mark, surfaced the one way this ecosystem has: a podspec's own `deprecated` flag,
        // which `pod install` prints. CocoaPods has no second native - no unlist, no yank - so a YANKED mark is not
        // rendered here as if it were a deprecation; it takes the ecosystem's only "gone" signal instead and drops
        // the version out of the sharded listing (see allPodsVersions), which is the same absence RubyGems uses for
        // a yank. An unmarked podspec is untouched: emitting `deprecated: false` would put a field in every response
        // that the publisher never wrote.
        Lifecycle.read(blobs.store(), name, version)
                .filter(flag -> flag.state() == Lifecycle.State.DEPRECATED)
                .ifPresent(flag -> podspec.put("deprecated", true));
        exchange.setResponseHeader("Content-Type", "application/json");
        exchange.answer(MAPPER.writeValueAsBytes(podspec));
    }

    /** Serve a pod archive from the CAS. The path is {@code <name>/<version>/<file>.zip}. */
    private void download(String repo, String tail, Blobs blobs, FormatExchange exchange) throws IOException {
        if (!tail.endsWith(ZIP)) {
            exchange.respond(404);
            return;
        }
        String[] parts = tail.split("/", -1);
        if (parts.length != 3 || Keys.unsafe(parts[0]) || Keys.unsafe(parts[1])) {
            exchange.respond(404);
            return;
        }
        String key = blobKey(repo, parts[0], parts[1]);
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

    /** The canonical public CocoaPods CDN this format mirrors when a deployment enables proxying without naming one:
     *  the trunk CDN. A deployment can always set a different upstream per repository. */
    @Override
    public Optional<URI> defaultUpstream() {
        return Optional.of(CDN);
    }

    /**
     * Proxy a CocoaPods CDN miss to an upstream CocoaPods CDN. The root {@code CocoaPods-version.yml} is always
     * generated locally (never proxied); a mutable sharded {@code all_pods_versions_<a>_<b>_<c>.txt} listing is
     * streamed through fresh; a mutable per-version {@code Specs/.../<name>.podspec.json} is fetched fresh and its
     * {@code source} rewritten to route an http-zip download back through this registry (any other source passed
     * through); and an immutable pod archive is fetched, cached into the CAS and served, with its upstream URL resolved
     * from the upstream podspec. The request path {@code /cocoapods/<repo>/<sub>} maps to {@code <upstream>/<sub>} - a
     * CocoaPods CDN serves its metadata under the same layout this registry exposes, so no path rewrite is needed
     * beyond the {@code source} routing. Returns {@code false} to let the local {@code 404} stand.
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
        String sub = rest.substring(slash + 1);
        String root = upstream.toString();
        while (root.endsWith("/")) {
            root = root.substring(0, root.length() - 1);
        }
        if (sub.equals(VERSION_FILE)) {
            // The local version document is always generated (it advertises the sharding), so it never misses.
            return false;
        }
        if (sub.startsWith(ALL_PODS) && sub.endsWith(TXT)) {
            return proxyShardListing(sub, root, exchange, fetcher);
        }
        if (sub.startsWith(SPECS) && sub.endsWith(PODSPEC_SUFFIX)) {
            return proxySpec(rest.substring(0, slash), sub, root, exchange, fetcher);
        }
        if (sub.startsWith(PODS) && sub.endsWith(ZIP)) {
            return proxyDownload(rest.substring(0, slash), sub.substring(PODS.length()), root, exchange, store, fetcher);
        }
        return false;
    }

    /**
     * Stream an upstream sharded pod-versions listing through fresh (mutable, never cached). The file carries only
     * {@code <name>/<version>...} lines and no URLs, so it needs no rewrite; it can be large, so it is streamed with
     * {@link ProxyFormat.Download} rather than materialised. The target is reconstructed from validated hex shard
     * segments, so a crafted request path cannot steer the upstream fetch off the shard-listing route.
     */
    private boolean proxyShardListing(String sub, String root, FormatExchange exchange, ProxyFormat.Fetcher fetcher)
            throws IOException {
        String[] shard = sub.substring(ALL_PODS.length(), sub.length() - TXT.length()).split("_", -1);
        if (shard.length != 3 || !hex(shard[0]) || !hex(shard[1]) || !hex(shard[2])) {
            return false;
        }
        URI target = URI.create(root + "/" + ALL_PODS + shard[0] + "_" + shard[1] + "_" + shard[2] + TXT);
        // The sharded listing is a plain-text mutable index: stream it through fresh, never cached, defaulting the
        // served type to text/plain (the CDN serves these .txt listings as text/plain). ENUMERATION: this file IS the
        // pod-version list a Podfile resolves against, so an absent one is an answer ("no such pod, no such version")
        // and only an upstream that ANSWERED it may reach the client as a 404.
        return ProxyRelay.streamFresh(fetcher, target, "text/plain", exchange, ProxyRelay.Document.ENUMERATION);
    }

    /**
     * Fetch an upstream per-version podspec, rewrite an http-zip {@code source} to route the download back through this
     * registry (so the archive is cached here) and stream it through fresh (mutable, never cached). A podspec is a
     * small bounded metadata document, not an artifact, so it may be materialised to rewrite. A non-zip source (a git
     * checkout, an http tarball) is left unchanged so the client fetches it directly - the CDN hosts only metadata, and
     * only an http zip is a stable URL this registry serves back as a {@code .zip}. The shard, name and version are
     * validated exactly as the local {@link #spec} read, so a crafted path cannot steer the upstream fetch.
     */
    private boolean proxySpec(String repo, String sub, String root, FormatExchange exchange,
                              ProxyFormat.Fetcher fetcher) throws IOException {
        String[] parts = sub.split("/", -1);
        if (parts.length != 7 || !hex(parts[1]) || !hex(parts[2]) || !hex(parts[3])
                || Keys.unsafe(parts[4]) || Keys.unsafe(parts[5]) || !parts[6].equals(parts[4] + PODSPEC_SUFFIX)) {
            return false;
        }
        String name = parts[4];
        String version = parts[5];
        // PINNED, not ENUMERATION: the request already names the pod AND the version, so this document decides nothing
        // about what exists - the shard listing above is where a Podfile resolves. Its absence therefore keeps the
        // contract's "not cached here, re-pull" meaning and stays a plain decline.
        ProxyRelay.Answer answer = ProxyRelay.fetchFresh(fetcher, URI.create(root + "/" + sub), Map.of(), exchange,
                ProxyRelay.Document.PINNED);
        if (!answer.answered()) {
            return answer.served();
        }
        ObjectNode podspec = parse(answer.document().body());
        if (podspec == null) {
            return false;
        }
        if (httpZipSource(podspec) != null) {
            ObjectNode source = MAPPER.createObjectNode();
            source.put("http", downloadUrl(repo, name, version, exchange));
            podspec.set("source", source);
        }
        exchange.setResponseHeader("Content-Type", "application/json");
        exchange.answer(MAPPER.writeValueAsBytes(podspec));
        return true;
    }

    /**
     * Fetch, cache and serve an immutable pod archive: resolve the upstream http-zip {@code source} from the upstream
     * podspec, stream the archive into the CAS ({@link ProxyRelay#fill}, never buffered), then serve
     * it from the local hit. Only reached on a miss, and the archive is then cached, so a later read never re-resolves.
     * A pod whose source is not an http zip is not one this registry rewrote a local download for, so the miss is
     * declined (the client fetches such a pod from its own git/tarball source).
     */
    private boolean proxyDownload(String repo, String tail, String root, FormatExchange exchange, ArtifactStore store,
                                  ProxyFormat.Fetcher fetcher) throws IOException {
        String[] parts = tail.split("/", -1);
        if (parts.length != 3 || Keys.unsafe(parts[0]) || Keys.unsafe(parts[1])) {
            return false;
        }
        String name = parts[0];
        String version = parts[1];
        Source source = sourceUrl(root, name, version, fetcher, ProxyLeg.allowInternalTargets(exchange));
        if (source == null) {
            return false;
        }
        // Point-integrity: the podspec's http source may declare the archive's checksum (:sha256 / :sha1), so verify the
        // streamed archive against it and refuse a mismatch - the Maven proxy leg's checksum parity.
        //
        // Like Composer and PyPI and unlike npm/cargo/nuget/conan/rubygems/conda/rpm/go, this leg has no split to
        // make: the podspec is the SAME document that resolves the download URL, so a podspec this repository could not
        // read leaves nothing to fetch and sourceUrl already declines the whole fill above. The only fall-back
        // reachable here is the documented one - a podspec that answered and declares no checksum, which CocoaPods
        // leaves optional.
        try (ProxyFormat.Download download = fetcher.download(source.url(), Map.of()).orElse(null)) {
            if (download == null || download.status() != 200) {
                return false;
            }
            if (!ProxyRelay.fill(new Blobs(store), blobKey(repo, name, version), source.url(), download.body(),
                    source.expected() == null
                            ? ProxyRelay.Declared.NONE
                            : ProxyRelay.Declared.of(source.algorithm(), source.expected()))) {
                return false;
            }
        }
        download(repo, tail, new Blobs(store), exchange);
        return true;
    }

    /** A resolved pod download: the archive URL and, when the podspec declared one, the checksum to verify it against. */
    private record Source(URI url, String algorithm, byte[] expected) {
    }

    /** Resolve the upstream download for a pod version by reading the upstream podspec (at the pod's CDN shard) and
     *  taking its http-zip {@code source} - and, where the podspec declares one, the source's {@code :sha256} /
     *  {@code :sha1} checksum so the fetched archive can be verified. A small bounded metadata read, only on a
     *  pod-archive miss (once per version, since the archive is then cached). Returns {@code null} when the upstream
     *  podspec is absent or its source is not an http zip this registry serves. */
    private static Source sourceUrl(String root, String name, String version, ProxyFormat.Fetcher fetcher,
                                    boolean allowInternal) throws IOException {
        String[] shard = shard(name);
        String specPath = SPECS + shard[0] + "/" + shard[1] + "/" + shard[2] + "/" + name + "/" + version
                + "/" + name + PODSPEC_SUFFIX;
        Optional<ProxyFormat.Fetched> fetched = fetcher.fetch(URI.create(root + "/" + specPath), Map.of());
        if (fetched.isEmpty() || fetched.get().status() != 200) {
            return null;
        }
        ObjectNode podspec = parse(fetched.get().body());
        if (podspec == null) {
            return null;
        }
        String http = httpZipSource(podspec);
        if (http == null) {
            return null;
        }
        try {
            URI source = URI.create(http);
            // The source.http comes from untrusted upstream podspec metadata (an attacker can publish a pod to the
            // public CDN whose source points at an internal service, or at a plaintext host of their choosing), so an
            // unguarded fetch would be an SSRF and an unguarded http one would put the archive and any per-host
            // upstream credential in front of every observer. The one shared outbound screen decides it;
            // a refused target is declined so the miss falls through to a 404, never a throw (ProxyLeg clause 2).
            if (!OutboundTargets.mayFollow(source, URI.create(root), allowInternal)) {
                return null;
            }
            // A podspec's http source may carry the archive checksum: `:sha256` (preferred) or the older `:sha1`.
            JsonNode declared = podspec.path("source");
            byte[] sha256 = decodeHex(text(declared, "sha256"), 32);
            if (sha256 != null) {
                return new Source(source, "SHA-256", sha256);
            }
            byte[] sha1 = decodeHex(text(declared, "sha1"), 20);
            if (sha1 != null) {
                return new Source(source, "SHA-1", sha1);
            }
            return new Source(source, null, null);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Decode a hex digest of exactly {@code bytes} bytes to its raw bytes, or {@code null} when absent or malformed. */
    private static byte[] decodeHex(String value, int bytes) {
        if (value == null || value.length() != bytes * 2) {
            return null;
        }
        try {
            return HexFormat.of().parseHex(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The {@code source.http} URL of a podspec when it is an http zip archive (a stable URL this registry can cache
     *  and re-serve as a {@code .zip}), or {@code null} for a git checkout, an http tarball, or an absent/other source
     *  - which the proxy passes through unchanged for the client to fetch directly. The extension is matched on the
     *  URL path (ignoring any {@code ?query}/{@code #fragment}), so a {@code .tar.gz} that would be mis-unarchived as a
     *  zip is not routed through the local {@code .zip} download. */
    private static String httpZipSource(JsonNode podspec) {
        String http = text(podspec.path("source"), "http");
        if (http == null) {
            return null;
        }
        String bare = http;
        int cut = bare.indexOf('?');
        if (cut >= 0) {
            bare = bare.substring(0, cut);
        }
        cut = bare.indexOf('#');
        if (cut >= 0) {
            bare = bare.substring(0, cut);
        }
        return bare.toLowerCase(Locale.ROOT).endsWith(ZIP) ? http : null;
    }

    /** Parse an upstream metadata document as a JSON object, or {@code null} when it is malformed or not an object
     *  (Jackson signals a parse failure with an unchecked exception) - a malformed upstream podspec is treated as a
     *  miss rather than a {@code 500}. */
    private static ObjectNode parse(byte[] body) {
        try {
            return MAPPER.readTree(body) instanceof ObjectNode object ? object : null;
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
        if (!sub.startsWith(PODS) || !sub.endsWith(ZIP)) {
            // The publish path, <name>/<version>: what a pod is pushed at, and so the path the gate links a review
            // pointer at when it holds one. A release's cross-alias guard asks every review pointer's path to be
            // placed, and this one was not - measured 2026-09-12 as the pointer that failed every release's guard
            // closed for a quarter of an hour. The served path below carries the same coordinate.
            String[] pushed = sub.split("/", -1);
            if (pushed.length == 2 && !pushed[0].startsWith(ALL_PODS) && ArtifactLayout.addressable(pushed[0], pushed[1])) {
                return Optional.of(new ArtifactDescriptor(ECOSYSTEM, pushed[0], pushed[1], path,
                        "application/zip", prerelease(pushed[1]), null, -1L));
            }
            return Optional.empty();
        }
        String[] parts = sub.substring(PODS.length(), sub.length() - ZIP.length()).split("/", -1);
        if (parts.length != 3) {
            return Optional.of(ArtifactDescriptor.at(ECOSYSTEM, path));
        }
        String name = parts[0];
        String version = parts[1];
        return Optional.of(new ArtifactDescriptor(ECOSYSTEM, name, version, path,
                "application/zip", prerelease(version), null, -1L));
    }

    /**
     * The pod version a stored CocoaPods pointer serves - the backwards direction the inventory back-fill rebuilds a
     * lost {@code published/} row from.
     *
     * <p>Both of this format's pointer shapes carry the pair in <em>path segments</em>, which is the easy case: the
     * download pointer is {@code cocoapods/<repo>/blob/<name>/<version>} and the podspec stanza is
     * {@code cocoapods/<repo>/spec/<a>/<b>/<c>/<name>/<version>} under the CDN's three-character MD5 shard. A pod
     * name and a pod version are each a single key segment by construction - {@code blobKey} and {@code specKey}
     * compose them as one - so counting segments decides both, and nothing is split on a character a name may
     * contain.
     *
     * <p>Both are claimed rather than just the download, because they are not always both present: a pod whose
     * source is external has a podspec and no stored archive, and that version is exactly as much a published
     * release as one that has both.
     */
    @Override
    public Optional<ArtifactDescriptor> describePointer(String key) {
        String[] parts = key.split("/", -1);
        String name, version;
        if (parts.length == 5 && parts[0].equals("cocoapods") && parts[2].equals("blob")) {
            name = parts[3];
            version = parts[4];
        } else if (parts.length == 8 && parts[0].equals("cocoapods") && parts[2].equals("spec")) {
            name = parts[6];
            version = parts[7];
        } else {
            return Optional.empty();     // a versions listing, an all_pods index: no per-version pointer here
        }
        if (!BlobLayout.addressable(name, version)) {
            return Optional.empty();
        }
        return Optional.of(new ArtifactDescriptor(ECOSYSTEM, name, version, key,
                "application/zip", prerelease(version), null, 0L));
    }

    @Override
    public List<String> paths(String coordinate, String version, ArtifactStore store) {
        // CocoaPods pod pointers live in the shared Blobs namespace (like npm/pypi/go/rpm/cargo/conda/composer), not the
        // Publication namespace coordinate-based eviction walks, so nothing is enumerable from the coordinate alone.
        return List.of();
    }

    /** Read the pod's {@code .podspec.json} from a just-stored archive, inflating only as far as it: a root
     *  {@code <name>.podspec.json} is preferred, else the first one directory deep ({@code <prefix>/*.podspec.json}, as
     *  a VCS-exported archive files it); a podspec deeper than that belongs to a bundled dependency and is ignored.
     *  Only the small JSON is materialised (bounded), and the whole scan is bounded so a hostile archive cannot drive
     *  an unbounded inflate. Returns {@code null} when no usable podspec is found. */
    private static ObjectNode readPodspec(InputStream blob) throws IOException {
        return ArchiveWalk.walk(blob, CocoaPodsFormat::declaredPodspec).orNull();
    }

    /** The podspec inside an already-bounded archive stream, or {@code null} when it carries none. */
    private static ObjectNode declaredPodspec(InputStream archive) throws IOException {
        ZipInputStream zip = new ZipInputStream(archive);
        ObjectNode nested = null;
        for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
            if (entry.isDirectory()) {
                continue;
            }
            String entryName = entry.getName();
            int depth = depth(entryName);
            if (depth == 0 && entryName.endsWith(PODSPEC_SUFFIX)) {
                ObjectNode root = parse(zip);
                if (root != null) {
                    return root;
                }
            } else if (depth == 1 && nested == null && entryName.endsWith(PODSPEC_SUFFIX)) {
                nested = parse(zip);
            }
        }
        return nested;
    }

    /**
     * Parse the current zip entry as a JSON object under the shared archive-inflation ceiling, or {@code null} when it
     * is not a JSON object (an unusable manifest).
     *
     * <p>The podspec is this publish's <b>guard input</b> - {@link #publish} checks the declared name/version against
     * the deploy path so a pod cannot publish itself under another's coordinate - so a read the ceiling stopped takes
     * {@code required(...)} and fails closed rather than answering "this entry declares nothing". Degrading it would
     * be worse here than a lost declaration: the walk would fall through to the fallback one directory deep, which
     * belongs to a <em>bundled dependency</em>, and the pod would be checked against that podspec instead.
     */
    private static ObjectNode parse(InputStream entry) throws IOException {
        byte[] json = ArchiveInflation.entry(entry).required("CocoaPods pod", "podspec");
        try {
            return MAPPER.readTree(json) instanceof ObjectNode object ? object : null;
        } catch (RuntimeException e) {
            // A malformed podspec (Jackson signals a parse failure with an unchecked exception) is not a usable
            // manifest: treat it as absent rather than let the 500 escape.
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

    /** The CDN shard for a pod: the first three hex characters of {@code MD5(name)} (the default {@code [1, 1, 1]}
     *  prefix lengths), which is the CDN's own bucketing so a client computing the same shard finds the pod. */
    static String[] shard(String name) {
        try {
            String hex = HexFormat.of().formatHex(
                    MessageDigest.getInstance("MD5").digest(name.getBytes(StandardCharsets.UTF_8)));
            return new String[]{hex.substring(0, 1), hex.substring(1, 2), hex.substring(2, 3)};
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 is a required JDK algorithm", e);
        }
    }

    /** Whether a value is a single lower-case hex character (a valid CDN shard segment, as {@code MD5} hex is emitted). */
    private static boolean hex(String value) {
        if (value.length() != 1) {
            return false;
        }
        char c = value.charAt(0);
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
    }

    /** Whether a CocoaPods version denotes a prerelease (a semantic-version {@code -} pre-release suffix). */
    private static boolean prerelease(String version) {
        return version.indexOf('-') >= 0;
    }

    private static String text(JsonNode node, String field) {
        return node == null ? null : node.path(field).asString(null);
    }

    /** The absolute download URL for a pod version, generated from the serving request so it routes back to whatever
     *  host serves this registry. */
    private static String downloadUrl(String repo, String name, String version, FormatExchange exchange) {
        return repoBase(repo, exchange) + "/" + PODS + name + "/" + version + "/" + name + ZIP;
    }

    /** The external base URL of this registry ({@code <scheme>://<host><prefix>/cocoapods/<repo>}), for download URLs. */
    private static String repoBase(String repo, FormatExchange exchange) {
        return RequestBase.of(exchange) + exchange.external(PREFIX + repo);
    }

    /**
     * A {@code name} or {@code version} becomes store-key path segments (the download pointer {@link #blobKey} and the
     * podspec stanza {@link #specKey}) and a routed request path, so a value that is empty, carries a path separator or
     * control character, or is a {@code .}/{@code ..} traversal segment could steer a write or read outside the pod's
     * key space and is refused. Real CocoaPods pod names are identifiers and versions carry no {@code /}, so no
     * legitimate value is rejected.
     */

    static String shardPrefix(String repo, String[] shard) {
        return "cocoapods/" + repo + "/spec/" + shard[0] + "/" + shard[1] + "/" + shard[2];
    }

    static String specKey(String repo, String[] shard, String name, String version) {
        return shardPrefix(repo, shard) + "/" + name + "/" + version;
    }

    static String blobKey(String repo, String name, String version) {
        return "cocoapods/" + repo + "/blob/" + name + "/" + version;
    }

    /** The migration-import capability (WSPI.2 (c)), delegated to the layout-only {@link CocoaPodsImporter} - the format IS the
     *  discovered importer now (an {@code instanceof} capability), and the importer class stays as its delegate. */
    private final CocoaPodsImporter importer = new CocoaPodsImporter();

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

    /** Each registry's pod archive of the version is put where a pod push goes - {@code <repo>/<name>/<version>} - and
     *  asked for back at the path it is served from; the target derives its own podspec stanza from the archive. */
    @Override
    public Exported export(ArtifactStore repository, String coordinate, String version, ExportTarget target)
            throws IOException {
        if (!BlobLayout.addressable(coordinate, version)) {
            return Exported.WITHHELD;
        }
        List<BlobExport.Pair> pairs = new ArrayList<>();
        for (String repo : repository.list("cocoapods")) {
            pairs.add(new BlobExport.Pair(blobKey(repo, coordinate, version), repo + "/" + coordinate + "/" + version,
                    repo + "/" + PODS + coordinate + "/" + version + "/" + coordinate + ZIP));
        }
        return BlobExport.put(repository, pairs, target);
    }
}
