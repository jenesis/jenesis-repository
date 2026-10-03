package build.jenesis.repository.format.helm;

import module java.base;
import module org.apache.commons.compress;
import module org.yaml.snakeyaml;

import build.jenesis.repository.format.LifecycleMark;
import build.jenesis.repository.format.Listings;
import build.jenesis.repository.blobs.BlobExport;
import build.jenesis.repository.blobs.BlobLayout;
import build.jenesis.repository.format.ExportTarget;
import build.jenesis.repository.format.RepositoryExporter;
import build.jenesis.repository.format.ArtifactSignatures;
import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.blobs.Keys;
import build.jenesis.repository.blobs.OutboundTargets;
import build.jenesis.repository.blobs.ProxyLeg;
import build.jenesis.repository.blobs.ProxyRelay;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.ArtifactLayout;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.store.ArchiveInflation;
import build.jenesis.repository.store.ArchiveWalk;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.StoredListing;
import build.jenesis.repository.store.Publication;

/**
 * The classic Helm chart repository - {@code index.yaml} and {@code .tgz} charts - so {@code helm repo add},
 * {@code helm install} and {@code helm pull} work over the shared store.
 *
 * <p>It owns {@code /helm/<repo>/...}: the index at {@code index.yaml}, a chart at {@code charts/<name>-<version>.tgz},
 * and two publish routes - {@code POST api/charts} with the archive as the body (ChartMuseum's call, which
 * {@code helm cm-push} makes) and {@code PUT charts/<name>-<version>.tgz}, the direct form the importer replays
 * through.
 *
 * <p><b>The coordinate comes from inside the archive.</b> Chart names may contain hyphens, so
 * {@code <name>-<version>.tgz} does not say where the version begins; {@code Chart.yaml}, which Helm treats as
 * authoritative, does. The publish reads the coordinate from it and, on the {@code PUT} route, refuses an archive whose
 * metadata disagrees with its path. The download keys its pointer on the whole file name, so it never splits.
 *
 * <p><b>The digest in the index is the store's</b>: the SHA-256 computed as the archive streamed in, so Helm verifies
 * the bytes this repository serves. Only {@code Chart.yaml} is materialised, through the shared archive-walk and
 * inflation bounds, and parsed with SnakeYAML's {@code SafeConstructor}, which instantiates nothing.
 *
 * <p>Chart pointers live in the shared {@code Blobs} namespace, so {@link #paths} is empty and coordinate-scoped
 * enforcement runs through the {@link BlobLayout} seam.
 */
public final class HelmFormat implements RepositoryFormat, ArtifactLayout, BlobLayout, RepositoryImporter.Delegating,
        ArtifactSignatures, RepositoryExporter, ProxyLeg {

    /** The ecosystem name Helm coordinates report. OSV has no Helm feed, so vulnerability lookup finds nothing while
     *  the deny-list, the malicious-package flag and licence policy still apply. */
    public static final String ECOSYSTEM = "Helm";

    private static final String PREFIX = "/helm/";

    private static final String INDEX = "index.yaml";
    private static final String CHARTS = "charts/";
    private static final String PUSH = "api/charts";
    private static final String TGZ = ".tgz";

    /** A chart's provenance file, {@code <chart>.tgz.prov}: an OpenPGP clearsigned document over the chart's
     *  {@code Chart.yaml} and its archive's SHA-256, which {@code helm verify} reads. */
    private static final String PROV = ".tgz.prov";

    /** The chart's signature is its provenance sidecar: optional, clearsigned rather than detached (the statement it
     *  covers names the archive by digest, and the verifier checks both), and never asked of the sidecar itself. */
    private static final ArtifactSignatures SIGNATURES = ArtifactSignatures.detachedSidecar(ECOSYSTEM, ".prov",
            ArtifactSignatures.Scheme.OPENPGP_CLEARSIGNED, HelmFormat::signable, ArtifactSignatures.Coverage.OPTIONAL);

    private static boolean signable(String path) {
        return path.startsWith(PREFIX) && path.endsWith(TGZ) && path.contains("/" + CHARTS);
    }

    @Override
    public List<ArtifactSignatures.Expectation> expects(String path) {
        return SIGNATURES.expects(path);
    }

    @Override
    public Optional<String> covers(String path) {
        return SIGNATURES.covers(path);
    }

    @Override
    public List<ArtifactSignatures.Evidence> evidence(String path, ArtifactSignatures.Material material)
            throws IOException {
        return SIGNATURES.evidence(path, material);
    }

    /** A request path's serving key for the compliance screen's sibling read: a chart or its provenance file under
     *  {@code charts/}, both keyed by file name, when the pointer exists. */
    @Override
    public Optional<String> servingKey(String requestPath, ArtifactStore store) throws IOException {
        if (!requestPath.startsWith(PREFIX)) {
            return Optional.empty();
        }
        String rest = requestPath.substring(PREFIX.length());
        int slash = rest.indexOf('/');
        if (slash <= 0) {
            return Optional.empty();
        }
        String repo = rest.substring(0, slash), sub = rest.substring(slash + 1);
        if (Keys.unsafe(repo) || !sub.startsWith(CHARTS) || !(sub.endsWith(TGZ) || sub.endsWith(PROV))) {
            return Optional.empty();
        }
        String file = sub.substring(CHARTS.length());
        if (file.indexOf('/') >= 0 || Keys.unsafe(file)) {
            return Optional.empty();
        }
        String key = fileKey(repo, file);
        return store.readVersioned(key).isPresent() ? Optional.of(key) : Optional.empty();
    }

    /** Store a chart's provenance file beside its archive. The body is held to the seam's signature bound and must be a
     *  clearsigned document, else {@code 400}. It is stored whether or not the chart has arrived, and the gate reads it
     *  as the chart's sidecar either way. */
    private void provenance(String repo, String file, FormatExchange exchange, Blobs blobs) throws IOException {
        if (file.indexOf('/') >= 0 || Keys.unsafe(file)) {
            exchange.respond(404);
            return;
        }
        byte[] body;
        try (InputStream in = exchange.requestStream()) {
            body = in.readNBytes(ArtifactSignatures.Material.LARGEST_SIGNATURE + 1);
        }
        if (body.length > ArtifactSignatures.Material.LARGEST_SIGNATURE) {
            exchange.respond(413);
            return;
        }
        if (!new String(body, 0, Math.min(body.length, 64), StandardCharsets.US_ASCII).strip()
                .startsWith("-----BEGIN PGP SIGNED MESSAGE-----")) {
            exchange.respond(400);
            return;
        }
        blobs.write(fileKey(repo, file), body);
        exchange.respond(201);
    }

    private static final String CHART_YAML = "Chart.yaml";

    private final HelmImporter importer = new HelmImporter();

    @Override
    public RepositoryImporter importer() {
        return importer;
    }

    public HelmFormat() {
    }

    @Override
    public String name() {
        return "helm";
    }

    /** The marks this format's clients see, each by its own word. */
    @Override
    public Map<LifecycleMark, String> lifecycleMarks() {
        return LifecycleMark.shown(LifecycleMark.DEPRECATED, LifecycleMark.YANKED);
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
        switch (exchange.method()) {
            case "POST" -> {
                if (sub.equals(PUSH)) {
                    publish(repo, null, exchange, blobs);
                } else {
                    exchange.respond(404);
                }
            }
            case "PUT" -> {
                if (sub.startsWith(CHARTS) && sub.endsWith(TGZ)) {
                    publish(repo, sub.substring(CHARTS.length()), exchange, blobs);
                } else if (sub.startsWith(CHARTS) && sub.endsWith(PROV)) {
                    provenance(repo, sub.substring(CHARTS.length()), exchange, blobs);
                } else {
                    exchange.respond(404);
                }
            }
            case "GET", "HEAD" -> {
                if (sub.equals(INDEX)) {
                    index(repo, exchange, blobs);
                } else if (sub.startsWith(CHARTS) && (sub.endsWith(TGZ) || sub.endsWith(PROV))) {
                    download(repo, sub.substring(CHARTS.length()), exchange, blobs);
                } else {
                    exchange.respond(404);
                }
            }
            default -> exchange.respond(405);
        }
    }

    /**
     * Stream a chart into the store, read its {@code Chart.yaml} back out of the stored blob, and record the pointer
     * and the index stanza {@code index.yaml} is assembled from. Only {@code Chart.yaml} is materialised.
     *
     * @param file the file name when the route carried one ({@code PUT}), or {@code null} for the ChartMuseum
     *     {@code POST} - the coordinate is the archive's own either way, and the file name is a claim to check
     */
    private void publish(String repo, String file, FormatExchange exchange, Blobs blobs) throws IOException {
        ArtifactStore store = blobs.store();
        String hash = blobs.store(exchange.requestStream());
        Map<?, ?> chart;
        try (InputStream blob = store.open("blobs/" + hash)) {
            chart = chartYaml(blob);
        } catch (IOException unreadable) {
            chart = null;
        }
        if (chart == null) {
            // Not a readable gzipped tar with a Chart.yaml: refuse rather than store bytes nothing can name.
            exchange.respond(400);
            return;
        }
        String name = text(chart, "name");
        String version = text(chart, "version");
        if (name == null || version == null || Keys.unsafe(name) || Keys.unsafe(version)) {
            exchange.respond(400);
            return;
        }
        String expected = name + "-" + version + TGZ;
        if (file != null && !file.equals(expected)) {
            // The archive's metadata disagrees with its path: refuse, so a chart cannot publish under another's
            // coordinate.
            exchange.respond(400);
            return;
        }
        try {
            blobs.linkRelease(blobKey(repo, name, version), hash, -1L);
        } catch (Publication.RepublishConflict taken) {
            exchange.respond(409, Blobs.alreadyPublished(name + " " + version));
            return;
        }
        blobs.write(entryKey(repo, name, version), stanza(chart, name, version, hash, expected));
        new HelmListings(blobs).refresh(repo, name);
        exchange.respond(201);
    }

    /** Stream the stored index document as it is - one read, no rendering, no enumeration. */
    private void index(String repo, FormatExchange exchange, Blobs blobs) throws IOException {
        Optional<StoredListing.Served> served = StoredListing.open(blobs.store(),
                new HelmListings(blobs).indexSpec(repo));
        if (served.isEmpty()) {
            exchange.respond(404);
            return;
        }
        try (StoredListing.Served document = served.get()) {
            // Handed over whole rather than streamed: `helm repo update` polls this document, and only a whole response
            // carries the content-derived validator that lets an unchanged index be answered 304.
            Listings.serve(exchange, document, "application/x-yaml");
        }
    }

    /** Stream one chart archive. The pointer is keyed on the whole file name, so the read never splits name from
     *  version. */
    private void download(String repo, String file, FormatExchange exchange, Blobs blobs) throws IOException {
        if (file.indexOf('/') >= 0 || Keys.unsafe(file)) {
            exchange.respond(404);
            return;
        }
        Optional<Blobs.Located> located = blobs.locate(fileKey(repo, file));
        if (located.isEmpty()) {
            exchange.respond(404);
            return;
        }
        long size = located.get().size();
        exchange.setResponseHeader("Content-Type", file.endsWith(PROV) ? "text/plain" : "application/gzip");
        if (exchange.method().equals("HEAD")) {
            exchange.setResponseHeader("Content-Length", Long.toString(size));
            exchange.respond(200, -1L).close();
            return;
        }
        blobs.serve(located.get(), exchange);
    }

    // ---- proxy

    /**
     * Proxy a miss to an upstream classic chart repository: an {@code index.yaml} at its root naming each version's
     * archive and {@code digest}.
     *
     * <p>The index is an ENUMERATION, fetched fresh on every read; only an upstream that answered 404/410 reaches the
     * client as a 404. It is rewritten on the way out: each version's {@code urls} become {@code charts/<file>}
     * relative to this repository, so a client installs through this repository's cache and gate, while the
     * {@code digest} stays as it was. A version whose URL names no chart file is left out.
     *
     * <p>A chart archive is PINNED. Its location and digest come from the same index, so an unreadable index declines
     * the fill for want of a location. The upstream chooses the location, often on another host, so it passes the
     * outbound screen ({@link OutboundTargets}) first. Helm has no canonical public repository, so
     * {@link #defaultUpstream()} is empty.
     */
    @Override
    public boolean pullThrough(FormatExchange exchange, ArtifactStore store, URI upstream,
                               ProxyFormat.Fetcher fetcher) throws IOException {
        String rest = exchange.path().substring(PREFIX.length());
        int slash = rest.indexOf('/');
        if (slash < 0) {
            return false;
        }
        String repo = rest.substring(0, slash), sub = rest.substring(slash + 1);
        String root = upstream.toString().endsWith("/") ? upstream.toString() : upstream + "/";
        URI index = URI.create(root + INDEX);
        if (sub.equals(INDEX)) {
            ProxyRelay.Answer answer = ProxyRelay.fetchFresh(fetcher, index, Map.of(), exchange,
                    ProxyRelay.Document.ENUMERATION);
            if (!answer.answered()) {
                return answer.served();
            }
            Map<?, ?> document = upstreamIndex(answer.document().body());
            if (document == null) {
                return ProxyRelay.unanswered(index, exchange, ProxyRelay.Document.ENUMERATION,
                        "the upstream answered an index.yaml that is not a chart repository index");
            }
            exchange.setResponseHeader("Content-Type", "application/x-yaml");
            exchange.respond(200, rewritten(document, index).getBytes(StandardCharsets.UTF_8));
            return true;
        }
        if (!sub.startsWith(CHARTS) || !sub.endsWith(TGZ)) {
            return false;
        }
        String file = sub.substring(CHARTS.length());
        if (file.indexOf('/') >= 0 || Keys.unsafe(file)) {
            return false;
        }
        Optional<ProxyFormat.Fetched> fetched = fetcher.beside().fetch(index, Map.of());
        if (fetched.isEmpty() || fetched.get().status() != 200) {
            return false;
        }
        Map<?, ?> document = upstreamIndex(fetched.get().body());
        Upstream chart = document == null ? null : locate(document, index, file);
        if (chart == null || !OutboundTargets.mayFollow(chart.url(), upstream,
                ProxyLeg.allowInternalTargets(exchange))) {
            return false;
        }
        Blobs blobs = new Blobs(store);
        try (ProxyFormat.Download download = fetcher.download(chart.url(), Map.of()).orElse(null)) {
            if (download == null || download.status() != 200
                    || !ProxyRelay.fill(blobs, fileKey(repo, file), chart.url(), download.body(), chart.digest())) {
                return false;
            }
        }
        download(repo, file, exchange, blobs);
        return true;
    }

    /** Where a proxied chart's archive is, and the digest its index entry declares for it. */
    private record Upstream(URI url, ProxyRelay.Declared digest) {
    }

    /** An upstream {@code index.yaml}, or {@code null} when the body is not a YAML mapping with an {@code entries}
     *  mapping. Read whole, since it is rewritten; the fetch already bounds it, so the parser's ceiling is raised to
     *  the body's size. */
    private static Map<?, ?> upstreamIndex(byte[] body) {
        try {
            Object loaded = indexYaml(body.length).load(new ByteArrayInputStream(body));
            return loaded instanceof Map<?, ?> document && document.get("entries") instanceof Map<?, ?>
                    ? document : null;
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    /** The index as this repository serves it: every version's {@code urls} pointing at its own {@code charts/}. */
    private static String rewritten(Map<?, ?> document, URI index) {
        Map<Object, Object> rewritten = new LinkedHashMap<>(document);
        Map<Object, Object> entries = new LinkedHashMap<>();
        for (Map.Entry<?, ?> chart : ((Map<?, ?>) document.get("entries")).entrySet()) {
            if (!(chart.getValue() instanceof List<?> versions)) {
                continue;
            }
            List<Object> served = new ArrayList<>();
            for (Object version : versions) {
                if (!(version instanceof Map<?, ?> entry)) {
                    continue;
                }
                List<String> files = new ArrayList<>();
                for (URI url : urls(entry, index)) {
                    String file = chartFile(url);
                    if (file != null && !files.contains(CHARTS + file)) {
                        files.add(CHARTS + file);
                    }
                }
                if (!files.isEmpty()) {
                    Map<Object, Object> copy = new LinkedHashMap<>(entry);
                    copy.put("urls", files);
                    served.add(copy);
                }
            }
            if (!served.isEmpty()) {
                entries.put(chart.getKey(), served);
            }
        }
        rewritten.put("entries", entries);
        return indexYaml(1).dump(rewritten);
    }

    /** The YAML an upstream index is read and written back with. Scalars other than booleans and nulls stay text: a
     *  default reading turns {@code version: 1.10} into {@code 1.1} and {@code created} into a date, which writing back
     *  would change. */
    private static Yaml indexYaml(int limit) {
        LoaderOptions loading = new LoaderOptions();
        loading.setCodePointLimit(Math.max(limit, 1));
        loading.setAllowDuplicateKeys(false);
        DumperOptions dumping = new DumperOptions();
        dumping.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        dumping.setSplitLines(false);
        return new Yaml(new SafeConstructor(loading), new Representer(dumping), dumping, loading, new Resolver() {
            @Override
            protected void addImplicitResolvers() {
                addImplicitResolver(Tag.BOOL, BOOL, "yYnNtTfFoO");
                addImplicitResolver(Tag.NULL, NULL, "~nN\0");
                addImplicitResolver(Tag.NULL, EMPTY, null);
            }
        });
    }

    /** The version whose archive is {@code file}, located against the index it was read from. */
    private static Upstream locate(Map<?, ?> document, URI index, String file) {
        for (Object versions : ((Map<?, ?>) document.get("entries")).values()) {
            if (!(versions instanceof List<?> list)) {
                continue;
            }
            for (Object version : list) {
                if (!(version instanceof Map<?, ?> entry)) {
                    continue;
                }
                for (URI url : urls(entry, index)) {
                    if (file.equals(chartFile(url))) {
                        String digest = text(entry, "digest");
                        return new Upstream(url, digest != null && digest.matches("[0-9a-fA-F]{64}")
                                ? ProxyRelay.Declared.of("SHA-256", HexFormat.of().parseHex(digest))
                                : ProxyRelay.Declared.NONE);
                    }
                }
            }
        }
        return null;
    }

    /** A version entry's {@code urls}, each resolved against the index, which may name archives relative to itself; one
     *  that is not a URL is left out. */
    private static List<URI> urls(Map<?, ?> entry, URI index) {
        List<URI> urls = new ArrayList<>();
        if (entry.get("urls") instanceof List<?> listed) {
            for (Object url : listed) {
                if (url instanceof String text) {
                    try {
                        urls.add(index.resolve(text.strip()));
                    } catch (IllegalArgumentException _) {
                        // not a URL: nothing this repository could fetch or serve under it
                    }
                }
            }
        }
        return urls;
    }

    /** The chart archive a URL names - the last path segment when it is a {@code .tgz} this repository can key - or
     *  {@code null}. */
    private static String chartFile(URI url) {
        String path = url.getPath();
        if (path == null) {
            return null;
        }
        String file = path.substring(path.lastIndexOf('/') + 1);
        return file.endsWith(TGZ) && !file.equals(TGZ) && !Keys.unsafe(file) ? file : null;
    }

    // ---- layout

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
        if (sub.startsWith(CHARTS) && sub.endsWith(PROV) && sub.indexOf('/', CHARTS.length()) < 0) {
            return Optional.of(ArtifactDescriptor.at(ECOSYSTEM, path));   // a chart's provenance file: material, not a chart
        }
        if (!sub.startsWith(CHARTS) || !sub.endsWith(TGZ) || sub.indexOf('/', CHARTS.length()) >= 0) {
            return Optional.empty();
        }
        String[] split = split(sub.substring(CHARTS.length(), sub.length() - TGZ.length()));
        if (split == null) {
            return Optional.of(ArtifactDescriptor.at(ECOSYSTEM, path));
        }
        return Optional.of(new ArtifactDescriptor(ECOSYSTEM, split[0], split[1], path,
                "application/gzip", split[1].indexOf('-') >= 0, null, -1L));
    }

    /** Split {@code <name>-<version>} as Helm does: at the last hyphen followed by a digit, since a chart name may
     *  contain hyphens and a SemVer version starts with a digit. A stem with no such hyphen is not a chart file
     *  name. */
    private static String[] split(String stem) {
        for (int at = stem.lastIndexOf('-'); at > 0; at = stem.lastIndexOf('-', at - 1)) {
            if (at + 1 < stem.length() && Character.isDigit(stem.charAt(at + 1))) {
                return new String[]{stem.substring(0, at), stem.substring(at + 1)};
            }
        }
        return null;
    }

    @Override
    public List<String> paths(String coordinate, String version, ArtifactStore store) {
        // Helm pointers live in the shared Blobs namespace; blobKeys/servedPaths carry the coordinate.
        return List.of();
    }

    @Override
    public List<String> blobRoots() {
        return List.of("helm");
    }

    @Override
    public List<String> blobKeys(String coordinate, String version, ArtifactStore store) throws IOException {
        if (!BlobLayout.addressable(coordinate, version)) {
            return List.of();
        }
        List<String> keys = new ArrayList<>();
        for (String repo : store.list("helm")) {
            String blob = blobKey(repo, coordinate, version);
            if (store.readVersioned(blob).isPresent()) {
                keys.add(blob);
            }
            String entry = entryKey(repo, coordinate, version);
            if (store.readVersioned(entry).isPresent()) {
                keys.add(entry);
            }
        }
        return keys;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Both pointer shapes this layout writes, since an eviction deletes both: the download pointer
     * {@code helm/<repo>/blob/<chart>-<version>.tgz} and the entry pointer {@code helm/<repo>/entry/<chart>/<version>}.
     * Neither is a served path, so this parses its own constants. The file name splits by {@link #split}; the entry
     * pointer needs no split, which is why a repair should prefer it.
     */
    @Override
    public Optional<ArtifactDescriptor> describePointer(String key) {
        if (!key.startsWith("helm/")) {
            return Optional.empty();
        }
        int repo = key.indexOf('/', "helm/".length());
        if (repo < 0) {
            return Optional.empty();
        }
        String sub = key.substring(repo + 1);
        String name;
        String version;
        if (sub.startsWith(BLOB + "/")) {
            String file = sub.substring(BLOB.length() + 1);
            if (file.indexOf('/') >= 0 || !file.endsWith(TGZ)) {
                return Optional.empty();
            }
            String[] split = split(file.substring(0, file.length() - TGZ.length()));
            if (split == null) {
                return Optional.empty();
            }
            name = split[0];
            version = split[1];
        } else if (sub.startsWith(ENTRY + "/")) {
            String[] parts = sub.substring(ENTRY.length() + 1).split("/");
            if (parts.length != 2) {
                return Optional.empty();
            }
            name = parts[0];
            version = parts[1];
        } else {
            return Optional.empty();   // the index, a checksum, something else this layout keeps beside its charts
        }
        if (!BlobLayout.addressable(name, version)) {
            return Optional.empty();
        }
        return Optional.of(new ArtifactDescriptor(ECOSYSTEM, name, version, key,
                "application/gzip", version.indexOf('-') >= 0, null, -1L));
    }

    @Override
    public List<String> servedPaths(String coordinate, String version, ArtifactStore store) throws IOException {
        if (!BlobLayout.addressable(coordinate, version)) {
            return List.of();
        }
        List<String> paths = new ArrayList<>();
        for (String repo : store.list("helm")) {
            if (store.readVersioned(blobKey(repo, coordinate, version)).isPresent()) {
                paths.add(PREFIX + repo + "/" + CHARTS + coordinate + "-" + version + TGZ);
            }
        }
        return paths;
    }

    // ---- importer

    // ---- keys

    /** The two key segments under a repository, composed and parsed here. */
    private static final String ENTRY = "entry";

    private static final String BLOB = "blob";

    static String entryPrefix(String repo) {
        return "helm/" + repo + "/" + ENTRY;
    }

    static String entryKey(String repo, String name, String version) {
        return entryPrefix(repo) + "/" + name + "/" + version;
    }

    /** The download pointer, keyed on the served file name so the read never has to split it. */
    static String fileKey(String repo, String file) {
        return "helm/" + repo + "/" + BLOB + "/" + file;
    }

    static String blobKey(String repo, String name, String version) {
        return fileKey(repo, name + "-" + version + TGZ);
    }

    // ---- chart metadata

    /** The chart's {@code Chart.yaml}, read from the stored archive through the shared archive bounds. */
    private static Map<?, ?> chartYaml(InputStream blob) throws IOException {
        try (GZIPInputStream gzip = new GZIPInputStream(blob)) {
            // A walk the bound cut short yields nothing, never a truncated prefix that might parse into a plausible
            // coordinate.
            return ArchiveWalk.walk(gzip, HelmFormat::chartFromTar).orNull();
        } catch (IOException | RuntimeException notAnArchive) {
            return null;
        }
    }

    /** {@code <chart>/Chart.yaml}, exactly one directory deep, as {@code helm package} writes it. */
    private static Map<?, ?> chartFromTar(InputStream stream) throws IOException {
        TarArchiveInputStream tar = new TarArchiveInputStream(stream, "UTF-8");
        for (TarArchiveEntry entry = tar.getNextEntry(); entry != null; entry = tar.getNextEntry()) {
            String name = entry.getName().startsWith("./") ? entry.getName().substring(2) : entry.getName();
            if (entry.isDirectory() || !name.endsWith("/" + CHART_YAML)
                    || name.indexOf('/') != name.length() - CHART_YAML.length() - 1) {
                continue;                       // only the chart's own root, never a packaged subchart's copy
            }
            byte[] yaml = ArchiveInflation.entry(tar).orNull();
            if (yaml == null) {
                return null;
            }
            Object loaded = new Yaml(new SafeConstructor(new LoaderOptions()))
                    .load(new String(yaml, StandardCharsets.UTF_8));
            return loaded instanceof Map<?, ?> map ? map : null;
        }
        return null;
    }

    /** One version's index stanza: the fields Helm reads, dumped by SnakeYAML so quoting and escaping are the
     *  library's, then indented into the sequence item the index nests it as. */
    private static byte[] stanza(Map<?, ?> chart, String name, String version, String hash, String file) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", name);
        entry.put("version", version);
        entry.put("apiVersion", text(chart, "apiVersion") == null ? "v2" : text(chart, "apiVersion"));
        // The digest the client verifies: the store's, over the bytes this repository will serve.
        entry.put("digest", hash);
        entry.put("urls", List.of(CHARTS + file));
        for (String optional : List.of("description", "appVersion", "type", "icon", "home")) {
            String value = text(chart, optional);
            if (value != null) {
                entry.put(optional, value);
            }
        }
        if (chart.get("deprecated") instanceof Boolean deprecated && deprecated) {
            entry.put("deprecated", true);
        }
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setSplitLines(false);
        String dumped = new Yaml(options).dump(entry);
        StringBuilder out = new StringBuilder();
        boolean first = true;
        for (String line : dumped.split("\n", -1)) {
            if (line.isEmpty()) {
                continue;
            }
            out.append(first ? "  - " : "    ").append(line).append('\n');
            first = false;
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String text(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value instanceof String string && !string.isBlank() ? string : null;
    }

    /** Each registry's chart is put at {@code <repo>/charts/<name>-<version>.tgz} and its provenance file after it at
     *  the same name with {@code .prov} - the direct upload a chart repository takes and the path each is served from,
     *  so a file already there is not sent again. The target derives its own {@code index.yaml}. */
    @Override
    public Exported export(ArtifactStore repository, String coordinate, String version, ExportTarget target)
            throws IOException {
        if (!BlobLayout.addressable(coordinate, version)) {
            return Exported.WITHHELD;
        }
        String file = coordinate + "-" + version + TGZ;
        List<BlobExport.Pair> pairs = new ArrayList<>();
        for (String repo : repository.list("helm")) {
            pairs.add(new BlobExport.Pair(fileKey(repo, file), repo + "/" + CHARTS + file));
            pairs.add(new BlobExport.Pair(fileKey(repo, file + ".prov"), repo + "/" + CHARTS + file + ".prov"));
        }
        return BlobExport.put(repository, pairs, target);
    }
}
