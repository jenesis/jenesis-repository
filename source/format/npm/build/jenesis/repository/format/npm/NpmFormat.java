package build.jenesis.repository.format.npm;

import module java.base;
import module tools.jackson.databind;

import build.jenesis.repository.blobs.RequestBase;
import build.jenesis.repository.blobs.BlobLayout;
import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.blobs.Keys;
import build.jenesis.repository.blobs.ProxyLeg;
import build.jenesis.repository.blobs.ProxyRelay;
import build.jenesis.repository.format.Listings;
import build.jenesis.repository.format.ArtifactSignatures;
import build.jenesis.repository.format.ExportTarget;
import build.jenesis.repository.format.RepositoryExporter;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.icon.IconResource;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.format.lifecycle.Lifecycle;
import build.jenesis.repository.format.LifecycleMark;
import build.jenesis.repository.store.Checksums;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.StoredListing;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.PublishInterceptor;
import build.jenesis.repository.store.Withheld;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import build.jenesis.repository.format.Semver;

/**
 * The npm registry format: {@code npm publish} and {@code npm install} over the same store, under {@code /npm/...}. A
 * publish ({@code PUT /npm/<package>}) carries the version's metadata and its tarball base64-encoded under
 * {@code _attachments}; the metadata is stored under {@code npm/<package>/versions/<version>} and the tarball under
 * {@code npm/<package>/tarballs/<file>}. The packument ({@code GET /npm/<package>}) is a stored document the publish
 * maintains, each version's {@code dist.tarball} completed to this registry's URL with npm's integrity and shasum kept;
 * the tarball is served verbatim, so the client's integrity check passes.
 */
public final class NpmFormat implements RepositoryFormat, ProxyLeg, BlobLayout, RepositoryImporter.Delegating,
        ArtifactSignatures, RepositoryExporter {

    static final ObjectMapper MAPPER = new ObjectMapper();

    /** The most version metadata one publish envelope may carry, all versions together: a version's metadata is its
     *  {@code package.json} with the readme, kilobytes to a megabyte, so this is generous and keeps an envelope from
     *  filling the heap. */
    static final int LARGEST_VERSIONS = 16 * 1024 * 1024;

    /** The most a publish envelope's {@code dist-tags} may carry: a handful of tag names and versions. */
    static final int LARGEST_DIST_TAGS = 64 * 1024;

    @Override
    public String name() {
        return "npm";
    }

    /** The marks this format's clients see, each by its own word. */
    @Override
    public Map<LifecycleMark, String> lifecycleMarks() {
        return LifecycleMark.shown(LifecycleMark.DEPRECATED, LifecycleMark.YANKED);
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
        // A version's metadata pointer and its tarball; dist-tags and the package root are shared and left.
        if (!BlobLayout.addressable(coordinate, version)) {
            return List.of();   // a traversal-shaped coordinate maps nowhere - these keys are what an eviction DELETES
        }
        List<String> keys = new ArrayList<>();
        String versionKey = "npm/" + coordinate + "/versions/" + version;
        if (store.readVersioned(versionKey).isPresent()) {
            keys.add(versionKey);
        }
        // The tarball is the one the packument entry points at, the conventional <shortName>-<version>.tgz key, probed
        // rather than found by scanning.
        String tarball = tarballKey(coordinate, shortName(coordinate), version);
        if (store.readVersioned(tarball).isPresent()) {
            keys.add(tarball);
        }
        return keys;
    }

    /** The request paths this version's tarballs serve at ({@code /npm/<name>/-/<file>}), where a retroactive hold
     *  links its {@code /quarantine} handles; the metadata pointer is not a download. */
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

    /** The coordinate a tarball path carries ({@code /npm/<name>/-/<shortName>-<version>.tgz}), the name kept whole
     *  with its {@code @scope}. A packument path and the dist-tags carry no version and stay empty, as does a filename
     *  off the convention, rather than guessing. A {@code -} in the version marks a prerelease, as
     *  {@link Semver#compare} ranks it. */
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
            // A '/' in the parsed version means the source path leaked into it: described coordinate-less rather than
            // as a version the store's segment check would refuse. Unreachable from a well-formed path; a second guard.
            return Optional.of(ArtifactDescriptor.at("npm", path));
        }
        return Optional.of(new ArtifactDescriptor("npm", name, version, path,
                "application/octet-stream", version.contains("-"), null, -1L));
    }

    // An original CC0 line glyph (a bracketed package block).
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

    /** Demo-mode suggestions: {@code lodash 4.17.11} and {@code minimist 1.2.0}, old benign-but-vulnerable tarballs, so
     *  a fresh repository's browse rows and advisory panel carry npm data; pulled through this format's own
     *  {@link #defaultUpstream() upstream}. */
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

    /** The encoded slash of a scoped package name, {@code @scope%2Fname}: after a scope, and nowhere else. */
    private static final Pattern SCOPED_SEPARATOR = Pattern.compile("(@[A-Za-z0-9._~-]+)%2[Ff]");

    /** An answer as npm's registry reports one, {@code {"error":...}}, which the npm client prints as it stands. */
    @Override
    public void explain(FormatExchange exchange, int status, String sentence) throws IOException {
        exchange.setResponseHeader("Content-Type", "application/json");
        exchange.respond(status, MAPPER.writeValueAsBytes(Map.of("error", sentence)));
    }

    /**
     * Not edge-screened: an {@code npm publish} body is an envelope carrying the tarball base64-encoded under
     * {@code _attachments}, and screening it at the shared edge would assess the document while the bytes that serve
     * are the {@code .tgz} inside it ({@code RepositoryFormat} clause 14). This format screens at its own choke point:
     * {@link #parse} decodes each attachment through the shared {@code Publication.commit} ({@link #commitTarball})
     * with the discovered chain and observers, under the tarball's own path. The package root {@code PUT} is the only
     * way a tarball is hosted-published here.
     */
    @Override
    public boolean screened() {
        return false;
    }

    @Override
    public void serve(FormatExchange exchange, ArtifactStore store) throws IOException {
        Blobs blobs = new Blobs(store);
        // npm encodes a scoped name's slash (@scope%2Fname). Only that separator is decoded, so an encoded slash
        // elsewhere, "..%2f" among them, stays literal and cannot compose a traversal.
        String rest = SCOPED_SEPARATOR.matcher(exchange.path().substring("/npm/".length())).replaceAll("$1/");
        String method = exchange.method();
        if (rest.startsWith(DIST_TAGS_API)) {
            distTags(rest.substring(DIST_TAGS_API.length()), exchange, blobs, store);
            return;
        }
        int tarball = rest.indexOf("/-/");
        if (tarball >= 0) {
            if (!method.equals("GET") && !method.equals("HEAD")) {
                // A tarball path is read-only; a write verb is a 405.
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

    /** The documents npm updates in place for an existing version, last-writer-wins: the dist-tags and a version's
     *  index entry, which {@code npm deprecate} rewrites with no tarball. A published version's bytes are governed by
     *  {@link #republish}. */
    private static final Publication.Republish METADATA = Publication.Republish.overwrite();

    /**
     * What a publish of an already-published version does. npm's registry refuses it, and release immutability is on by
     * default with {@code allow-redeploy} as the opt-out, honoured here as the edge resolved it for the tenant
     * ({@link Publication#redeployAllowed()}), since a publish PUTs the packument root, which names no version for the
     * edge to protect.
     *
     * <p>The probe is the tarball's serving pointer, the version's bytes, not the packument, which changes with every
     * publish. A replay of identical bytes is idempotent, which is how a publish that crashed mid-layout is repaired;
     * different bytes at a published version are refused.
     */
    private static Publication.Republish republish(String pointer) {
        return Publication.redeployAllowed()
                ? Publication.Republish.overwrite()
                : Publication.Republish.idempotent(pointer);
    }

    /**
     * Publish streaming, so the tarball is never materialised: the body is parsed with {@link JsonParser}, the small
     * metadata subtrees ({@code versions}, {@code dist-tags}) read whole, while each attachment's {@code data} is
     * base64-decoded straight into the shared commit's hash-on-write ({@link #commitTarball}). A tarball of any size
     * publishes in bounded heap.
     *
     * <h2>The tarball is the screened body, written once</h2>
     * The decoded stream is the commit's accepted body, so the tarball crosses {@code writeBlob} once and <b>the hash
     * the interceptor chain assesses is the hash {@code npm install} downloads</b>, never the surrounding packument.
     *
     * <h2>Content first, index after</h2>
     * The envelope's field order is the client's, and every npm CLI sends {@code versions} before {@code _attachments},
     * so writing as it parses would index a version before its tarball existed. The order is the operation's instead:
     * {@link #parse} commits each attachment as it streams, linking the tarball pointer only, and every index write
     * waits for {@link #index} after the whole envelope is read. A version is indexed only when its bytes are servable
     * - published now or already stored, so an {@code npm deprecate} with no attachments still updates its versions.
     *
     * <p>A malformed envelope tail answers {@code 400} after a tarball pointer has landed, leaving a screened, servable
     * artifact no packument lists - the benign half of the operation's crash window, never an index entry without
     * bytes.
     */
    private void publish(String name, FormatExchange exchange, Blobs blobs, ArtifactStore store) throws IOException {
        Envelope envelope;
        try {
            envelope = parse(name, exchange, blobs, store);
        } catch (TooLarge refused) {
            exchange.respond(413);   // an envelope field past its bound - nothing was indexed
            return;
        } catch (Publication.RepublishConflict taken) {
            publishedOver(exchange);
            return;
        }
        if (envelope == null) {
            exchange.respond(400);   // not a JSON object, or an unsafe version / attachment key - nothing was indexed
            return;
        }
        // The strongest verdict any tarball drew: a multi-version envelope answers by its worst, so no client is told
        // 201 for a version that 404s. A refusal stops the index phase; a hold does not, since a held version is laid
        // out behind its marker and screened out of the packument until released.
        if (envelope.strongest() != null) {
            switch (envelope.strongest().disposition()) {
                case ACCEPT -> {
                }
                // Held: the layout was written behind the withhold marker commitTarball set, so it is indexed like any
                // stored version and the packument screens it out until a release clears the marker.
                case QUARANTINE -> {
                    try {
                        index(name, envelope, blobs, store);
                    } catch (Publication.RepublishConflict taken) {
                        publishedOver(exchange);
                        return;
                    }
                    explain(exchange, 202, envelope.strongest().explanation());
                    return;
                }
                // Refused: no pointer, marker or index entry; the stored blob is an unreferenced object the collector
                // reclaims.
                case REJECT -> {
                    explain(exchange, 422, envelope.strongest().explanation());
                    return;
                }
            }
        }
        try {
            index(name, envelope, blobs, store);
        } catch (Publication.RepublishConflict taken) {
            publishedOver(exchange);
            return;
        }
        deprecations(name, envelope, blobs, store, exchange);
        exchange.setResponseHeader("Content-Type", "application/json");
        exchange.respond(201, MAPPER.writeValueAsString(Map.of("ok", true)).getBytes(StandardCharsets.UTF_8));
    }

    /** A published version's tarball, document and attestations never change, and the refusal says so in the JSON error
     *  field, the one place {@code npm publish} reads a reason from; any other body prints as a missing permission. */
    private static void publishedOver(FormatExchange exchange) throws IOException {
        exchange.setResponseHeader("Content-Type", "application/json");
        exchange.respond(403, MAPPER.writeValueAsBytes(
                Map.of("error", "You cannot publish over the previously published versions.")));
    }

    /** {@code npm deprecate <pkg>@<range> "<message>"}: the client PUTs the package document back with a
     *  {@code deprecated} message on each version the range names, empty to undo it. Each stored version whose message
     *  changed becomes the product's lifecycle mark, through the path the console and API use
     *  ({@link Lifecycle#mark(FormatExchange, ArtifactStore, String, String, Lifecycle.Flag)}). A message equal to what
     *  the version renders is no change, an absent field says nothing, and a yank, which npm can only render as a
     *  deprecation, is never made or undone here. */
    private static void deprecations(String name, Envelope envelope, Blobs blobs, ArtifactStore store,
                                     FormatExchange exchange) throws IOException {
        String shortName = shortName(name);
        for (Map.Entry<String, byte[]> version : envelope.versions().entrySet()) {
            String file = shortName + "-" + version.getKey() + ".tgz";
            if (!envelope.published().contains(file) && !blobs.exists("npm/" + name + "/tarballs/" + file)) {
                continue;
            }
            JsonNode deprecated = MAPPER.readTree(version.getValue()).get("deprecated");
            if (deprecated == null || !deprecated.isString()) {
                continue;
            }
            String message = deprecated.asString();
            Optional<Lifecycle.Flag> mark = Lifecycle.read(store, name, version.getKey());
            if (mark.filter(flag -> flag.state() == LifecycleMark.YANKED).isPresent()) {
                continue;
            }
            if (message.isEmpty()) {
                if (mark.isPresent()) {
                    Lifecycle.clear(exchange, store, name, version.getKey());
                }
            } else if (mark.map(NpmFormat::deprecation).filter(message::equals).isEmpty()) {
                Lifecycle.mark(exchange, store, name, version.getKey(),
                        new Lifecycle.Flag(LifecycleMark.DEPRECATED, message));
            }
        }
    }

    /** The stronger of two commits' verdicts, {@code null} losing to any: how a multi-attachment envelope folds its
     *  verdicts, as the interceptor chain does. */
    private static Publication.Commit stronger(Publication.Commit held, Publication.Commit next) {
        if (next == null) {
            return held;
        }
        return held == null || next.disposition().compareTo(held.disposition()) > 0 ? next : held;
    }

    /** One walked envelope: the per-version index documents, the attachment filenames this request made servable, the
     *  verbatim {@code dist-tags} ({@code null} when absent), the strongest verdict ({@code null} when no tarball was
     *  carried), and the attestations. Only the small index documents are held. */
    private record Envelope(Map<String, byte[]> versions, Set<String> published, byte[] distTags,
                            Publication.Commit strongest, byte[] attestations) {
    }

    /** Whether an attachment read was usable, and the strongest verdict its tarballs drew; {@code safe} is false for a
     *  filename that would forge a pointer key, failing the publish. */
    private record Attachments(boolean safe, Publication.Commit strongest) {
    }

    /** Walk the envelope in one pass: each attachment is screened and linked as it streams ({@link #commitTarball}),
     *  each version's metadata is held, and no index write happens here ({@link #index}). {@code null}, a {@code 400},
     *  when the body is not a JSON object or a version key or filename would forge a pointer key. */
    private Envelope parse(String name, FormatExchange exchange, Blobs blobs, ArtifactStore store) throws IOException {
        Map<String, byte[]> versions = new LinkedHashMap<>();
        Set<String> published = new LinkedHashSet<>();
        byte[] distTags = null;
        byte[] attestations = null;
        Publication.Commit strongest = null;
        // The discovering constructor: this format is its own screening choke point (screened()).
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
                            distTags = bounded(parser, LARGEST_DIST_TAGS);
                            if (distTags == null) {
                                throw new TooLarge("dist-tags");
                            }
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

    /** The Sigstore bundles {@code npm publish --provenance} sends: {@code _attestations} as the registry's
     *  {@code {"attestations":[{"predicateType","bundle"}]}} envelope or the bare list. Held whole, being kilobytes,
     *  and bounded at the signature limit, past which the publish carries none. */
    private static byte[] readAttestations(JsonParser parser) throws IOException {
        JsonToken token = parser.currentToken();
        if (token != JsonToken.START_OBJECT && token != JsonToken.START_ARRAY) {
            parser.skipChildren();
            return null;
        }
        byte[] held = bounded(parser, ArtifactSignatures.Material.LARGEST_SIGNATURE);
        if (held == null) {
            return null;
        }
        JsonNode read = MAPPER.readTree(held);
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

    /** The stored attestations of a version, as published, served at {@code /-/attestations/<version>}. */
    static String attestationsKey(String name, String version) {
        return "npm/" + name + "/attestations/" + version;
    }

    /** A version's metadata with {@code dist.attestations} naming where the bundles are served - the placeholder base
     *  the packument completes - and the provenance predicate, as registry.npmjs.org writes it. */
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

    /**
     * Hold each version's metadata subtree against its version, all within {@link #LARGEST_VERSIONS}, the key validated
     * before anything is written from it. False on a version that would forge a pointer key.
     *
     * @throws TooLarge when the version metadata grows past the bound
     */
    private static boolean readVersions(JsonParser parser, Map<String, byte[]> versions) throws IOException {
        if (parser.currentToken() != JsonToken.START_OBJECT) {
            parser.skipChildren();
            return true;
        }
        int remaining = LARGEST_VERSIONS;
        while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
            String version = parser.currentName();
            parser.nextToken();   // advance onto the version's metadata object
            if (Keys.unsafe(version)) {
                return false;
            }
            byte[] metadata = bounded(parser, remaining);
            if (metadata == null) {
                throw new TooLarge("versions");
            }
            remaining -= metadata.length;
            versions.put(version, metadata);
        }
        return true;
    }

    /** The subtree the parser is on as its JSON bytes, or {@code null} when past {@code limit}, read to its end one
     *  token at a time, so an envelope field costs the limit and a token whatever was sent. */
    private static byte[] bounded(JsonParser parser, int limit) throws IOException {
        Bounded out = new Bounded(limit);
        try (JsonGenerator generator = MAPPER.createGenerator(out)) {
            generator.copyCurrentStructure(parser);
        }
        return out.over ? null : out.toByteArray();
    }

    /** A buffer that holds at most its limit, and past it holds nothing and remembers that it was passed. */
    private static final class Bounded extends ByteArrayOutputStream {

        private final int limit;
        private boolean over;

        private Bounded(int limit) {
            this.limit = limit;
        }

        @Override
        public void write(int value) {
            write(new byte[]{(byte) value}, 0, 1);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            if (over) {
                return;
            }
            if ((long) count + length > limit) {
                over = true;
                reset();
                return;
            }
            super.write(bytes, offset, length);
        }
    }

    /** An envelope field past its bound, answered {@code 413} rather than read. */
    private static final class TooLarge extends IOException {

        private TooLarge(String field) {
            super("the publish envelope's " + field + " is past its bound");
        }
    }

    /** Screen and link each attachment's tarball as it streams, base64-decoded through the shared commit, never
     *  buffered or written twice; the filename is validated before its bytes are read. Not safe on an unsafe filename,
     *  still carrying the strongest verdict read so far, since a tarball before it was already judged. */
    private static Attachments readAttachments(JsonParser parser, String name, Blobs blobs, Publication screening,
                                               Set<String> published) throws IOException {
        if (parser.currentToken() != JsonToken.START_OBJECT) {
            parser.skipChildren();
            return new Attachments(true, null);
        }
        Publication.Commit strongest = null;
        while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
            // npm names a scoped tarball after the whole name ("@scope/name-1.0.0.tgz"); it is stored under the
            // unscoped file name the packument URL uses, and any other slash is unsafe.
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
     * The index phase: every write that makes a version discoverable, after the whole envelope is read. One commit per
     * indexable version in envelope order, then the {@code dist-tags} last, so {@code latest} never resolves to a
     * version whose entry has not landed. A version is indexable when its bytes are servable, published now or already
     * stored.
     *
     * <p>These commits pass an explicitly empty chain and observer list: an index document is not an artifact, its
     * tarball was screened as it streamed and its commit already fired the publish event. The operation is used for its
     * ordering and idempotency.
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
            boolean attached = envelope.published().contains(file);
            if (!attached && blobs.exists(versionKey)) {
                // A version without a tarball in this request keeps the document it was published with; a deprecation
                // reaches it through its lifecycle mark.
                continue;
            }
            if (envelope.attestations() != null && attached) {
                // The bundles land before the version is discoverable, so no client reads a version whose attestations
                // are to come.
                blobs.writeRelease(attestationsKey(name, version.getKey()), envelope.attestations());
                version.setValue(withAttestations(version.getValue(), version.getKey(), envelope.attestations()));
            }
            // The release's own document: written where none stands, kept where the same stands, and refused when a
            // re-publish of the tarball carries another.
            indexing.commit(
                    new ArtifactDescriptor("npm", name, version.getKey(), "/npm/" + name,
                            "application/json", version.getKey().contains("-"), null, -1L),
                    new ByteArrayInputStream(version.getValue()), METADATA,
                    _ -> Publication.Visibility.through((hash, size, _) -> blobs.linkRelease(versionKey, hash,
                            size)));
        }
        if (envelope.distTags() != null) {
            indexing.commit(ArtifactDescriptor.at("npm", "/npm/" + name),
                    new ByteArrayInputStream(envelope.distTags()), METADATA,
                    _ -> Publication.Visibility
                            .through((hash, _, _) -> blobs.link("npm/" + name + "/dist-tags", hash)));
        }
        // The packument is maintained on the publish: the servable versions and the dist-tags join the stored document.
        new NpmListings(blobs).published(name, envelope.versions(), envelope.distTags() != null);
    }

    /** Where {@code npm dist-tag} addresses a package's tags: {@code -/package/<name>/dist-tags[/<tag>]}. */
    private static final String DIST_TAGS_API = "-/package/";

    /** {@code npm dist-tag ls|add|rm}: {@code GET -/package/<name>/dist-tags} answers the packument's tags, {@code PUT}
     *  or {@code POST} {@code .../dist-tags/<tag>} with a JSON string points a tag at a listed version, and
     *  {@code DELETE} removes one other than {@code latest}, as npm's registry does. A first change starts from the
     *  tags the packument shows, a computed {@code latest} included. */
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

    /** The dist-tags a packument shows, read without holding its versions; empty when nothing of the package is
     *  listed. */
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

    /** One version as {@code npm publish} sends it: {@code PUT <name>} with the version document, the tarball base64
     *  under {@code _attachments} and the dist-tags pointing at it, streamed. A target already serving the tarball with
     *  the same SHA-256 has it. */
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
                        // The reader sees the pipe close early and the request fails with the target's answer.
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

    /** After a package's last version, every source tag is set as {@code npm dist-tag add} sets it, since a registry
     *  replacing tags on publish keeps only the last version's. */
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

    /** The version an attachment filename carries under the {@code <shortName>-<version>.tgz} convention
     *  ({@link #tarballKey}), or empty when it does not follow it. */
    private static String versionOf(String shortName, String file) {
        String prefix = shortName + "-";
        if (!file.startsWith(prefix) || !file.endsWith(".tgz")) {
            return "";
        }
        return file.substring(prefix.length(), file.length() - ".tgz".length());
    }

    /**
     * Base64-decode the {@code data} string the parser is on through the shared hosted-publish operation, so the
     * tarball is screened and linked in one streamed pass: {@link JsonParser#readBinaryValue} decodes on a helper
     * thread into a pipe consumed by {@code Publication.commit}'s hash-on-write, so it crosses {@code writeBlob} once
     * and is never held.
     *
     * <p>The descriptor is the tarball's own - its download path and, under the conventional filename, its coordinate
     * and version - so a deny-list, a review handle and an inspector key on the artifact; another filename is screened
     * coordinate-less. The accepted layout links the tarball pointer only, and joins the decoder first: a base64 run
     * that broke mid-stream would otherwise store and link a self-consistent truncated tarball. A failed decode
     * declares nothing and is rethrown.
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
                // The body reached EOF; join the decoder before declaring, so a broken decode links nothing.
                join(decoder);
                // Decided inside the pointer's compare-and-set: two first publishes with different bytes both pass the
                // earlier probe, and only the link tells them apart.
                return failure.get() == null
                        ? Publication.Visibility.through((hash, size, _) -> blobs.linkRelease(key, hash, size))
                        : Publication.Visibility.declined();
            });
        } finally {
            // Idempotent after the layout's join, and releases a decoder still pushing when the commit threw before it.
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
        // The held layout, written here because the accepted one never runs on a hold, and after the decode is checked,
        // so a reviewer never releases a truncated tarball. The marker goes first and the pointer after it, so the held
        // tarball is never downloadable; every npm read - download, packument and dist-tags - screens on that marker
        // until HoldLifecycle.release lifts it.
        switch (commit.disposition()) {
            case QUARANTINE -> {
                // A hold never replaces a released tarball: refused before the mark.
                blobs.refuseReplacement(key, commit.hash());
                Withheld.mark(blobs.store(), commit.hash(), descriptor);
                blobs.linkRelease(key, commit.hash(), -1L);
            }
            default -> {
            }
        }
        return commit;
    }

    /** Wait for the base64 decoder, turning an interrupt into an {@code IOException} and keeping the interrupt flag
     *  set. */
    private static void join(Thread decoder) throws IOException {
        try {
            decoder.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while streaming the npm tarball", e);
        }
    }

    private void serveTarball(String name, String file, Blobs blobs, FormatExchange exchange) throws IOException {
        blobs.answer("npm/" + name + "/tarballs/" + file, exchange, "application/octet-stream");
    }

    /** The attestations a version was published with, what a client following {@code dist.attestations.url} fetches; a
     *  version without any is a 404. */
    private void serveAttestations(String name, String version, Blobs blobs, FormatExchange exchange)
            throws IOException {
        if (Keys.unsafe(version)) {
            exchange.respond(404);
            return;
        }
        blobs.answer(attestationsKey(name, version), exchange, "application/json");
    }

    // ---- the signature seam: Sigstore bundles published beside the tarball ----

    /** A tarball may carry Sigstore attestations - CI provenance and the registry's publish attestation - each a bundle
     *  over its digest; optional, and read from the document the publish stored beside the tarball. */
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
        Optional<ArtifactDescriptor> described = describe(path).filter(d -> d.coordinate() != null && d.version() != null);
        if (described.isEmpty()) {
            return List.of();
        }
        String attestationsPath = "/npm/" + described.get().coordinate() + "/-/attestations/" + described.get().version();
        // Whole or nothing: a document cut at the bound would verify as nothing and read as a failed signature.
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

    /** A request path's serving key, for the compliance screen's sibling read: a tarball or a version's attestations,
     *  when the pointer exists. */
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
        // The packument is a stored listing completed with this registry's tarball base on the way out; the ETag folds
        // the base into the document's digest.
        String tarballBase = RequestBase.of(exchange) + exchange.requestUri();
        Optional<StoredListing.Served> served = StoredListing.open(store, new NpmListings(blobs).spec(name));
        if (served.isEmpty()) {
            exchange.respond(404);
            return;
        }
        try (StoredListing.Served document = served.get()) {
            // Streamed with the tarball base folded in: a package's document is every version of it.
            Listings.serve(exchange, document, "application/json", NpmListings.BASE, tarballBase);
        }
    }

    /**
     * The coordinate version a stored npm pointer serves, for the inventory back-fill. Only the metadata pointer
     * {@code npm/<name>/versions/<version>} is read: a tarball filename's split is ambiguous when the short name ends
     * in a digit, and every version has a metadata pointer.
     *
     * <p>Split on the last {@code /versions/}: a scoped {@code @scope/versions} stores
     * {@code npm/@scope/versions/versions/1.0.0}. Screened through {@link BlobLayout#addressable} per part, so a
     * traversal-shaped key decodes to nothing.
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

    /** The tarball pointer key a version's bytes live at, which the packument's screen judges each version by. */
    static String tarballKey(String name, String shortName, String version) {
        return "npm/" + name + "/tarballs/" + shortName + "-" + version + ".tgz";
    }

    /** The npm {@code deprecated} string for a lifecycle flag: the operator's message, else a default naming the state;
     *  npm has no yank, so a yank surfaces as a deprecation. */
    static String deprecation(Lifecycle.Flag flag) {
        if (!flag.message().isEmpty()) {
            return flag.message();
        }
        return flag.state() == LifecycleMark.YANKED
                ? "This version has been yanked."
                : "This version is deprecated.";
    }

    /** Proxy an npm miss to the upstream registry. A tarball ({@code /-/}) is immutable, so it is fetched, cached and
     *  served. A packument is mutable: fetched fresh, each {@code dist.tarball} rewritten to this registry so the
     *  tarball is cached here, with npm's integrity and shasum kept. */
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
            // The packument declares each tarball's dist.integrity (sha512) or dist.shasum (sha1), and the streamed
            // tarball is held to it. The packument is a separate fetch, and one that could not be read must not become
            // an unverified fill.
            URI target = URI.create(root + name + "/-/" + file);
            ProxyRelay.Declared expected = tarballChecksum(root, name, file, fetcher);
            if (!expected.readable()) {
                return ProxyRelay.unverifiable(target, expected);
            }
            // Streamed from the network into the content-addressed store, since a tarball is unbounded.
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
        // The client's validators are forwarded, so a revalidation reaches the origin.
        Map<String, String> request = ProxyRelay.conditionalHeaders(exchange);
        request.put("Accept", "application/json");
        // The packument is the enumeration a resolver decides versions from, so a 404 means "no such package"; only an
        // upstream that answered 404/410 may reach the client as one, and anything else refuses visibly.
        ProxyRelay.Answer answer = ProxyRelay.fetchRemembered(fetcher, URI.create(root + rest), request, exchange,
                ProxyRelay.Document.ENUMERATION, store);
        if (!answer.answered()) {
            return answer.served();
        }
        ProxyRelay.relayValidators(answer.document(), exchange);
        exchange.setResponseHeader("Content-Type", "application/json");
        exchange.respond(200, rewritePackument(answer.document().body(), exchange));
        return true;
    }

    /**
     * The checksum the packument declares for a version's tarball: {@code dist.integrity} ({@code sha512-<base64>},
     * preferred) or {@code dist.shasum} (sha1 hex), the version matched by its {@code dist.tarball} basename. Fetched
     * once per tarball miss.
     *
     * <p>{@link ProxyRelay.Declared#NONE}, caching without a point check, when the registry answered and declares
     * nothing: a {@code 404}/{@code 410} packument, no version for this file, or neither field parseable.
     * {@linkplain ProxyRelay.Declared#unreadable Unreadable} when the packument could not be read: a transport failure,
     * a {@code 429}/{@code 5xx}/challenge, or a {@code 200} that is no packument.
     */
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
                    // A malformed integrity string: fall through to the shasum.
                }
            }
            JsonNode shasum = dist.get("shasum");
            if (shasum != null) {
                byte[] raw = Checksums.parse(shasum.asString(), 20);
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

    /** The migration-import capability, delegated to {@link NpmImporter}. */
    private final NpmImporter importer = new NpmImporter();

    @Override
    public RepositoryImporter importer() {
        return importer;
    }

}
