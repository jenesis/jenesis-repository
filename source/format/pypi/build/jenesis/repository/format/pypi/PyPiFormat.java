package build.jenesis.repository.format.pypi;

import module java.base;
import module tools.jackson.databind;
import module java.xml;
import module org.slf4j;

import build.jenesis.repository.format.LifecycleMark;
import build.jenesis.repository.format.Listings;
import build.jenesis.repository.blobs.HostedMarker;
import build.jenesis.repository.blobs.VersionFiles;
import build.jenesis.repository.blobs.BlobLayout;
import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.blobs.Keys;
import build.jenesis.repository.blobs.OutboundTargets;
import build.jenesis.repository.blobs.ProxyLeg;
import build.jenesis.repository.blobs.ProxyRelay;
import build.jenesis.repository.format.ArtifactSignatures;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.icon.IconResource;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.lifecycle.Lifecycle;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.multipart.MultipartBody;
import build.jenesis.repository.multipart.MultipartForm;
import build.jenesis.repository.format.ExportTarget;
import build.jenesis.repository.format.PublishedExport;
import build.jenesis.repository.format.RepositoryExporter;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.StoredListing;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.PublishInterceptor;
import build.jenesis.repository.store.ServableNames;
import build.jenesis.repository.store.Withheld;
import build.jenesis.repository.walk.BoundedChildren;
import build.jenesis.repository.walk.ScreenedNames;

/**
 * The PyPI format (the Simple Repository API and the legacy upload endpoint): {@code twine upload} and
 * {@code pip install} over the same store, under {@code /pypi/...}. An upload ({@code POST /pypi/}, twine's multipart
 * form) stores the distribution under {@code pypi/<project>/files/<filename>}, the project PEP 503-normalized. The
 * project index ({@code GET /pypi/simple/<project>/}) is a stored page the upload maintains, each link relative with
 * the file's {@code #sha256}, and the file is served at {@code /pypi/simple/<project>/<filename>}.
 */
public final class PyPiFormat implements RepositoryFormat, ProxyLeg, BlobLayout, RepositoryImporter.Delegating,
        ArtifactSignatures, RepositoryExporter {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(PyPiFormat.class);

    // The anchor href of a proxied Simple page.
    private static final Pattern HREF = Pattern.compile("href=\"([^\"]*)\"");
    // The whole anchor, since a PEP 658 sidecar's digest is an attribute beside the href.
    private static final Pattern ANCHOR = Pattern.compile("<a\\s([^>]*)>", Pattern.CASE_INSENSITIVE);
    // PEP 714 renamed PEP 658's attribute; both are read and the newer wins.
    private static final Pattern CORE_METADATA =
            Pattern.compile("data-core-metadata=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern DIST_INFO_METADATA =
            Pattern.compile("data-dist-info-metadata=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern SEPARATORS = Pattern.compile("[-_.]+");

    @Override
    public String name() {
        return "pypi";
    }

    /** The marks this format's clients see, each by its own word. */
    @Override
    public Map<LifecycleMark, String> lifecycleMarks() {
        return LifecycleMark.shown(LifecycleMark.YANKED);
    }

    @Override
    public String ecosystem() {
        return "PyPI";
    }

    @Override
    public List<String> blobRoots() {
        return List.of("pypi");
    }

    @Override
    public List<String> blobKeys(String coordinate, String version, ArtifactStore store) throws IOException {
        // Every distribution file whose name carries this version as its '-'-delimited token, an sdist or a wheel; a
        // project's versions share one files/ directory.
        if (!BlobLayout.addressable(coordinate, version)) {
            return List.of();   // a traversal-shaped coordinate maps nowhere - these keys are what an eviction DELETES
        }
        String project = normalize(coordinate);
        String dir = "pypi/" + project + "/files";
        List<String> keys = new ArrayList<>();
        // The files the version's record lists answer without a scan; a version recorded before it listed them is
        // scanned. A listed file whose pointer is gone was evicted, and is passed over.
        Optional<List<String>> listed = VersionFiles.installed().listed(store, ecosystem(), coordinate, version);
        if (listed.isPresent()) {
            for (String key : listed.get()) {
                if (store.readVersioned(key).isPresent()) {
                    keys.add(key);
                }
            }
            return keys;
        }
        DISTRIBUTIONS.scan(store, dir, file -> {
            if (matchesVersion(file, version)) {
                keys.add(dir + "/" + file);
            }
        });
        return keys;
    }

    /** The project version a stored distribution pointer serves, from which the inventory back-fill and forwarding's
     *  repair rebuild a lost row. Only {@code pypi/<project>/files/<file>} is decoded, through {@link #describe} of its
     *  served path, the parse the publish recorded its row with; a file it cannot version answers nothing. The reverse
     *  index beside it is left alone, so one row has one derivation. */
    @Override
    public Optional<ArtifactDescriptor> describePointer(String key) {
        String[] parts = key.split("/", -1);
        if (parts.length != 4 || !parts[0].equals("pypi") || !parts[2].equals("files")
                || !BlobLayout.addressable(parts[1], parts[3])) {
            return Optional.empty();
        }
        return describe("/pypi/simple/" + parts[1] + "/" + parts[3])
                .filter(described -> described.coordinate() != null && described.version() != null);
    }

    /** One project's distribution files, a flat container. It feeds {@code blobKeys} and {@code servedPaths}, where a
     *  short listing would leave a held distribution serving, so the entry cap is off and the step budget (1000 drain
     *  pages) raises a {@link build.jenesis.repository.walk.TraversalException} rather than dropping keys. */
    private static final BoundedChildren DISTRIBUTIONS = BoundedChildren.bounded().entries(Integer.MAX_VALUE)
            .page(BoundedChildren.DRAIN_PAGE);

    /** The request paths this version's distributions serve at ({@code /pypi/simple/<project>/<file>}), where a
     *  retroactive hold links its {@code /quarantine} handles: the files {@link #blobKeys} names. */
    @Override
    public List<String> servedPaths(String coordinate, String version, ArtifactStore store) throws IOException {
        if (!BlobLayout.addressable(coordinate, version)) {
            return List.of();
        }
        String project = normalize(coordinate);
        List<String> paths = new ArrayList<>();
        for (String key : blobKeys(coordinate, version, store)) {
            paths.add("/pypi/simple/" + project + "/" + key.substring(key.lastIndexOf('/') + 1));
        }
        return paths;
    }

    /** Whether a distribution filename carries exactly this version as its {@code -}-delimited token, not a longer
     *  version it prefixes. A wheel bounds the version with {@code -} on both sides; an sdist ends it with the
     *  extension's dot, so {@code -<version>.} matches only when a non-digit follows. Otherwise evicting {@code 1.0}
     *  would delete {@code 1.0.10}'s files. */
    private static boolean matchesVersion(String file, String version) {
        if (file.contains("-" + version + "-")) {
            return true;
        }
        String needle = "-" + version + ".";
        int at = file.indexOf(needle);
        return at >= 0
                && at + needle.length() < file.length()
                && !Character.isDigit(file.charAt(at + needle.length()));
    }

    /** The distribution extensions a describable file carries, the set the PyPI inspector screens. */
    private static final List<String> DIST_EXTENSIONS = List.of(".whl", ".tar.gz", ".zip", ".egg");

    /** The coordinate a distribution path carries ({@code /pypi/simple/<project>/<file>}), the project PEP
     *  503-normalized as everywhere else. The indexes and a PEP 658 {@code .metadata} sidecar name no version. The
     *  version is peeled from the filename as the inspector does: a wheel's second {@code -} field (the wheel spec
     *  escapes the name's dashes), an sdist's or egg's after the normalized project prefix; a filename neither fits
     *  describes coordinate-less. */
    @Override
    public Optional<ArtifactDescriptor> describe(String path) {
        if (!path.startsWith("/pypi/simple/")) {
            return Optional.empty();
        }
        String after = path.substring("/pypi/simple/".length());
        int slash = after.indexOf('/');
        if (slash < 0 || slash == after.length() - 1) {
            return Optional.empty();
        }
        String project = normalize(after.substring(0, slash));
        String file = after.substring(slash + 1);
        if (file.indexOf('/') >= 0) {
            // A distribution path is exactly <project>/<filename>, so no slash can leak into the parsed version.
            return Optional.empty();
        }
        String base = null;
        for (String extension : DIST_EXTENSIONS) {
            if (file.endsWith(extension)) {
                base = file.substring(0, file.length() - extension.length());
                break;
            }
        }
        if (base == null) {
            return Optional.empty();
        }
        String version = version(project, base, file.endsWith(".whl"));
        if (version == null) {
            return Optional.of(ArtifactDescriptor.at("PyPI", path));
        }
        return Optional.of(new ArtifactDescriptor("PyPI", project, version, path,
                "application/octet-stream", false, null, -1L));
    }

    /** The mark on the version a distribution file belongs to, or null, the filename parsed as {@link #describe} parses
     *  it. */
    static Lifecycle.Flag marked(Map<String, Lifecycle.Flag> lifecycle, String project, String file) {
        if (lifecycle.isEmpty()) {
            return null;
        }
        for (String extension : DIST_EXTENSIONS) {
            if (file.endsWith(extension)) {
                String version = version(project, file.substring(0, file.length() - extension.length()),
                        extension.equals(".whl"));
                return version == null ? null : lifecycle.get(version);
            }
        }
        return null;
    }

    /** The version in a distribution filename's extension-less base, given its normalized project: the PyPI
     *  inspector's parse, so both report one coordinate for one file. */
    private static String version(String project, String base, boolean wheel) {
        if (wheel) {
            // {name}-{version}(-{build})?-{python}-{abi}-{platform}: the name's dashes are escaped, so the version is
            // the second field.
            String[] parts = base.split("-");
            return parts.length >= 2 ? parts[1] : null;
        }
        // sdist/egg: {name}-{version}, the name possibly containing '-', peeled off by matching the project.
        int dash = base.length();
        while ((dash = base.lastIndexOf('-', dash - 1)) > 0) {
            if (normalize(base.substring(0, dash)).equals(project)) {
                return base.substring(dash + 1);
            }
        }
        int last = base.lastIndexOf('-');
        return last <= 0 ? null : base.substring(last + 1);
    }

    // An original CC0 line glyph (two interlocking rounded squares).
    private static final IconResource ICON = IconResource.svg("""
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round">
              <rect x="4" y="4" width="11" height="11" rx="3"/><rect x="9" y="9" width="11" height="11" rx="3"/>
            </svg>""");

    @Override
    public Optional<IconResource> icon() {
        return Optional.of(ICON);
    }

    @Override
    public Optional<URI> defaultUpstream() {
        return Optional.of(URI.create("https://pypi.org/"));
    }

    @Override
    public boolean handles(String path) {
        return path.startsWith("/pypi/");
    }

    /** Not edge-screened: a {@code twine upload} body is a multipart envelope, and screening it at the shared edge
     *  would assess the envelope while the bytes that serve are the wheel or sdist inside it ({@code RepositoryFormat}
     *  clause 14). This format screens at its own choke point: {@link #upload} peels the {@code content} part off as it
     *  streams and drives {@code Publication.commit} with the discovered chain and observers over the distribution's
     *  bytes. The legacy upload endpoint is the only way a distribution is hosted-published here. */
    @Override
    public boolean screened() {
        return false;
    }

    @Override
    public void serve(FormatExchange exchange, ArtifactStore store) throws IOException {
        Blobs blobs = new Blobs(store);
        if (exchange.method().equals("POST")) {
            upload(exchange, blobs, store);
            return;
        }
        String rest = exchange.path().substring("/pypi/".length());
        if (rest.startsWith("integrity/")) {
            provenance(rest.substring("integrity/".length()), blobs, exchange);
            return;
        }
        if (rest.startsWith("simple/")) {
            String after = rest.substring("simple/".length());
            if (after.isEmpty()) {
                projects(blobs, exchange);
                return;
            }
            int slash = after.indexOf('/');
            if (slash < 0 || slash == after.length() - 1) {
                index(normalize(slash < 0 ? after : after.substring(0, slash)), blobs, exchange);
            } else {
                serveFile(normalize(after.substring(0, slash)), after.substring(slash + 1), blobs, exchange);
            }
            return;
        }
        exchange.respond(404);
    }

    /** Where a proxied distribution's PEP 740 provenance is fetched: the integrity API's base, the document being
     *  {@code <base>/<project>/<version>/<file>/provenance}. Empty by default, meaning the upstream's own origin
     *  (pypi.org serves {@code https://pypi.org/integrity/}); a mirror without it answers 404, which is absence. */
    public static final String PROVENANCE_URL = "pypi-provenance-url";

    /** The provenance document PyPI publishes for a distribution and pip never fetches, fetched beside the file so the
     *  screen judges the file by it, and kept through {@link #keep} as the attestations an upload would have stored. */
    @Override
    public List<ProxyFormat.Companion> companions(FormatExchange exchange, URI upstream) {
        String path = exchange.path();
        Optional<ArtifactDescriptor> described = describe(path)
                .filter(d -> d.coordinate() != null && d.version() != null);
        if (described.isEmpty() || !ArtifactStore.traversalFree(path)) {
            return List.of();
        }
        String file = path.substring(path.lastIndexOf('/') + 1);
        String base = exchange.setting(PROVENANCE_URL);
        if (base == null || base.isBlank()) {
            base = upstream.getScheme() + "://" + upstream.getRawAuthority() + "/integrity/";
        } else if (!base.endsWith("/")) {
            base += "/";
        }
        String rest = described.get().coordinate() + "/" + described.get().version() + "/" + file + "/provenance";
        return List.of(new ProxyFormat.Companion("/pypi/integrity/" + rest, URI.create(base + rest)));
    }

    /** A fetched provenance document is kept as the attestations it carries - every attestation of every publisher
     *  bundle, flattened as an upload stores them - so {@link #evidence} and the integrity endpoint read a proxied file
     *  as an uploaded one. Always {@code true}: a document that is not provenance is dropped. */
    @Override
    public boolean keep(ArtifactStore store, ProxyFormat.Companion companion, byte[] body) throws IOException {
        if (!companion.path().startsWith("/pypi/integrity/")) {
            return true;
        }
        String[] parts = companion.path().substring("/pypi/integrity/".length()).split("/");
        if (parts.length != 4 || !parts[3].equals("provenance") || Keys.unsafe(parts[0]) || Keys.unsafe(parts[2])) {
            return true;
        }
        JsonNode document;
        try {
            document = MAPPER.readTree(body);
        } catch (RuntimeException notJson) {
            return true;
        }
        ArrayNode attestations = MAPPER.createArrayNode();
        for (JsonNode bundle : document.path("attestation_bundles")) {
            for (JsonNode attestation : bundle.path("attestations")) {
                attestations.add(attestation);
            }
        }
        if (attestations.isEmpty()) {
            return true;
        }
        new Blobs(store).write(attestationsKey(normalize(parts[0]), parts[2]), MAPPER.writeValueAsBytes(attestations));
        return true;
    }

    /** Proxy a PyPI miss to the upstream index. The project page is mutable: fetched fresh, each file link rewritten to
     *  its bare filename so it resolves here. A file is immutable: its location found in the project page (on another
     *  host), fetched, cached and served. */
    @Override
    public boolean pullThrough(FormatExchange exchange, ArtifactStore store, URI upstream,
                               ProxyFormat.Fetcher fetcher) throws IOException {
        String path = exchange.path();
        if (!path.startsWith("/pypi/simple/")) {
            // ProxyLeg has screened the path; only the simple/ subtree proxies.
            return false;
        }
        String after = path.substring("/pypi/simple/".length());
        if (after.isEmpty()) {
            // The root index is declined: it is PyPI's entire project list, read through the buffered fetch, so
            // answering it would materialise it on every request (clause 4), and a truncated one would read as projects
            // the index does not carry. Nothing pip does on an install reads it.
            return false;
        }
        int slash = after.indexOf('/');
        String root = upstream.toString();
        if (!root.endsWith("/")) {
            root += "/";
        }
        if (slash < 0 || slash == after.length() - 1) {
            String project = normalize(slash < 0 ? after : after.substring(0, slash));
            // The project page is pip's file list for the project, an ENUMERATION: absent means no such project, empty
            // means no matching distribution, so only an upstream that answered 404/410 reaches the client as a 404.
            ProxyRelay.Answer answer = ProxyRelay.fetchRemembered(fetcher, URI.create(root + "simple/" + project + "/"),
                    Map.of(), exchange, ProxyRelay.Document.ENUMERATION, store);
            if (!answer.answered()) {
                return answer.served();
            }
            exchange.setResponseHeader("Content-Type", "text/html");
            exchange.respond(200, rewriteIndex(new String(answer.document().body(), StandardCharsets.UTF_8))
                    .getBytes(StandardCharsets.UTF_8));
            return true;
        }
        String project = normalize(after.substring(0, slash));
        String file = after.substring(slash + 1);
        Optional<ProxyFormat.Fetched> index = fetcher.beside().fetch(URI.create(root + "simple/" + project + "/"),
                Map.of());
        if (index.isEmpty() || index.get().status() != 200) {
            return false;
        }
        // PEP 658: pip requests <distribution>.metadata, the distribution's URL plus the suffix.
        boolean metadata = file.endsWith(".metadata");
        String distribution = metadata ? file.substring(0, file.length() - ".metadata".length()) : file;
        Located found = findFile(new String(index.get().body(), StandardCharsets.UTF_8), distribution);
        if (found == null) {
            return false;
        }
        String location = found.location();
        // The location comes from the untrusted upstream page and is cross-host by design, so a publisher to the
        // upstream chooses its host and scheme: it is screened by the shared outbound call, and a refused target falls
        // through to a 404 (ProxyLeg clause 2).
        URI target;
        try {
            target = URI.create(metadata ? location + ".metadata" : location);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (!OutboundTargets.mayFollow(target, upstream, ProxyLeg.allowInternalTargets(exchange))) {
            return false;
        }
        // Streamed from the network into the content-addressed store, since a distribution is unbounded.
        try (ProxyFormat.Download download = fetcher.download(target, Map.of()).orElse(null)) {
            if (download == null || download.status() != 200) {
                return false;
            }
            // The cached file is bound to the SHA-256 the page declares (#sha256=), so a diverging file host cannot
            // have its bytes cached; with no usable digest the write is unverified. A sidecar is held to its own digest
            // from the anchor. A page that could not be read already declined the fill above, being the same document
            // that locates the file.
            byte[] expected = metadata ? hexOrNull(found.metadataSha256()) : hexOrNull(found.sha256());
            if (!ProxyRelay.fill(new Blobs(store), "pypi/" + project + "/files/" + file, target, download.body(),
                    expected == null ? ProxyRelay.Declared.NONE : ProxyRelay.Declared.of("SHA-256", expected))) {
                // A refused PEP 658 sidecar is answered visibly: pip reads a 404 as "no sidecar" and falls back to the
                // wheel, so a refusal spelled as a miss would let the build go green over a corrupted document. A
                // distribution keeps the plain decline, since its absence fails an install loudly.
                if (metadata) {
                    LOGGER.warn("Refusing to answer the proxied PEP 658 sidecar {} as an absent one: its bytes do not "
                            + "match the digest the upstream index declares for it. Nothing was served; a local 404 "
                            + "would have been read as \"this distribution publishes no metadata\", and pip would "
                            + "have resolved from the wheel without an error.", target);
                    exchange.respond(502);
                    return true;
                }
                return false;
            }
        }
        handle(exchange, store);
        return true;
    }

    private static String rewriteIndex(String html) {
        return HREF.matcher(html).replaceAll(match -> {
            String url = match.group(1);
            int hash = url.indexOf('#');
            String location = hash < 0 ? url : url.substring(0, hash);
            String fragment = hash < 0 ? "" : url.substring(hash);
            return "href=\"" + location.substring(location.lastIndexOf('/') + 1) + fragment + "\"";
        });
    }

    /**
     * One file's row on a proxied Simple page: its bare {@code location} URL and two digests.
     *
     * @param sha256 the distribution's digest, off the href fragment
     * @param metadataSha256 the PEP 658 sidecar's own digest, off the {@code data-core-metadata} or
     *     {@code data-dist-info-metadata} attribute: a different file, so neither stands in for the other
     */
    private record Located(String location, String sha256, String metadataSha256) {
    }

    private static Located findFile(String html, String file) {
        Matcher matcher = ANCHOR.matcher(html);
        while (matcher.find()) {
            String attributes = matcher.group(1);
            Matcher href = HREF.matcher(attributes);
            if (!href.find()) {
                continue;
            }
            String url = href.group(1);
            int hash = url.indexOf('#');
            String location = hash < 0 ? url : url.substring(0, hash);
            if (!location.substring(location.lastIndexOf('/') + 1).equals(file)) {
                continue;
            }
            String sha256 = null;
            if (hash >= 0) {
                for (String part : url.substring(hash + 1).split("&")) {
                    if (part.startsWith("sha256=")) {
                        sha256 = part.substring("sha256=".length());
                        break;
                    }
                }
            }
            String metadata = digest(attributes, CORE_METADATA);
            return new Located(location, sha256,
                    metadata == null ? digest(attributes, DIST_INFO_METADATA) : metadata);
        }
        return null;
    }

    /** The {@code sha256=<hex>} an anchor attribute declares, or null. PEP 658's bare {@code true} vouches for nothing
     *  and is not read as a digest. */
    private static String digest(String attributes, Pattern attribute) {
        Matcher matcher = attribute.matcher(attributes);
        if (!matcher.find()) {
            return null;
        }
        String value = matcher.group(1);
        return value.startsWith("sha256=") ? value.substring("sha256=".length()) : null;
    }

    /** Decode a hex digest to bytes, or {@code null} when absent or malformed, which falls back to an unverified
     *  write. */
    private static byte[] hexOrNull(String hex) {
        if (hex == null || hex.isBlank()) {
            return null;
        }
        try {
            return HexFormat.of().parseHex(hex);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The republish policy for the hosted publish: {@code OVERWRITE}, since the colliding key is known only once the
     *  layout has read the {@code name} field and the {@code content} filename, in either order. PyPI refuses a
     *  filename already uploaded whatever its bytes, and so does this, at the link ({@link Blobs#linkOnce}) inside the
     *  pointer's compare-and-set, answered as PyPI answers ({@link #alreadyExists}); an identical re-upload
     *  converges. */
    private static final Publication.Republish REPUBLISH = Publication.Republish.overwrite();

    /**
     * The legacy {@code twine upload} endpoint, through {@code Publication.commit}: the distribution streams into the
     * store, the envelope is read to its end for the small fields that name it, and only then is the stored
     * distribution screened and, when accepted, the serving pointer linked and the per-project hosted marker stamped.
     * <b>The commit point is the {@code pypi/<project>/files/<filename>} pointer link</b>; the hosted marker, which
     * switches the project index on, is written after it, never ahead of the bytes it lists.
     *
     * <p>The {@code content} part is the one unbounded part, streamed content-addressed into the store, so the wheel
     * is stored once and the hash the chain assesses is the {@code #sha256} the index publishes and pip downloads.
     *
     * <p><b>This is the format's screening choke point</b> ({@link #screened()}): the operation carries the discovered
     * chain and observers. The descriptor is the distribution's served path
     * ({@code /pypi/simple/<project>/<filename>}), so a deny-list, a review handle and an inspector key on what the
     * download serves, in whatever order the client sent the fields: the commit is handed the stored distribution once
     * the {@code name} that may follow the file has been read.
     */
    private void upload(FormatExchange exchange, Blobs blobs, ArtifactStore store) throws IOException {
        Optional<String> boundary = MultipartBody.boundary(exchange.requestHeader("Content-Type"));
        if (boundary.isEmpty()) {
            exchange.respond(400);
            return;
        }
        // One bounded, forward-only cursor over the envelope: the content part reaches the store as a stream, the small
        // fields are read against a bound.
        MultipartBody body = MultipartBody.over(exchange.requestStream(), boundary.get());
        Form form = new Form();
        InputStream distribution = form.readToDistribution(body);
        if (distribution == null) {
            exchange.respond(400);   // an envelope carrying no `content` file part uploads no distribution
            return;
        }
        // Stored before it is screened and the envelope read to its end, so the screen is handed the coordinate a
        // name field following the file gives; the commit answers the stored blob by its hash without a second write.
        Publication.Blob stored;
        try (InputStream part = distribution) {
            stored = new Publication(store, List.of(), List.of()).stored(part);
        }
        form.readRemainder(body);
        Publication.Commit commit = null;
        try (InputStream part = new Publication.Stored(store, stored)) {
            commit = new Publication(store).commit(
                    uploaded(exchange, form.project, form.filename), part, REPUBLISH,
                    _ -> {
                        String project = form.project;
                        String filename = form.filename;
                        if (project == null || Keys.unsafe(project) || Keys.unsafe(filename)) {
                            // No project, or a project or filename that would forge a pointer key: nothing is declared.
                            return Publication.Visibility.declined();
                        }
                        return Publication.Visibility
                                // The serving pointer, in this format's namespace rather than publish/, so a Serving
                                // step.
                                // The version's record lists the file before its pointer is linked, so no linked file
                                // is missing from the list an eviction deletes by.
                                .through((_, _, target) -> recordFile(target, project, filename))
                                .andThrough((hash, size, _) -> blobs.linkRelease(fileKey(project, filename), hash,
                                        size))
                                // Attestations are kept before the page links them, so no client reads a link whose
                                // provenance is to come.
                                .andThrough((_, _, _) -> storeAttestations(blobs, project, filename, form.attestations))
                                // The per-project hosted marker switches on the local index; a pull-through proxy never
                                // writes it, so its index falls through to the upstream's.
                                .andThrough((_, _, target) -> HostedMarker.mark(target, hostedKey(project)))
                                // The Simple pages are maintained on the upload.
                                .andThrough((_, _, _) -> new PyPiListings(blobs).refresh(project, filename));
                    });
            // Held: the layout is written here behind the withhold marker (see held).
            switch (commit.disposition()) {
                case QUARANTINE -> held(form, blobs, store, commit.hash());
                default -> {
                }
            }
        } catch (Publication.RepublishConflict taken) {
            if (commit != null) {
                // A held re-upload was refused before anything was marked, so its review handle goes.
                new Publication(store, List.of(), List.of()).unpublish("/quarantine" + commit.artifact().path());
            }
            exchange.respond(400, alreadyExists(form.filename, taken));
            return;
        }
        switch (commit.disposition()) {
            case ACCEPT -> exchange.respond(commit.visible() ? 200 : 400);
            // Held: stored, laid out and withheld; the Simple index screens it out until released.
            case QUARANTINE -> explain(exchange, 202, commit.explanation());
            // Refused: nothing linked or marked; the stored blob is an unreferenced object the collector reclaims.
            case REJECT -> explain(exchange, 422, commit.explanation());
        }
    }

    /** Lay a held distribution out behind its withhold marker, so its review release is the marker clear a retroactive
     *  hold's is; the shared commit lays out only on {@code ACCEPT}. {@link Withheld#mark} first, and only after it the
     *  serving pointer and the hosted marker, so the held wheel is never downloadable or listed: {@link #index},
     *  {@link #projects} and {@link #serveFile} screen on that marker. A body naming no project lays nothing out, and
     *  the hold stays reviewable by its stored blob. */
    private static void held(Form form, Blobs blobs, ArtifactStore store, String hash) throws IOException {
        String project = form.project;
        String filename = form.filename;
        if (project == null || Keys.unsafe(project) || Keys.unsafe(filename)) {
            return;
        }
        // A hold never replaces a released file or its attestations: refused before the mark.
        blobs.refuseReplacement(fileKey(project, filename), hash);
        byte[] attestations = attestationsDocument(form.attestations);
        if (attestations != null) {
            blobs.refuseReplacement(attestationsKey(project, filename), attestations);
        }
        Withheld.mark(store, hash, new PyPiFormat().describe("/pypi/simple/" + project + "/" + filename)
                .orElse(ArtifactDescriptor.at("PyPI", "/pypi/simple/" + project + "/" + filename)));
        recordFile(store, project, filename);
        blobs.linkRelease(fileKey(project, filename), hash, -1L);
        storeAttestations(blobs, project, filename, form.attestations);
        HostedMarker.mark(store, hostedKey(project));
        new PyPiListings(blobs).refresh(project, filename);   // held: the stored pages keep it out
    }

    /** The serving pointer of one uploaded file. */
    private static String fileKey(String project, String filename) {
        return "pypi/" + project + "/files/" + filename;
    }

    /** PyPI's answer to a filename already uploaded, which {@code twine upload --skip-existing} recognises by its
     *  {@code 400} and words. */
    private static byte[] alreadyExists(String filename, Publication.RepublishConflict taken) {
        return ("File already exists ('" + filename + "', with sha256 hash '" + taken.published() + "'). A published "
                + "file cannot be replaced; upload it under a new version.").getBytes(StandardCharsets.UTF_8);
    }

    /** List the file in its version's record, so its version's keys are found without scanning the project. */
    private static void recordFile(ArtifactStore store, String project, String filename) throws IOException {
        Optional<ArtifactDescriptor> described = new PyPiFormat().describe("/pypi/simple/" + project + "/" + filename);
        if (described.isPresent() && described.get().version() != null && !Keys.unsafe(described.get().version())) {
            VersionFiles.installed().record(store, described.get().ecosystem(), described.get().coordinate(),
                    described.get().version(), fileKey(project, filename));
        }
    }

    /** The descriptor the uploaded distribution is screened under: its served path with the coordinate
     *  {@link #describe} parses. The coordinate-less endpoint descriptor when the form names no project or a value
     *  would forge a key; the content is screened either way. */
    private ArtifactDescriptor uploaded(FormatExchange exchange, String project, String filename) {
        if (project == null || filename == null || Keys.unsafe(project) || Keys.unsafe(filename)) {
            return ArtifactDescriptor.at("PyPI", exchange.path());
        }
        String served = "/pypi/simple/" + project + "/" + filename;
        return describe(served).orElseGet(() -> ArtifactDescriptor.at("PyPI", served));
    }

    /** The twine form fields naming the upload, accumulated over the one pass: the {@code content} part's filename and
     *  the PEP 503-normalized {@code name}. {@link #readToDistribution} walks up to the file part's headers, and
     *  {@link #readRemainder} finishes the envelope once the distribution is stored. A method-local accumulator, never
     *  shared. */
    private static final class Form {

        private String project;
        private String filename;
        private String attestations;

        /** Walk the envelope to the {@code content} part, recording small text fields, and return it as a stream
         *  bounded to the distribution, the publish's accepted body; {@code null} when there is none, and nothing was
         *  stored. */
        private InputStream readToDistribution(MultipartBody body) throws IOException {
            for (Optional<MultipartBody.Part> next = body.next(); next.isPresent(); next = body.next()) {
                MultipartBody.Part part = next.get();
                if ("content".equals(part.name()) && part.file()) {
                    filename = part.filename().orElseThrow();
                    return part.stream();   // bounded to this part; hash-on-write happens at the commit
                }
                field(part);
            }
            return null;
        }

        /** Finish the envelope after the distribution part was stored, so a later {@code name} field is read before the
         *  distribution is screened. The cursor releases the distribution part as it advances; the caller's close is a
         *  no-op. */
        private void readRemainder(MultipartBody body) throws IOException {
            for (Optional<MultipartBody.Part> next = body.next(); next.isPresent(); next = body.next()) {
                field(next.get());
            }
        }

        /** Read one small text field: {@code name} is kept PEP 503-normalized, anything else discarded. A {@code name}
         *  past the field bound is left unset rather than truncated, which would forge another coordinate; the upload
         *  then answers 400. */
        private void field(MultipartBody.Part part) throws IOException {
            if ("name".equals(part.name())) {
                project = part.text(MultipartBody.FIELD_LIMIT)
                        .map(value -> normalize(value.trim()))
                        .orElse(null);
            } else if ("attestations".equals(part.name())) {
                // PEP 740: twine's JSON list of attestations, a few kilobytes, bounded at the signature limit.
                attestations = part.text(ArtifactSignatures.Material.LARGEST_SIGNATURE).orElse(null);
            } else {
                part.discard();   // any other metadata field, not needed here
            }
        }
    }

    /** The JSON mapper for the attestations an upload carries and the provenance served. */
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** The hosted marker's bytes; only its presence gates. */

    /** The per-project hosted marker, beside {@code files}, so no listing surfaces it; {@link PyPiImporter} stamps it
     *  too, an import being a hosted publish. */
    static String hostedKey(String project) {
        return "pypi/" + project + "/.hosted";
    }

    /** Whether this normalized project has taken a hosted upload or import. The project-index gate keys on it, so a
     *  proxy repository's index misses locally and relays the upstream's full page. */
    private static boolean hosted(String project, Blobs blobs) throws IOException {
        return blobs.exists(hostedKey(project));
    }

    /** Whether the root index may list this normalized project: it has a distribution a client can fetch, or no
     *  distribution at all. A project whose every file is held is screened out, since listing it discloses a
     *  quarantined project; the first servable file short-circuits the scan. */
    static boolean servable(String project, Blobs blobs) throws IOException {
        String prefix = "pypi/" + project + "/files";
        // The shared screened enumeration, short-circuiting at the first disclosable name, the per-project index's
        // screen.
        if (ScreenedNames.keys(blobs.servableNames(), ServableNames.Policy.HIDE_WITHHELD).any(blobs.store(), prefix)) {
            return true;
        }
        // No disclosable file: still listed when there are no files at all, a structural probe that discloses no name.
        return blobs.isEmpty("pypi/" + project + "/files");
    }

    /** The stored attestations of a distribution: PEP 740's list, as the client sent it, under the file. */
    static String attestationsKey(String project, String file) {
        return "pypi/" + project + "/attestations/" + file;
    }

    /** Keep an upload's attestations when it carried a non-empty list. */
    private static void storeAttestations(Blobs blobs, String project, String filename, String attestations)
            throws IOException {
        byte[] document = attestationsDocument(attestations);
        if (document != null) {
            // The attestations are the file's own: a re-upload with others is refused.
            blobs.writeRelease(attestationsKey(project, filename), document);
        }
    }

    /** The document an upload's {@code attestations} field is kept as, or {@code null} for no non-empty JSON array. */
    private static byte[] attestationsDocument(String attestations) {
        if (attestations == null) {
            return null;
        }
        JsonNode list;
        try {
            list = MAPPER.readTree(attestations);
        } catch (RuntimeException notJson) {
            return null;
        }
        if (list == null || !list.isArray() || list.isEmpty()) {
            return null;
        }
        return MAPPER.writeValueAsBytes(list);
    }

    /** PEP 740's provenance endpoint, {@code /integrity/<project>/<version>/<file>/provenance}: the distribution's
     *  uploaded attestations as one bundle per publisher. The publisher is what each certificate's identity says; the
     *  verifier reads it. */
    private void provenance(String rest, Blobs blobs, FormatExchange exchange) throws IOException {
        String[] parts = rest.split("/");
        if (parts.length != 4 || !parts[3].equals("provenance") || Keys.unsafe(parts[0]) || Keys.unsafe(parts[2])) {
            exchange.respond(404);
            return;
        }
        if (!exchange.method().equals("GET") && !exchange.method().equals("HEAD")) {
            exchange.respond(405);
            return;
        }
        String key = attestationsKey(normalize(parts[0]), parts[2]);
        ByteArrayOutputStream stored = new ByteArrayOutputStream();
        if (!blobs.read(key, stored)) {
            exchange.respond(404);
            return;
        }
        ObjectNode document = MAPPER.createObjectNode();
        document.put("version", 1);
        ObjectNode bundle = document.putArray("attestation_bundles").addObject();
        bundle.putObject("publisher").put("kind", "unknown");
        bundle.set("attestations", MAPPER.readTree(stored.toByteArray()));
        exchange.setResponseHeader("Content-Type", "application/vnd.pypi.integrity.v1+json");
        if (exchange.method().equals("HEAD")) {
            exchange.respond(200, -1L).close();
            return;
        }
        exchange.respond(200, MAPPER.writeValueAsBytes(document));
    }

    // ---- the signature seam: PEP 740 attestations as Sigstore bundles ----

    /** A distribution may carry PEP 740 attestations, each a Sigstore signing of an in-toto statement naming the file
     *  by digest; optional. */
    @Override
    public List<ArtifactSignatures.Expectation> expects(String path) {
        return describe(path).map(described -> described.coordinate() != null && described.version() != null)
                .orElse(false)
                ? List.of(ArtifactSignatures.Expectation.optional(ArtifactSignatures.Scheme.SIGSTORE_BUNDLE))
                : List.of();
    }

    /** The signature rides beside the artifact, where it is already visible, not inside its bytes. */
    @Override
    public boolean embedsEvidence(String path) {
        return false;
    }

    /** No signature material here has a request path of its own, so none covers another path. */
    @Override
    public Optional<String> covers(String path) {
        return Optional.empty();
    }

    @Override
    public List<ArtifactSignatures.Evidence> evidence(String path, ArtifactSignatures.Material material)
            throws IOException {
        Optional<ArtifactDescriptor> described = describe(path)
                .filter(d -> d.coordinate() != null && d.version() != null);
        if (described.isEmpty()) {
            return List.of();
        }
        String file = path.substring(path.lastIndexOf('/') + 1);
        String provenancePath = "/pypi/integrity/" + described.get().coordinate() + "/" + described.get().version()
                + "/" + file + "/provenance";
        Optional<byte[]> document = material.sibling(provenancePath, ArtifactSignatures.Material.LARGEST_SIGNATURE)
                .filter(bounded -> !bounded.truncated())
                .map(PublishInterceptor.Content.Bounded::content);
        Optional<ArtifactSignatures.Signed> body = material.body();
        if (document.isEmpty() || body.isEmpty()) {
            return List.of();
        }
        List<ArtifactSignatures.Evidence> evidence = new ArrayList<>();
        int index = 0;
        for (JsonNode attestation : MAPPER.readTree(document.get())) {
            Optional<byte[]> bundle = bundle(attestation);
            if (bundle.isPresent()) {
                evidence.add(new ArtifactSignatures.Evidence(ArtifactSignatures.Scheme.SIGSTORE_BUNDLE, bundle.get(),
                        body.get(), provenancePath + "#" + index));
            }
            index++;
        }
        return evidence;
    }

    /** A PEP 740 attestation as the Sigstore bundle the verifier reads: the certificate, the transparency-log entries,
     *  and the statement and signature as a DSSE envelope over the in-toto payload type. Empty for an object that is
     *  not one. */
    static Optional<byte[]> bundle(JsonNode attestation) throws IOException {
        JsonNode material = attestation.path("verification_material");
        JsonNode envelope = attestation.path("envelope");
        String certificate = material.path("certificate").asString("");
        String statement = envelope.path("statement").asString("");
        String signature = envelope.path("signature").asString("");
        if (certificate.isEmpty() || statement.isEmpty() || signature.isEmpty()) {
            return Optional.empty();
        }
        ObjectNode bundle = MAPPER.createObjectNode();
        bundle.put("mediaType", "application/vnd.dev.sigstore.bundle.v0.3+json");
        ObjectNode verification = bundle.putObject("verificationMaterial");
        verification.putObject("certificate").put("rawBytes", certificate);
        JsonNode entries = material.path("transparency_entries");
        verification.set("tlogEntries", entries.isArray() ? entries : MAPPER.createArrayNode());
        ObjectNode dsse = bundle.putObject("dsseEnvelope");
        dsse.put("payload", statement);
        dsse.put("payloadType", "application/vnd.in-toto+json");
        dsse.putArray("signatures").addObject().put("sig", signature);
        return Optional.of(MAPPER.writeValueAsBytes(bundle));
    }

    /** A request path's serving key, for the compliance screen's sibling read: a distribution under its Simple page, or
     *  its provenance under the integrity endpoint, when the pointer exists. */
    @Override
    public Optional<String> servingKey(String requestPath, ArtifactStore store) throws IOException {
        String key;
        if (requestPath.startsWith("/pypi/integrity/")) {
            String[] parts = requestPath.substring("/pypi/integrity/".length()).split("/");
            if (parts.length != 4 || !parts[3].equals("provenance") || Keys.unsafe(parts[0]) || Keys.unsafe(parts[2])) {
                return Optional.empty();
            }
            key = attestationsKey(normalize(parts[0]), parts[2]);
        } else if (requestPath.startsWith("/pypi/simple/")) {
            String after = requestPath.substring("/pypi/simple/".length());
            int slash = after.indexOf('/');
            if (slash <= 0 || slash == after.length() - 1 || after.indexOf('/', slash + 1) >= 0) {
                return Optional.empty();
            }
            String project = normalize(after.substring(0, slash)), file = after.substring(slash + 1);
            if (Keys.unsafe(project) || Keys.unsafe(file)) {
                return Optional.empty();
            }
            key = "pypi/" + project + "/files/" + file;
        } else {
            return Optional.empty();
        }
        return store.readVersioned(key).isPresent() ? Optional.of(key) : Optional.empty();
    }

    private void serveFile(String project, String file, Blobs blobs, FormatExchange exchange) throws IOException {
        blobs.answer("pypi/" + project + "/files/" + file, exchange, "application/octet-stream");
    }

    /** The PEP 503 root index ({@code /pypi/simple/}): the stored page uploads maintain, listing every hosted project
     *  with a servable distribution, which an enumeration starts from. */
    private void projects(Blobs blobs, FormatExchange exchange) throws IOException {
        Optional<StoredListing.Served> served = StoredListing.open(blobs.store(), new PyPiListings(blobs).rootSpec());
        if (served.isEmpty()) {
            exchange.respond(404);
            return;
        }
        try (StoredListing.Served page = served.get()) {
            if (page.header().size() <= EMPTY_ROOT_LENGTH) {
                exchange.respond(404);   // no project is listed
                return;
            }
            respondPage(page, exchange);
        }
    }

    /** The length of a root page listing nothing: the frame alone. */
    private static final long EMPTY_ROOT_LENGTH = PyPiListings.page("Simple index").join(new TreeMap<>()).length;

    private static void respondPage(StoredListing.Served page, FormatExchange exchange) throws IOException {
        Listings.serve(exchange, page, "text/html");
    }

    private void index(String project, Blobs blobs, FormatExchange exchange) throws IOException {
        if (Keys.unsafe(project) || !hosted(project, blobs)
                || (!StoredListing.present(blobs.store(), PyPiListings.project(project))
                        && blobs.isEmpty("pypi/" + project + "/files"))) {
            exchange.respond(404);
            return;
        }
        // The project page is a stored listing, streamed as is.
        Optional<StoredListing.Served> served = StoredListing.open(blobs.store(),
                new PyPiListings(blobs).projectSpec(project));
        if (served.isEmpty()) {
            exchange.respond(404);
            return;
        }
        try (StoredListing.Served page = served.get()) {
            respondPage(page, exchange);
        }
    }

    private static String normalize(String name) {
        return SEPARATORS.matcher(name.toLowerCase(Locale.ROOT)).replaceAll("-");
    }

    /** The migration-import capability, delegated to {@link PyPiImporter}. */
    private final PyPiImporter importer = new PyPiImporter();

    @Override
    public RepositoryImporter importer() {
        return importer;
    }

    /** Each distribution of the version is uploaded as {@code twine upload} sends it: the legacy form posted to the
     *  repository root with the project, version, file, SHA-256 and attestations, unless its Simple path already
     *  answers. */
    @Override
    public Exported export(ArtifactStore repository, String coordinate, String version, ExportTarget target)
            throws IOException {
        if (!BlobLayout.addressable(coordinate, version)) {
            return Exported.WITHHELD;
        }
        String project = normalize(coordinate);
        Blobs blobs = new Blobs(repository);
        List<PublishedExport.File> files = new ArrayList<>();
        for (String key : blobKeys(coordinate, version, repository)) {
            Optional<Blobs.Located> located = blobs.locate(key);
            if (located.isEmpty()) {
                continue;
            }
            String filename = key.substring(key.lastIndexOf('/') + 1);
            String hash = located.get().hash();
            MultipartForm form = MultipartForm.create()
                    .field(":action", "file_upload")
                    .field("protocol_version", "1")
                    .field("name", project)
                    .field("version", version)
                    .field("filetype", filename.endsWith(".whl") ? "bdist_wheel" : "sdist")
                    .field("pyversion", pythonTag(filename))
                    .field("metadata_version", "2.1")
                    .field("sha256_digest", hash);
            ByteArrayOutputStream attestations = new ByteArrayOutputStream();
            if (blobs.read(attestationsKey(project, filename), attestations)) {
                form.field("attestations", attestations.toString(StandardCharsets.UTF_8));
            }
            form.file("content", filename, "application/octet-stream", located.get().size(), () -> blobs.open(hash));
            files.add(new PublishedExport.File(
                    ExportTarget.Request.post("", form.contentType(), ExportTarget.Body.of(form.length(), form::open)),
                    Optional.of("simple/" + project + "/" + filename), hash));
        }
        return PublishedExport.send(files, target);
    }

    /** The {@code pyversion} twine sends: a wheel's python tag, {@code source} for an sdist. */
    private static String pythonTag(String filename) {
        if (!filename.endsWith(".whl")) {
            return "source";
        }
        String[] parts = filename.substring(0, filename.length() - ".whl".length()).split("-", -1);
        return parts.length >= 5 ? parts[parts.length - 3] : "py3";
    }

    /**
     * Every artifact the upstream rooted at {@code upstream} publishes, walked through its own index by
     * {@link PyPiEnumeration}: what an import from that index lays out. A link off the upstream's own origin must be
     * {@code https} and public: an import screens the source's host before it starts, and trusts nothing beyond it.
     */
    @Override
    public Stream<ProxyFormat.Coordinate> enumerate(ProxyFormat.Fetcher fetcher, URI upstream) throws IOException {
        return PyPiEnumeration.enumerate(fetcher, upstream, false)
                .map(entry -> new ProxyFormat.Coordinate(entry.getKey(), entry.getValue()));
    }
}
