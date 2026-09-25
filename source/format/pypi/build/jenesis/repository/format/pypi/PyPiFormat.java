package build.jenesis.repository.format.pypi;

import module java.base;
import module tools.jackson.databind;
import module java.xml;
import module org.slf4j;

import build.jenesis.repository.format.Listings;
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
 * The PyPI format (the Simple Repository API plus the legacy upload endpoint), so {@code twine upload} and
 * {@code pip install} work over the same store. It owns {@code /pypi/...}. An upload ({@code POST /pypi/}, a
 * multipart form from twine) stores the distribution file under {@code pypi/<project>/files/<filename>}, the
 * project name normalized per PEP 503. The project index ({@code GET /pypi/simple/<project>/}) is served from a
 * stored page the upload maintains, as the PEP 503 HTML, each link relative to the index with the file's
 * {@code #sha256} so pip verifies it; the file itself is served at {@code /pypi/simple/<project>/<filename>}.
 */
public final class PyPiFormat implements RepositoryFormat, ProxyLeg, BlobLayout, RepositoryImporter,
        ArtifactSignatures {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(PyPiFormat.class);

    // Compiled once, not per request: the anchor href of a proxied Simple page, and the PEP 503 project-name separator run.
    private static final Pattern HREF = Pattern.compile("href=\"([^\"]*)\"");
    // The whole anchor, because a PEP 658 sidecar's digest lives on the tag beside the href rather than in it.
    private static final Pattern ANCHOR = Pattern.compile("<a\\s([^>]*)>", Pattern.CASE_INSENSITIVE);
    // PEP 714 renamed PEP 658's attribute; indexes in the wild serve either, so both are read and the newer wins.
    private static final Pattern CORE_METADATA =
            Pattern.compile("data-core-metadata=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern DIST_INFO_METADATA =
            Pattern.compile("data-dist-info-metadata=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern SEPARATORS = Pattern.compile("[-_.]+");

    @Override
    public String name() {
        return "pypi";
    }

    /** A lifecycle mark surfaces in the metadata this format's clients read, so marks are accepted here. */
    @Override
    public boolean surfacesLifecycleMarks() {
        return true;
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
        // Every distribution file whose name carries this version as its '-'-delimited token: an sdist
        // (<name>-<version>.tar.gz / .zip) or a wheel (<name>-<version>-<pytag>...whl). All of a project's versions
        // share one files/ directory, so the version is matched in the filename rather than a directory segment.
        if (!BlobLayout.addressable(coordinate, version)) {
            return List.of();   // a traversal-shaped coordinate maps nowhere - these keys are what an eviction DELETES
        }
        String project = normalize(coordinate);
        String dir = "pypi/" + project + "/files";
        List<String> keys = new ArrayList<>();
        if (!store.isEmpty("pypi/" + project + "/by")) {
            // The reverse index an upload writes answers without a scan; a project from before it is scanned as
            // before.
            for (String file : store.list(reverseKey(project, version, ""))) {
                if (store.readVersioned(dir + "/" + file).isPresent()) {
                    keys.add(dir + "/" + file);
                } else {
                    store.delete(reverseKey(project, version, file));   // evicted: the note is stale
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

    /** {@code pypi/<project>/by/<version>/<file>}: the reverse index an upload writes for a file it can version. */
    static String reverseKey(String project, String version, String file) {
        return "pypi/" + project + "/by/" + version + (file.isEmpty() ? "" : "/" + file);
    }

    /** One project's distribution files: a flat container enumerated through the shared bounded primitive.
     *  This feeds {@code blobKeys}/{@code servedPaths}, so a listing that answered short would be a KEV-listed
     *  distribution that keeps serving after its hold - the entry cap is therefore OFF, and the binding bound is the
     *  primitive's step budget (1000 page round-trips of the drain page), which raises a named
     *  {@link build.jenesis.repository.walk.TraversalException} rather than dropping keys. */
    private static final BoundedChildren DISTRIBUTIONS = BoundedChildren.bounded().entries(Integer.MAX_VALUE)
            .page(BoundedChildren.DRAIN_PAGE);


    /** The request paths this project version's distributions serve at ({@code /pypi/simple/<project>/<file>}), the
     *  inverse of {@link #describe} - a retroactive hold links a {@code /quarantine} review handle at each. Matches the
     *  same version-carrying distribution files {@link #blobKeys} names, mapped back to their {@code simple/} URL. */
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

    /** Whether a distribution filename carries exactly this version as its {@code -}-delimited token, without matching
     *  a longer version of which it is a prefix. A wheel bounds the version with {@code -} on both sides
     *  ({@code <name>-<version>-<pytag>...whl}), so {@code -<version>-} never matches a longer {@code -<version>.x-}
     *  (a dot, not a dash, follows the prefix). An sdist ends the version with the extension's leading dot
     *  ({@code <name>-<version>.tar.gz}/{@code .zip}), so {@code -<version>.} is a match only when a non-digit (the
     *  extension) follows - a continued version like {@code 1.0.10} has a digit after {@code -1.0.} and is not
     *  mistaken for {@code 1.0}. This prevents retention from deleting {@code 1.0.10}'s files when evicting {@code 1.0}. */
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

    /** The distribution extensions a describable file carries - the same set the PyPI compliance inspector screens. */
    private static final List<String> DIST_EXTENSIONS = List.of(".whl", ".tar.gz", ".zip", ".egg");

    /** The coordinate a distribution request path carries ({@code /pypi/simple/<project>/<file>}, the project
     *  PEP 503-normalized exactly as {@link #blobKeys}, the upload and the PyPI compliance inspector key it), so the
     *  inventory writes the {@code published/} sidecar the retroactive enforcement sweeps enumerate the version by.
     *  The root and per-project indexes and a PEP 658 {@code .metadata} sidecar name no versioned artifact and stay
     *  empty. The version is peeled from the filename the way the inspector does: a wheel's is unambiguously its
     *  second {@code -} field (the wheel spec escapes the name's dashes to {@code _}), an sdist/egg's by matching the
     *  normalized project prefix; a filename neither parse fits describes coordinate-less rather than guessing. */
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
            // A distribution path is exactly <project>/<filename>; a deeper path is not a coordinate this format serves.
            // Defence in depth so a stray multi-segment path can never leak its slashes into the parsed version segment.
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

    /** The version encoded in a distribution filename's extension-less base, given the normalized project it belongs
     *  to - the PyPI compliance inspector's parse, mirrored so both report one coordinate for one file. */
    /** The mark on the version a distribution FILE belongs to, or null when it carries none. The filename is parsed
     *  by the same rule {@link #describe} uses, so a file the coordinate parse cannot place is one this cannot mark
     *  either - it lists unyanked rather than guessing at a version. */
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

    private static String version(String project, String base, boolean wheel) {
        if (wheel) {
            // {name}-{version}(-{build})?-{python}-{abi}-{platform}: the wheel spec escapes every '-' in the name to
            // '_', so the version is unambiguously the second '-'-separated field.
            String[] parts = base.split("-");
            return parts.length >= 2 ? parts[1] : null;
        }
        // sdist/egg: {name}-{version}. The name may itself contain '-', so peel it off by matching the known project.
        int dash = base.length();
        while ((dash = base.lastIndexOf('-', dash - 1)) > 0) {
            if (normalize(base.substring(0, dash)).equals(project)) {
                return base.substring(dash + 1);
            }
        }
        int last = base.lastIndexOf('-');
        return last <= 0 ? null : base.substring(last + 1);
    }

    // An original CC0 line glyph (two interlocking rounded squares) drawn for this project.
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

    /**
     * A {@code twine upload} wraps its artifact in a multipart form, so this format is <b>not</b> edge-screened
     * ((a)): the request body is an <em>envelope</em>, and gating it at the shared single-body edge would hash and
     * assess the multipart while the bytes that later serve are the wheel or sdist inside it - a second
     * content-addressed object under a hash no interceptor ever saw, which is {@code RepositoryFormat} clause 14's
     * fail-open direction. The shared edge ({@code ScreenedDispatch}) takes the request body verbatim and offers no
     * seam to unwrap one, so this format is in the {@code screened() == false} case the clause names and screens at its
     * own documented choke point: {@link #upload} peels the {@code content} part off the envelope while it streams and
     * drives the shared {@code Publication.commit} - with the <em>discovered</em> interceptor chain and observers -
     * over the distribution's own bytes. The legacy upload endpoint is the only way a distribution is hosted-published
     * here, so declaring {@code false} does not leave the format unscreened.
     */
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

    /**
     * Where a proxied distribution's PEP 740 provenance is fetched from: the base of the integrity API, the document
     * being {@code <base>/<project>/<version>/<file>/provenance}. Empty by default, which reads the upstream's own
     * origin - pypi.org serves it at {@code https://pypi.org/integrity/}; a mirror that serves no integrity API
     * answers 404, which is absence and never a failure.
     */
    public static final String PROVENANCE_URL = "pypi-provenance-url";

    /**
     * The provenance document PyPI publishes for a distribution and pip never fetches, named so the pull-through
     * fetches it beside the file and the screen judges the file by it. Kept through {@link #keep} as the attestations
     * the upload leg would have stored, so the integrity endpoint and the evidence reader serve both alike.
     */
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

    /**
     * A fetched provenance document is kept as the attestations it carries - every attestation of every publisher
     * bundle, flattened into the list the upload leg stores - under the distribution's attestations key, so
     * {@link #evidence} and the integrity endpoint read a proxied file exactly as an uploaded one. Always answers
     * {@code true}: a document that is not provenance is dropped rather than linked at a path nothing serves.
     */
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

    /**
     * Proxy a PyPI miss to the upstream index (pypi.org). The project index is mutable: the upstream PEP 503 page is
     * fetched and each file link rewritten to its bare filename (so it resolves to this repository's file URL),
     * then served fresh. A file is immutable: its upstream location is found in the project index (the file lives on
     * a different host than the index), fetched, cached and served locally.
     */
    @Override
    public boolean pullThrough(FormatExchange exchange, ArtifactStore store, URI upstream,
                               ProxyFormat.Fetcher fetcher) throws IOException {
        String path = exchange.path();
        if (!path.startsWith("/pypi/simple/")) {
            // ProxyLeg has screened the path already - this format claims it, and it carries no traversal
            // segment, backslash or control character. What is left is this leg's own routing: only the
            // subtree below proxies, and any other claimed path lets the local 404 stand.
            return false;
        }
        String after = path.substring("/pypi/simple/".length());
        if (after.isEmpty()) {
            // The ROOT index, which this leg must not answer. `handle` splits this case off to projects() and serves
            // the repository's own project list; `pullThrough` did not, so "" fell through as a project name: it
            // fetched `<upstream>simple//` and handed the result to rewriteIndex, which reduces every href to the text
            // after its last '/'. PyPI's root links are `/simple/<project>/` and END in '/', so **every rewritten href
            // came out empty** - a 200 carrying a page of links to nowhere. Only a pure pass-through repository ever
            // reached it; one with a store answers locally and never gets here.
            //
            // Declined rather than repaired, and the buffering is why. The root index is PyPI's entire project list -
            // the one index in this product that is categorically not small - and this leg reads it through the
            // BUFFERED fetch, so answering it at all materialises the whole list in heap on every request, against
            // clause 4 (only small metadata may be materialised). Serving it correctly would mean rewriting while
            // streaming, and serving it partially would be worse than not serving it: a project missing from a
            // truncated list reads as "this index does not carry it", which is a wrong answer rather than a short one.
            // A pass-through repository publishes no project list of its own, and saying so is honest - nothing pip
            // does on an install reads this document, and every link it has ever served here was empty.
            return false;
        }
        int slash = after.indexOf('/');
        String root = upstream.toString();
        if (!root.endsWith("/")) {
            root += "/";
        }
        if (slash < 0 || slash == after.length() - 1) {
            String project = normalize(slash < 0 ? after : after.substring(0, slash));
            // ENUMERATION: the PEP 503 project page IS pip's file list for the project - every distribution and
            // therefore every version it may resolve to - so an absent one is the answer "no such project here" and an
            // EMPTY one is "no distribution matches your Python". A fetch that never landed rendered as either is a
            // resolution changed by a network blip, so only an upstream that ANSWERED 404/410 reaches the client as a
            // 404; anything else refuses visibly.
            ProxyRelay.Answer answer = ProxyRelay.fetchFresh(fetcher, URI.create(root + "simple/" + project + "/"),
                    Map.of(), exchange, ProxyRelay.Document.ENUMERATION);
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
        Optional<ProxyFormat.Fetched> index = fetcher.fetch(URI.create(root + "simple/" + project + "/"), Map.of());
        if (index.isEmpty() || index.get().status() != 200) {
            return false;
        }
        // PEP 658: pip requests <distribution>.metadata; its upstream URL is the distribution's URL plus the suffix.
        boolean metadata = file.endsWith(".metadata");
        String distribution = metadata ? file.substring(0, file.length() - ".metadata".length()) : file;
        Located found = findFile(new String(index.get().body(), StandardCharsets.UTF_8), distribution);
        if (found == null) {
            return false;
        }
        String location = found.location();
        // The file's location is read from the untrusted upstream index body and is cross-host by design (pypi.org's
        // files live on files.pythonhosted.org), so an attacker who can publish a project to the proxied upstream
        // chooses both its host and its scheme: an unguarded fetch would be an SSRF, and an unguarded http one would
        // put the wheel and any per-host upstream credential in front of every observer. The one shared outbound
        // screen decides it - the SAME call the composer, cocoapods, nuget, cargo and rpm legs make, so
        // "mirrors the composer guard" is now a fact about the code rather than a claim in a comment. A refused target
        // is declined and the miss falls through to a 404, never a throw (ProxyLeg clause 2).
        URI target;
        try {
            target = URI.create(metadata ? location + ".metadata" : location);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (!OutboundTargets.mayFollow(target, upstream, ProxyLeg.allowInternalTargets(exchange))) {
            return false;
        }
        // A distribution file is an immutable artifact of unbounded size: stream it from the network straight into the
        // content-addressed store rather than buffering the whole body, then re-serve it locally.
        try (ProxyFormat.Download download = fetcher.download(target, Map.of()).orElse(null)) {
            if (download == null || download.status() != 200) {
                return false;
            }
            // Bind the cached distribution to the SHA-256 the upstream PEP 503 index declares for it (the #sha256=
            // fragment on the file href), the way conda/nuget/cargo/cocoapods/conan/huggingface/npm bind their
            // proxy-cached artifacts: the file lives on a different host than the index that vouches for it (the SSRF
            // note above), so a diverging or MITM'd file host cannot have its bytes durably cached and re-served as the
            // authentic artifact - writeVerified refuses and caches nothing on a digest mismatch. Absent a usable digest
            // (the PEP 658 .metadata sidecar carries the distribution's hash, not its own, so it is never verified here)
            // the write is unverified, the prior behaviour.
            //
            // No split to make here, and for the same protocol reason Composer and CocoaPods have none: the
            // simple index is the SAME document that resolves the file's location, so an index this repository could
            // not read declines the whole fill above (the `index.isEmpty() || status != 200` return) rather than
            // reaching this point with "the index declares no digest".
            byte[] expected = metadata ? hexOrNull(found.metadataSha256()) : hexOrNull(found.sha256());
            if (!ProxyRelay.fill(new Blobs(store), "pypi/" + project + "/files/" + file, target, download.body(),
                    expected == null ? ProxyRelay.Declared.NONE : ProxyRelay.Declared.of("SHA-256", expected))) {
                // A refused fill on the PEP 658 sidecar is answered VISIBLY, not as a local miss. Most distributions
                // publish no .metadata at all, so a 404 here is the ordinary, legal answer and pip acts on it: it
                // downloads the whole wheel and reads METADATA out of it, and reports success. Spelling a refusal the
                // same way therefore does not withhold the sidecar, it substitutes a different resolution for it
                // silently - the repository detected a corrupted document and the build went green over it (§9).
                // Exactly the Maven .module split (D-210), on the one PyPI path with the same property.
                //
                // The distribution keeps the plain decline: a wheel's absence is a loud answer that fails an install,
                // so nothing resolves around it and a miss cannot be mistaken for a decision.
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

    /** A file href resolved out of the upstream simple index: its bare {@code location} URL and the {@code sha256} hex
     *  the {@code #sha256=} fragment declares for it ({@code null} when the index carries none), so the proxy can bind
     *  the cached bytes to the digest the index vouches for. */
    /**
     * One file's row on a proxied Simple page.
     *
     * @param sha256         the DISTRIBUTION's digest, off the href fragment
     * @param metadataSha256 the PEP 658 sidecar's OWN digest, off the anchor's {@code data-core-metadata} /
     *                       {@code data-dist-info-metadata} attribute - a different file and therefore a different
     *                       hash, which is why one cannot stand in for the other and why the sidecar went unverified
     *                       while the fragment was the only digest read (D-290)
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

    /** The {@code sha256=<hex>} an anchor attribute declares, or null when it declares none.
     *
     *  <p>PEP 658 also allows a bare {@code true} - "a sidecar exists here" - which vouches for nothing and is
     *  deliberately NOT read as a digest: treating it as one would have to invent a value, and the honest handling of
     *  an index that publishes no digest is the unverified write that was the behaviour for every sidecar before this.
     *  Only a declared digest narrows anything. */
    private static String digest(String attributes, Pattern attribute) {
        Matcher matcher = attribute.matcher(attributes);
        if (!matcher.find()) {
            return null;
        }
        String value = matcher.group(1);
        return value.startsWith("sha256=") ? value.substring("sha256=".length()) : null;
    }

    /** Decode a hex digest to bytes, or {@code null} when it is absent or malformed - a malformed upstream digest falls
     *  back to an unverified cache write rather than refusing the fetch, exactly as an absent one does. */
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

    /**
     * The republish conflict policy this format hands the hosted-publish operation as <em>data</em>, rather
     * than re-implementing "is this filename already taken" beside the layout: {@code OVERWRITE}, last-writer-wins.
     * That is exactly what a {@code twine upload} does today - a distribution pointer lives in the {@code pypi/} blobs
     * namespace, not in {@code publish/}, so the release-immutability edge hook (which reads a
     * {@code publish/<path>} pointer) has never seen it, and a re-upload has always silently re-pointed. A
     * <em>probing</em> mode could not be expressed here in any case: the operation evaluates the policy before the
     * accepted layout runs, and the pointer key an upload collides on is only known once the envelope's
     * {@code name} field and the {@code content} part's filename have both been read - which happens inside that
     * layout, since the protocol does not order the two.
     */
    private static final Publication.Republish REPUBLISH = Publication.Republish.overwrite();

    /**
     * The legacy {@code twine upload} endpoint, run through the one shared hosted-publish choreography
     * ({@code Publication.commit}) rather than hand-assembled here: the distribution streams
     * content-addressed into the store, the accepted layout finishes reading the small form fields that name it, and
     * only then does the operation link the serving pointer and stamp the per-project hosted marker.
     * <b>The commit point is the {@code pypi/<project>/files/<filename>} pointer link</b> - before it nothing serves
     * and the Simple index answers a miss; after it the distribution downloads and the project index lists it.
     *
     * <p>This is the ordering fix owns for PyPI: the former code linked the distribution pointer <em>first</em>
     * and only then stamped {@code pypi/<project>/.hosted}, so a crash in between left a downloadable file whose
     * project index had not yet been switched on. The marker gates a listing surface, so it is a visibility write and
     * is now declared beside the pointer - after it, never before, so the index is never switched on ahead of the
     * bytes it would list.
     *
     * <p>The distribution file (the multipart {@code content} part) is the one unbounded part - a wheel or sdist of
     * arbitrary size - so it is handed to the operation as the accepted body and streams straight into the
     * content-addressed store (hash-on-write, never buffered) while the small text fields (name, version, digests) are
     * read whole. Since (a) that part is also what the <b>screen</b> sees: this format opts out of the single-body
     * ingress edge ({@link #screened()}), so nothing content-addresses the multipart envelope any more and there is no
     * second CAS object under a hash no interceptor ever saw. The wheel is stored exactly once, the accepted hash is
     * the distribution's own - which is what the Simple index publishes as its {@code #sha256} - and the bytes the
     * chain assessed are the bytes {@code pip} later downloads.
     *
     * <p>The pointer is declared, never written by the layout, so the upload stays robust to part order: the
     * {@code name} field may arrive before or after the file (twine sends it before; the protocol does not require
     * it), and the layout finishes walking the envelope for the fields it still needs before it declares anything.
     * No size cap: a multi-gigabyte upload that no heap could hold still completes, because the body is never a
     * {@code byte[]}.
     *
     * <p><b>This is the format's screening choke point</b> ((a)). Because {@link #screened()} is {@code false} the
     * shared ingress edge dispatches the upload straight here, so the operation is constructed with the
     * <em>discovered</em> interceptor chain and observer list rather than two empty ones: the one screen runs here,
     * over the distribution's own bytes, and the one after-commit notification fires here once it is visible. The
     * choreography, the ordering and the layout are otherwise unchanged; only the bytes the chain sees moved from the
     * envelope to the artifact.
     *
     * <p>The descriptor the screen assesses under is the distribution's own <em>served</em> path
     * ({@code /pypi/simple/<project>/<filename>}), not the {@code POST} endpoint, so a deny-list, a
     * {@code /quarantine} review handle and an inspector's artifact leg all key on the coordinate the download will
     * serve. {@code twine} and every other client build the envelope metadata-first, so the {@code name} field is
     * already in hand when the file part is reached; a client that sends it <em>after</em> the file is screened under
     * the coordinate-less endpoint descriptor instead - its content is still hashed and assessed, only the
     * coordinate-keyed dimensions degrade, and the layout still refuses to declare anything without a project.
     */
    private void upload(FormatExchange exchange, Blobs blobs, ArtifactStore store) throws IOException {
        Optional<String> boundary = MultipartBody.boundary(exchange.requestHeader("Content-Type"));
        if (boundary.isEmpty()) {
            exchange.respond(400);
            return;
        }
        // The shared streaming reader (build.jenesis.repository.multipart): one bounded, forward-only cursor over the
        // envelope, so the `content` part reaches the store as a stream and the small fields are read against an
        // explicit bound. The NuGet push walks the same reader.
        MultipartBody body = MultipartBody.over(exchange.requestStream(), boundary.get());
        Form form = new Form();
        InputStream distribution = form.readToDistribution(body);
        if (distribution == null) {
            exchange.respond(400);   // an envelope carrying no `content` file part uploads no distribution
            return;
        }
        Publication.Commit commit;
        try (InputStream part = distribution) {
            commit = new Publication(store).commit(
                    uploaded(exchange, form.project, form.filename), part, REPUBLISH,
                    _ -> {
                        // Finish the envelope: the accepted body is already stored content-addressed, and the fields
                        // that name it may still be ahead of us in the stream. Nothing servable has been written yet,
                        // so reading them here is exactly "parse before you declare".
                        form.readRemainder(body);
                        String project = form.project;
                        String filename = form.filename;
                        if (project == null || Keys.unsafe(project) || Keys.unsafe(filename)) {
                            // No project field, or a body-supplied project/filename that would forge a pointer key
                            // with '/' or '..': nothing servable, so nothing is declared and nothing is linked.
                            return Publication.Visibility.declined();
                        }
                        return Publication.Visibility
                                // The serving pointer, in this format's own namespace rather than publish/ - so it is
                                // declared through a Serving step, not named with at().
                                .through((hash, _, _) -> blobs.link("pypi/" + project + "/files/" + filename, hash))
                                // The attestations the upload carried, kept beside the file before the Simple page
                                // links them, so no client reads a link whose provenance is still to come.
                                .andThrough((_, _, _) -> storeAttestations(blobs, project, filename, form.attestations))
                                // Stamp the per-project hosted-publish marker, so a later project-index read serves the
                                // local files. A pull-through proxy repository (whose files are cached by proxy(), never
                                // uploaded) never writes it, so its index read misses locally and the pull-through
                                // fetches the authoritative upstream Simple index for every version rather than
                                // shadowing it with only the cached files. Mirrors the RPM hosted-revision gate.
                                .andThrough((_, _, target) -> markHosted(target, hostedKey(project)))
                                .andThrough((_, _, _) -> reverseIndex(blobs, project, filename))
                                // The served Simple pages are written here, on the upload: the file's link joins the
                                // project's stored page and the project the stored root page.
                                .andThrough((_, _, _) -> new PyPiListings(blobs).refresh(project, filename));
                    });
            // The chain HELD the distribution. The layout above never ran, so it is written here instead - behind the
            // withhold marker (see {@link #held}) and inside this try, while the envelope cursor is still live, since
            // the fields that NAME the distribution may still be ahead of the file part.
            switch (commit.disposition()) {
                case QUARANTINE -> held(body, form, blobs, store, commit.hash());
                default -> {
                }
            }
        }
        switch (commit.disposition()) {
            case ACCEPT -> exchange.respond(commit.visible() ? 200 : 400);
            // Held for review: stored, laid out and withheld - the Simple index screens it out until it is released.
            case QUARANTINE -> exchange.respond(202);
            // Refused outright: nothing is linked and no marker is set, so the Simple index never lists it and the
            // stored blob is the usual unreferenced content-addressed object a collection reclaims. A refusal is never
            // released, so it is never laid out.
            case REJECT -> exchange.respond(422);
        }
    }

    /**
     * Lay a <em>held</em> distribution out behind its withhold marker, so the review release that follows is the
     * same marker clear a retroactive KEV/licence hold's release is - one hold-release mechanism for this format, not
     * two. The shared commit operation runs its accepted layout only on {@code ACCEPT}, so a screen-time
     * {@code QUARANTINE} would otherwise store the wheel, link nothing and index nothing:
     * {@code HoldLifecycle.release} would then resolve the hold and materialise no distribution at all, which is the
     * regression this closes.
     *
     * <p>The envelope is finished first - exactly as the accepted layout finishes it - because a client may send the
     * {@code name} field <em>after</em> the file part, and the project is what every key below is built from. Then
     * {@link Withheld#mark} retracts the distribution's hash, and only after it are the serving pointer and the
     * project's hosted marker written, so at no instant is the held wheel downloadable or listed: {@link #index} and
     * {@link #projects} screen on that same marker, exactly as {@link #serveFile} does. A body that names no project (or
     * one that would forge a pointer key) lays nothing out, for the reason the accepted layout declines it - there is no
     * servable coordinate to hold open, and the hold stays reviewable by its stored blob alone.
     */
    private static void held(MultipartBody body, Form form, Blobs blobs, ArtifactStore store, String hash)
            throws IOException {
        form.readRemainder(body);
        String project = form.project;
        String filename = form.filename;
        if (project == null || Keys.unsafe(project) || Keys.unsafe(filename)) {
            return;
        }
        Withheld.mark(store, hash, new PyPiFormat().describe("/pypi/simple/" + project + "/" + filename)
                .orElse(ArtifactDescriptor.at("PyPI", "/pypi/simple/" + project + "/" + filename)));
        blobs.link("pypi/" + project + "/files/" + filename, hash);
        storeAttestations(blobs, project, filename, form.attestations);
        markHosted(store, hostedKey(project));
        reverseIndex(blobs, project, filename);
        new PyPiListings(blobs).refresh(project, filename);   // held: the stored pages keep it out
    }

    /** Write the file's reverse-index entry, so its version's keys are found without scanning the project. */
    private static void reverseIndex(Blobs blobs, String project, String filename) throws IOException {
        Optional<ArtifactDescriptor> described = new PyPiFormat().describe("/pypi/simple/" + project + "/" + filename);
        if (described.isPresent() && described.get().version() != null && !Keys.unsafe(described.get().version())) {
            blobs.note(reverseKey(project, described.get().version(), filename), filename);
        }
    }

    /** The descriptor the uploaded distribution is screened under: its own served path
     *  ({@code /pypi/simple/<project>/<filename>}) with the coordinate {@link #describe} parses out of it, so the
     *  screen assesses the artifact under the identity the download will serve. Falls back to the coordinate-less
     *  endpoint descriptor when the envelope has not yet named a project (a client that sends {@code name} after the
     *  file part) or when either body-supplied value would forge a key - the content is screened either way, and the
     *  layout refuses to declare anything servable in those cases. */
    private ArtifactDescriptor uploaded(FormatExchange exchange, String project, String filename) {
        if (project == null || filename == null || Keys.unsafe(project) || Keys.unsafe(filename)) {
            return ArtifactDescriptor.at("PyPI", exchange.path());
        }
        String served = "/pypi/simple/" + project + "/" + filename;
        return describe(served).orElseGet(() -> ArtifactDescriptor.at("PyPI", served));
    }

    /**
     * The twine form fields the upload is named by, accumulated across the one pass over the multipart envelope: the
     * distribution's filename (the {@code content} part's {@code Content-Disposition} filename) and the PEP 503
     * -normalized project (the {@code name} text field). The walk is split in two because the artifact part is handed
     * to the hosted-publish operation as a bounded stream: {@link #readToDistribution} walks up to and including that
     * part's headers, and {@link #readRemainder} finishes the envelope from inside the accepted layout - which is why
     * a {@code name} field sent <em>after</em> the file is still read before anything is declared.
     *
     * <p>A method-local accumulator over one request, never shared and never escaping the upload, so its mutation is
     * the loop-accumulator kind the immutability rule allows rather than a churned live object.
     */
    private static final class Form {

        private String project;
        private String filename;
        private String attestations;

        /** Walk the envelope until the {@code content} file part, recording every small text field on the way, and
         *  return that part as a stream bounded to the distribution's bytes - the accepted body of the publish.
         *  {@code null} when the envelope ends without one, in which case nothing was stored. */
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

        /** Finish the envelope after the distribution part has been consumed and stored, so a {@code name} field the
         *  client sent after the file is still read before the layout declares anything. The cursor releases the
         *  distribution part itself as it advances - it has been read to its terminating boundary, and closing it
         *  before the walk resumes keeps the two from interleaving their positions in the shared buffer. The caller's
         *  own try-with-resources closing it again is a no-op. */
        private void readRemainder(MultipartBody body) throws IOException {
            for (Optional<MultipartBody.Part> next = body.next(); next.isPresent(); next = body.next()) {
                field(next.get());
            }
        }

        /** Read one small text field - the project {@code name} is kept PEP 503-normalized, anything else is
         *  discarded. A `name` longer than the shared field bound is left unset rather than truncated: a value that
         *  large is not a project name, and a cut-off one would forge a different coordinate, so the upload declines
         *  below (400) instead of publishing under half a name. */
        private void field(MultipartBody.Part part) throws IOException {
            if ("name".equals(part.name())) {
                project = part.text(MultipartBody.FIELD_LIMIT)
                        .map(value -> normalize(value.trim()))
                        .orElse(null);
            } else if ("attestations".equals(part.name())) {
                // PEP 740: the JSON list of attestation objects twine sends beside the distribution - a certificate,
                // a transparency-log entry and a signed statement each, a few kilobytes - bounded at the signature
                // limit past which the upload carries none rather than an unbounded document.
                attestations = part.text(ArtifactSignatures.Material.LARGEST_SIGNATURE).orElse(null);
            } else {
                part.discard();   // any other metadata field, not needed here
            }
        }
    }

    /** The JSON reader and writer for the attestations an upload carries and the provenance document served. */
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** The bytes of the hosted-publish marker (its value is irrelevant; only its presence gates). */
    private static final byte[] HOSTED = "1".getBytes(StandardCharsets.UTF_8);

    /** The per-project hosted-publish marker key - a sibling of the {@code files} directory, so no index listing ever
     *  surfaces it. Package-private so {@link PyPiImporter} stamps it too: an import is a hosted publish, exactly as a
     *  {@code twine} upload is. The project is already PEP 503-normalized. */
    static String hostedKey(String project) {
        return "pypi/" + project + "/.hosted";
    }

    /** Whether this (already-normalized) project has ever taken a hosted upload (or import) - it then carries the
     *  marker {@link #upload} stamps, which a pull-through proxy never writes. The project-index gate keys on it so a
     *  proxy repository's index read always misses locally and reproxies the upstream index (every version) for an
     *  uncached version rather than shadowing it with only the cached distribution files. */
    private static boolean hosted(String project, Blobs blobs) throws IOException {
        return blobs.exists(hostedKey(project));
    }

    /** Whether the root index may list this (already-normalized) project - it has at least one distribution a client
     *  can actually fetch. A project every one of whose distributions a compliance hold has withheld is screened out:
     *  listing its name would disclose a quarantined project pip then 404s on the download of, the same disclosure the
     *  per-project {@link #index} screen and the OCI catalog screen close. A project with no local distribution files
     *  (a proxy/metadata-only entry) is left listed - it names no withheld coordinate - and the first servable file
     *  short-circuits the scan, so a normal project pays only one {@code withheld} probe. */
    static boolean servable(String project, Blobs blobs) throws IOException {
        String prefix = "pypi/" + project + "/files";
        // The membership question the shared screened enumeration answers directly, short-circuiting at the first
        // disclosable name: a normal project pays one probe, and the answer is the same screen the per-project index
        // applies, not a second private one.
        if (ScreenedNames.keys(blobs.servableNames(), ServableNames.Policy.HIDE_WITHHELD).any(blobs.store(), prefix)) {
            return true;
        }
        // Provably no disclosable distribution. A project with NO distribution file at all is still listed - it names no
        // withheld coordinate - so only the emptiness probe distinguishes the two, and it is a structural question about
        // the container, not a disclosure of any name.
        return blobs.isEmpty("pypi/" + project + "/files");
    }

    /** Stamp a hosted-publish marker once, idempotently - a compare-and-set against an absent pointer, so a concurrent
     *  upload's lost race simply means a peer already set it. The marker is a bare presence flag (not a blob pointer),
     *  written straight through the store like the RPM revision stamp. */
    static void markHosted(ArtifactStore store, String key) throws IOException {
        if (store.readVersioned(key).isEmpty()) {
            store.writeVersioned(key, HOSTED, null);
        }
    }

    /** Whether a body-derived value must not be spliced into a store pointer key - empty, a dot segment, or carrying a
     *  path separator or control character. Mirrors the composer/cargo/lifecycle guard. */

    /** The stored attestations of a distribution: PEP 740's list, as the client sent it, under the file. */
    static String attestationsKey(String project, String file) {
        return "pypi/" + project + "/attestations/" + file;
    }

    /** Keep an upload's attestations when it carried a non-empty list; anything else is not provenance. */
    private static void storeAttestations(Blobs blobs, String project, String filename, String attestations)
            throws IOException {
        if (attestations == null) {
            return;
        }
        JsonNode list;
        try {
            list = MAPPER.readTree(attestations);
        } catch (RuntimeException notJson) {
            return;
        }
        if (list == null || !list.isArray() || list.isEmpty()) {
            return;
        }
        blobs.write(attestationsKey(project, filename), MAPPER.writeValueAsBytes(list));
    }

    /**
     * PEP 740's provenance endpoint, {@code /integrity/<project>/<version>/<file>/provenance}: the attestations the
     * distribution was uploaded with, as one attestation bundle - a version, the bundles, each naming its publisher
     * and carrying its attestations. The publisher is what the identity in each attestation's certificate says, and
     * this registry does not restate it: the verifier reads the certificate.
     */
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

    /** A distribution may have been uploaded with PEP 740 attestations - each a Sigstore signing of an in-toto
     *  statement naming the file by digest; optional, since most uploads carry none. */
    @Override
    public List<ArtifactSignatures.Expectation> expects(String path) {
        return describe(path).map(described -> described.coordinate() != null && described.version() != null)
                .orElse(false)
                ? List.of(ArtifactSignatures.Expectation.optional(ArtifactSignatures.Scheme.SIGSTORE_BUNDLE))
                : List.of();
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

    /**
     * A PEP 740 attestation as the Sigstore bundle the verifier reads: the same certificate, the same
     * transparency-log entries, and the statement and signature as a DSSE envelope over the in-toto payload type.
     * Empty for an object that is not one.
     */
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

    /** A request path's serving key, for the compliance screen's sibling read: a distribution under its project's
     *  Simple page, or a distribution's provenance under the integrity endpoint - when the pointer exists. */
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
        Optional<Blobs.Located> located = blobs.locate("pypi/" + project + "/files/" + file);
        if (located.isEmpty()) {
            exchange.respond(404);
            return;
        }
        long size = located.get().size();
        exchange.setResponseHeader("Content-Type", "application/octet-stream");
        if (exchange.method().equals("HEAD")) {
            // Answer HEAD from the stored blob size (Content-Length, 200, no body) rather than streaming the whole
            // distribution just to discard it - pip issues HEADs to probe a file's size and existence.
            if (size >= 0) {
                exchange.setResponseHeader("Content-Length", Long.toString(size));
            }
            exchange.respond(200, -1L).close();
            return;
        }
        blobs.serve(located.get(), exchange);
    }

    /** The PEP 503 root index ({@code /pypi/simple/}): the stored page every upload maintains, listing every hosted
     *  project with a servable distribution - the page the standard requires and the one an enumeration starts
     *  from. A project whose every distribution is compliance-withheld is not listed, the same way the per-project
     *  page screens a withheld file (and OCI its catalog). */
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

    /** The length of a root page listing nothing - the page frame alone. */
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
        // The project page is a stored listing the upload maintains, streamed as is.
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

    /** The migration-import capability (WSPI.2 (c)), delegated to the layout-only {@link PyPiImporter} - the format IS the
     *  discovered importer now (an {@code instanceof} capability), and the importer class stays as its delegate. */
    private final PyPiImporter importer = new PyPiImporter();

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
