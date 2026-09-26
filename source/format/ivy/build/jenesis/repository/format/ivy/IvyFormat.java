package build.jenesis.repository.format.ivy;

import module java.base;

import build.jenesis.repository.blobs.ProxyLeg;
import build.jenesis.repository.blobs.ProxyRelay;
import build.jenesis.repository.format.ArtifactLayout;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.ArtifactSignatures;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.StoredListing;
import build.jenesis.repository.format.RepositoryExporter;
import build.jenesis.repository.format.ExportTarget;
import build.jenesis.repository.format.PublishedExport;

/**
 * An Ivy repository, as Gradle publishes to and resolves from one.
 *
 * <h2>How little of Ivy reaches a repository server</h2>
 *
 * <p>Ivy and Maven differ a great deal, and almost none of it lands here. Ivy's layout is a <em>pattern</em> over
 * tokens ({@code [organisation]}, {@code [module]}, {@code [revision]}, {@code [artifact]}, {@code [type]},
 * {@code [ext]}, {@code [classifier]}) where Maven's fixed layout is one point in that space; an {@code ivy.xml}
 * carries <em>configurations</em> - arbitrary named graph partitions, the ancestor of Gradle's - where a POM
 * carries inheritance and profiles; Ivy declares several artifacts per module where Maven has one primary plus
 * classified attachments by convention; and an Ivy repository may serve artifacts with no descriptor at all.
 *
 * <p>A repository server stores and serves bytes rather than resolving, and this one builds its dependency graph
 * from SBOM documents rather than from descriptors. So the whole difference collapses to <b>Maven plus a
 * configurable layout, plus directory-listing version discovery, minus {@code maven-metadata.xml}</b> - and
 * {@code ivy.xml} needs no parser at all. This class is the first half of that; a stored listing of a module's
 * revisions is the second.
 *
 * <h2>The ecosystem is Maven, and that is what makes the layout restriction load-bearing</h2>
 *
 * <p>These coordinates are declared to the {@code Maven} coordinate space, which is what buys advisory matching,
 * retention and the browse - and it is a claim that has to be true rather than convenient. Ivy's
 * {@code organisation} is not generally a Maven {@code groupId}, so a format accepting <em>any</em> Ivy pattern
 * could not honestly make it: its coordinates would match no advisory while asserting that they should. Gradle
 * writes the {@code group} as the organisation, so restricting the accepted layout is exactly what makes the claim
 * honest. Declaring an {@code Ivy} ecosystem of its own is the rejected alternative - it matches no feed at all,
 * which is a security loss wearing the clothes of caution.
 *
 * <p>Coordinate rows therefore merge with Maven's, deliberately: they are the same coordinate in the same space,
 * one artifact served two ways. Every seam that maps an ecosystem back to a layout asks each format that declares
 * it and unions the answers, which is the rule that was written for this case before this format existed.
 *
 * <h2>What the accepted layout has to satisfy, stated as properties</h2>
 *
 * <p>Gradle's default is
 * {@code [organisation]/[module]/[revision]/[artifact]-[revision](-[classifier])(.[ext])} with the descriptor at
 * {@code ivy-[revision].xml}, and {@code [organisation]} a <b>single path segment</b> rather than Maven's
 * dotted-to-slashes. But what is actually required of a pattern is two things, and naming the failing one is a
 * better refusal than "not the Gradle layout":
 *
 * <ol>
 *   <li><b>the revision owns a directory</b> - {@link ArtifactLayout} clause 7, so an eviction's prefix contains
 *       that version and nothing else;</li>
 *   <li><b>the path maps to a coordinate in the declared ecosystem's space</b> - which is what makes the
 *       {@code Maven} claim above true rather than aspirational.</li>
 * </ol>
 *
 * <p>A pattern is deployment-wide rather than per-repository, which is the standing rule recorded at
 * {@link ArtifactLayout#paths(String, String, ArtifactStore)} and the reason this format needs no configuration
 * document of its own.
 */
public final class IvyFormat implements RepositoryFormat, ArtifactLayout, ArtifactSignatures, RepositoryExporter,
        ProxyLeg {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(IvyFormat.class);

    /**
     * The coordinate space these artifacts belong to.
     *
     * <p>Spelled rather than referenced, and that is not an oversight: an ecosystem name is the vocabulary a
     * vulnerability database uses for a coordinate space, not the property of whichever format declared it first.
     * Taking it from the Maven format would make this module depend on that one to borrow a string, and would read
     * as though Maven owns the space rather than sharing it.
     */
    public static final String ECOSYSTEM = "Maven";

    private static final String PREFIX = "/ivy/";

    /** The request root without its leading slash - the shape a pointer key carries under {@code publish/}. */
    static final String REQUEST_ROOT = "ivy/";

    /** Where a module's pointers live, which is what the revision listing is generated from. */
    static String publishPrefix(String organisation, String module) {
        return "publish/" + REQUEST_ROOT + organisation + "/" + module;
    }

    /** How many segments a servable path has under the prefix: organisation, module, revision, file. */
    private static final int SEGMENTS = 4;

    /**
     * Ivy's inbound signature story: a detached OpenPGP {@code .asc} beside the artifact, the same convention Maven
     * uses - and the same one, since these are two layouts over one coordinate space.
     *
     * <p>{@code OPTIONAL} where Maven is {@code REQUIRED}, and the difference is about the ecosystem rather than the
     * layout. Central has refused an unsigned release for over a decade, so a {@code /maven/} artifact without an
     * {@code .asc} is a fact worth reporting; Ivy has no such central authority and an internal Ivy repository is
     * commonly unsigned by policy. Declaring {@code REQUIRED} here would report every artifact in such a repository
     * as missing a signature, which is noise rather than a finding. A deployment that does sign its Ivy artifacts
     * still gets each one verified, graded and attributed - what {@code OPTIONAL} withholds is only the complaint
     * about absence.
     *
     * <p>A Sigstore bundle at {@code <artifact>.sigstore.json} is read beside it, optional for the same reason and
     * the same file the Maven layout reads: the sigstore-maven-plugin writes one per published file whatever layout
     * the repository serves, and the two are layouts over one coordinate space.
     *
     * <p>Nothing here verifies anything: the format says which paths carry a signature and what it covers, and the
     * compliance gate's signature dimension does the rest. That is why this costs two delegations.
     */
    private static final ArtifactSignatures SIGNATURES = ArtifactSignatures.composed(ECOSYSTEM,
            ArtifactSignatures.detachedSidecar(ECOSYSTEM, ".asc", ArtifactSignatures.Scheme.OPENPGP_DETACHED,
                    IvyFormat::signable, ArtifactSignatures.Coverage.OPTIONAL),
            ArtifactSignatures.detachedSidecar(ECOSYSTEM, ".sigstore.json", ArtifactSignatures.Scheme.SIGSTORE_BUNDLE,
                    IvyFormat::signable, ArtifactSignatures.Coverage.OPTIONAL));

    /** The sidecar suffixes a signature never covers - a checksum is not a published artifact, and signing one would
     *  report a missing {@code x.jar.sha1.asc} for every artifact in the repository. The two signature suffixes are
     *  here as well: each leg excludes its own, and with two conventions each must exclude the other's too, or a
     *  bundle would be read as an artifact wanting an {@code .asc}. */
    private static final List<String> NOT_SIGNED =
            List.of(".md5", ".sha1", ".sha256", ".sha512", ".sig", ".asc", ".sigstore.json");

    /** Whether a request path names an artifact a publisher's signature would cover. */
    private static boolean signable(String path) {
        return path.startsWith(PREFIX)
                && !path.endsWith("/")
                && NOT_SIGNED.stream().noneMatch(path::endsWith);
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
    public String name() {
        return "ivy";
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
        String path = exchange.path();
        // A module directory, which is how a resolver discovers versions here: Ivy has no maven-metadata.xml, so
        // `1.+` and `latest.release` are answered by LISTING this and choosing. Checked before the artifact split
        // because it is three segments where an artifact is four.
        Optional<Module> module = Module.of(path);
        if (module.isPresent() && !exchange.method().equals("PUT")) {
            revisions(exchange, store, module.get());
            return;
        }
        Optional<Coordinate> coordinate = Coordinate.of(path);
        if (coordinate.isEmpty()) {
            // A path that does not fit the pattern is refused rather than stored, on both directions. Storing one
            // would put bytes where no coordinate can name them: they would serve, and be invisible to a hold, a
            // retention sweep and an advisory match - present to a client and absent from every decision about it.
            exchange.respond(404);
            return;
        }
        Publication publication = new Publication(store);
        if (exchange.method().equals("PUT")) {
            // Layout only: the ingress edge has already screened the body to ACCEPT and restreams the stored blob
            // into this format, so this stores it content-addressed - streamed, never buffered - links its path and
            // answers 201. Verdicts are the edge's business, not a format's.
            String hash = publication.storeBlob(exchange.requestStream());
            publication.link(path, hash);
            // The revision joins its module's listing on the write, which is what makes a dynamic revision resolve
            // without anything having to walk the store on the read - on the write of one of its own files, not of a
            // checksum or a signature beside one. A client uploads those after each file whatever the gate did with
            // it, so a sidecar joining the listing put a revision whose files were all held straight back into the
            // document a resolver selects from.
            if (!sidecar(coordinate.get().file())) {
                new IvyListings(store).published(coordinate.get().organisation(),
                        coordinate.get().module(), coordinate.get().revision());
            }
            exchange.respond(201);
            return;
        }
        Optional<Publication.Located> located = publication.locate(path);
        if (located.isEmpty()) {
            exchange.respond(404);
            return;
        }
        long size = located.get().size();
        if (exchange.method().equals("HEAD")) {
            // Answered from the length the pointer records without opening the blob - the HEAD-from-metadata contract
            // every format here follows, rather than streaming an artifact in order to discard it.
            if (size >= 0) {
                exchange.setResponseHeader("Content-Length", Long.toString(size));
            }
            exchange.respond(200);
            return;
        }
        // Opened before the status is committed: a pointer whose blob a collector has since reclaimed is a clean
        // 404 from the open, never a 200 with no body - the format contract's GONE_BLOB_IS_A_CLEAN_404.
        InputStream in;
        try {
            in = store.open(located.get().key());
        } catch (NoSuchFileException gone) {
            exchange.respond(404);
            return;
        }
        try (in; OutputStream out = exchange.respond(200, size)) {
            in.transferTo(out);
        }
    }

    /** Serve a module's revision listing, or 404 when the module has none - which is the honest answer for a
     *  module nothing was ever published under, and the one a resolver reads as "no such module". */
    private void revisions(FormatExchange exchange, ArtifactStore store, Module module) throws IOException {
        // Nothing published under this module means no listing, and it is checked BEFORE opening one. Opening
        // materialises an absent document, so a request naming a module that does not exist would write a stored
        // key for it - which a hostile organisation segment turns into a key composed from a client's string. A
        // bounded child probe rather than a walk: one page of one prefix, asked whether it has any child at all.
        if (store.list(publishPrefix(module.organisation(), module.module())).isEmpty()) {
            exchange.respond(404);
            return;
        }
        StoredListing.Spec spec = new IvyListings(store).spec(module.organisation(), module.module());
        Optional<StoredListing.Served> served = StoredListing.open(store, spec);
        if (served.isEmpty()) {
            exchange.respond(404);
            return;
        }
        try (StoredListing.Served document = served.get()) {
            // Read AFTER opening: open() materialises an absent document, so a module never published to arrives
            // with a freshly generated empty one rather than with nothing. A module whose every revision is
            // withheld lands here too, and answers the same way - there is nothing a resolver could select.
            if (document.header().count().orElse(-1L) == 0L) {
                exchange.respond(404);
                return;
            }
            exchange.setResponseHeader("Content-Type", "text/html");
            if (exchange.method().equals("HEAD")) {
                exchange.setResponseHeader("Content-Length", Long.toString(document.header().size()));
                exchange.respond(200);
                return;
            }
            try (OutputStream out = exchange.respond(200, document.header().size())) {
                document.body().transferTo(out);
            }
        }
    }

    /** A module directory: {@code /ivy/<organisation>/<module>}, with or without a trailing slash. */
    private record Module(String organisation, String module) {

        static Optional<Module> of(String path) {
            if (!path.startsWith(PREFIX)) {
                return Optional.empty();
            }
            String rest = path.substring(PREFIX.length());
            String[] segments = (rest.endsWith("/") ? rest.substring(0, rest.length() - 1) : rest).split("/");
            if (segments.length != 2 || !nameable(segments[0]) || !nameable(segments[1])) {
                return Optional.empty();
            }
            return Optional.of(new Module(segments[0], segments[1]));
        }
    }

    /**
     * Whether a segment may name a module in a <em>stored key</em>, which is a stricter question than whether it
     * may appear in a path.
     *
     * <p>{@link ArtifactLayout#addressable} screens what a path segment may be - no slash, no {@code .} or
     * {@code ..} - and a percent-encoded traversal passes it, because {@code ..%2fsomething} carries no slash and
     * is neither. That is right for a path, where the encoding is data; it is wrong here, because this segment
     * composes the key of a document a GET would otherwise materialise, so a client's escape sequence would
     * become a stored object named after it.
     *
     * <p>So a name here is letters, digits, dot, underscore and hyphen - which every real Ivy organisation and
     * module already is, dotted like a Maven group or plain like an artifact id, and which no encoding survives.
     */
    private static boolean nameable(String segment) {
        if (!ArtifactLayout.addressable(segment)) {
            return false;
        }
        for (int at = 0; at < segment.length(); at++) {
            char character = segment.charAt(at);
            if (!Character.isLetterOrDigit(character) && character != '.'
                    && character != '_' && character != '-') {
                return false;
            }
        }
        return !segment.isEmpty();
    }

    // ---- proxy ----

    /**
     * Proxy a miss to an upstream Ivy repository laid out as this one is,
     * {@code <organisation>/<module>/<revision>/<file>} under the upstream's root. Every target is composed from the
     * configured upstream and the request path, so nothing an upstream advertises is followed.
     *
     * <p>A module directory is an ENUMERATION: Ivy resolves {@code 1.+} and {@code latest.release} by listing it, so it
     * is fetched fresh on every read and only an upstream that answered 404/410 reaches the client as a 404. Its
     * entries are names relative to the directory, so it is relayed unchanged.
     *
     * <p>A revision's file is PINNED, and held to the {@code .sha1} the upstream publishes beside it - a separate
     * document. One the upstream answered 404 for leaves the file unverified, as an Ivy repository is allowed to
     * publish no checksum; one this repository could not read declines the fill, and a mismatch is refused. The bytes
     * are stored as they stream and linked only once they are held to the checksum, so a refused fill leaves nothing
     * reachable. A checksum or signature beside a file is laid out as it is.
     *
     * <p>The revision's descriptor is the one file a client resolves against the absence of: with no
     * {@code ivy-<revision>.xml} Ivy assumes a module with one jar and no dependencies. So a descriptor this leg
     * refuses, or cannot read the checksum of, answers {@code 502} rather than a miss the client would take for that.
     *
     * <p>A proxied revision does not join the module's listing: through a proxy, the listing a client reads is the
     * upstream's.
     */
    @Override
    public boolean pullThrough(FormatExchange exchange, ArtifactStore store, URI upstream,
                               ProxyFormat.Fetcher fetcher) throws IOException {
        String path = exchange.path();
        String root = upstream.toString().endsWith("/") ? upstream.toString() : upstream + "/";
        Optional<Module> module = Module.of(path);
        if (module.isPresent()) {
            return ProxyRelay.streamFresh(fetcher,
                    URI.create(root + module.get().organisation() + "/" + module.get().module() + "/"), "text/html",
                    exchange, ProxyRelay.Document.ENUMERATION);
        }
        Optional<Coordinate> coordinate = Coordinate.of(path);
        if (coordinate.isEmpty()) {
            return false;
        }
        URI target = URI.create(root + path.substring(PREFIX.length()));
        Publication publication = new Publication(store);
        boolean descriptor = coordinate.get().file().equals("ivy-" + coordinate.get().revision() + ".xml")
                || coordinate.get().file().equals("ivy.xml");
        if (sidecar(coordinate.get().file())) {
            try (ProxyFormat.Download download = fetcher.download(target, Map.of()).orElse(null)) {
                if (download == null || download.status() != 200) {
                    return false;
                }
                publication.link(path, publication.storeBlob(download.body()));
            }
            serve(exchange, store);
            return true;
        }
        URI checksum = URI.create(target + ".sha1");
        ProxyRelay.Sidecar sidecar = ProxyRelay.declaring(fetcher, checksum, Map.of());
        ProxyRelay.Declared declared = sidecar.answered() ? sha1(sidecar.document().body(), checksum)
                : sidecar.verdict();
        if (!declared.readable()) {
            return descriptor
                    ? undecided(target, exchange, "its checksum could not be read: " + declared.unreadable())
                    : ProxyRelay.unverifiable(target, declared);
        }
        Optional<ProxyFormat.Download> fetched = fetcher.download(target, Map.of());
        if (fetched.isEmpty()) {
            return descriptor && undecided(target, exchange, "the upstream could not be reached");
        }
        try (ProxyFormat.Download download = fetched.get()) {
            if (download.status() != 200) {
                return descriptor && !ProxyRelay.upstreamMiss(download.status())
                        && undecided(target, exchange, "the upstream answered " + download.status());
            }
            MessageDigest digest = sha1();
            Publication.Blob stored = publication.stored(new DigestInputStream(download.body(), digest));
            if (declared.verifiable() && !MessageDigest.isEqual(declared.expected(), digest.digest())) {
                LOGGER.warn("Refusing to cache the proxied Ivy file {}: it does not match the SHA-1 {} the upstream "
                        + "publishes for it. Nothing was cached or served.", target,
                        HexFormat.of().formatHex(declared.expected()));
                return descriptor && undecided(target, exchange, "it does not match the SHA-1 the upstream publishes for it");
            }
            publication.link(path, stored.hash(), stored.size());
        }
        serve(exchange, store);
        return true;
    }

    /** Answer a descriptor this leg could not decide {@code 502}, since Ivy reads a missing one as a module with one jar
     *  and no dependencies. */
    private static boolean undecided(URI target, FormatExchange exchange, String reason) throws IOException {
        LOGGER.warn("Refusing to answer the proxied Ivy descriptor {} as a miss: {}. Ivy reads a missing descriptor as "
                + "a module with one jar and no dependencies, so the client is answered 502.", target, reason);
        exchange.respond(502);
        return true;
    }

    /** What an upstream {@code .sha1} declares: its first token, when that is 40 hex characters. Anything else is a
     *  document this repository could not read, not one declaring nothing. */
    private static ProxyRelay.Declared sha1(byte[] body, URI document) {
        String token = new String(body, StandardCharsets.UTF_8).strip().split("\\s+", 2)[0];
        return token.matches("[0-9a-fA-F]{40}")
                ? ProxyRelay.Declared.of("SHA-1", HexFormat.of().parseHex(token))
                : ProxyRelay.Declared.unreadable("the checksum " + document + " is not a SHA-1");
    }

    private static MessageDigest sha1() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JDK provides SHA-1", e);
        }
    }

    // ---- layout ----

    @Override
    public Optional<ArtifactDescriptor> describe(String path) {
        return Coordinate.of(path).map(at -> new ArtifactDescriptor(ECOSYSTEM, at.coordinate(), at.revision(),
                path, contentType(at.file()), at.revision().indexOf('-') >= 0, null, -1L));
    }

    @Override
    public List<String> paths(String coordinate, String version) {
        int colon = coordinate.indexOf(':');
        if (colon < 0) {
            return List.of();
        }
        String organisation = coordinate.substring(0, colon), module = coordinate.substring(colon + 1);
        // Clause 3: a coordinate is as client-supplied as a request path, and these paths are handed to eviction,
        // which unpublishes and DELETES under them. The organisation is a single segment here rather than Maven's
        // dotted-to-slashes, so all three parts are screened the same way - a part that is not addressable maps
        // nowhere rather than composing a path that aims a delete at a neighbouring key space.
        if (!ArtifactLayout.addressable(organisation, module, version)) {
            return List.of();
        }
        return List.of(PREFIX + organisation + "/" + module + "/" + version);
    }

    @Override
    public List<String> paths(String coordinate, String version, ArtifactStore store) {
        return paths(coordinate, version);
    }

    /** The content type a file's extension implies. Advisory only - what a client does with an Ivy artifact is
     *  decided by the descriptor it came from, not by this header. */
    private static String contentType(String file) {
        int dot = file.lastIndexOf('.');
        String extension = dot < 0 ? "" : file.substring(dot + 1).toLowerCase(Locale.ROOT);
        return switch (extension) {
            case "xml", "pom" -> "application/xml";
            case "jar", "war", "ear" -> "application/java-archive";
            case "zip" -> "application/zip";
            case "module", "json" -> "application/json";
            default -> "application/octet-stream";
        };
    }

    /**
     * One servable path, split into the coordinate it names.
     *
     * <p>The revision owning a directory is what makes this a split rather than a parse: everything above the file
     * name is the coordinate and the revision, so an eviction's prefix contains that version and nothing else -
     * clause 7, and the first of the two properties an accepted pattern must have.
     */
    /** Whether a file is a checksum or a signature beside one of the revision's files rather than one of them. */
    private static boolean sidecar(String file) {
        return SIDECARS.stream().anyMatch(file::endsWith);
    }

    private static final List<String> SIDECARS = List.of(".md5", ".sha1", ".sha256", ".sha512", ".asc", ".sig");

    private record Coordinate(String organisation, String module, String revision, String file) {

        static Optional<Coordinate> of(String path) {
            if (!path.startsWith(PREFIX)) {
                return Optional.empty();
            }
            String[] segments = path.substring(PREFIX.length()).split("/");
            // The organisation and the module compose a LISTING key as well as a pointer key, so they take the
            // stricter screen; the revision and file name are pointer-only and take the path one. A publish is
            // where this matters most: it is the write that creates the listing, so an unscreened segment here
            // becomes a stored document named after a client's string.
            if (segments.length != SEGMENTS || !ArtifactLayout.addressable(segments)
                    || !nameable(segments[0]) || !nameable(segments[1])) {
                return Optional.empty();
            }
            return Optional.of(new Coordinate(segments[0], segments[1], segments[2], segments[3]));
        }

        String coordinate() {
            return organisation + ":" + module;
        }
    }

    /** A revision's folder, each file put at its path under the client's {@code .../ivy/} URL. */
    @Override
    public Exported export(ArtifactStore repository, String coordinate, String version, ExportTarget target)
            throws IOException {
        return PublishedExport.putAll(repository, paths(coordinate, version), PREFIX, target);
    }
}
