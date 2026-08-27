package build.jenesis.repository.format.maven;

import module java.base;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.format.ArtifactLayout;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.format.java.JavaLayout;
import build.jenesis.repository.format.java.bridge.ModuleView;
import build.jenesis.repository.store.ArtifactStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Maven layout ({@code /maven/...}): a {@code PUT} stores the blob content-addressed through the shared
 * {@link Publication} store - including a {@code maven-metadata.xml} and its checksum siblings, stored verbatim like
 * any artifact - and a {@code GET} serves the stored bytes byte-for-byte, an absent one a 404. Deriving
 * {@code maven-metadata.xml} on read is no longer the default: it is the opt-in {@link MavenMetadata#COMPUTE_SETTING}
 * computation, read off the exchange, which reconciles a stored document's version list (or derives one for a
 * coordinate no client uploaded). When the uploaded artifact is a
 * modular jar, it is cross-published into the Jenesis module layout: this format reads the module name and hands it to
 * the {@link ModuleView} the Jenesis format provides (discovered with {@link ServiceLoader}), so a client resolving by
 * module name reaches the same blob - the bridge between the two layouts, exposed only between them and never on the
 * public SPI. Discovered like any other format; the core knows nothing of it.
 */
public final class MavenFormat implements RepositoryFormat, ProxyFormat, ArtifactLayout, RepositoryImporter {

    private static final List<ModuleView> MODULE_VIEWS = ServiceLoader.load(ModuleView.class)
            .stream().map(ServiceLoader.Provider::get).toList();

    /** The migration-import capability (WSPI.2 (c)), delegated to the layout-only {@link MavenImporter} - the format
     *  IS the discovered importer now (an {@code instanceof} capability), and the importer class stays as its delegate. */
    private final MavenImporter importer = new MavenImporter();

    /** The package-ecosystem name the neutral descriptor carries - the OSV name "Maven" that advisory feeds and
     *  quality inspectors key on - distinct from {@link #name()} "maven", the format id that routes the {@code /maven/}
     *  paths. Any consumer of a Maven artifact reports the same ecosystem, whichever edition it runs in. */
    public static final String ECOSYSTEM = "Maven";

    @Override
    public String name() {
        return "maven";
    }

    @Override
    public boolean handles(String path) {
        return path.startsWith("/maven/");
    }

    @Override
    public String ecosystem() {
        return ECOSYSTEM;
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
        // ArtifactLayout clause 3: a coordinate is as client-supplied as a request path, and these paths are handed to
        // eviction, which unpublishes and DELETES under them. A groupId's dots become separators, so its components are
        // screened one by one; the artifactId and the version are single segments. A part that is not addressable maps
        // nowhere - the empty list this method already documents for a coordinate that maps nowhere - rather than
        // composing "/maven/g/../1.0" and aiming an eviction delete at a neighbouring key space.
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
        // Also the module view this format cross-published for a modular jar: read the module name back from the
        // stored jar (the same read publish did), so a cleanup that unpublishes this version removes its /module/
        // mirror too and the shared blob becomes unreferenced. Best-effort: no jar, no module, no mirror. This is the
        // one store read, and it is why a read path (a console search) must call the store-free overload instead.
        //
        // The pointer is resolved through blob(), NOT located(): this method answers "which request paths does this
        // version OCCUPY", which is a fact about stored state, and located() answers "which of them would a GET
        // serve", which is a fact about the current hold. Asking the serving question here made the mirror vanish
        // from every caller the moment the jar was held - and the callers are the retroactive holds' own converge
        // pass, eviction, reconciliation and the release path's cross-alias exclusion set. Driven consequences
        //: a hold that crashed between the coordinate pointer and the mirror pointer could never converge,
        // because the re-run no longer saw the path it had not yet held; an eviction of a held version left the
        // mirror pointing at a blob it had just reclaimed; and a release of the coordinate could not lift the
        // content-addressed marker, because the version's OWN mirror - missing from the exclusion set - read as a
        // foreign alias still holding those bytes. The blob stat keeps the old "no jar, no mirror" degrade for a
        // torn pointer, since a module name cannot be read out of content the store does not hold.
        try {
            Publication publication = new Publication(store);
            Optional<String> hash = publication.blob(mavenDir + "/" + artifact + "-" + version + ".jar");
            if (hash.isPresent() && store.exists("blobs/" + hash.get())) {
                String module = moduleName(store, hash.get());
                if (module != null) {
                    paths.add("/module/" + module + "/" + version);
                    // And the module's "latest" pointer, but ONLY while it names this version. It is the one
                    // cross-published path that is not version-addressed, so it belongs to whichever version it
                    // currently points at and to no other - which is exactly what the store can answer here, by
                    // comparing what the pointer resolves to against this version's own jar.
                    //
                    // Omitting it made this method under-reach in both directions it is used. An eviction of the
                    // version the pointer names removed the blob and left the pointer aimed at it, so the module's
                    // latest view 404'd until someone republished, instead of falling back to the newest survivor.
                    // And a release could not lift the content-addressed marker, because the version's own latest
                    // alias - missing from the exclusion set - read as a foreign alias still holding those bytes,
                    // which left a reviewer's released artifact permanently unreachable under its module name.
                    // Reporting it by resolution rather than by construction also settles the opposite error one
                    // layout over, where every version claimed the pointer and a first-version eviction destroyed
                    // a live pointer naming a later one.
                    String latest = "/module/" + module + "/" + module + ".jar";
                    if (publication.blob(latest).filter(hash.get()::equals).isPresent()) {
                        paths.add(latest);
                    }
                }
            }
        } catch (IOException _) {
            // best-effort; the /maven/ pointers still evict and the blob is reclaimed if now unreferenced
        }
        return paths;
    }

    /** The neutral descriptor of a {@code /maven/...} path, or empty for generated metadata (nothing to describe): a
     *  full coordinate maps to {@code group:artifact} + version, and this is the one place the {@code -SNAPSHOT}
     *  prerelease rule lives; a path that is not a full coordinate (a checksum root) carries the ecosystem only. */
    private static Optional<ArtifactDescriptor> descriptor(String path) {
        if (MavenMetadata.isMetadataRequest(path)) {
            return Optional.empty();
        }
        String[] coordinate = JavaLayout.mavenCoordinate(path);
        if (coordinate == null) {
            return Optional.of(ArtifactDescriptor.at(ECOSYSTEM, path));
        }
        return Optional.of(new ArtifactDescriptor(ECOSYSTEM, coordinate[0] + ":" + coordinate[1], coordinate[2],
                path, null, coordinate[2].endsWith("-SNAPSHOT"), null, -1L));
    }

    @Override
    public void serve(FormatExchange exchange, ArtifactStore store) throws IOException {
        String path = exchange.path();
        // RepositoryFormat clause 6: a request path carrying a . or .. segment addresses nothing under /maven/, so it
        // is refused here - before Publication.link would hand it to the store's key screen, which throws (an unmapped
        // 500 where the truth is "no such artifact"). Same screen, same shapes, stated once in ArtifactStore; this is
        // the in-format seam OciFormat has always carried through isImageName/isTag/isDigestHex (§13 parity).
        if (!ArtifactStore.traversalFree(path)) {
            exchange.respond(404);
            return;
        }
        if (exchange.method().equals("PUT")) {
            // (1): a maven-metadata.xml (and its checksum siblings) is stored verbatim like any artifact rather
            // than dropped, so a publisher-authored document round-trips even when the server does not derive one.
            // Screening rides the ingress edge now (EPIC 26): this branch only lays the body out and responds 201 -
            // the body reaching here has already been screened to ACCEPT, so verdicts are no longer the format's call.
            layout(store, path, exchange.requestStream());
            if (metadataCompute(exchange)) {
                // The computed maven-metadata.xml is a stored listing the upload maintains, not a read-time
                // reconciliation: a version's artifact adds its version, a metadata upload resets the document.
                new MavenMetadata(store).uploaded(path);
            }
            exchange.respond(201);
            return;
        }
        boolean head = exchange.method().equals("HEAD");
        // (3): with the opt-in computation on, an artifact-level document has its version list reconciled (or is
        // derived for a coordinate no client uploaded); a checksum is served from the authored bytes. Empty means the
        // default verbatim serve stands.
        if (MavenMetadata.isMetadataRequest(path) && metadataCompute(exchange)) {
            Optional<byte[]> computed = new MavenMetadata(store).served(path);
            if (computed.isPresent()) {
                // A HEAD answers from the computed document's length (Content-Length only, no body), the way OCI/Raw
                // answer a HEAD from metadata instead of writing the whole document out.
                if (head) {
                    exchange.setResponseHeader("Content-Length", Long.toString(computed.get().length));
                    exchange.respond(200);
                } else {
                    exchange.respond(200, computed.get());
                }
                return;
            }
        }
        // (2): the default - serve the stored metadata (and its stored checksums) byte-for-byte, a 404 when
        // absent; a normal artifact is streamed from its content-addressed blob.
        Optional<String> key = new Publication(store).located(path);
        if (key.isEmpty()) {
            exchange.respond(404);
            return;
        }
        long size = store.size(key.get());
        if (head) {
            // A HEAD is answered from the stored size (Content-Length), 200 with no body, without opening the blob -
            // the read-first HEAD-from-metadata contract OciFormat/RawFormat already follow, so a HEAD never streams
            // the whole artifact just to discard it.
            exchange.setResponseHeader("Content-Length", Long.toString(size));
            exchange.respond(200);
            return;
        }
        try (OutputStream out = exchange.respond(200, size)) {
            store.read(key.get(), out);
        }
    }

    /** Whether this deployment opts into computing {@code maven-metadata.xml} on read (default off), read off the
     *  exchange so this free format consults the setting without depending on any settings layer. */
    private static boolean metadataCompute(FormatExchange exchange) {
        return Boolean.parseBoolean(exchange.setting(MavenMetadata.COMPUTE_SETTING));
    }

    /** Lay an already-screened body out into the Maven namespace: store it content-addressed ({@link
     *  Publication#storeBlob}, streamed straight to storage, never buffered whole) and then run the layout sequence
     *  below over the stored blob. Screening no longer happens here (EPIC 26): the ingress edge screens the body to
     *  ACCEPT and restreams the stored blob into this layout, so a body reaching {@code layout} is already accepted and
     *  there is no verdict to map - the redundant format-embedded screen pass is dropped, the essential link (what
     *  {@link Publication#located} serves over) is kept. The restreamed body dedupes to the same {@code blobs/<hash>}, so reading
     *  the module name back is identical to before. Returns the content-addressed blob hash. */
    public static String layout(ArtifactStore store, String path, InputStream body) throws IOException {
        return layout(store, path, new Publication(store).storeBlob(body));
    }

    /**
     * The layout sequence over an <em>already-stored</em> blob, and the one place this format makes an artifact
     * reachable. It is stated here because it is the format's only multi-step visibility write, and because a caller
     * that has a reason to store the bytes before deciding to serve them (the proxy leg, which must hold the fetched
     * bytes to their upstream checksum first) links through this overload rather than re-deriving the sequence.
     *
     * <p><b>The sequence, and what is true after a failure at each step.</b>
     * <ol>
     *   <li><b>The {@code /maven/} pointer is linked.</b> This is the commit point: before it nothing serves and the
     *       stored blob is an unreferenced object a garbage collection reclaims; after it the artifact serves under
     *       its coordinate. A failure here fails the caller with nothing servable.</li>
     *   <li><b>The module name is read back from the stored blob.</b> A failure here (a store read that could not be
     *       served) leaves the artifact serving under its coordinate with no {@code /module/} view.</li>
     *   <li><b>Each discovered {@link ModuleView} links the module's views.</b> A failure at the n-th view leaves the
     *       coordinate serving, the first n-1 views linked and the rest absent.</li>
     * </ol>
     *
     * <p><b>Why the coordinate goes first, given that it is the step that exposes the artifact.</b> Because it is the
     * only order whose residue converges. The {@code /module/} view is <em>derived</em> from the Maven coordinate -
     * the module name is read out of the very blob the coordinate points at - so every partial state above is one a
     * later pass can finish from what survived: {@code ModuleViewRebuild} re-derives the version-addressed view for
     * every published Maven jar on each rebuild pass, and a byte-identical republish re-runs the whole sequence. The
     * reverse order (views first, coordinate last) leaves a residue nothing can repair: a {@code /module/} view names
     * a module and a version and no Maven coordinate, so no pass can re-derive the coordinate from it, and deleting
     * the stray view instead would be an orphan purge over a namespace the Jenesis format also publishes into
     * first-hand. The exposure the first step buys is the exposure a successful publish buys anyway, one moment later.
     */
    public static String layout(ArtifactStore store, String path, String hash) throws IOException {
        new Publication(store).link(path, hash);
        String[] coordinate = JavaLayout.mavenCoordinate(path);
        if (!path.endsWith(".jar") || coordinate == null) {
            return hash;
        }
        String module = moduleName(store, hash);
        if (module == null) {
            return hash;
        }
        for (ModuleView view : MODULE_VIEWS) {
            view.publish(module, coordinate[2], hash, store, path);
        }
        return hash;
    }

    /** The module name the content-addressed blob {@code hash} declares, or null when the blob is gone or the jar is
     *  non-modular - the single read the layout cross-link and its rebuild share, so both act on the same module. */
    static String moduleName(ArtifactStore store, String hash) throws IOException {
        try (InputStream in = store.open("blobs/" + hash)) {
            return JavaLayout.moduleName(in);
        }
    }

    @Override
    public Optional<URI> defaultUpstream() {
        return Optional.of(URI.create("https://repo1.maven.org/maven2/"));
    }

    /**
     * Demo-mode suggestions: a Log4Shell-era {@code log4j-core 2.14.1} (its POM and jar) and a
     * {@code commons-collections 3.2.1} (the classic deserialization coordinate), deliberately old,
     * benign-but-vulnerable releases so a fresh repository's vulnerability and quarantine surfaces light up at once -
     * the coordinates are what the OSV / GHSA / KEV / EPSS feeds and a demo gate config key on (a version floor
     * quarantines the old log4j-core, a deny-list rejects commons-collections), and the bytes themselves are ordinary,
     * harmless libraries. The seeder pulls these through this format's own upstream ({@link #defaultUpstream() Maven
     * Central}); nothing malicious is ever fetched.
     */
    @Override
    public List<String> demoArtifacts() {
        return List.of(
                "/maven/org/apache/logging/log4j/log4j-core/2.14.1/log4j-core-2.14.1.pom",
                "/maven/org/apache/logging/log4j/log4j-core/2.14.1/log4j-core-2.14.1.jar",
                "/maven/commons-collections/commons-collections/3.2.1/commons-collections-3.2.1.jar");
    }

    /**
     * Proxy a {@code /maven/} miss to the upstream Maven repository (Maven Central). Artifacts (jars, poms and their
     * checksums) are immutable and cached, and a cached modular jar is cross-published like a local one;
     * {@code maven-metadata.xml} is a mutable index, so it is proxied fresh from upstream on each miss - never
     * derived locally or cached - the way every other format's index is, so a later upstream publish shows through.
     *
     * <p>A cached artifact is held to the upstream's {@code .sha1} <em>before</em> it is laid out, so a fill this leg
     * refuses (a mismatch, or a sibling this repository could not read -) never becomes reachable under any
     * coordinate: the fetched bytes are stored content-addressed as they stream, and the {@link #layout(ArtifactStore,
     * String, String) layout sequence} runs only once the bytes have been held to the checksum. Nothing is retracted
     * because nothing was linked.
     */
    @Override
    public boolean proxy(FormatExchange exchange, ArtifactStore store, URI upstream, ProxyFormat.Fetcher fetcher)
            throws IOException {
        String path = exchange.path();
        // The proxy leg carries the same clause-6 screen as handle(): a traversal-shaped path is no proxy target
        // either, so it never reaches the upstream and never lays a fetched body out under a path the store refuses.
        if (!path.startsWith("/maven/") || !ArtifactStore.traversalFree(path)) {
            return false;
        }
        String rest = path.substring("/maven/".length());
        String root = upstream.toString();
        String prefix = root.endsWith("/") ? root : root + "/";
        if (MavenMetadata.isMetadataRequest(path)) {
            // A mutable index: fetch it fresh (the small buffered fetch, not a cached download) and stream it straight
            // to the client, leaving nothing cached, so the repository never serves a stale metadata document.
            Optional<ProxyFormat.Fetched> index = fetcher.fetch(URI.create(prefix + rest), Map.of());
            // Clause 2's split. The maven-metadata.xml document itself is an ENUMERATION - it IS the <versions> list a
            // range or a LATEST/RELEASE marker resolves against, and a SNAPSHOT document is the timestamped build a
            // resolver picks - so a 404 here is not "not cached here, re-pull", it is the answer that the coordinate
            // has no versions. Serving a fetch that never landed as that answer breaks a build with a wrong fact it
            // cannot tell from the truth (on the Go leg's @v/list). Only an upstream that ANSWERED 404/410 may
            // reach the client as one; a transport failure or any other status refuses visibly instead.
            //
            // Its .sha1/.md5 siblings deliberately keep the plain decline: a checksum answers "what digest", not "what
            // exists", nothing resolves against its absence, and Maven already treats an unavailable checksum as a
            // warning rather than as a fact about the repository.
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
                // An upstream 404/410 for a descriptor IS the answer that the component publishes none, so it passes
                // through as the plain decline. Any other status is this repository failing to read what the upstream
                // has, which is not that answer.
                if (resolvedAgainstAbsence(rest) && download.status() != 404 && download.status() != 410) {
                    return undecided(prefix + rest, exchange, "the upstream answered " + download.status());
                }
                return false;
            }
            if (isChecksum(rest)) {
                layout(store, path, download.body());
            } else {
                // Verify a proxied artifact against the upstream-published SHA-1, so a body corrupted or tampered
                // between the upstream and here is never left cached and served. The digest is computed as the blob
                // streams to storage; the tiny checksum sibling is fetched afterwards, so it never delays the artifact.
                //
                // The bytes are STORED here and laid out only below, once they have been held to the checksum: the
                // blob is inert until a pointer references it, so a fill that fails verification links nothing and
                // needs no retraction - the same order the OCI leg holds a mismatched digest to (a layer lands only
                // under its own true hash, so the requested key is never created). Storing first is what lets the
                // digest be computed while the body streams, without buffering it (§1); the unreferenced blob a
                // refused fill leaves behind is exactly the object garbage collection exists to reclaim.
                MessageDigest sha1 = sha1();
                String hash = new Publication(store).storeBlob(new DigestInputStream(download.body(), sha1));
                URI sibling = URI.create(prefix + rest + ".sha1");
                Sha1 expected = upstreamSha1(fetcher, sibling);
                if (expected.unreadable() != null) {
                    // Clause 5's split. "The upstream publishes no.sha1 for this artifact" is a fact about
                    // Maven repositories that is true often enough to be documented, and it is the ONLY thing that may
                    // downgrade a fill to unverified. A .sha1 fetch that never landed, or one answered by a 429 under a
                    // shared egress IP, is not that fact - it is this repository having failed to read what the
                    // upstream published, and treating it as the fact means anyone who can drop one sidecar request
                    // turns verification off for that pull. So the fill is refused exactly as a mismatch is: nothing
                    // linked, nothing served, the local 404 standing so a later pull re-hits the upstream and reads
                    // the sibling again.
                    LOG.warn("Refusing to cache the proxied artifact {} unverified: {}. Nothing was cached or served; "
                            + "the local 404 stands so a later pull re-hits the upstream.", prefix + rest,
                            expected.unreadable());
                    return resolvedAgainstAbsence(rest)
                            ? undecided(prefix + rest, exchange, "its checksum sibling could not be read")
                            : false;
                }
                if (expected.hex() != null && !expected.hex().equalsIgnoreCase(HexFormat.of().formatHex(sha1.digest()))) {
                    // A body that does not hash to what the upstream published for it. Refused with a line of its own:
                    // this is the one outcome on this leg that says something happened to the bytes between the
                    // upstream and here, and a silent `false` (which is all a resolver sees - the local 404) would
                    // leave an operator with no way to tell a tampered mirror from an artifact nobody published.
                    LOG.warn("Refusing to cache the proxied artifact {}: it does not match the SHA-1 {} the upstream "
                            + "publishes for it. Nothing was cached or served; the local 404 stands.", prefix + rest,
                            expected.hex());
                    return resolvedAgainstAbsence(rest)
                            ? undecided(prefix + rest, exchange, "it does not match the SHA-1 the upstream publishes")
                            : false;
                }
                layout(store, path, hash);
            }
        }
        handle(exchange, store);
        return true;
    }

    /**
     * Answer a {@code maven-metadata.xml} request this repository could not put to its upstream - a transport failure,
     * or an upstream that answered something other than the document - with a {@code 502} rather than the local
     * {@code 404}, and say in the log which target failed and how.
     *
     * <p>It returns {@code true} because the leg <em>did</em> serve a response: {@link ProxyFormat}'s {@code false}
     * means "let the local {@code 404} stand", and on this one document the local {@code 404} is a lie. A resolver
     * reads a {@code 502} as a repository error and stops; it reads a {@code 404} as "this coordinate has no versions"
     * and fails the build with a wrong reason, or - worse, under a mirror list - moves on to the next repository as
     * though this one had genuinely answered. Clause 2 states the rule and why it is only this shape.
     */
    private static boolean unanswered(String target, FormatExchange exchange, String reason) throws IOException {
        LOG.warn("Refusing to answer the Maven metadata request {} as an empty version list: {}. Nothing was served; "
                + "the local 404 would have been read by the resolver as the upstream's own answer.", target, reason);
        exchange.respond(502);
        return true;
    }

    /**
     * Whether this path is one a client <em>resolves against the absence of</em> - so answering a refusal as a miss
     * would not be quiet, it would be wrong.
     *
     * <p>A 404 normally means "we do not have it", and for a jar or a POM that is a loud answer: the build fails and
     * an operator goes looking. Gradle Module Metadata is the case where it is not, because the overwhelming majority
     * of coordinates publish no {@code .module} at all. A 404 there is the <b>legal and expected</b> answer, and
     * Gradle acts on it - it falls back to the POM, picks a variant by the old rules and reports
     * {@code BUILD SUCCESSFUL}. So spelling a refusal as a 404 does not withhold the descriptor, it substitutes a
     * different resolution for it, silently, and the operator sees a green build over a repository that detected a
     * problem and said nothing (&sect;9).
     *
     * <p>This is the same split the {@code maven-metadata.xml} leg above makes for the same reason, and it is
     * deliberately narrow: {@code .sha1}/{@code .md5} siblings keep the plain decline, because a checksum answers
     * "what digest", not "what exists", and nothing resolves against its absence.
     */
    private static boolean resolvedAgainstAbsence(String rest) {
        return rest.endsWith(".module");
    }

    /** Refuse visibly on a path whose absence is itself an answer: the client is told this repository could not
     *  decide, rather than being handed a miss it would read as the upstream's own answer. */
    private static boolean undecided(String target, FormatExchange exchange, String reason) throws IOException {
        LOG.warn("Refusing to answer the proxied descriptor {} as an absent descriptor: {}. Nothing was served; the "
                + "local 404 would have been read by the client as \"this component publishes no module metadata\", "
                + "and it would have resolved a different variant without an error.", target, reason);
        exchange.respond(502);
        return true;
    }

    private static final Logger LOG = LoggerFactory.getLogger(MavenFormat.class);

    /** A Maven checksum or signature sibling - itself the integrity token, so it is proxied as-is, not re-verified. */
    private static boolean isChecksum(String rest) {
        return rest.endsWith(".sha1") || rest.endsWith(".md5") || rest.endsWith(".sha256")
                || rest.endsWith(".sha512") || rest.endsWith(".asc");
    }

    private static MessageDigest sha1() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * What the upstream's {@code .sha1} sibling says about an artifact this leg is caching - three states, because
     * clause 5 licenses the fall-back for exactly one of them.
     *
     * @param hex        the 40-hex digest the sibling publishes, or {@code null} when the upstream <em>answered</em>
     *                   that it publishes none for this artifact; that artifact is then proxied unverified, which is
     *                   the documented behaviour for a repository that carries no checksums
     * @param unreadable why the sibling could not be read at all, or {@code null} when it was read. Never a fall-back:
     *                   the fill is refused, because "we could not ask" is not "the upstream publishes nothing"
     */
    private record Sha1(String hex, String unreadable) {
    }

    /** The upstream SHA-1 for an artifact, read from its {@code .sha1} sibling (the 40-hex digest, optionally followed
     *  by a filename). The upstream <em>answering</em> {@code 404}/{@code 410}, or answering with a body that is not a
     *  40-hex digest, publishes none - the artifact is proxied unverified rather than refused. A transport failure or
     *  any other status could not be read, and is refused instead. */
    private static Sha1 upstreamSha1(ProxyFormat.Fetcher fetcher, URI sha1) throws IOException {
        Optional<ProxyFormat.Fetched> response = fetcher.fetch(sha1, Map.of());
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

    // --- RepositoryImporter capability (WSPI.2 (c)): delegated to MavenImporter. importTarget avoids the erasure
    //     clash with this format's ArtifactLayout.describe(String); imports avoids the clash with handles(String). ---

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
