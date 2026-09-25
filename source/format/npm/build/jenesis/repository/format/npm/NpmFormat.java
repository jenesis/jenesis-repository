package build.jenesis.repository.format.npm;

import module java.base;
import module tools.jackson.databind;

import build.jenesis.repository.blobs.RequestBase;
import build.jenesis.repository.blobs.BlobLayout;
import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.blobs.Keys;
import build.jenesis.repository.blobs.ProxyLeg;
import build.jenesis.repository.blobs.ProxyRelay;
import build.jenesis.repository.format.ArtifactSignatures;
import build.jenesis.repository.format.ExportTarget;
import build.jenesis.repository.format.RepositoryExporter;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.icon.IconResource;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.format.lifecycle.Lifecycle;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.StoredListing;
import build.jenesis.repository.store.Features;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.PublishInterceptor;
import build.jenesis.repository.store.Withheld;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import build.jenesis.repository.format.Semver;

/**
 * The npm registry format, so {@code npm publish} and {@code npm install} work over the same store. It owns
 * {@code /npm/...} (the registry is configured at that base). A publish ({@code PUT /npm/<package>}) carries the
 * new version's metadata and the tarball base64-encoded under {@code _attachments}; each version's metadata is
 * stored under {@code npm/<package>/versions/<version>} and the tarball under {@code npm/<package>/tarballs/<file>},
 * exactly as the Maven format keeps per-version folders. The packument ({@code GET /npm/<package>}) is served from a
 * stored document the publish maintains, with each version's {@code dist.tarball} completed to this
 * registry's URL while npm's own integrity and shasum (which match the stored tarball) are kept. The tarball is
 * served verbatim, so npm's integrity check passes.
 */
public final class NpmFormat implements RepositoryFormat, ProxyLeg, BlobLayout, RepositoryImporter,
        ArtifactSignatures, RepositoryExporter {

    static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String name() {
        return "npm";
    }

    /** A lifecycle mark surfaces in the metadata this format's clients read, so marks are accepted here. */
    @Override
    public boolean surfacesLifecycleMarks() {
        return true;
    }

    @Override
    public String ecosystem() {
        return "npm";
    }

    @Override
    public List<String> blobRoots() {
        return List.of("npm");
    }

    @Override
    public List<String> blobKeys(String coordinate, String version, ArtifactStore store) throws IOException {
        // A version's per-version metadata pointer plus its tarball(s); dist-tags and the package root are shared
        // across versions and left. The stored tarball filename (an _attachments key) is not always derivable, so it
        // is discovered by listing and matched on the conventional <shortName>-<version>.tgz suffix.
        if (!BlobLayout.addressable(coordinate, version)) {
            return List.of();   // a traversal-shaped coordinate maps nowhere - these keys are what an eviction DELETES
        }
        List<String> keys = new ArrayList<>();
        String versionKey = "npm/" + coordinate + "/versions/" + version;
        if (store.readVersioned(versionKey).isPresent()) {
            keys.add(versionKey);
        }
        // The tarball a version serves from is the one its packument entry points at - the conventional
        // <shortName>-<version>.tgz key - so it is probed, never found by scanning the package's tarballs.
        String tarball = tarballKey(coordinate, shortName(coordinate), version);
        if (store.readVersioned(tarball).isPresent()) {
            keys.add(tarball);
        }
        return keys;
    }

    /** The request paths this coordinate version's tarball(s) serve at ({@code /npm/<name>/-/<file>}), the inverse of
     *  {@link #describe} - a retroactive hold links a {@code /quarantine} review handle at each. The per-version
     *  metadata pointer is not a served download and carries no {@code /-/} path, so only tarballs map. */
    @Override
    public List<String> servedPaths(String coordinate, String version, ArtifactStore store) throws IOException {
        if (!BlobLayout.addressable(coordinate, version)) {
            return List.of();
        }
        String file = shortName(coordinate) + "-" + version + ".tgz";
        return store.readVersioned(tarballKey(coordinate, shortName(coordinate), version)).isPresent()
                ? List.of("/npm/" + coordinate + "/-/" + file)
                : List.of();
    }

    /** The coordinate a tarball request path carries ({@code /npm/<name>/-/<shortName>-<version>.tgz}, the name kept
     *  whole with its {@code @scope} - the coordinate {@link #blobKeys} and the npm compliance inspector key on), so
     *  the inventory writes the {@code published/} sidecar the retroactive enforcement sweeps enumerate the version
     *  by. A packument or publish path ({@code /npm/<name>}) and the dist-tags carry no single version and stay
     *  empty; a tarball filename off the conventional {@code <shortName>-<version>} shape describes coordinate-less
     *  rather than guessing a wrong version. A {@code -} suffix in the version marks a prerelease, the same
     *  convention {@link Semver#compare} ranks by. */
    @Override
    public Optional<ArtifactDescriptor> describe(String path) {
        if (!path.startsWith("/npm/")) {
            return Optional.empty();
        }
        String rest = path.substring("/npm/".length());
        int tarball = rest.indexOf("/-/");
        if (tarball < 0) {
            return Optional.empty();
        }
        String name = rest.substring(0, tarball);
        String file = rest.substring(tarball + "/-/".length());
        String shortName = name.substring(name.lastIndexOf('/') + 1);
        String prefix = shortName + "-";
        if (!file.startsWith(prefix) || !file.endsWith(".tgz")
                || file.length() <= prefix.length() + ".tgz".length()) {
            return Optional.of(ArtifactDescriptor.at("npm", path));
        }
        String version = file.substring(prefix.length(), file.length() - ".tgz".length());
        if (version.indexOf('/') >= 0) {
            // A tarball filename is a single path segment, so a '/' in the parsed version means the source path leaked
            // into it - describe coordinate-less rather than emit a slash-bearing version the store's traversal-free
            // segment check would later reject (the PyPI import defect, whose fix this mirrors). The /-/ split above
            // makes this unreachable from a well-formed registry path, so it is defence in depth, the guard GoFormat:84
            // keeps on its own parsed version.
            return Optional.of(ArtifactDescriptor.at("npm", path));
        }
        return Optional.of(new ArtifactDescriptor("npm", name, version, path,
                "application/octet-stream", version.contains("-"), null, -1L));
    }

    // An original CC0 line glyph (a bracketed package block) drawn for this project.
    private static final IconResource ICON = IconResource.svg("""
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round">
              <rect x="3" y="3" width="18" height="18" rx="2"/><path d="M8 8v8M12 8v8M16 8v8"/>
            </svg>""");

    @Override
    public Optional<IconResource> icon() {
        return Optional.of(ICON);
    }

    @Override
    public Optional<URI> defaultUpstream() {
        return Optional.of(URI.create("https://registry.npmjs.org/"));
    }

    /**
     * Demo-mode suggestions: two old, benign-but-vulnerable npm tarballs - {@code lodash 4.17.11} (the prototype
     * pollution before 4.17.12) and {@code minimist 1.2.0} (the prototype pollution before 1.2.3) - so a fresh
     * repository's browse rows and, after a vulnerability sweep, its advisory panel carry npm data. The bytes are
     * ordinary libraries, pulled through this format's own {@link #defaultUpstream() upstream}; nothing malicious is
     * fetched.
     */
    @Override
    public List<String> demoArtifacts() {
        return List.of(
                "/npm/lodash/-/lodash-4.17.11.tgz",
                "/npm/minimist/-/minimist-1.2.0.tgz");
    }

    @Override
    public boolean handles(String path) {
        return path.startsWith("/npm/");
    }

    /**
     * An {@code npm publish} wraps its artifact in a JSON document, so this format is <b>not</b> edge-screened
     * ((a)): the request body is an <em>envelope</em> - a packument carrying the tarball base64-encoded under
     * {@code _attachments} - and gating it at the shared single-body edge would hash and assess that document while the
     * bytes that later serve are the {@code .tgz} inside it, a second content-addressed object under a hash no
     * interceptor ever saw. That is {@code RepositoryFormat} clause 14's fail-open direction: "we screened the request
     * that contained it" is not a claim anyone can act on. The shared edge ({@code ScreenedDispatch}) takes the request
     * body verbatim and offers no seam to unwrap one, so this format is in the {@code screened() == false} case the
     * clause names and screens at its own documented choke point: {@link #parse} base64-decodes each attachment
     * ({@link #commitTarball}) through the shared {@code Publication.commit} - with the <em>discovered</em> interceptor
     * chain and observers - so the chain assesses the tarball's own bytes, under the tarball's own request path. The package root {@code PUT} is the only way a tarball is hosted-published here, so declaring
     * {@code false} does not leave the format unscreened.
     */
    /** The encoded slash of a scoped package name, {@code @scope%2Fname}: after a scope, and nowhere else. */
    private static final Pattern SCOPED_SEPARATOR = Pattern.compile("(@[A-Za-z0-9._~-]+)%2[Ff]");

    @Override
    public boolean screened() {
        return false;
    }

    @Override
    public void serve(FormatExchange exchange, ArtifactStore store) throws IOException {
        Blobs blobs = new Blobs(store);
        // npm sends a scoped package's name with its slash encoded (@scope%2Fname), on a publish, a packument read and
        // the tarball URL a packument read completes. Only that separator is decoded: an encoded slash anywhere else -
        // "..%2f" among them - stays the literal name it is, so no decoding here can compose a traversal.
        String rest = SCOPED_SEPARATOR.matcher(exchange.path().substring("/npm/".length())).replaceAll("$1/");
        String method = exchange.method();
        if (rest.startsWith(DIST_TAGS_API)) {
            distTags(rest.substring(DIST_TAGS_API.length()), exchange, blobs, store);
            return;
        }
        int tarball = rest.indexOf("/-/");
        if (tarball >= 0) {
            if (!method.equals("GET") && !method.equals("HEAD")) {
                // A tarball path is read-only; a publish is a PUT to the package root, not to /-/. Gate the write verbs
                // so a PUT/DELETE is a 405 rather than being answered as a download, like the other formats do.
                exchange.respond(405);
                return;
            }
            String file = rest.substring(tarball + "/-/".length());
            if (file.startsWith("attestations/")) {
                serveAttestations(rest.substring(0, tarball), file.substring("attestations/".length()), blobs, exchange);
            } else {
                serveTarball(rest.substring(0, tarball), file, blobs, exchange);
            }
        } else if (method.equals("PUT")) {
            publish(rest, exchange, blobs, store);
        } else if (method.equals("GET") || method.equals("HEAD")) {
            packument(rest, blobs, store, exchange);
        } else {
            exchange.respond(405);
        }
    }

    /**
     * The documents npm updates in place for a version that already exists, which are last-writer-wins and always
     * have been: the dist-tags ({@code npm dist-tag add} re-points {@code latest}) and a version's own index entry,
     * which {@code npm deprecate} rewrites with no tarball attached at all. What must not change under a published
     * version is its <em>bytes</em>, and those are governed by {@link #republish} below.
     */
    private static final Publication.Republish METADATA = Publication.Republish.overwrite();

    /**
     * What a publish of an already-published version does. npm's own registry refuses it, and this product documents
     * release-version immutability as on by default with {@code allow-redeploy} as the operator's opt-out.
     *
     * <p>This used to be {@link Publication.Republish#overwrite()} unconditionally, which quietly exempted npm from
     * that guarantee: {@code ReleaseImmutability} keys on the request path, and an npm publish PUTs the packument
     * root, which carries no version for it to protect. The versioned writes happen inside this format, so this is
     * where the dial has to be read - through {@link Features}, the seam a format already has, rather than by
     * reaching for the server module and inverting the layering.
     *
     * <p>The probe is the format's <em>own</em> serving pointer, not the publication's request path: an npm publish
     * addresses the packument, which legitimately changes on every publish as a version joins it. The key that must
     * not move twice is the tarball's - the version's <em>bytes</em>. Its metadata document is a different thing and
     * stays mutable, because that is what {@code npm deprecate} rewrites.
     *
     * <p>Idempotent rather than refused, because a replay of <em>identical</em> bytes is how a publish that crashed
     * mid-layout is repaired - a documented property of this product, and one that a flat refusal would have taken
     * away. What is refused is different bytes at a version already published, which is the hole.
     */
    private static Publication.Republish republish(String pointer) {
        return "true".equalsIgnoreCase(Features.lookup().apply("jenreg.allow-redeploy"))
                ? Publication.Republish.overwrite()
                : Publication.Republish.idempotent(pointer);
    }

    /**
     * Publish streaming, so the tarball is never materialised. npm's publish protocol carries the tarball inline
     * (base64 under {@code _attachments.<file>.data}) in one JSON document; the former code read the whole body into a
     * {@code byte[]}, built a Jackson tree over it, pulled the base64 out as a {@code String} and decoded that to
     * another {@code byte[]} - four copies of the tarball on the heap ({@code ~3-4x}), capped at 512 MiB and unable to
     * even represent a package past the array limit. Instead the body is parsed with the streaming
     * {@link JsonParser}: the small metadata subtrees ({@code versions}, {@code dist-tags}) are read whole (index
     * metadata, allowed), while each attachment's {@code data} value is base64-decoded straight into the
     * shared commit operation's hash-on-write ({@link #commitTarball}), never held whole. A multi-gigabyte tarball
     * therefore publishes in bounded heap, with no size cap.
     *
     * <h2>The tarball is the screened body, and it is written once ((a))</h2>
     * Each attachment's {@code data} is base64-decoded <em>through</em> the shared hosted-publish operation
     * ({@code Publication.commit}): the decoded stream is the operation's accepted body, so the tarball flows through
     * {@code writeBlob} exactly once and <b>the hash the interceptor chain assesses is the hash {@code npm install}
     * downloads</b>. Before this it was stored by the format and the surrounding packument was what the ingress edge
     * hashed and gated - the envelope-vs-artifact gap. Re-reading the stored blob to feed a second commit would have
     * been the easy fix and is rejected here: it would double the write of a multi-gigabyte tarball, which
     * {@code NpmPublishStreamingTest} pins against ("streamed through the store exactly once").
     *
     * <h2>Content first, index after (preserved)</h2>
     * An npm publish envelope is a JSON object whose field order is the <em>client's</em>, and the original code wrote
     * as it parsed - so a client that put {@code versions} before {@code _attachments} (which every npm CLI does) had
     * its per-version pointer written <em>permanently</em> before the tarball it names existed, and a publish that
     * failed in between left a package whose packument advertised a version {@code npm install} could only 404 on.
     *
     * <p>That ordering is still the operation's, not the client's, and it is now expressed as two phases of a single
     * pass: {@link #parse} walks the envelope and commits each attachment as its bytes stream by - linking the tarball
     * pointer and nothing else - while every <em>index</em> write is deferred to {@link #index}, which runs only once
     * the whole envelope has been read. So a version's index entry can never precede the tarball it names, whichever
     * order the client's fields arrived in, and a version is indexed <b>only</b> when its bytes are servable -
     * published in this request or already stored (so an {@code npm deprecate}, which PUTs a packument with no
     * attachments, still updates its versions). A version whose tarball never arrives is not indexed at all.
     *
     * <p>The one thing the single pass gives up: a tarball pointer now lands before the envelope's <em>tail</em> has
     * been read, so a malformed tail (an unsafe version key, a truncated document) answers {@code 400} with a
     * downloadable tarball that no packument lists. That is the benign half of the crash window the operation's own
     * contract already names - a stored, servable artifact nothing indexes, never an index entry with no bytes - and
     * those bytes were screened, so it admits nothing unassessed.
     */
    private void publish(String name, FormatExchange exchange, Blobs blobs, ArtifactStore store) throws IOException {
        Envelope envelope = parse(name, exchange, blobs, store);
        if (envelope == null) {
            exchange.respond(400);   // not a JSON object, or an unsafe version / attachment key - nothing was indexed
            return;
        }
        // The strongest verdict any tarball in this envelope drew. A publish normally carries one version, so this is
        // one commit's disposition; a multi-version envelope answers by its worst, since a client told 201 while one of
        // its versions was held would believe it published something that 404s. A REFUSED tarball stops the index
        // phase outright; a HELD one does not - it was laid out under its withhold marker, so it must be indexed like
        // any other stored version and is screened back out of the packument until it is released.
        if (envelope.strongest() != null) {
            switch (envelope.strongest().disposition()) {
                case ACCEPT -> {
                }
                // The chain HELD a tarball. Its layout was still written - behind the withhold marker
                // {@link #commitTarball} set before it linked the pointer - so the version exists exactly as a
                // retroactively-held one does and a review release is the marker clear. Index it here for
                // the same reason: the packument screens every version on its tarball's marker, so a held version is
                // laid out and listed nowhere until it is released.
                case QUARANTINE -> {
                    index(name, envelope, blobs, store);
                    exchange.respond(202);
                    return;
                }
                // Refused outright: no pointer, no marker, no index entry - the stored blob is the usual unreferenced
                // content-addressed object a collection reclaims. A refusal is never released, so it is never laid out.
                case REJECT -> {
                    exchange.respond(422);
                    return;
                }
            }
        }
        index(name, envelope, blobs, store);
        exchange.setResponseHeader("Content-Type", "application/json");
        exchange.respond(201, MAPPER.writeValueAsString(Map.of("ok", true)).getBytes(StandardCharsets.UTF_8));
    }

    /** The stronger of two commits' verdicts ({@code null} - no commit at all - loses to any present one): the
     *  reduction a multi-attachment envelope folds its per-tarball verdicts with, mirroring the way the interceptor
     *  chain itself keeps the strongest disposition across its members. */
    private static Publication.Commit stronger(Publication.Commit held, Publication.Commit next) {
        if (next == null) {
            return held;
        }
        return held == null || next.disposition().compareTo(held.disposition()) > 0 ? next : held;
    }

    /** One walked publish envelope: the per-version index documents keyed by version, the attachment filenames whose
     *  tarballs this request made servable, the verbatim {@code dist-tags} document ({@code null} when the envelope
     *  carries none), and the strongest verdict the screened tarballs drew ({@code null} when the envelope carried
     *  none). Only the small index documents are held - a tarball is already in the content-addressed store and
     *  already linked by its own commit. */
    private record Envelope(Map<String, byte[]> versions, Set<String> published, byte[] distTags,
                            Publication.Commit strongest, byte[] attestations) {
    }

    /** Whether an attachment read produced a usable result, and the strongest verdict its tarballs drew: {@code safe}
     *  is false for a filename that would forge a pointer key, which fails the whole publish. */
    private record Attachments(boolean safe, Publication.Commit strongest) {
    }

    /** Walk the whole publish envelope in one pass: each attachment's tarball is screened and linked as its bytes
     *  stream by ({@link #commitTarball}), each version's metadata subtree is held as the small index document it is,
     *  and <em>no index write happens here</em> - that is {@link #index}'s, so a version's entry can never precede the
     *  tarball it names. {@code null} (a {@code 400}) when the body is not a JSON object, or when a version key /
     *  attachment filename would forge a pointer key with {@code /} or {@code ..}. */
    private Envelope parse(String name, FormatExchange exchange, Blobs blobs, ArtifactStore store) throws IOException {
        Map<String, byte[]> versions = new LinkedHashMap<>();
        Set<String> published = new LinkedHashSet<>();
        byte[] distTags = null;
        byte[] attestations = null;
        Publication.Commit strongest = null;
        // The artifact screen: the discovering constructor, because this format IS the choke point now (screened()).
        Publication screening = new Publication(store);
        try (JsonParser parser = MAPPER.createParser(exchange.requestStream())) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                return null;   // a publish body is a JSON object
            }
            while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
                String field = parser.currentName();
                parser.nextToken();   // advance onto the field's value
                switch (field) {
                    case "versions" -> {
                        if (!readVersions(parser, versions)) {
                            return null;   // an unsafe version key
                        }
                    }
                    case "dist-tags" -> {
                        if (parser.currentToken() == JsonToken.START_OBJECT) {
                            JsonNode tags = parser.readValueAsTree();
                            distTags = MAPPER.writeValueAsBytes(tags);
                        } else {
                            parser.skipChildren();
                        }
                    }
                    case "_attestations" -> attestations = readAttestations(parser);
                    case "_attachments" -> {
                        Attachments read = readAttachments(parser, name, blobs, screening, published);
                        strongest = stronger(strongest, read.strongest());
                        if (!read.safe()) {
                            return null;   // an unsafe attachment filename
                        }
                    }
                    default -> parser.skipChildren();   // _id, name, description, readme, access - not stored here
                }
            }
        }
        return new Envelope(versions, published, distTags, strongest, attestations);
    }

    /**
     * The Sigstore bundles a provenance-publishing client sends beside its tarball ({@code npm publish
     * --provenance}): {@code _attestations} as the registry's {@code {"attestations":[{"predicateType","bundle"}]}}
     * envelope, or the bare list. Held whole - a bundle is a few kilobytes of certificate, log entry and statement -
     * and bounded at the signature limit, past which the publish carries none rather than an unbounded document.
     */
    private static byte[] readAttestations(JsonParser parser) throws IOException {
        JsonToken token = parser.currentToken();
        if (token != JsonToken.START_OBJECT && token != JsonToken.START_ARRAY) {
            parser.skipChildren();
            return null;
        }
        JsonNode read = parser.readValueAsTree();
        ObjectNode envelope = read instanceof ObjectNode object ? object : MAPPER.createObjectNode();
        if (read instanceof ArrayNode list) {
            envelope.set("attestations", list);
        }
        if (!(envelope.get("attestations") instanceof ArrayNode bundles) || bundles.isEmpty()) {
            return null;
        }
        byte[] bytes = MAPPER.writeValueAsBytes(envelope);
        return bytes.length > ArtifactSignatures.Material.LARGEST_SIGNATURE ? null : bytes;
    }

    /** The stored attestations of a version: the envelope as published, served at {@code /-/attestations/<version>}
     *  under the package. */
    static String attestationsKey(String name, String version) {
        return "npm/" + name + "/attestations/" + version;
    }

    /**
     * A version's stored metadata with {@code dist.attestations} naming where the registry serves its bundles - the
     * placeholder base the packument completes on the way out - and the provenance predicate a client asks for
     * first, as registry.npmjs.org writes it.
     */
    private static byte[] withAttestations(byte[] metadata, String version, byte[] attestations) throws IOException {
        if (!(MAPPER.readTree(metadata) instanceof ObjectNode object)) {
            return metadata;
        }
        ObjectNode dist = object.get("dist") instanceof ObjectNode existing ? existing : object.putObject("dist");
        ObjectNode entry = dist.putObject("attestations");
        entry.put("url", NpmListings.BASE + "/-/attestations/" + version);
        for (JsonNode bundle : MAPPER.readTree(attestations).path("attestations")) {
            String predicateType = bundle.path("predicateType").asString("");
            if (predicateType.contains("slsa") || predicateType.contains("provenance")) {
                entry.putObject("provenance").put("predicateType", predicateType);
                break;
            }
        }
        return MAPPER.writeValueAsBytes(object);
    }

    /** Hold each version's metadata subtree against its version. A version subtree is small index metadata, so it is
     *  read whole; the key is validated here, before anything can be written from it. False on an unsafe version that
     *  would forge a pointer key with {@code /} or {@code ..}. */
    private static boolean readVersions(JsonParser parser, Map<String, byte[]> versions) throws IOException {
        if (parser.currentToken() != JsonToken.START_OBJECT) {
            parser.skipChildren();
            return true;
        }
        while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
            String version = parser.currentName();
            parser.nextToken();   // advance onto the version's metadata object
            if (Keys.unsafe(version)) {
                return false;
            }
            JsonNode metadata = parser.readValueAsTree();
            versions.put(version, MAPPER.writeValueAsBytes(metadata));
        }
        return true;
    }

    /** Screen and link each attachment's tarball as its bytes stream by. The tarball is the one unbounded field, so it
     *  is base64-decoded chunk-by-chunk through the shared commit operation, never buffered and never written twice;
     *  the filename is validated before its bytes are read, so no body-supplied name reaches a pointer key. Not safe on
     *  an unsafe attachment filename - the strongest verdict read so far is still carried back, since a tarball
     *  committed before the bad name was reached has already been judged. */
    private static Attachments readAttachments(JsonParser parser, String name, Blobs blobs, Publication screening,
                                               Set<String> published) throws IOException {
        if (parser.currentToken() != JsonToken.START_OBJECT) {
            parser.skipChildren();
            return new Attachments(true, null);
        }
        Publication.Commit strongest = null;
        while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
            // npm names a scoped package's tarball after the whole name - "@scope/name-1.0.0.tgz" - and it is stored
            // and served under the unscoped file name the packument's tarball URL uses; any other slash is unsafe.
            String sent = parser.currentName();
            String scope = name.contains("/") ? name.substring(0, name.indexOf('/') + 1) : "";
            String file = !scope.isEmpty() && sent.startsWith(scope) ? sent.substring(scope.length()) : sent;
            parser.nextToken();   // advance onto the attachment object
            if (Keys.unsafe(file)) {
                return new Attachments(false, strongest);
            }
            if (parser.currentToken() == JsonToken.START_OBJECT) {
                while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
                    boolean data = "data".equals(parser.currentName());
                    parser.nextToken();   // advance onto the attachment field's value
                    if (data && parser.currentToken() == JsonToken.VALUE_STRING) {
                        Publication.Commit commit = commitTarball(parser, name, file, blobs, screening);
                        strongest = stronger(strongest, commit);
                        if (commit.visible()) {
                            published.add(file);
                        }
                    } else {
                        parser.skipChildren();   // content_type, length - not needed
                    }
                }
            } else {
                parser.skipChildren();
            }
        }
        return new Attachments(true, strongest);
    }

    /**
     * The index phase: every write that makes a version <em>discoverable</em>, run once the whole envelope has been
     * read, so nothing here can precede the tarball it names. One commit per indexable version, in envelope order,
     * then the package-level {@code dist-tags} document - which is committed last, so a {@code latest} tag can never
     * resolve to a version whose index entry has not landed.
     *
     * <p>A version is indexable when its bytes are servable: published in this request (and accepted by the chain) or
     * already stored, so an {@code npm deprecate} - a packument with no {@code _attachments} - still updates its
     * versions, while a version whose tarball never arrived is not indexed at all.
     *
     * <p>These commits pass an <b>explicitly empty</b> chain and observer list, and that is the whole difference from
     * the artifact commits {@link #parse} drives: an index document is not an artifact. There is nothing new to screen
     * (its tarball was screened as it streamed in) and nothing new to notify (the tarball's own commit already fired
     * the one after-commit event for this publish), so running the discovered chain here would be a second gate over
     * bytes that are not the artifact and a second publish event for one upload. The operation is used purely for the
     * ordering and idempotency it guarantees.
     */
    private void index(String name, Envelope envelope, Blobs blobs, ArtifactStore store) throws IOException {
        String shortName = shortName(name);
        Publication indexing = new Publication(store, List.of(), List.of());
        for (Map.Entry<String, byte[]> version : envelope.versions().entrySet()) {
            String file = shortName + "-" + version.getKey() + ".tgz";
            String tarballKey = "npm/" + name + "/tarballs/" + file;
            if (!envelope.published().contains(file) && !blobs.exists(tarballKey)) {
                continue;
            }
            String versionKey = "npm/" + name + "/versions/" + version.getKey();
            if (envelope.attestations() != null && envelope.published().contains(file)) {
                // The bundles land before the version is discoverable, and the version's own entry names them, so
                // no client reads a version whose attestations are still to come.
                blobs.write(attestationsKey(name, version.getKey()), envelope.attestations());
                version.setValue(withAttestations(version.getValue(), version.getKey(), envelope.attestations()));
            }
            indexing.commit(
                    new ArtifactDescriptor("npm", name, version.getKey(), "/npm/" + name,
                            "application/json", version.getKey().contains("-"), null, -1L),
                    new ByteArrayInputStream(version.getValue()), METADATA,
                    _ -> Publication.Visibility.through((hash, _, _) -> blobs.link(versionKey, hash)));
        }
        if (envelope.distTags() != null) {
            indexing.commit(ArtifactDescriptor.at("npm", "/npm/" + name),
                    new ByteArrayInputStream(envelope.distTags()), METADATA,
                    _ -> Publication.Visibility
                            .through((hash, _, _) -> blobs.link("npm/" + name + "/dist-tags", hash)));
        }
        // The served packument is written here, on the publish: the envelope's versions (those that are servable)
        // and dist-tags join the stored document rather than being enumerated and screened on every read.
        new NpmListings(blobs).published(name, envelope.versions(), envelope.distTags() != null);
    }

    /** Where {@code npm dist-tag} addresses a package's tags: {@code -/package/<name>/dist-tags[/<tag>]}. */
    private static final String DIST_TAGS_API = "-/package/";

    /**
     * {@code npm dist-tag ls|add|rm}: {@code GET -/package/<name>/dist-tags} answers the tags the packument carries,
     * {@code PUT} (or {@code POST}) {@code .../dist-tags/<tag>} with a JSON string body points a tag at a listed
     * version, and {@code DELETE .../dist-tags/<tag>} removes one other than {@code latest}, which npm's own registry
     * refuses to remove. A package whose tags were never written has a computed {@code latest}, and a first change
     * keeps it: the stored document starts from the tags the packument shows, not from nothing.
     */
    private void distTags(String rest, FormatExchange exchange, Blobs blobs, ArtifactStore store) throws IOException {
        int marker = rest.lastIndexOf("/dist-tags");
        String name = marker > 0 ? rest.substring(0, marker) : "";
        String tail = marker > 0 ? rest.substring(marker + "/dist-tags".length()) : "";
        String tag = tail.startsWith("/") ? tail.substring(1) : tail;
        if (name.isEmpty() || Keys.unsafePath(name) || (!tail.isEmpty() && (!tail.startsWith("/") || Keys.unsafe(tag)))) {
            exchange.respond(404);
            return;
        }
        Optional<ObjectNode> current = tags(name, blobs, store);
        if (current.isEmpty()) {
            exchange.respond(404);
            return;
        }
        ObjectNode tags = current.get();
        String method = exchange.method();
        if (tag.isEmpty()) {
            if (!method.equals("GET") && !method.equals("HEAD")) {
                exchange.respond(405);
                return;
            }
            exchange.setResponseHeader("Content-Type", "application/json");
            exchange.respond(200, MAPPER.writeValueAsBytes(tags));
            return;
        }
        if (method.equals("PUT") || method.equals("POST")) {
            JsonNode body;
            try (InputStream in = exchange.requestStream()) {
                body = MAPPER.readTree(in.readNBytes(1024));
            } catch (RuntimeException malformed) {
                exchange.respond(400);
                return;
            }
            String version = body.isString() ? body.asString() : "";
            if (Keys.unsafe(version) || blobs.locate(tarballKey(name, shortName(name), version)).isEmpty()
                    || !blobs.exists("npm/" + name + "/versions/" + version)) {
                exchange.respond(404);   // a tag points at a listed version or nowhere
                return;
            }
            tags.put(tag, version);
        } else if (method.equals("DELETE")) {
            if (tag.equals("latest")) {
                exchange.respond(400);
                return;
            }
            tags.remove(tag);
        } else {
            exchange.respond(405);
            return;
        }
        byte[] document = MAPPER.writeValueAsBytes(tags);
        new Publication(store, List.of(), List.of()).commit(ArtifactDescriptor.at("npm", "/npm/" + name),
                new ByteArrayInputStream(document), METADATA,
                _ -> Publication.Visibility.through((hash, _, _) -> blobs.link("npm/" + name + "/dist-tags", hash)));
        new NpmListings(blobs).published(name, Map.of(), true);
        exchange.setResponseHeader("Content-Type", "application/json");
        exchange.respond(200, document);
    }

    /** The dist-tags a package's packument shows, read off the stored document without holding its versions; empty
     *  when nothing of the package is listed. */
    private static Optional<ObjectNode> tags(String name, Blobs blobs, ArtifactStore store) throws IOException {
        if (!StoredListing.present(store, NpmListings.packument(name)) && blobs.isEmpty("npm/" + name + "/versions")) {
            return Optional.empty();
        }
        Optional<StoredListing.Served> served = StoredListing.open(store, new NpmListings(blobs).spec(name));
        if (served.isEmpty()) {
            return Optional.empty();
        }
        try (StoredListing.Served document = served.get();
             JsonParser parser = MAPPER.createParser(document.body())) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                return Optional.empty();
            }
            while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
                String field = parser.currentName();
                parser.nextToken();
                if (field.equals("dist-tags") && parser.currentToken() == JsonToken.START_OBJECT
                        && parser.readValueAsTree() instanceof ObjectNode tags) {
                    return Optional.of(tags);
                }
                parser.skipChildren();
            }
        }
        return Optional.of(MAPPER.createObjectNode());
    }

    // ---- export: each version as npm publish sends it ----

    /**
     * One version as {@code npm publish} sends it: {@code PUT <name>} with the stored version document under
     * {@code versions}, the tarball base64 under {@code _attachments}, and the dist-tags the source points at this
     * version - streamed, so the tarball is never held. A target that already serves the tarball with the same
     * SHA-256 has it.
     */
    @Override
    public Exported export(ArtifactStore repository, String coordinate, String version, ExportTarget target)
            throws IOException {
        Blobs blobs = new Blobs(repository);
        String shortName = shortName(coordinate);
        String file = shortName + "-" + version + ".tgz";
        Optional<Blobs.Located> tarball = Keys.unsafe(version) ? Optional.empty()
                : blobs.locate(tarballKey(coordinate, shortName, version));
        ByteArrayOutputStream metadata = new ByteArrayOutputStream();
        if (tarball.isEmpty() || !blobs.read("npm/" + coordinate + "/versions/" + version, metadata)) {
            return Exported.WITHHELD;
        }
        String served = coordinate + "/-/" + file;
        if (target.sha256(served).filter(tarball.get().hash()::equals).isPresent()) {
            return Exported.ALREADY_PRESENT;
        }
        ObjectNode tags = MAPPER.createObjectNode();
        storedTags(coordinate, blobs).properties().forEach(tag -> {
            if (tag.getValue().asString("").equals(version)) {
                tags.set(tag.getKey(), tag.getValue());
            }
        });
        String name = MAPPER.writeValueAsString(coordinate);
        String head = "{\"_id\":" + name + ",\"name\":" + name + ",\"versions\":{"
                + MAPPER.writeValueAsString(version) + ":" + metadata.toString(StandardCharsets.UTF_8) + "},"
                + (tags.isEmpty() ? "" : "\"dist-tags\":" + MAPPER.writeValueAsString(tags) + ",")
                + "\"_attachments\":{" + MAPPER.writeValueAsString(coordinate + "-" + version + ".tgz")
                + ":{\"content_type\":\"application/octet-stream\",\"length\":" + tarball.get().size()
                + ",\"data\":\"";
        String tail = "\"}}}";
        byte[] prefix = head.getBytes(StandardCharsets.UTF_8);
        byte[] suffix = tail.getBytes(StandardCharsets.UTF_8);
        long size = tarball.get().size();
        long length = size < 0 ? -1 : prefix.length + 4 * ((size + 2) / 3) + suffix.length;
        ExportTarget.Body body = new ExportTarget.Body() {
            @Override
            public long length() {
                return length;
            }

            @Override
            public InputStream open() throws IOException {
                PipedInputStream in = new PipedInputStream(1 << 16);
                PipedOutputStream out = new PipedOutputStream(in);
                Thread.ofVirtual().name("npm-export-" + file).start(() -> {
                    try (out) {
                        out.write(prefix);
                        try (OutputStream encoded = Base64.getEncoder().wrap(new FilterOutputStream(out) {
                            @Override
                            public void write(byte[] bytes, int offset, int count) throws IOException {
                                out.write(bytes, offset, count);
                            }

                            @Override
                            public void close() throws IOException {
                                flush();   // the encoder's close pads; the pipe stays open for the suffix
                            }
                        })) {
                            blobs.stream(tarball.get(), encoded);
                        }
                        out.write(suffix);
                    } catch (IOException _) {
                        // the reader sees the pipe close early and the request fails with the target's answer
                    }
                });
                return in;
            }
        };
        ExportTarget.Response response = target.send(new ExportTarget.Request("PUT", coordinate,
                Map.of("Content-Type", "application/json", "npm-command", "publish"), body));
        if (response.ok()) {
            return Exported.PUBLISHED;
        }
        if (target.sha256(served).filter(tarball.get().hash()::equals).isPresent()) {
            return Exported.ALREADY_PRESENT;
        }
        throw new IOException("the target answered " + response.status() + " to the publish of " + served + ": "
                + response.body());
    }

    /**
     * After a package's last version, every tag the source carries is set as {@code npm dist-tag add} sets it, since
     * a registry that replaces a package's tags on publish (as this one does) keeps only the last version's.
     */
    @Override
    public void exported(ArtifactStore repository, String coordinate, ExportTarget target) throws IOException {
        Blobs blobs = new Blobs(repository);
        for (Map.Entry<String, JsonNode> tag : storedTags(coordinate, blobs).properties()) {
            String version = tag.getValue().asString("");
            if (Keys.unsafe(tag.getKey()) || Keys.unsafe(version)
                    || blobs.locate(tarballKey(coordinate, shortName(coordinate), version)).isEmpty()) {
                continue;   // a tag at a withheld version was not exported with it
            }
            ExportTarget.Response response = target.send(ExportTarget.Request.put(
                    DIST_TAGS_API + coordinate + "/dist-tags/" + tag.getKey(), "application/json",
                    ExportTarget.Body.of(MAPPER.writeValueAsBytes(version))));
            if (!response.ok()) {
                throw new IOException("the target answered " + response.status() + " to setting the dist-tag "
                        + tag.getKey() + " of " + coordinate + " to " + version + ": " + response.body());
            }
        }
    }

    /** The source's stored dist-tags document, or an empty one when its tags are computed. */
    private static ObjectNode storedTags(String name, Blobs blobs) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        if (blobs.read("npm/" + name + "/dist-tags", buffer)
                && MAPPER.readTree(buffer.toByteArray()) instanceof ObjectNode tags) {
            return tags;
        }
        return MAPPER.createObjectNode();
    }

    /** A package's unscoped short name - the {@code <shortName>-<version>.tgz} tarball filenames are built from it. */
    static String shortName(String name) {
        return name.contains("/") ? name.substring(name.indexOf('/') + 1) : name;
    }

    /** The version an attachment filename carries under the conventional {@code <shortName>-<version>.tgz} shape -
     *  the same convention {@link #tarballKey} builds and the packument's {@code dist.tarball} points at - or the empty
     *  string when the filename does not follow it (which matches no published version). */
    private static String versionOf(String shortName, String file) {
        String prefix = shortName + "-";
        if (!file.startsWith(prefix) || !file.endsWith(".tgz")) {
            return "";
        }
        return file.substring(prefix.length(), file.length() - ".tgz".length());
    }

    /**
     * Base64-decode the {@code data} string the parser is positioned on <em>through</em> the shared hosted-publish
     * operation, so the tarball is screened and linked in one streamed pass. The tarball is never held whole in heap,
     * nor even as a base64 {@code String}, and it crosses {@code writeBlob} exactly once: Jackson's
     * {@link JsonParser#readBinaryValue} decodes the base64 in its bounded read buffer on a helper thread, pushing the
     * decoded bytes through a pipe whose consumer is {@code Publication.commit}'s own hash-on-write. A multi-gigabyte
     * tarball therefore flows decode-to-screen-to-store in bounded chunks, with no size cap.
     *
     * <p>The descriptor is the tarball's own: the request path it will download from, and - when the filename follows
     * the conventional {@code <shortName>-<version>.tgz} shape - the coordinate and version too, so a deny-list, a
     * {@code /quarantine} review handle and an inspector's artifact leg all key on the artifact rather than the publish
     * endpoint. A filename off that shape is screened coordinate-less rather than under a guessed version.
     *
     * <p>The accepted layout links the tarball pointer and nothing else: every index write is deferred to
     * {@link #index}. Before it declares, it joins the decoder and checks its outcome - a base64 run that broke
     * mid-stream would otherwise reach the store as a self-consistent <em>truncated</em> tarball and be linked before
     * the failure surfaced, so a failed decode declares nothing and the error is rethrown here (&sect;9: a publish never
     * answers success having stored something else). Any failure on the helper thread is carried back the same way.
     */
    private static Publication.Commit commitTarball(JsonParser parser, String name, String file, Blobs blobs,
                                                    Publication screening) throws IOException {
        String key = "npm/" + name + "/tarballs/" + file;
        String path = "/npm/" + name + "/-/" + file;
        String version = versionOf(shortName(name), file);
        ArtifactDescriptor descriptor = version.isEmpty() || version.indexOf('/') >= 0
                ? ArtifactDescriptor.at("npm", path)
                : new ArtifactDescriptor("npm", name, version, path,
                        "application/octet-stream", version.contains("-"), null, -1L);
        PipedInputStream in = new PipedInputStream(1 << 16);
        PipedOutputStream out = new PipedOutputStream(in);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread decoder = new Thread(() -> {
            try (OutputStream sink = out) {
                parser.readBinaryValue(sink);   // streaming base64 decode from the parser into the pipe
            } catch (Throwable e) {
                failure.set(e);
            }
        }, "npm-publish-tarball");
        decoder.start();
        Publication.Commit commit;
        try (InputStream decoded = in) {
            commit = screening.commit(descriptor, decoded, republish(key), _ -> {
                // The body reached EOF, so the decoder has closed its sink and is done; join it before declaring so a
                // broken decode links nothing.
                join(decoder);
                return failure.get() == null
                        ? Publication.Visibility.through((hash, _, _) -> blobs.link(key, hash))
                        : Publication.Visibility.declined();
            });
        } finally {
            // Idempotent after the layout's join, and the escape hatch for a commit that threw before reaching it: the
            // pipe is closed by now, so a decoder still pushing bytes is released rather than left parked.
            join(decoder);
        }
        Throwable failed = failure.get();
        switch (failed) {
            case null -> {
            }
            case IOException io -> throw io;
            case RuntimeException runtime -> throw runtime;
            case Error error -> throw error;
            default -> throw new IOException("could not store the npm tarball", failed);
        }
        // the held tarball's layout, written here because the operation's accepted layout above never runs on a
        // non-ACCEPT verdict. It comes AFTER the decode outcome is checked, for the reason the accepted layout joins
        // the decoder before it declares: a base64 run that broke mid-stream stored a self-consistent TRUNCATED tarball,
        // and laying that out - even withheld - would give a reviewer a release that materialises the wrong bytes.
        // The withhold marker goes FIRST and the pointer only after it, so no window exists in which the held tarball is
        // downloadable; every npm read keys on that marker - the download ({@link Blobs#size}/{@link Blobs#read}), the
        // packument's per-version screen and the dist-tags screen - so the version is stored, reviewable and invisible
        // until {@code HoldLifecycle.release} lifts it. Without the layout a release would have nothing to make
        // servable, which is the regression this closes; without the marker-first order the layout is the disclosure.
        switch (commit.disposition()) {
            case QUARANTINE -> {
                Withheld.mark(blobs.store(), commit.hash(), descriptor);
                blobs.link(key, commit.hash());
            }
            default -> {
            }
        }
        return commit;
    }

    /** Wait for the base64 decoder to finish, translating an interrupt into the {@code IOException} the publish path
     *  reports - never swallowing it, and never leaving the interrupt flag cleared. */
    private static void join(Thread decoder) throws IOException {
        try {
            decoder.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while streaming the npm tarball", e);
        }
    }

    private void serveTarball(String name, String file, Blobs blobs, FormatExchange exchange) throws IOException {
        Optional<Blobs.Located> located = blobs.locate("npm/" + name + "/tarballs/" + file);
        if (located.isEmpty()) {
            exchange.respond(404);
            return;
        }
        long size = located.get().size();
        exchange.setResponseHeader("Content-Type", "application/octet-stream");
        if (exchange.method().equals("HEAD")) {
            // Answer HEAD from the stored blob size (Content-Length, 200, no body) rather than streaming the whole
            // tarball just to discard it - npm issues HEADs to probe a tarball's size and existence.
            if (size >= 0) {
                exchange.setResponseHeader("Content-Length", Long.toString(size));
            }
            exchange.respond(200, -1L).close();
            return;
        }
        blobs.serve(located.get(), exchange);
    }

    /** The attestations a version was published with, as the envelope the client sent - what a client that read
     *  {@code dist.attestations.url} fetches to verify provenance; a version published without any is a 404. */
    private void serveAttestations(String name, String version, Blobs blobs, FormatExchange exchange)
            throws IOException {
        Optional<Blobs.Located> located = Keys.unsafe(version) ? Optional.empty()
                : blobs.locate(attestationsKey(name, version));
        if (located.isEmpty()) {
            exchange.respond(404);
            return;
        }
        exchange.setResponseHeader("Content-Type", "application/json");
        if (exchange.method().equals("HEAD")) {
            exchange.respond(200, -1L).close();
            return;
        }
        blobs.serve(located.get(), exchange);
    }

    // ---- the signature seam: Sigstore bundles published beside the tarball ----

    /**
     * A tarball may have been published with Sigstore attestations - provenance from a CI build, and the registry's
     * own publish attestation - each a bundle over the tarball's digest; optional, since most packages carry none,
     * and read from the attestations document the publish stored beside the tarball.
     */
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
        Optional<ArtifactDescriptor> described = describe(path).filter(d -> d.coordinate() != null && d.version() != null);
        if (described.isEmpty()) {
            return List.of();
        }
        String attestationsPath = "/npm/" + described.get().coordinate() + "/-/attestations/" + described.get().version();
        // Whole or nothing: an attestations document past the signature bound is not read in part, since a bundle
        // cut short verifies as nothing and would be reported as a signature that failed rather than one unread.
        Optional<byte[]> document = material.sibling(attestationsPath, ArtifactSignatures.Material.LARGEST_SIGNATURE)
                .filter(bounded -> !bounded.truncated())
                .map(PublishInterceptor.Content.Bounded::content);
        Optional<ArtifactSignatures.Signed> body = material.body();
        if (document.isEmpty() || body.isEmpty()) {
            return List.of();
        }
        List<ArtifactSignatures.Evidence> evidence = new ArrayList<>();
        for (JsonNode attestation : MAPPER.readTree(document.get()).path("attestations")) {
            JsonNode bundle = attestation.get("bundle");
            if (bundle == null || !bundle.isObject()) {
                continue;
            }
            String predicateType = attestation.path("predicateType").asString("");
            evidence.add(new ArtifactSignatures.Evidence(ArtifactSignatures.Scheme.SIGSTORE_BUNDLE,
                    MAPPER.writeValueAsBytes(bundle), body.get(),
                    attestationsPath + (predicateType.isEmpty() ? "" : "#" + predicateType)));
        }
        return evidence;
    }

    /** A request path's serving key, for the compliance screen's sibling read: a tarball, or a version's
     *  attestations document, under the package that publishes them - when the pointer exists. */
    @Override
    public Optional<String> servingKey(String requestPath, ArtifactStore store) throws IOException {
        if (!requestPath.startsWith("/npm/")) {
            return Optional.empty();
        }
        String rest = requestPath.substring("/npm/".length());
        int tarball = rest.indexOf("/-/");
        if (tarball <= 0) {
            return Optional.empty();
        }
        String name = rest.substring(0, tarball), file = rest.substring(tarball + "/-/".length());
        String key;
        if (file.startsWith("attestations/")) {
            String version = file.substring("attestations/".length());
            if (Keys.unsafe(version) || version.indexOf('/') >= 0) {
                return Optional.empty();
            }
            key = attestationsKey(name, version);
        } else {
            if (Keys.unsafe(file) || file.indexOf('/') >= 0) {
                return Optional.empty();
            }
            key = "npm/" + name + "/tarballs/" + file;
        }
        return store.readVersioned(key).isPresent() ? Optional.of(key) : Optional.empty();
    }

    private void packument(String name, Blobs blobs, ArtifactStore store, FormatExchange exchange) throws IOException {
        if (!StoredListing.present(store, NpmListings.packument(name)) && blobs.isEmpty("npm/" + name + "/versions")) {
            exchange.respond(404);      // a structural emptiness probe: nothing published, so a proxy repo can fall through
            return;
        }
        // The packument is a stored listing the publish maintains, completed with this registry's tarball base on the
        // way out. The ETag is the stored document's digest, folded with the base it is completed for.
        String tarballBase = RequestBase.of(exchange) + exchange.requestUri();
        Optional<StoredListing.Served> served = StoredListing.open(store, new NpmListings(blobs).spec(name));
        if (served.isEmpty()) {
            exchange.respond(404);
            return;
        }
        try (StoredListing.Served document = served.get()) {
            String etag = '"' + document.header().sha256() + "-" + Integer.toHexString(tarballBase.hashCode()) + '"';
            exchange.setResponseHeader("ETag", etag);
            if (etag.equals(exchange.requestHeader("If-None-Match"))) {
                exchange.respond(304);
                return;
            }
            exchange.setResponseHeader("Content-Type", "application/json");
            if (exchange.method().equals("HEAD")) {
                exchange.respond(200, -1L).close();
                return;
            }
            // Streamed with the rewrite folded in, never as one byte array: a package's document is every version
            // of it, and answering it whole held the packument the publish had just streamed into - the
            // npm-packument canary's 500 at fifty thousand versions under 512 MiB, an OutOfMemoryError on the read
            // after the write was fixed. The length is not declared, since the rewrite changes it.
            try (OutputStream out = exchange.respond(200, -1L)) {
                document.copyTo(out, NpmListings.BASE, tarballBase);
            }
        }
    }

    /** The served tarball pointer key a version's bytes live at - the identity the packument's screened enumeration
     *  judges each version by, and the key the stored packument screens on, so the two can never drift. */
    /**
     * The coordinate version a stored npm pointer serves, for the inventory back-fill - {@code BlobLayout}'s one
     * backwards direction.
     *
     * <p>Only the per-version metadata pointer is read, {@code npm/<name>/versions/<version>}, because it is the one
     * npm key whose two parts are unambiguous. A tarball key carries the version inside a filename
     * ({@code <shortName>-<version>.tgz}) and a short name that ends in a digit makes that split ambiguous, so it is
     * deliberately not decoded: every published version has a versions pointer, so nothing is lost by reading only
     * the shape that cannot be misread.
     *
     * <p><b>Split on the LAST {@code /versions/}, not the first.</b> An npm coordinate is legitimately
     * multi-segment - a scoped {@code @scope/name} - and may itself end in {@code versions}: a package literally
     * called {@code @scope/versions} stores {@code npm/@scope/versions/versions/1.0.0}, where the first marker
     * yields the coordinate {@code @scope} and a version of {@code versions/1.0.0}. Taking the last one yields the
     * package and the version that were actually published.
     *
     * <p>Screened through {@link BlobLayout#addressable}, which applies the addressability rule to each part
     * of the coordinate - the rule written for exactly this, a blobs-namespace coordinate that is legitimately
     * multi-segment - so a traversal-shaped key decodes to nothing rather than to a row naming a coordinate this
     * format would never have written.
     */
    @Override
    public Optional<ArtifactDescriptor> describePointer(String key) {
        if (!key.startsWith("npm/")) {
            return Optional.empty();
        }
        String rest = key.substring("npm/".length());
        int marker = rest.lastIndexOf("/versions/");
        if (marker < 0) {
            return Optional.empty();
        }
        String coordinate = rest.substring(0, marker);
        String version = rest.substring(marker + "/versions/".length());
        if (version.indexOf('/') >= 0 || !BlobLayout.addressable(coordinate, version)) {
            return Optional.empty();
        }
        return Optional.of(new ArtifactDescriptor("npm", coordinate, version, key,
                "application/octet-stream", version.contains("-"), null, 0L));
    }

    static String tarballKey(String name, String shortName, String version) {
        return "npm/" + name + "/tarballs/" + shortName + "-" + version + ".tgz";
    }

    /** The npm {@code deprecated} warning string for a lifecycle flag: the operator's own message when they set one,
     *  otherwise a default naming the state (npm has no distinct "yanked" signal, so a yank surfaces as a deprecation). */
    static String deprecation(Lifecycle.Flag flag) {
        if (!flag.message().isEmpty()) {
            return flag.message();
        }
        return flag.state() == Lifecycle.State.YANKED
                ? "This version has been yanked."
                : "This version is deprecated.";
    }

    /**
     * Proxy an npm miss to the upstream registry (registry.npmjs.org). A tarball ({@code /-/}) is immutable, so it
     * is fetched, cached and served locally. A packument is mutable: the upstream document is fetched and each
     * version's {@code dist.tarball} rewritten to this registry's tarball URL (so the client fetches - and we cache -
     * the tarball through us), then served fresh; npm's own integrity and shasum, which match the bytes, are kept.
     */
    @Override
    public boolean pullThrough(FormatExchange exchange, ArtifactStore store, URI upstream,
                               ProxyFormat.Fetcher fetcher) throws IOException {
        String path = exchange.path();
        String rest = path.substring("/npm/".length());
        String root = upstream.toString();
        if (!root.endsWith("/")) {
            root += "/";
        }
        int tarball = rest.indexOf("/-/");
        if (tarball >= 0) {
            String name = rest.substring(0, tarball);
            String file = rest.substring(tarball + "/-/".length());
            // Point-integrity: npm's packument declares each tarball's checksum (dist.integrity, a sha512, or the older
            // dist.shasum, a sha1). Read it from the upstream packument and verify the streamed tarball against it,
            // refusing a mismatch - the checksum parity the Maven proxy leg has (it fetches the .sha1 sibling). The
            // packument is a SEPARATE fetch from the tarball below, so a packument this repository could not read is
            // not "npm publishes no checksum for this tarball" and must not become an unverified fill.
            URI target = URI.create(root + name + "/-/" + file);
            ProxyRelay.Declared expected = tarballChecksum(root, name, file, fetcher);
            if (!expected.readable()) {
                return ProxyRelay.unverifiable(target, expected);
            }
            // The tarball is an immutable artifact of unbounded size: stream it from the network straight into the
            // content-addressed store rather than buffering the whole body (fetch().body()), then re-serve it locally.
            try (ProxyFormat.Download download = fetcher.download(target, Map.of()).orElse(null)) {
                if (download == null || download.status() != 200) {
                    return false;
                }
                if (!ProxyRelay.fill(new Blobs(store), "npm/" + name + "/tarballs/" + file, target, download.body(),
                        expected)) {
                    return false;
                }
            }
            handle(exchange, store);
            return true;
        }
        // Forward the client's conditional-request validators so a 304-capable client's revalidation reaches the origin
        // rather than being dropped and forcing a full packument re-download on every read.
        Map<String, String> request = ProxyRelay.conditionalHeaders(exchange);
        request.put("Accept", "application/json");
        // The packument is npm's version list: it IS the enumeration a resolver reads to decide which versions of this
        // package exist, so a 404 here is not "not cached, re-pull" but the registry's answer that the package has no
        // versions - a "package not found" the client records, a lockfile resolves against, and a fallback registry
        // chain moves past. Only an upstream that ANSWERED 404/410 may reach the client as one; an upstream this
        // repository could not ask refuses visibly instead.
        ProxyRelay.Answer answer = ProxyRelay.fetchFresh(fetcher, URI.create(root + rest), request, exchange,
                ProxyRelay.Document.ENUMERATION);
        if (!answer.answered()) {
            return answer.served();
        }
        ProxyRelay.relayValidators(answer.document(), exchange);
        exchange.setResponseHeader("Content-Type", "application/json");
        exchange.respond(200, rewritePackument(answer.document().body(), exchange));
        return true;
    }

    /** The checksum npm's packument declares for a version's tarball: {@code dist.integrity} (a
     *  {@code sha512-<base64>} Subresource-Integrity string, preferred) or the legacy {@code dist.shasum} (a sha1 hex).
     *  The packument is a bounded metadata document, fetched buffered and only on a tarball miss (once per tarball,
     *  since it is then cached), and the version is matched by its {@code dist.tarball} basename so a scoped or
     *  odd-named package still resolves.
     *
     *  <p>{@link ProxyRelay.Declared#NONE} - cache without a point check, as Maven serves a jar with no {@code .sha1} -
     *  when the registry <em>answered</em> and declares nothing: a {@code 404}/{@code 410} packument, a packument that
     *  lists no version whose {@code dist.tarball} is this file, or one whose {@code dist} carries neither a parseable
     *  {@code integrity} nor a 40-hex {@code shasum}. {@linkplain ProxyRelay.Declared#unreadable Unreadable} when the
     *  packument could not be read at all - a transport failure, a {@code 429}/{@code 5xx}/auth challenge, or a
     *  {@code 200} that is not a packument - because none of those is npm declaring anything. */
    private static ProxyRelay.Declared tarballChecksum(String root, String name, String file,
            ProxyFormat.Fetcher fetcher) throws IOException {
        URI packument = URI.create(root + name);
        ProxyRelay.Sidecar sidecar =
                ProxyRelay.declaring(fetcher, packument, Map.of("Accept", "application/json"));
        if (!sidecar.answered()) {
            return sidecar.verdict();
        }
        if (!(MAPPER.readTree(sidecar.document().body()) instanceof ObjectNode document)
                || !(document.get("versions") instanceof ObjectNode versions)) {
            return ProxyRelay.Declared.unreadable("the packument at " + packument
                    + " answered 200 with a body that is not a packument");
        }
        for (String version : versions.propertyNames()) {
            if (!(versions.get(version) instanceof ObjectNode metadata)
                    || !(metadata.get("dist") instanceof ObjectNode dist)) {
                continue;
            }
            JsonNode tarball = dist.get("tarball");
            if (tarball == null || !basename(tarball.asString()).equals(file)) {
                continue;
            }
            JsonNode integrity = dist.get("integrity");
            if (integrity != null && integrity.asString().startsWith("sha512-")) {
                try {
                    byte[] raw = Base64.getDecoder().decode(integrity.asString().substring("sha512-".length()));
                    if (raw.length == 64) {
                        return ProxyRelay.Declared.of("SHA-512", raw);
                    }
                } catch (IllegalArgumentException _) {
                    // a malformed integrity string: fall through to shasum / no-check
                }
            }
            JsonNode shasum = dist.get("shasum");
            if (shasum != null) {
                byte[] raw = hex(shasum.asString(), 20);
                if (raw != null) {
                    return ProxyRelay.Declared.of("SHA-1", raw);
                }
            }
            return ProxyRelay.Declared.NONE;
        }
        return ProxyRelay.Declared.NONE;
    }

    /** The last path segment of a URL or path (its filename), for matching a tarball to its packument version. */
    private static String basename(String url) {
        int slash = url.lastIndexOf('/');
        return slash < 0 ? url : url.substring(slash + 1);
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

    private byte[] rewritePackument(byte[] body, FormatExchange exchange) throws IOException {
        if (!(MAPPER.readTree(body) instanceof ObjectNode packument)) {
            return body;
        }
        String tarballBase = RequestBase.of(exchange) + exchange.requestUri() + "/-/";
        if (packument.get("versions") instanceof ObjectNode versions) {
            for (String version : versions.propertyNames()) {
                if (versions.get(version) instanceof ObjectNode metadata
                        && metadata.get("dist") instanceof ObjectNode dist
                        && dist.get("tarball") != null) {
                    String url = dist.get("tarball").asString();
                    dist.put("tarball", tarballBase + url.substring(url.lastIndexOf('/') + 1));
                }
            }
        }
        return MAPPER.writeValueAsBytes(packument);
    }






    /** The migration-import capability (WSPI.2 (c)), delegated to the layout-only {@link NpmImporter} - the format IS the
     *  discovered importer now (an {@code instanceof} capability), and the importer class stays as its delegate. */
    private final NpmImporter importer = new NpmImporter();

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
