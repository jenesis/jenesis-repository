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
import build.jenesis.repository.store.Publication;

/**
 * The CocoaPods registry format (the CocoaPods CDN protocol): {@code pod install} and {@code pod update} over the
 * shared store, under {@code /cocoapods/...}, the first segment a registry. A pod is pushed with
 * {@code PUT /cocoapods/<repo>/<name>/<version>} (its zip as the body) and downloaded from
 * {@code /cocoapods/<repo>/pods/<name>/<version>/<name>.zip}. Of the CDN metadata a client reads, the root
 * {@code CocoaPods-version.yml} and each {@code Specs/<a>/<b>/<c>/<name>/<version>/<name>.podspec.json} are generated
 * on read and the sharded {@code all_pods_versions_<a>_<b>_<c>.txt} are stored listings every publish maintains.
 *
 * <p><b>The CDN shard.</b> A pod lives in the shard named by the first three hex characters of {@code MD5(name)} (the
 * {@code prefix_lengths: [1, 1, 1]} advertised in {@code CocoaPods-version.yml}), which a client computes too, so each
 * podspec is stored under {@code cocoapods/<repo>/spec/<a>/<b>/<c>/<name>/<version>} and a read is a direct lookup.
 * {@code MD5} here is the protocol's shard function, not a security digest.
 *
 * <p><b>Streaming publish.</b> The zip streams through {@link ArtifactStore#writeBlob} into the content-addressed
 * store. The coordinate, licence and dependencies live in a {@code <name>.podspec.json} inside the archive, so the
 * stored blob is reopened ({@link ArtifactStore#open}) and only the podspec - at the root or one directory deep - is
 * read, bounded. It is stored per version with {@code name}/{@code version} normalised to the deploy path and its
 * {@code source} removed; a read adds a {@code source} pointing the download here, so an imported pod still resolves.
 *
 * <p>The ecosystem is {@code "CocoaPods"}, and {@link #describe} maps a download path to its {@code <name>} coordinate
 * and version. OSV has no CocoaPods feed, so vulnerability screening finds nothing while licence and malicious-package
 * screening key on the coordinate. Pointers live in the shared {@code Blobs} namespace, so {@link #paths} is empty and
 * a coordinate is reached through {@link #blobKeys} and {@link #servedPaths}.
 *
 * <p><b>Pull-through proxy.</b> A local miss is served from an upstream CDN, {@code cdn.cocoapods.org} by default,
 * {@code /cocoapods/<repo>/<sub>} mapping to {@code <upstream>/<sub>}. {@code CocoaPods-version.yml} is always local. A
 * shard listing is mutable and streamed fresh, carrying no URLs. A podspec is mutable and fetched fresh, an
 * {@code :http} zip {@code source} rewritten to route the download here and any other source passed through, since the
 * CDN hosts only metadata and only an http zip is a URL this registry can cache. A pod archive is immutable: on its
 * first download its URL is resolved from the upstream podspec, and it streams into the store ({@link ProxyRelay#fill})
 * and is served.
 */
public final class CocoaPodsFormat implements RepositoryFormat, ArtifactLayout, ProxyLeg, BlobLayout, RepositoryImporter,
        RepositoryExporter {

    /** The ecosystem name this format's artifacts report, distinct from {@link #name()}, the routing id. */
    public static final String ECOSYSTEM = "CocoaPods";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The canonical public CDN mirrored when a deployment names no upstream: the trunk CDN at
     *  {@code cdn.cocoapods.org}. */
    private static final URI CDN = URI.create("https://cdn.cocoapods.org");

    private static final String PREFIX = "/cocoapods/";
    private static final String VERSION_FILE = "CocoaPods-version.yml";
    private static final String ALL_PODS = "all_pods_versions_";
    private static final String TXT = ".txt";
    private static final String SPECS = "Specs/";
    private static final String PODS = "pods/";
    private static final String PODSPEC_SUFFIX = ".podspec.json";
    private static final String ZIP = ".zip";

    /** The CDN version document advertising the default {@code [1, 1, 1]} sharding; {@code min} and {@code last} are
     *  the client versions the CDN reports. A fixed document, written literally. */
    private static final byte[] VERSION_YML = ("---\nmin: 1.0.0\nlast: 1.16.2\nprefix_lengths:\n- 1\n- 1\n- 1\n")
            .getBytes(StandardCharsets.UTF_8);

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
            // A traversal-shaped coordinate or version maps nowhere, since an eviction deletes these keys; judged part
            // by part.
            return List.of();
        }
        // A pod version is its download pointer cocoapods/<repo>/blob/<name>/<version> and its podspec stanza under the
        // MD5 shard, both bodies being blob hashes from which the withhold set derives; a hold marks those hashes and
        // eviction deletes these keys. Each registry is probed, the set being operator-configured; the two keys are
        // exact lookups.
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

    /** The request paths this pod version's download serves at
     *  ({@code /cocoapods/<repo>/pods/<name>/<version>/<name>.zip}) in every registry holding it, where a retroactive
     *  hold links its {@code /quarantine} handles. The podspec stanza is withheld but has no download path. Only
     *  pointers are read. */
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

    /** Stream a pod upload into the store while reading only its {@code .podspec.json}, then record the download
     *  pointer and the podspec stanza under the pod's shard. */
    private void publish(String repo, String sub, FormatExchange exchange, ArtifactStore store) throws IOException {
        // A publish is <name>/<version>; no read route is exactly two segments.
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
            // The podspec's name and version must match the deploy path, or a pod could publish under another's
            // coordinate.
            exchange.respond(400);
            return;
        }
        ObjectNode stanza = podspec.deepCopy();
        stanza.put("name", name);
        stanza.put("version", version);
        // The download source is generated on read from the serving request, so the stored stanza carries no host.
        stanza.remove("source");
        String[] shard = shard(name);
        // Blobs.link retries the compare-and-set and clears any gc/condemned marker on a byte-identical blob, so a
        // republish is not collected after its 201.
        Blobs blobs = new Blobs(store);
        try {
            blobs.linkRelease(blobKey(repo, name, version), hash, -1L);
        } catch (Publication.RepublishConflict taken) {
            exchange.respond(409, Blobs.alreadyPublished(name + " " + version));
            return;
        }
        blobs.write(specKey(repo, shard, name, version), MAPPER.writeValueAsBytes(stanza));
        // The shard listing is maintained on the publish: the version joins the pod's list, which re-derives its shard
        // line.
        new CocoaPodsListings(blobs).refresh(repo, name, version);
        exchange.respond(201);
    }

    /** The sharded pod-versions listing, the stored listing every publish maintains: {@code <a>_<b>_<c>} is a shard,
     *  and the file has a line per pod in it, {@code <name>/<v1>/<v2>/...}. An empty shard is a {@code 404}, which a
     *  proxy fills from upstream. */
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

    /** Serve a version's podspec from the stored stanza with a fresh {@code source} pointing the download here. The
     *  path is {@code Specs/<a>/<b>/<c>/<name>/<version>/<name>.podspec.json}, keyed like the stanza, so a client's own
     *  shard resolves to where the pod was stored, or a {@code 404}. */
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
        // A withheld version's podspec is screened out, since its archive 404s and the podspec would disclose a
        // quarantined pod.
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
        // The download source comes from the serving request, so the pod resolves to whatever host serves this
        // registry.
        ObjectNode source = MAPPER.createObjectNode();
        source.put("http", downloadUrl(repo, name, version, exchange));
        podspec.set("source", source);
        // A DEPRECATED mark surfaces as the podspec's own deprecated flag, which pod install prints. CocoaPods has
        // nothing else, so a YANKED mark instead drops the version from the shard listing. An unmarked podspec gets no
        // field the publisher did not write.
        Lifecycle.read(blobs.store(), name, version)
                .filter(flag -> flag.state() == Lifecycle.State.DEPRECATED)
                .ifPresent(flag -> podspec.put("deprecated", true));
        exchange.setResponseHeader("Content-Type", "application/json");
        exchange.answer(MAPPER.writeValueAsBytes(podspec));
    }

    /** Serve a pod archive from the store; the path is {@code <name>/<version>/<file>.zip}. */
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

    /** The trunk CDN, mirrored when a deployment names no upstream; a repository can set another. */
    @Override
    public Optional<URI> defaultUpstream() {
        return Optional.of(CDN);
    }

    /** Proxy a CocoaPods CDN miss to an upstream CDN: {@code CocoaPods-version.yml} is always local; a shard listing
     *  streams fresh; a podspec is fetched fresh with an http-zip {@code source} rewritten through this registry; a pod
     *  archive is fetched, cached and served, its URL resolved from the upstream podspec.
     *  {@code /cocoapods/<repo>/<sub>} maps to {@code <upstream>/<sub>}. {@code false} lets the local {@code 404}
     *  stand. */
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
            // The local version document never misses.
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

    /** Stream an upstream shard listing fresh, never cached: it carries no URLs and can be large. The target is rebuilt
     *  from validated hex shard segments, so a crafted path cannot steer the fetch. */
    private boolean proxyShardListing(String sub, String root, FormatExchange exchange, ProxyFormat.Fetcher fetcher)
            throws IOException {
        String[] shard = sub.substring(ALL_PODS.length(), sub.length() - TXT.length()).split("_", -1);
        if (shard.length != 3 || !hex(shard[0]) || !hex(shard[1]) || !hex(shard[2])) {
            return false;
        }
        URI target = URI.create(root + "/" + ALL_PODS + shard[0] + "_" + shard[1] + "_" + shard[2] + TXT);
        // The shard listing is the pod-version list a Podfile resolves against, an ENUMERATION: only an upstream that
        // answered 404/410 reaches the client as one. Served as text/plain by default, as the CDN serves it.
        return ProxyRelay.streamFresh(fetcher, target, "text/plain", exchange, ProxyRelay.Document.ENUMERATION);
    }

    /** Fetch an upstream podspec, rewrite an http-zip {@code source} through this registry, and stream it fresh; a
     *  podspec is small metadata, so it may be held to rewrite. Another source is left for the client to fetch
     *  directly. Shard, name and version are validated as the local {@link #spec} validates them. */
    private boolean proxySpec(String repo, String sub, String root, FormatExchange exchange,
                              ProxyFormat.Fetcher fetcher) throws IOException {
        String[] parts = sub.split("/", -1);
        if (parts.length != 7 || !hex(parts[1]) || !hex(parts[2]) || !hex(parts[3])
                || Keys.unsafe(parts[4]) || Keys.unsafe(parts[5]) || !parts[6].equals(parts[4] + PODSPEC_SUFFIX)) {
            return false;
        }
        String name = parts[4];
        String version = parts[5];
        // PINNED: the request names the pod and version, so its absence decides nothing about what exists.
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

    /** Fetch, cache and serve an immutable pod archive: its http-zip {@code source} resolved from the upstream podspec,
     *  the archive streamed into the store ({@link ProxyRelay#fill}), then served locally. A pod whose source is not an
     *  http zip was never rewritten to a local download, so the miss is declined. */
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
        // The podspec's http source may declare the archive's :sha256 or :sha1, and the streamed archive is held to it.
        // The podspec is the same document that locates the download, so an unreadable one already declined above; a
        // podspec declaring no checksum, which CocoaPods allows, caches unverified.
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

    /** A resolved pod download: the archive URL and, when declared, the checksum to verify it against. */
    private record Source(URI url, String algorithm, byte[] expected) {
    }

    /** Resolve a pod version's upstream download from the upstream podspec at its shard: its http-zip {@code source}
     *  and, where declared, its {@code :sha256} or {@code :sha1}. A bounded read once per archive miss; {@code null}
     *  when the podspec is absent or its source is no http zip. */
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
            // source.http is untrusted upstream metadata - a publisher could point it at an internal or plaintext host
            // - so it is screened, and a refused target falls through to a 404 (ProxyLeg clause 2).
            if (!OutboundTargets.mayFollow(source, URI.create(root), allowInternal)) {
                return null;
            }
            // The archive checksum: :sha256 preferred, else :sha1.
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

    /** The {@code source.http} of a podspec when it is an http zip, a URL this registry can cache and serve as a
     *  {@code .zip}, else {@code null} for a git checkout, a tarball or another source, which the proxy passes through.
     *  The extension is matched on the URL path, so a {@code .tar.gz} is never served as a zip. */
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

    /** Parse an upstream document as a JSON object, or {@code null} when malformed or not an object, read as a miss
     *  rather than a {@code 500}. */
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
            // The publish path <name>/<version> is where the gate links a review pointer when it holds one, so a
            // release's cross-alias guard asks for it; the served path carries the same coordinate.
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

    /** The pod version a stored CocoaPods pointer serves, from which the inventory back-fill rebuilds a lost
     *  {@code published} record. Both pointer shapes carry the pair in segments: the download
     *  {@code cocoapods/<repo>/blob/<name>/<version>} and the podspec stanza
     *  {@code cocoapods/<repo>/spec/<a>/<b>/<c>/<name>/<version>}, a name and a version each one segment by
     *  construction. Both are claimed, since a pod with an external source has a podspec and no stored archive. */
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
        // Pointers live in the shared Blobs namespace, so the coordinate enumerates nothing in publish/.
        return List.of();
    }

    /** Read the pod's {@code .podspec.json} from a stored archive, inflating only as far as it: at the root, else one
     *  directory deep, as a VCS export files it; one deeper belongs to a bundled dependency. Bounded; {@code null} when
     *  none is usable. */
    private static ObjectNode readPodspec(InputStream blob) throws IOException {
        return ArchiveWalk.walk(blob, CocoaPodsFormat::declaredPodspec).orNull();
    }

    /** The podspec inside an already-bounded archive stream, or {@code null} when it carries none. */
    private static ObjectNode declaredPodspec(InputStream archive) throws IOException {
        ZipInputStream zip = ArchiveWalk.zip(archive);
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

    /** Parse the current zip entry as a JSON object under the shared inflation ceiling, or {@code null} when it is
     *  none. The podspec is the publish's guard input, so a read the ceiling stopped fails closed: degrading it would
     *  fall through to a bundled dependency's podspec one directory deeper. */
    private static ObjectNode parse(InputStream entry) throws IOException {
        byte[] json = ArchiveInflation.entry(entry).required("CocoaPods pod", "podspec");
        try {
            return MAPPER.readTree(json) instanceof ObjectNode object ? object : null;
        } catch (RuntimeException e) {
            // A malformed podspec is no usable manifest: treated as absent rather than a 500.
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

    /** The CDN shard of a pod: the first three hex characters of {@code MD5(name)}, the CDN's own bucketing. */
    static String[] shard(String name) {
        try {
            String hex = HexFormat.of().formatHex(
                    MessageDigest.getInstance("MD5").digest(name.getBytes(StandardCharsets.UTF_8)));
            return new String[]{hex.substring(0, 1), hex.substring(1, 2), hex.substring(2, 3)};
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 is a required JDK algorithm", e);
        }
    }

    /** Whether a value is one lower-case hex character, a valid shard segment. */
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

    /** The absolute download URL of a pod version, from the serving request. */
    private static String downloadUrl(String repo, String name, String version, FormatExchange exchange) {
        return repoBase(repo, exchange) + "/" + PODS + name + "/" + version + "/" + name + ZIP;
    }

    /** The external base URL of this registry ({@code <scheme>://<host><prefix>/cocoapods/<repo>}), for download URLs. */
    private static String repoBase(String repo, FormatExchange exchange) {
        return RequestBase.of(exchange) + exchange.external(PREFIX + repo);
    }

    /** The store key prefix of a shard's podspec stanzas. */

    static String shardPrefix(String repo, String[] shard) {
        return "cocoapods/" + repo + "/spec/" + shard[0] + "/" + shard[1] + "/" + shard[2];
    }

    static String specKey(String repo, String[] shard, String name, String version) {
        return shardPrefix(repo, shard) + "/" + name + "/" + version;
    }

    static String blobKey(String repo, String name, String version) {
        return "cocoapods/" + repo + "/blob/" + name + "/" + version;
    }

    /** The migration-import capability, delegated to {@link CocoaPodsImporter}. */
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

    /** Each registry's pod archive of the version is put where a push goes, {@code <repo>/<name>/<version>}, unless its
     *  served path already answers; the target derives its own stanza. */
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
