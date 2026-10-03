package build.jenesis.repository.format.maven;

import module java.base;
import module org.slf4j;
import build.jenesis.repository.format.LifecycleMark;
import build.jenesis.repository.store.ServableNames;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.format.ArtifactLayout;
import build.jenesis.repository.format.ArtifactSignatures;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.format.java.JavaLayout;
import build.jenesis.repository.format.java.bridge.ModuleView;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.format.RepositoryExporter;
import build.jenesis.repository.format.ExportTarget;
import build.jenesis.repository.format.PublishedExport;


/**
 * The Maven layout ({@code /maven/...}): a {@code PUT} stores the body content-addressed through {@link Publication} -
 * a {@code maven-metadata.xml} and its checksums verbatim like any artifact - and a {@code GET} serves the stored
 * bytes, an absent one a 404. Computing {@code maven-metadata.xml} is the opt-in {@link MavenMetadata#COMPUTE_SETTING},
 * read off the exchange. A modular jar is cross-published into the Jenesis module layout: its module name is handed to
 * the discovered {@link ModuleView} ({@link ServiceLoader}), so a client resolving by module name reaches the same
 * blob.
 */
public final class MavenFormat implements RepositoryFormat, ProxyFormat, ArtifactLayout, ArtifactSignatures,
        RepositoryImporter.Delegating, RepositoryExporter {

    private static final List<ModuleView> MODULE_VIEWS = ModuleView.installed();

    /** The migration-import capability, delegated to {@link MavenImporter}. */
    private final MavenImporter importer = new MavenImporter();

    @Override
    public RepositoryImporter importer() {
        return importer;
    }

    /** The ecosystem name the descriptor carries, OSV's "Maven", distinct from {@link #name()}, the format id routing
     *  {@code /maven/}. */
    public static final String ECOSYSTEM = "Maven";

    @Override
    public String name() {
        return "maven";
    }

    /** A yank shows, as Maven people say it: an unlisted version is left out of {@code maven-metadata.xml}, so ranges
     *  and the newest-version lookups skip it while a build naming it exactly still gets it. */
    @Override
    public Map<LifecycleMark, String> lifecycleMarks() {
        return Map.of(LifecycleMark.YANKED, "unlisted");
    }

    /** Maven's paths keep their {@code /maven/} segment inside a repository ({@code /repository/<name>/maven/...}), so
     *  a Maven repository can become a {@code java} one, serving the module layout beside it from the same blobs with
     *  every client URL unchanged. */
    @Override
    public String mount() {
        return "";
    }

    /** A Maven repository is a folder tree, and some clients list its folders where the metadata is missing. */
    @Override
    public boolean browsable() {
        return true;
    }

    @Override
    public boolean handles(String path) {
        return path.startsWith("/maven/");
    }

    @Override
    public String ecosystem() {
        return ECOSYSTEM;
    }

    /**
     * Maven's inbound signatures, two sidecars beside one artifact: a detached OpenPGP signature at
     * {@code <artifact>.asc} over the artifact's bytes, expected since the upstream this layout mirrors demands one on
     * every release; and a Sigstore bundle at {@code <artifact>.sigstore.json}, optional since few artifacts carry one.
     * The bundle is keyless, so one that verifies is VALID only where an operator's pin names its identity, and
     * otherwise UNTRUSTED.
     *
     * <p>Both are sidecars ({@link ServableNames#sidecar}): neither is signable, both are left out of listings and
     * inherit the artifact's hold. One format answers for both, since the completion observer takes
     * the first format whose {@code covers} answers. {@code maven-metadata.xml} is excluded because no publisher signs
     * it, and the checksum and signature siblings because a sidecar carries no sidecar.
     */
    private static final ArtifactSignatures SIGNATURES = ArtifactSignatures.composed(ECOSYSTEM,
            ArtifactSignatures.detachedSidecar(ECOSYSTEM, ".asc", ArtifactSignatures.Scheme.OPENPGP_DETACHED,
                    MavenFormat::signable, ArtifactSignatures.Coverage.REQUIRED),
            ArtifactSignatures.detachedSidecar(ECOSYSTEM, ".sigstore.json", ArtifactSignatures.Scheme.SIGSTORE_BUNDLE,
                    MavenFormat::signable, ArtifactSignatures.Coverage.OPTIONAL));

    /** Whether a request path names a released artifact a publisher's signature covers: only the {@code /maven/} tree,
     *  since the {@code /module/} mirror points at the same blob and claiming both would judge one artifact twice. */
    private static boolean signable(String path) {
        return path.startsWith("/maven/")
                && !ServableNames.sidecar(path)
                && !path.endsWith("/maven-metadata.xml")
                && !path.endsWith("/");
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

    @Override
    public Optional<ArtifactDescriptor> describe(String path) {
        return descriptor(path);
    }

    @Override
    public List<String> paths(String coordinate, String version) {
        int colon = coordinate.indexOf(':');
        if (colon < 0) {
            return List.of();
        }
        String artifact = coordinate.substring(colon + 1);
        // ArtifactLayout clause 3: these paths are handed to eviction, which deletes under them, so each groupId
        // component, the artifactId and the version are screened; a part that is not addressable maps nowhere.
        String[] group = coordinate.substring(0, colon).split("\\.", -1);
        if (!ArtifactLayout.addressable(group) || !ArtifactLayout.addressable(artifact, version)) {
            return List.of();
        }
        return List.of("/maven/" + String.join("/", group) + "/" + artifact + "/" + version);
    }

    @Override
    public List<String> paths(String coordinate, String version, ArtifactStore store) {
        List<String> primary = paths(coordinate, version);
        if (primary.isEmpty()) {
            return primary;
        }
        int colon = coordinate.indexOf(':');
        String artifact = coordinate.substring(colon + 1);
        String mavenDir = primary.getFirst();
        List<String> paths = new ArrayList<>(primary);
        // Also the /module/ view of a modular jar, its module name read back from the stored jar, so cleanup removes
        // the mirror too. Best-effort, and the one store read, which is why a read path calls the store-free overload.
        //
        // Resolved through blob(), not located(): this answers which paths the version occupies, not which a GET would
        // serve. Otherwise the mirror would vanish once the jar was held, and a hold's converge pass, eviction and the
        // release's cross-alias exclusion set would all miss it.
        try {
            Publication publication = new Publication(store);
            Optional<String> hash = publication.blob(mavenDir + "/" + artifact + "-" + version + ".jar");
            if (hash.isPresent() && store.exists("blobs/" + hash.get())) {
                String module = moduleName(store, hash.get());
                if (module != null) {
                    paths.add("/module/" + module + "/" + version);
                    // And the module's "latest" pointer, only while it resolves to this version's jar: the one path not
                    // version-addressed, so it belongs to the version it names. Without it an eviction would leave the
                    // pointer aimed at a reclaimed blob, and a release could not lift the marker its own alias holds.
                    paths.add(JavaLayout.ARTIFACT_ROUTE + module + "/" + version);
                    for (String latest : List.of(JavaLayout.latestModule(module),
                            JavaLayout.latestArtifact(module, "jar"))) {
                        if (publication.blob(latest).filter(hash.get()::equals).isPresent()) {
                            paths.add(latest);
                        }
                    }
                    Optional<String> pom = publication.blob(mavenDir + "/" + artifact + "-" + version + ".pom");
                    String latestPom = JavaLayout.latestArtifact(module, "pom");
                    if (pom.isPresent() && publication.blob(latestPom).equals(pom)) {
                        paths.add(latestPom);
                    }
                }
            }
        } catch (IOException _) {
            // Best-effort: the /maven/ pointers still evict.
        }
        return paths;
    }

    /** The neutral descriptor of a {@code /maven/...} path, or empty for generated metadata: a full coordinate maps to
     *  {@code group:artifact} and version, with the {@code -SNAPSHOT} prerelease rule here; a path that is not a full
     *  coordinate, and a sidecar - a checksum or a signature, which is no file of the version - carries the ecosystem
     *  only, so it records nothing against the version, raises no event and counts no download. */
    private static Optional<ArtifactDescriptor> descriptor(String path) {
        if (MavenMetadata.isMetadataRequest(path)) {
            return Optional.empty();
        }
        String[] coordinate = ServableNames.sidecar(path) ? null : JavaLayout.mavenCoordinate(path);
        if (coordinate == null) {
            return Optional.of(ArtifactDescriptor.at(ECOSYSTEM, path));
        }
        return Optional.of(new ArtifactDescriptor(ECOSYSTEM, coordinate[0] + ":" + coordinate[1], coordinate[2],
                path, null, coordinate[2].endsWith("-SNAPSHOT"), null, -1L));
    }

    @Override
    public void serve(FormatExchange exchange, ArtifactStore store) throws IOException {
        String path = exchange.path();
        if (exchange.method().equals("PUT")) {
            // A maven-metadata.xml is stored verbatim like any artifact. The body was already screened at the ingress
            // edge.
            layout(store, path, exchange.requestStream());
            if (metadataCompute(exchange)) {
                // The computed document is a stored listing the upload maintains: an artifact adds its version, a
                // metadata upload resets it.
                new MavenMetadata(store).uploaded(path);
            }
            exchange.respond(201);
            return;
        }
        boolean head = exchange.method().equals("HEAD");
        // With the computation on, an artifact-level document is served from its listing; empty leaves the verbatim
        // serve.
        if (MavenMetadata.isMetadataRequest(path) && metadataCompute(exchange)) {
            Optional<byte[]> computed = new MavenMetadata(store).served(path);
            if (computed.isPresent()) {
                // HEAD answers from the computed document's length.
                if (head) {
                    exchange.setResponseHeader("Content-Length", Long.toString(computed.get().length));
                    exchange.respond(200);
                } else {
                    exchange.respond(200, computed.get());
                }
                return;
            }
        }
        // The default: the stored bytes, a 404 when absent.
        Optional<Publication.Located> located = new Publication(store).locate(path);
        if (located.isEmpty()) {
            exchange.respond(404);
            return;
        }
        String key = located.get().key();
        long size = located.get().size();
        if (head) {
            // HEAD answers from the pointer's recorded size, touching no blob.
            if (size >= 0) {
                exchange.setResponseHeader("Content-Length", Long.toString(size));
            }
            exchange.respond(200);
            return;
        }
        // Opened before the response is committed, so a pointer whose blob is gone answers a clean 404 rather than a
        // 200 with no body.
        InputStream in;
        try {
            in = store.open(key, exchange.from(size));
        } catch (NoSuchFileException gone) {
            exchange.respond(404);
            return;
        }
        try (in; OutputStream out = exchange.respond(200, size)) {
            in.transferTo(out);
        }
    }

    /** Whether this deployment opts into computing {@code maven-metadata.xml} (default off), read off the exchange so
     *  this format needs no settings layer. */
    private static boolean metadataCompute(FormatExchange exchange) {
        return Boolean.parseBoolean(exchange.setting(MavenMetadata.COMPUTE_SETTING));
    }

    /** Lay an already-screened body out into the Maven namespace: store it content-addressed, streamed
     *  ({@link Publication#storeBlob}), then run the layout sequence over the stored blob. Returns the blob hash. */
    public static String layout(ArtifactStore store, String path, InputStream body) throws IOException {
        Publication.Blob blob = new Publication(store).stored(body);
        return layout(store, path, blob.hash(), blob.size());
    }

    /**
     * The layout sequence over an already-stored blob, the one place this format makes an artifact reachable; the proxy
     * leg, which holds fetched bytes to their checksum first, links through it too.
     *
     * <p><b>The sequence, and what is true after a failure at each step.</b>
     * <ol>
     *   <li><b>The {@code /maven/} pointer is linked.</b> The commit point: before it the blob is unreferenced; after
     *       it the artifact serves under its coordinate.</li>
     *   <li><b>The module name is read back from the stored blob.</b> A failure leaves the artifact serving with no
     *       {@code /module/} view.</li>
     *   <li><b>Each discovered {@link ModuleView} links the module's views.</b> A failure at the n-th leaves the first
     *       n-1 linked.</li>
     * </ol>
     *
     * <p>The coordinate goes first because only that order's residue converges: the module view is derived from the
     * blob the coordinate points at, so {@link ModuleViewRebuild} or a republish finishes any partial state. A stray
     * view names no coordinate, so the reverse order would leave a residue nothing could repair.
     */
    public static String layout(ArtifactStore store, String path, String hash) throws IOException {
        return layout(store, path, hash, -1L);
    }

    /** {@link #layout(ArtifactStore, String, String)} with the blob's length in hand, so the pointer records it without
     *  a stat. */
    public static String layout(ArtifactStore store, String path, String hash, long size) throws IOException {
        Publication publication = new Publication(store);
        publication.link(path, hash, size);
        String[] coordinate = JavaLayout.mavenCoordinate(path);
        if (coordinate == null) {
            return hash;
        }
        String pom = JavaLayout.attachment(path, ".pom");
        if (path.equals(pom)) {
            // Maven deploys the jar before the POM, so the descriptor joins the jar's module view now, as the latest
            // one when the latest jar is this version's.
            String jar = JavaLayout.attachment(path, ".jar");
            Optional<String> jarHash = publication.blob(jar);
            String module = jarHash.isEmpty() ? null : moduleName(store, jarHash.get());
            if (module != null) {
                boolean latest = publication.blob(JavaLayout.latestModule(module)).equals(jarHash);
                for (ModuleView view : MODULE_VIEWS) {
                    view.describe(module, coordinate[2], hash, latest, store, path);
                }
            }
            return hash;
        }
        Optional<String> classifier = JavaLayout.mavenClassifier(path);
        if (classifier.isEmpty()) {
            return hash;
        }
        String module = moduleName(store, hash);
        if (module == null) {
            return hash;
        }
        for (ModuleView view : MODULE_VIEWS) {
            view.publish(module, coordinate[2], classifier.get(), hash, store, path);
        }
        // And the descriptor, when it arrived first.
        Optional<String> pomHash = classifier.get().isEmpty() ? publication.blob(pom) : Optional.empty();
        if (pomHash.isPresent()) {
            boolean latest = publication.blob(JavaLayout.latestModule(module)).equals(Optional.of(hash));
            for (ModuleView view : MODULE_VIEWS) {
                view.describe(module, coordinate[2], pomHash.get(), latest, store, pom);
            }
        }
        return hash;
    }

    /** The reverse index of a jar's module name by blob hash ({@code by/module/<hash>}), written when the name is first
     *  read, so a pass reads it rather than opening every jar. A collected blob's record is left behind; it costs a few
     *  bytes. */
    public static final String MODULE_INDEX = "by/module";

    /** The module name the blob {@code hash} declares, or null when the blob is gone or the jar is non-modular; the one
     *  read the layout and its rebuild share. Read from {@link #MODULE_INDEX} when recorded, else out of the jar and
     *  then recorded, an empty body standing for non-modular; without the record every rebuild pass would download
     *  every jar. A store that refuses the record still answers. */
    static String moduleName(ArtifactStore store, String hash) throws IOException {
        Optional<ArtifactStore.Versioned> recorded = store.readVersioned(MODULE_INDEX + "/" + hash);
        if (recorded.isPresent()) {
            String name = new String(recorded.get().content(), StandardCharsets.UTF_8).trim();
            return name.isEmpty() ? null : name;
        }
        String name;
        try (InputStream in = store.open("blobs/" + hash)) {
            name = JavaLayout.moduleName(in);
        }
        try {
            store.write(MODULE_INDEX + "/" + hash,
                    new ByteArrayInputStream((name == null ? "" : name).getBytes(StandardCharsets.UTF_8)));
        } catch (IOException | RuntimeException unrecordable) {
            // A store that refused the small write: the name is still the jar's.
        }
        return name;
    }

    @Override
    public Optional<URI> defaultUpstream() {
        return Optional.of(URI.create("https://repo1.maven.org/maven2/"));
    }

    /** Demo-mode suggestions: {@code log4j-core 2.14.1} (POM and jar) and {@code commons-collections 3.2.1}, old,
     *  benign-but-vulnerable releases so a fresh repository's vulnerability and quarantine surfaces have something to
     *  show. They are pulled through this format's own upstream ({@link #defaultUpstream() Maven Central}). */
    @Override
    public List<String> demoArtifacts() {
        return List.of(
                "/maven/org/apache/logging/log4j/log4j-core/2.14.1/log4j-core-2.14.1.pom",
                "/maven/org/apache/logging/log4j/log4j-core/2.14.1/log4j-core-2.14.1.jar",
                "/maven/commons-collections/commons-collections/3.2.1/commons-collections-3.2.1.jar");
    }

    /**
     * Proxy a {@code /maven/} miss to the upstream Maven repository. Artifacts are immutable and cached, a modular jar
     * cross-published like a local one; {@code maven-metadata.xml} is mutable and proxied fresh on each miss, never
     * cached.
     *
     * <p>A cached artifact is held to the upstream's {@code .sha1} before it is laid out: the bytes are stored
     * content-addressed as they stream and the {@link #layout(ArtifactStore, String, String) layout sequence} runs only
     * on a match, so a refused fill is never reachable and nothing needs retracting.
     */
    @Override
    public boolean proxy(FormatExchange exchange, ArtifactStore store, URI upstream, ProxyFormat.Fetcher fetcher)
            throws IOException {
        String path = exchange.path();
        // Clause 6: a traversal-shaped path is no proxy target.
        if (!path.startsWith("/maven/") || !ArtifactStore.traversalFree(path)) {
            return false;
        }
        String rest = path.substring("/maven/".length());
        String root = upstream.toString();
        String prefix = root.endsWith("/") ? root : root + "/";
        if (MavenMetadata.isMetadataRequest(path)) {
            // A mutable index: fetched fresh and streamed to the client, nothing cached.
            Optional<ProxyFormat.Fetched> index = fetcher.fetch(URI.create(prefix + rest), Map.of());
            // Clause 2: maven-metadata.xml is an enumeration a range or LATEST/RELEASE resolves against, so a 404 means
            // "no versions"; only an upstream that answered 404/410 may reach the client as one, and anything else
            // refuses visibly. Its checksums keep the plain decline, since nothing resolves against their absence.
            if (rest.endsWith("/maven-metadata.xml")) {
                if (index.isEmpty()) {
                    return unanswered(prefix + rest, exchange, "the upstream could not be reached");
                }
                if (index.get().status() != 200 && index.get().status() != 404 && index.get().status() != 410) {
                    return unanswered(prefix + rest, exchange, "the upstream answered " + index.get().status());
                }
            }
            if (index.isEmpty() || index.get().status() != 200) {
                return false;
            }
            exchange.respond(200, index.get().body());
            return true;
        }
        Optional<ProxyFormat.Download> fetched = fetcher.download(URI.create(prefix + rest), Map.of());
        if (fetched.isEmpty()) {
            return resolvedAgainstAbsence(rest)
                    ? undecided(prefix + rest, exchange, "the upstream could not be reached")
                    : false;
        }
        try (ProxyFormat.Download download = fetched.get()) {
            if (download.status() != 200) {
                // An upstream 404/410 for a descriptor is the answer that none is published; any other status is not.
                if (resolvedAgainstAbsence(rest) && download.status() != 404 && download.status() != 410) {
                    return undecided(prefix + rest, exchange, "the upstream answered " + download.status());
                }
                return false;
            }
            if (ServableNames.sidecar(rest)) {
                layout(store, path, download.body());
            } else {
                // Verified against the upstream's SHA-1, computed as the blob streams to storage. The bytes are stored
                // here and laid out below only once they match, so a refused fill links nothing; its unreferenced blob
                // is the collector's.
                MessageDigest sha1 = sha1();
                Publication.Blob stored = new Publication(store).stored(new DigestInputStream(download.body(), sha1));
                String hash = stored.hash();
                URI sibling = URI.create(prefix + rest + ".sha1");
                Sha1 expected = upstreamSha1(fetcher, sibling);
                if (expected.unreadable() != null) {
                    // Clause 5: only an upstream that publishes no .sha1 may downgrade a fill to unverified. A sibling
                    // that could not be read is refused like a mismatch, or anyone able to drop one request could
                    // switch verification off.
                    LOGGER.warn("Refusing to cache the proxied artifact {} unverified: {}. Nothing was cached or served; "
                            + "the local 404 stands so a later pull re-hits the upstream.", prefix + rest,
                            expected.unreadable());
                    return resolvedAgainstAbsence(rest)
                            ? undecided(prefix + rest, exchange, "its checksum sibling could not be read")
                            : false;
                }
                if (expected.hex() != null && !expected.hex().equalsIgnoreCase(HexFormat.of().formatHex(sha1.digest()))) {
                    // A mismatch is logged on its own, since it says the bytes changed between the upstream and here.
                    LOGGER.warn("Refusing to cache the proxied artifact {}: it does not match the SHA-1 {} the upstream "
                            + "publishes for it. Nothing was cached or served; the local 404 stands.", prefix + rest,
                            expected.hex());
                    return resolvedAgainstAbsence(rest)
                            ? undecided(prefix + rest, exchange, "it does not match the SHA-1 the upstream publishes")
                            : false;
                }
                layout(store, path, hash, stored.size());
            }
        }
        handle(exchange, store);
        return true;
    }

    /** What Central publishes beside a released file and a client never asks for: its {@code .asc} and, where present,
     *  its {@code .sigstore.json}, fetched on a fill so the proxy screen judges the signature the upstream publishes. A
     *  listing, checksum or signature has none. */
    @Override
    public List<ProxyFormat.Companion> companions(FormatExchange exchange, URI upstream) {
        String path = exchange.path();
        if (!signable(path) || MavenMetadata.isMetadataRequest(path) || !ArtifactStore.traversalFree(path)) {
            return List.of();
        }
        String root = upstream.toString();
        String target = (root.endsWith("/") ? root : root + "/") + path.substring("/maven/".length());
        return List.of(new ProxyFormat.Companion(path + ".asc", URI.create(target + ".asc")),
                new ProxyFormat.Companion(path + ".sigstore.json", URI.create(target + ".sigstore.json")));
    }

    /** Answer a {@code maven-metadata.xml} request that could not be put to the upstream with a {@code 502} rather than
     *  the local {@code 404}, logging which target failed and how. It returns {@code true} because a response was
     *  served: a resolver reads a {@code 404} here as "no versions" and fails with a wrong reason or moves to the next
     *  mirror (clause 2). */
    private static boolean unanswered(String target, FormatExchange exchange, String reason) throws IOException {
        LOGGER.warn("Refusing to answer the Maven metadata request {} as an empty version list: {}. Nothing was served; "
                + "the local 404 would have been read by the resolver as the upstream's own answer.", target, reason);
        exchange.respond(502);
        return true;
    }

    /** Whether a client resolves against this path's absence, so answering a refusal as a miss would be wrong. Most
     *  coordinates publish no Gradle {@code .module}, and Gradle reads a 404 as "use the POM" and builds green, so a
     *  refusal spelled as a 404 silently substitutes another resolution. Checksums keep the plain decline. */
    private static boolean resolvedAgainstAbsence(String rest) {
        return rest.endsWith(".module");
    }

    /** Refuse visibly on a path whose absence is itself an answer. */
    private static boolean undecided(String target, FormatExchange exchange, String reason) throws IOException {
        LOGGER.warn("Refusing to answer the proxied descriptor {} as an absent descriptor: {}. Nothing was served; the "
                + "local 404 would have been read by the client as \"this component publishes no module metadata\", "
                + "and it would have resolved a different variant without an error.", target, reason);
        exchange.respond(502);
        return true;
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(MavenFormat.class);

    private static MessageDigest sha1() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * What the upstream's {@code .sha1} sibling says about an artifact being cached: three states, since clause 5
     * licenses the fall-back for one.
     *
     * @param hex the 40-hex digest, or {@code null} when the upstream answered that it publishes none, and the artifact
     *     is proxied unverified
     * @param unreadable why the sibling could not be read, or {@code null} when it was; never a fall-back, the fill is
     *     refused
     */
    private record Sha1(String hex, String unreadable) {
    }

    /** The upstream SHA-1 for an artifact from its {@code .sha1} sibling (40 hex, optionally followed by a filename).
     *  An upstream answering {@code 404}/{@code 410}, or a body that is no digest, publishes none; a transport failure
     *  or other status is unreadable. */
    private static Sha1 upstreamSha1(ProxyFormat.Fetcher fetcher, URI sha1) throws IOException {
        Optional<ProxyFormat.Fetched> response = fetcher.beside().fetch(sha1, Map.of());
        if (response.isEmpty()) {
            return new Sha1(null, "the checksum sibling " + sha1 + " could not be reached");
        }
        int status = response.get().status();
        if (status == 404 || status == 410) {
            return new Sha1(null, null);   // the upstream answered: it publishes no checksum for this artifact
        }
        if (status != 200) {
            return new Sha1(null, "the checksum sibling " + sha1 + " answered " + status);
        }
        String body = new String(response.get().body(), StandardCharsets.UTF_8).trim();
        int space = body.indexOf(' ');
        String hex = space > 0 ? body.substring(0, space) : body;
        return new Sha1(hex.length() == 40 && hex.chars().allMatch(c -> Character.digit(c, 16) >= 0) ? hex : null,
                null);
    }

    /** A version's folder, each file put under the client's {@code .../maven/} URL; the {@code /module/} view is left
     *  to a target that derives it from the jar. */
    @Override
    public Exported export(ArtifactStore repository, String coordinate, String version, ExportTarget target)
            throws IOException {
        return PublishedExport.putAll(repository, paths(coordinate, version), CLIENT_PATH, target);
    }

    /** A Maven client is pointed at a repository's {@code /maven/}, the root this format lays its paths out from. */
    @Override
    public String clientPath() {
        return CLIENT_PATH;
    }

    private static final String CLIENT_PATH = "/maven/";

    /** The coordinate's {@code maven-metadata.xml} and its checksums, after its last version, as {@code mvn deploy}
     *  sends them. */
    @Override
    public void exported(ArtifactStore repository, String coordinate, ExportTarget target) throws IOException {
        int colon = coordinate.indexOf(':');
        if (colon < 0) {
            return;
        }
        String[] group = coordinate.substring(0, colon).split("\\.", -1);
        String artifact = coordinate.substring(colon + 1);
        if (!ArtifactLayout.addressable(group) || !ArtifactLayout.addressable(artifact)) {
            return;
        }
        String metadata = "/maven/" + String.join("/", group) + "/" + artifact + "/maven-metadata.xml";
        PublishedExport.put(repository, List.of(metadata, metadata + ".sha1", metadata + ".md5",
                metadata + ".sha256", metadata + ".sha512"), "/maven/", target);
    }
}
