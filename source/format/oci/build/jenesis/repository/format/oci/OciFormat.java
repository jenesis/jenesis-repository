package build.jenesis.repository.format.oci;

import module java.base;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import build.jenesis.repository.net.Origins;
import build.jenesis.repository.format.ArtifactSignatures;
import build.jenesis.repository.format.BlobReferences;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.Listings;
import build.jenesis.repository.net.PrivateHosts;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.format.ExportTarget;
import build.jenesis.repository.format.RepositoryExporter;
import build.jenesis.repository.store.Retries;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.StoredListing;
import build.jenesis.repository.store.PublishInterceptor;
import build.jenesis.repository.store.Withheld;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import build.jenesis.repository.format.OciTags;
import build.jenesis.repository.format.OciTagIndex;
import build.jenesis.repository.format.Checksums;
import build.jenesis.repository.audit.AuditActions;
import build.jenesis.repository.cleanup.VersionRemoval;
import build.jenesis.repository.store.ServableNames;

/**
 * The OCI / Docker registry format (the {@code /v2/} Distribution API). An OCI blob is addressed by its
 * {@code sha256:<hex>} digest, which is exactly the store's content-addressed {@code blobs/<hex>}, so layers, configs
 * and manifests share storage with everything else. A push uploads blobs (monolithic, or a session of chunks) then a
 * manifest, both stored by digest; a tag is a pointer {@code oci/<name>/tags/<tag>} to a digest; a manifest's media
 * type is kept in a sidecar so a pull returns it verbatim. Stateless: each call is handed the repository's store.
 *
 * <p>It implements {@link BlobReferences} because the only key whose body names a blob is the tag pointer, naming the
 * manifest: config and layer digests live inside the manifest JSON, and a manifest pulled by digest has no pointer.
 * {@link #references} lends the rest, without which a collection would leave a manifest serving over missing layers.
 *
 * <h2>Inbound signatures</h2>
 *
 * {@code cosign sign} pushes a signature as an artifact of its own, tagged {@code sha256-<manifest hex>.sig}: a
 * manifest whose layers are simple-signing payloads naming the image by digest, annotated with the signature, the
 * Fulcio certificate and chain and the transparency-log receipt. As {@link ArtifactSignatures}, a manifest's evidence
 * is each such layer - its annotations the material, the payload the signed document, the digest it names the
 * binding, so a payload naming another image is a finding. The same material attached as a referrer, or a Sigstore
 * bundle, decides the verdict the same way, and one pushed after its image re-derives it through {@link #covers}.
 *
 * <h2>Referrers</h2>
 *
 * A manifest pushed with a {@code subject} is a referrer of that manifest, listed by
 * {@code GET /v2/<name>/referrers/<digest>} from the index {@link OciReferrers} keeps on the write path. The push is
 * answered with {@code OCI-Subject}, so a client does not maintain a tag-schema index itself.
 *
 * <h2>Removal and mount</h2>
 *
 * {@code DELETE} of a manifest or a tag removes versions through the one removal ({@link VersionRemoval}), and an
 * upload naming {@code mount} and {@code from} links a blob the caller may read in another repository of the tenant.
 */
public final class OciFormat implements RepositoryFormat, ProxyFormat, RepositoryImporter.Delegating, BlobReferences,
        ArtifactSignatures, RepositoryExporter {

    /** cosign's tag for the signature artifact of the manifest {@code sha256:<hex>}: {@code sha256-<hex>.sig}. */
    private static final String SIGNATURE_TAG_PREFIX = "sha256-", SIGNATURE_TAG_SUFFIX = ".sig";
    /** The layer annotation carrying the signature over the payload, base64 - the one that makes a layer a signature. */
    private static final String COSIGN_SIGNATURE = "dev.cosignproject.cosign/signature";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The import capability's delegate. */
    private final OciImporter importer = new OciImporter();

    @Override
    public RepositoryImporter importer() {
        return importer;
    }

    static final String OCI_MANIFEST = "application/vnd.oci.image.manifest.v1+json";

    private static final String MANIFEST_ACCEPT = String.join(", ", OCI_MANIFEST,
            "application/vnd.oci.image.index.v1+json",
            "application/vnd.docker.distribution.manifest.v2+json",
            "application/vnd.docker.distribution.manifest.list.v2+json");

    /** The bound on a buffered manifest - pushed, proxied or imported - far above any real manifest or index, so a
     *  hostile body cannot exhaust the heap. */
    static final int MAX_MANIFEST = 4 * 1024 * 1024;

    /** The chunks of an in-flight chunked upload, staged by session id before they are finalized into a blob. */
    private static final String UPLOADS = "oci/.uploads/";

    /** One marker per open upload session, outside the session's numbered chunks and the quota-metered
     *  {@link #UPLOADS} staging; the reaper ages a never-finalized session out by it. */
    private static final String SESSIONS = "oci/.upload-sessions/";

    /** How long an un-finalized upload session - stored bytes counting against the quota - is kept before
     *  {@link #reap} drops it. */
    private static final Duration UPLOAD_SESSION_TTL = Duration.ofHours(24);

    private final Clock clock;
    private final Duration uploadTtl;

    public OciFormat() {
        this(Clock.systemUTC(), UPLOAD_SESSION_TTL);
    }

    /** With the clock and session TTL given, so a test can age a session out without sleeping. */
    public OciFormat(Clock clock, Duration uploadTtl) {
        this.clock = clock;
        this.uploadTtl = uploadTtl;
    }

    @Override
    public String name() {
        return "oci";
    }

    @Override
    public boolean handles(String path) {
        return path.equals("/v2") || path.equals("/v2/") || path.startsWith("/v2/");
    }

    /** The registry API's root, which every OCI client addresses. */
    @Override
    public String mount() {
        return "/v2";
    }

    /**
     * Opts out of the edge screen: a push spans many requests - blob uploads, then a manifest naming them by digest -
     * so no request carries a whole artifact, and the edge would gate each fragment as a publish. The manifest is
     * screened instead ({@link OciManifests}).
     */
    @Override
    public boolean screened() {
        return false;
    }

    @Override
    public void serve(FormatExchange exchange, ArtifactStore store) throws IOException {
        String path = exchange.path();
        if (path.equals("/v2") || path.equals("/v2/")) {
            exchange.setResponseHeader("Docker-Distribution-Api-Version", "registry/2.0");
            exchange.respond(200);
            return;
        }
        String rest = path.substring("/v2/".length());
        if (rest.equals("_catalog")) {
            catalog(store, exchange);
            return;
        }
        if (rest.endsWith("/tags/list")) {
            tags(rest.substring(0, rest.length() - "/tags/list".length()), store, exchange);
            return;
        }
        // The last /referrers/ followed by a digest and nothing else: an image may itself be named .../referrers/...
        int referrers = rest.lastIndexOf("/referrers/");
        if (referrers > 0 && rest.startsWith("sha256:", referrers + "/referrers/".length())
                && rest.indexOf('/', referrers + "/referrers/".length()) < 0) {
            String name = rest.substring(0, referrers);
            if (!isImageName(name) || !(exchange.method().equals("GET") || exchange.method().equals("HEAD"))) {
                exchange.respond(isImageName(name) ? 405 : 404);
                return;
            }
            new OciReferrers(store).serve(name, rest.substring(referrers + "/referrers/".length()), exchange);
            return;
        }
        int uploads = rest.indexOf("/blobs/uploads");
        if (uploads >= 0) {
            upload(rest.substring(0, uploads), rest.substring(uploads + "/blobs/uploads".length()), store, exchange);
            return;
        }
        int blobs = rest.indexOf("/blobs/");
        if (blobs >= 0) {
            blob(rest.substring(blobs + "/blobs/".length()), store, exchange);
            return;
        }
        int manifests = rest.indexOf("/manifests/");
        if (manifests >= 0) {
            manifest(rest.substring(0, manifests), rest.substring(manifests + "/manifests/".length()), store, exchange);
            return;
        }
        exchange.respond(404);
    }

    /**
     * A write the edge refuses before this format sees it - a {@code DELETE} or a push to a repository that takes no
     * write - answered with the Distribution error envelope, {@code UNSUPPORTED} for the {@code 405} the specification
     * names for a registry that does not allow the operation.
     */
    @Override
    public void refuse(FormatExchange exchange, int status) throws IOException {
        if (status == 405) {
            error(exchange, 405, "UNSUPPORTED", "this repository takes no writes: it serves what it holds or fetches");
        } else {
            error(exchange, status, "DENIED", "the request was refused");
        }
    }

    /** A refusal in the Distribution error envelope, so a registry client prints it: {@code DENIED} for a refusal, the
     *  spec's catch-all code for anything else. A hold is an accepted push and answers {@code 202} with no body, since
     *  a registry client reads any body beside a manifest push's answer as an error and fails the push; its sentence
     *  rides a {@code Warning} header instead. */
    @Override
    public void explain(FormatExchange exchange, int status, String sentence) throws IOException {
        if (status < 300) {
            exchange.setResponseHeader("Warning", "199 - \"" + sentence.replaceAll("[\\r\\n\"\\\\]", " ") + "\"");
            exchange.respond(status);
            return;
        }
        error(exchange, status, status == 403 ? "DENIED" : "UNKNOWN", sentence);
    }

    /** An answer in the Distribution error envelope: {@code {"errors":[{"code":...,"message":...}]}}. */
    static void error(FormatExchange exchange, int status, String code, String message) throws IOException {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        exchange.setResponseHeader("Content-Type", "application/json");
        exchange.respond(status, JSON.writeValueAsBytes(Map.of("errors", List.of(error))));
    }

    /** The media type a stored manifest declares in its own {@code mediaType} field, or empty when it declares none or
     *  does not parse - the type a held manifest is served under, since only an admitted one has a sidecar. The
     *  manifest was size-checked when it was ingested, so it is read whole. */
    private static String declaredType(ArtifactStore store, String key) throws IOException {
        try (InputStream in = store.open(key)) {
            JsonNode type = JSON.readTree(in.readNBytes(MAX_MANIFEST)).path("mediaType");
            return type.isString() ? type.stringValue().trim() : "";
        } catch (RuntimeException unreadable) {
            return "";
        }
    }

    private void blob(String digest, ArtifactStore store, FormatExchange exchange) throws IOException {
        String hex = hex(digest);
        if (!ServableNames.isSha256Hex(hex)) {
            // Anything but 64 lowercase hex chars names no blob, and could aim blobs/<hex> at another key space.
            exchange.respond(404);
            return;
        }
        String key = "blobs/" + hex;
            // OCI serves straight from blobs/, so a hold retracts these bytes through the withheld/<hex> marker. A
        // caller that may read held content - a scanner the hold waits on - is still served.
        if (!store.exists(key) || (Withheld.is(store, hex) && !exchange.readsHeld())) {
            exchange.respond(404);
            return;
        }
        long size = store.size(key);
        exchange.setResponseHeader("Docker-Content-Digest", digest);
        exchange.setResponseHeader("Content-Type", "application/octet-stream");
        if (exchange.method().equals("HEAD")) {
            exchange.setResponseHeader("Content-Length", Long.toString(size));
            exchange.respond(200);
            return;
        }
        try (OutputStream out = exchange.respond(200, size)) {
            store.read(key, out);
        }
    }

    private void upload(String name, String session, ArtifactStore store, FormatExchange exchange) throws IOException {
        if (!isImageName(name)) {
            exchange.respond(404);                              // a traversal-laced image name opens no upload session
            return;
        }
        String method = exchange.method();
        if (method.equals("POST")) {
            // Abandoned sessions are reclaimed as a new one opens, with no scheduler; the sweep lists every session, so
            // it runs at most once per REAP_INTERVAL per node.
            reapPaced(store);
            String mount = exchange.queryParameter("mount");
            String from = exchange.queryParameter("from");
            if (mount != null && from != null && mounted(name, mount, from, store, exchange)) {
                return;
            }
            String digest = exchange.queryParameter("digest");
            if (digest != null) {
                store(digest, exchange.requestStream(), store, name, exchange);
                return;
            }
            String id = UUID.randomUUID().toString();
            writeSession(store, id, clock.millis(), 0L, 0L);
            exchange.setResponseHeader("Location", exchange.external("/v2/" + name + "/blobs/uploads/" + id));
            exchange.setResponseHeader("Docker-Upload-UUID", id);
            exchange.setResponseHeader("Range", "0-0");
            exchange.respond(202);
            return;
        }
        String id = session.startsWith("/") ? session.substring(1) : session;
        if (!isImageName(id)) {
            exchange.respond(404);                              // a client-supplied, traversal-laced session id names
            return;                                             // no upload; the id must not aim an oci/.uploads/<id> key
        }
        if (method.equals("PATCH")) {
            long uploaded = append(store, id, exchange.requestStream());
            exchange.setResponseHeader("Location", exchange.external("/v2/" + name + "/blobs/uploads/" + id));
            exchange.setResponseHeader("Docker-Upload-UUID", id);
            exchange.setResponseHeader("Range", "0-" + (uploaded - 1));
            exchange.respond(202);
            return;
        }
        if (method.equals("PUT")) {
            String digest = exchange.queryParameter("digest");
            append(store, id, exchange.requestStream());
            try (InputStream combined = chunks(store, id)) {
                store(digest, combined, store, name, exchange);
            } finally {
                cleanup(store, id);
            }
            return;
        }
        exchange.respond(404);
    }

    /**
     * A cross-repository blob mount - {@code POST .../blobs/uploads/?mount=<digest>&from=<name>} - which links a blob
     * the client already pushed to another repository of this tenant instead of uploading it again: {@code 201} with
     * the blob's location when the caller may read {@code from} and the blob is there, and {@code false} otherwise,
     * so the caller opens an ordinary upload session and the client uploads.
     *
     * <p>Whether the caller may read {@code from} is decided as a {@code GET} of that blob would be
     * ({@link FormatExchange#readable}). Every refusal is the same fallback, so a mount never discloses whether
     * something the caller may not read exists. A blob is stored per repository, so linking is a copy streamed across
     * and held to its digest.
     */
    private boolean mounted(String name, String mount, String from, ArtifactStore store, FormatExchange exchange)
            throws IOException {
        String hex = hex(mount);
        if (!mount.startsWith("sha256:") || !ServableNames.isSha256Hex(hex) || !isImageName(from)) {
            return false;
        }
        Optional<ArtifactStore> source = exchange.readable("/v2/" + from + "/blobs/sha256:" + hex);
        if (source.isEmpty()) {
            return false;
        }
        String key = "blobs/" + hex;
        if (!source.get().exists(key) || Withheld.is(source.get(), hex) || Withheld.is(store, hex)) {
            return false;
        }
        if (!store.exists(key)) {
            try (InputStream in = source.get().open(key)) {
                if (!store.writeBlob(in).equals(hex)) {
                    return false;                               // not the bytes the digest names: upload them instead
                }
            } catch (NoSuchFileException gone) {
                return false;
            }
        }
        exchange.setResponseHeader("Location", exchange.external("/v2/" + name + "/blobs/sha256:" + hex));
        exchange.setResponseHeader("Docker-Content-Digest", "sha256:" + hex);
        exchange.respond(201);
        return true;
    }

    /** Streams one chunk to its own object under the session, indexed by arrival, and advances the running count and
     *  byte total in the session marker, so an N-chunk push costs O(N) store round-trips rather than a re-sum per
     *  {@code PATCH}. Returns the byte total for the {@code Range} header. */
    private long append(ArtifactStore store, String id, InputStream chunk) throws IOException {
        long[] session = session(store, id);
        long timestamp = session[0] == 0L ? clock.millis() : session[0];   // a stray chunk with no POST starts the clock
        long index = session[1];
        store.write("oci/.uploads/" + id + "/" + index, chunk);
        long size = Math.max(store.size("oci/.uploads/" + id + "/" + index), 0L);
        long bytes = session[2] + size;
        writeSession(store, id, timestamp, index + 1, bytes);
        return bytes;
    }

    /** The session marker as {@code [openMillis, chunkCount, byteTotal]}, all-zero when absent. */
    private static long[] session(ArtifactStore store, String id) throws IOException {
        Optional<ArtifactStore.Versioned> marker = store.readVersioned(SESSIONS + id);
        if (marker.isEmpty()) {
            return new long[] {0L, 0L, 0L};
        }
        String[] lines = new String(marker.get().content(), StandardCharsets.UTF_8).trim().split("\n", -1);
        return new long[] {sessionField(lines, 0), sessionField(lines, 1), sessionField(lines, 2)};
    }

    private static long sessionField(String[] lines, int index) {
        if (index >= lines.length) {
            return 0L;
        }
        try {
            return Long.parseLong(lines[index].trim());
        } catch (NumberFormatException malformed) {
            return 0L;
        }
    }

    /** Writes the session marker: the open timestamp on the first line, which {@link #reap} reads, then the chunk
     *  count and byte total. */
    private static void writeSession(ArtifactStore store, String id, long openMillis, long count, long bytes)
            throws IOException {
        store.write(SESSIONS + id, new ByteArrayInputStream(
                (openMillis + "\n" + count + "\n" + bytes).getBytes(StandardCharsets.UTF_8)));
    }

    /** The session's chunks as one stream in arrival order, each opened once the previous is drained, so a layer is
     *  never held in memory. */
    private static InputStream chunks(ArtifactStore store, String id) {
        List<String> indices = new ArrayList<>(store.list("oci/.uploads/" + id));
        indices.sort(Comparator.comparingInt(Integer::parseInt));
        Iterator<String> iterator = indices.iterator();
        return new SequenceInputStream(new Enumeration<>() {
            @Override
            public boolean hasMoreElements() {
                return iterator.hasNext();
            }

            @Override
            public InputStream nextElement() {
                try {
                    return store.open("oci/.uploads/" + id + "/" + iterator.next());
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        });
    }

    /** Drops a session's chunks, then its marker last, so a crash mid-cleanup leaves the marker for the reaper to
     *  retry rather than orphaning the chunks. */
    private static void cleanup(ArtifactStore store, String id) throws IOException {
        for (String index : store.list("oci/.uploads/" + id)) {
            store.delete("oci/.uploads/" + id + "/" + index);
        }
        store.delete(SESSIONS + id);
    }

    /** How often the fresh-upload sweep runs at most, per node; a push inside the interval opens its session without
     *  listing the others. */
    static final Duration REAP_INTERVAL = Duration.ofMinutes(1);

    private final AtomicLong lastReap = new AtomicLong(Long.MIN_VALUE);

    private void reapPaced(ArtifactStore store) throws IOException {
        long now = clock.millis();
        long last = lastReap.get();
        if (last != Long.MIN_VALUE && now - last < REAP_INTERVAL.toMillis()) {
            return;
        }
        if (lastReap.compareAndSet(last, now)) {
            reap(store);
        }
    }

    /** Drops every upload session opened longer ago than the TTL and never finalized; the chunk bytes leave the quota
     *  through {@link #cleanup}'s metered deletes. Returns the number reaped. */
    public int reap(ArtifactStore store) throws IOException {
        Instant cutoff = clock.instant().minus(uploadTtl);
        int reaped = 0;
        for (String id : store.list("oci/.upload-sessions")) {
            Optional<ArtifactStore.Versioned> marker = store.readVersioned(SESSIONS + id);
            if (marker.isEmpty()) {
                continue;
            }
            Instant startedAt;
            try {
                String first = new String(marker.get().content(), StandardCharsets.UTF_8).trim().split("\n", 2)[0];
                startedAt = Instant.ofEpochMilli(Long.parseLong(first.trim()));
            } catch (NumberFormatException malformed) {
                continue; // a marker we cannot read as a timestamp is left for an operator, never blindly reaped
            }
            if (startedAt.isBefore(cutoff)) {
                cleanup(store, id);
                reaped++;
            }
        }
        return reaped;
    }

    // ---- the reference-scan seam (BlobReferences): which blobs a live image keeps alive ----

    @Override
    public List<String> blobRoots() {
        // Every key this format pins a blob under: tag pointers, media-type sidecars and the upload staging.
        return List.of("oci");
    }

    /**
     * The blobs an OCI key keeps alive beyond the one its own body names.
     *
     * <ul>
     *   <li>{@code oci/<name>/tags/<tag>} - the tag pointer, whose body resolves the manifest;</li>
     *   <li>{@code oci/.types/<hex>} - the media-type sidecar, written for every accepted manifest tagged or not, and
     *       so the only lifeline of an image pulled by digest.</li>
     * </ul>
     * Both resolve to a manifest, and from there the image's set: the manifest, an index's sub-manifests (expanded with
     * a work-list, so a hostile nested index cannot overflow the stack), and each one's config, layers and legacy
     * {@code fsLayers}. The upload staging answers empty: {@link #reap} retires it.
     *
     * <p>A root manifest that is present but unparseable or past {@link #MAX_MANIFEST} raises
     * {@link BlobReferences.Unresolvable} naming the key, since a short list would get its layers deleted; only those
     * two sites raise it, and a store failure stays a plain {@link IOException}. A sub-manifest of an index degrades
     * silently: an index entry may legitimately name a layer, which has nothing to lose.
     */
    @Override
    public List<String> references(String key, ArtifactStore store) throws IOException {
        Optional<String> root = manifestOf(key, store);
        if (root.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> hashes = new LinkedHashSet<>();
        hashes.add(root.get());
        Deque<String> pending = new ArrayDeque<>();
        pending.push(root.get());
        Set<String> expanded = new HashSet<>();
        while (!pending.isEmpty()) {
            String hex = pending.pop();
            if (!expanded.add(hex)) {
                continue;                                       // each manifest expanded once (a shared/nested digest)
            }
            JsonNode node = referencedManifest(hex, store, hex.equals(root.get()) ? key : null);
            if (node == null) {
                continue;                                       // absent, or a sub-manifest that is not one
            }
            if (node.has("manifests")) {
                for (JsonNode child : node.path("manifests")) {
                    String digest = referenced(child.path("digest").asString(null));
                    if (digest != null && hashes.add(digest)) {
                        pending.push(digest);                   // its own config/layers, through the work-list
                    }
                }
                continue;
            }
            String config = referenced(node.path("config").path("digest").asString(null));
            if (config != null) {
                hashes.add(config);
            }
            for (JsonNode layer : node.path("layers")) {
                String digest = referenced(layer.path("digest").asString(null));
                if (digest != null) {
                    hashes.add(digest);
                }
            }
            for (JsonNode layer : node.path("fsLayers")) {      // the legacy Docker schema-1 layer shape
                String digest = referenced(layer.path("blobSum").asString(null));
                if (digest != null) {
                    hashes.add(digest);
                }
            }
        }
        return List.copyOf(hashes);
    }

    /** The manifest an {@code oci/} key resolves to, or empty when it names none - never how an unreadable manifest
     *  is reported. */
    private static Optional<String> manifestOf(String key, ArtifactStore store) throws IOException {
        if (!key.startsWith("oci/")) {
            return Optional.empty();
        }
        String rest = key.substring("oci/".length());
        if (rest.startsWith(".types/")) {
            String hex = rest.substring(".types/".length());
            return ServableNames.isSha256Hex(hex) ? Optional.of(hex) : Optional.empty();
        }
        if (rest.startsWith(".uploads/") || rest.startsWith(".upload-sessions/")) {
            return Optional.empty();                            // staged chunks of a push that never became an image
        }
        // An image name is multi-segment, so the tag level is the last /tags/, as a pull resolves it.
        int tags = rest.lastIndexOf("/tags/");
        if (tags < 0) {
            return Optional.empty();
        }
        String hex = store.readVersioned(key)
                .map(versioned -> hex(new String(versioned.content(), StandardCharsets.UTF_8).trim()))
                .orElse(null);
        return hex != null && ServableNames.isSha256Hex(hex) ? Optional.of(hex) : Optional.empty();
    }

    /** A manifest blob for the reference scan, bounded by {@link #MAX_MANIFEST}; {@code rootKey} is the visited key
     *  for the root and {@code null} for a sub-manifest. Absent is {@code null}; a present root that cannot be read
     *  raises {@link BlobReferences.Unresolvable}. */
    private static JsonNode referencedManifest(String hex, ArtifactStore store, String rootKey) throws IOException {
        if (!store.exists("blobs/" + hex)) {
            return null;
        }
        byte[] body;
        try (InputStream in = store.open("blobs/" + hex)) {
            body = in.readNBytes(MAX_MANIFEST + 1);
        }
        if (body.length > MAX_MANIFEST) {
            if (rootKey == null) {
                return null;                                    // an index entry aimed at a layer blob has no children
            }
            throw new BlobReferences.Unresolvable("the manifest " + hex + " that " + rootKey + " serves is past the "
                    + MAX_MANIFEST + "-byte manifest bound, so the blobs it references cannot be enumerated; discard "
                    + "it rather than risk collecting them");
        }
        JsonNode node;
        try {
            node = JSON.readTree(new String(body, StandardCharsets.UTF_8));
        } catch (RuntimeException malformed) {
            node = null;
        }
        // A top-level array or scalar has nothing to enumerate, like a parse failure.
        if (node != null && node.isObject()) {
            return node;
        }
        if (rootKey == null) {
            return null;
        }
        throw new BlobReferences.Unresolvable("the manifest " + hex + " that " + rootKey + " serves is not a parseable "
                + "JSON manifest document, so the blobs it references cannot be enumerated; discard it rather than "
                + "risk collecting them");
    }

    /** The bare hex of a referenced digest, or {@code null} when it is not a sha256 digest. */
    private static String referenced(String digest) {
        if (digest == null) {
            return null;
        }
        String hex = hex(digest);
        return ServableNames.isSha256Hex(hex) ? hex : null;
    }

    private void store(String digest, InputStream content, ArtifactStore store, String name, FormatExchange exchange)
            throws IOException {
        // Digested as it is stored, never buffered whole.
        String hex = store.writeBlob(content);
        if (digest != null && !hex.equals(hex(digest))) {
            exchange.respond(400);
            return;
        }
        exchange.setResponseHeader("Location", exchange.external("/v2/" + name + "/blobs/sha256:" + hex));
        exchange.setResponseHeader("Docker-Content-Digest", "sha256:" + hex);
        exchange.respond(201);
    }

    private void manifest(String name, String reference, ArtifactStore store, FormatExchange exchange)
            throws IOException {
        if (!isImageName(name)) {
            exchange.respond(404);                              // a traversal-laced image name names no manifest
            return;
        }
        if (exchange.method().equals("DELETE")) {
            delete(name, reference, store, exchange);
            return;
        }
        if (exchange.method().equals("PUT")) {
            if (!reference.startsWith("sha256:") && !OciTags.isTag(reference)) {
                // Neither a digest nor a tag: it would become a tags/<ref> key aimed at a neighbouring space.
                exchange.respond(400);
                return;
            }
            // Buffered for the screen, bounded: one byte past the cap is read to detect an overflow.
            byte[] body = exchange.requestStream().readNBytes(MAX_MANIFEST + 1);
            if (body.length > MAX_MANIFEST) {
                exchange.setResponseHeader("Content-Type", "application/json");
                exchange.respond(413, ("{\"errors\":[{\"code\":\"MANIFEST_INVALID\",\"message\":"
                        + "\"manifest exceeds the " + MAX_MANIFEST + "-byte limit\"}]}").getBytes(StandardCharsets.UTF_8));
                return;
            }
            OciManifests.Ingested ingested;
            try {
                ingested = OciManifests.ingest(
                        name, reference, body, exchange.requestHeader("Content-Type"), store);
            } catch (OciManifests.InvalidManifest invalid) {
                exchange.setResponseHeader("Content-Type", "application/json");
                exchange.respond(400, ("{\"errors\":[{\"code\":\"MANIFEST_INVALID\",\"message\":"
                        + "\"the manifest is not a valid JSON manifest\"}]}").getBytes(StandardCharsets.UTF_8));
                return;
            }
            String hex = ingested.hex();
            // A push by digest must hash to that digest.
            if (reference.startsWith("sha256:") && !reference.substring("sha256:".length()).equalsIgnoreCase(hex)) {
                exchange.setResponseHeader("Content-Type", "application/json");
                exchange.respond(400, ("{\"errors\":[{\"code\":\"MANIFEST_INVALID\",\"message\":"
                        + "\"the manifest body does not hash to the referenced digest\"}]}").getBytes(StandardCharsets.UTF_8));
                return;
            }
            ingested.subject().ifPresent(subject -> exchange.setResponseHeader("OCI-Subject", "sha256:" + subject));
            switch (ingested.disposition()) {
                case ACCEPT -> {
                    exchange.setResponseHeader("Docker-Content-Digest", "sha256:" + hex);
                    exchange.setResponseHeader("Location", exchange.external("/v2/" + name + "/manifests/sha256:" + hex));
                    exchange.respond(201);
                }
                case QUARANTINE -> {
                    // Held for review: accepted, but withheld from serving until released.
                    exchange.setResponseHeader("Docker-Content-Digest", "sha256:" + hex);
                    explain(exchange, 202, ingested.explanation());
                }
                case REJECT -> explain(exchange, 403, ingested.explanation());
            }
            return;
        }
        String hex;
        if (reference.startsWith("sha256:")) {
            hex = reference.substring("sha256:".length());
        } else {
            if (!OciTags.isTag(reference)) {
                exchange.respond(404);                          // a '/'- or '..'-laced tag names no pointer (symmetric
                return;                                         // with the PUT path's guard - never a raw store key)
            }
            Optional<ArtifactStore.Versioned> pointer = store.readVersioned("oci/" + name + "/tags/" + reference);
            if (pointer.isEmpty()) {
                exchange.respond(404);
                return;
            }
            hex = hex(new String(pointer.get().content(), StandardCharsets.UTF_8).trim());
        }
        if (!ServableNames.isSha256Hex(hex)) {
            exchange.respond(404);
            return;
        }
        String key = "blobs/" + hex;
        // A manifest serves while its media-type sidecar exists, and a withheld one 404s by digest and by tag, as a
        // withheld blob does. A held manifest has no sidecar and is served by digest only to a caller that may read
        // held content, under the type it declares of itself.
        Optional<ArtifactStore.Versioned> sidecar = store.readVersioned("oci/.types/" + hex);
        boolean withheld = Withheld.is(store, hex);
        boolean held = withheld && reference.startsWith("sha256:") && exchange.readsHeld();
        if (!store.exists(key) || (withheld && !held) || (sidecar.isEmpty() && !held)) {
            exchange.respond(404);
            return;
        }
        String recorded = sidecar.isPresent()
                ? new String(sidecar.get().content(), StandardCharsets.UTF_8).trim()
                : declaredType(store, key);
        String type = recorded.isEmpty() ? OCI_MANIFEST : recorded;
        long size = store.size(key);
        exchange.setResponseHeader("Content-Type", type);
        exchange.setResponseHeader("Docker-Content-Digest", "sha256:" + hex);
        if (exchange.method().equals("HEAD")) {
            exchange.setResponseHeader("Content-Length", Long.toString(size));
            exchange.respond(200);
            return;
        }
        try (OutputStream out = exchange.respond(200, size)) {
            store.read(key, out);
        }
    }

    /**
     * {@code DELETE /v2/<name>/manifests/<reference>}: a tag removes that tag alone, a digest the manifest and every tag
     * naming it, each version through the one removal ({@link VersionRemoval}) retention uses, leaving the blobs to the
     * collector. Answered {@code 202} and audited.
     *
     * <p>A reference that does not serve - absent or held - is {@code 404 MANIFEST_UNKNOWN}, as a pull is answered, so
     * a delete discloses nothing. A pinned version is {@code 403 DENIED} until an operator unpins it. With no inventory
     * installed the answer is {@code 405 UNSUPPORTED}. The tags naming a digest come from {@link OciTagIndex},
     * confirmed per pointer, so the delete reads nothing that grows with the repository.
     */
    private void delete(String name, String reference, ArtifactStore store, FormatExchange exchange)
            throws IOException {
        VersionRemoval removal = Removal.INSTALLED;
        if (!removal.supported()) {
            error(exchange, 405, "UNSUPPORTED", "this deployment removes no version: no inventory is installed");
            return;
        }
        boolean digest = reference.startsWith("sha256:");
        if (digest ? !ServableNames.isSha256Hex(hex(reference)) : !OciTags.isTag(reference)) {
            error(exchange, digest ? 400 : 404, digest ? "DIGEST_INVALID" : "MANIFEST_UNKNOWN",
                    "not a manifest reference: " + reference);
            return;
        }
        String hex;
        List<String> tags = new ArrayList<>();
        if (digest) {
            hex = hex(reference);
            if (!store.exists("oci/.types/" + hex)) {
                hex = null;
            } else {
                for (OciTagIndex.Tag tag : OciTagIndex.current(store, hex)) {
                    if (tag.name().equals(name)) {
                        tags.add(tag.tag());
                    }
                }
            }
        } else {
            hex = store.readVersioned("oci/" + name + "/tags/" + reference)
                    .map(pointer -> hex(new String(pointer.content(), StandardCharsets.UTF_8).trim()))
                    .filter(ServableNames::isSha256Hex)
                    .orElse(null);
            tags.add(reference);
        }
        if (hex == null || Withheld.is(store, hex)) {
            error(exchange, 404, "MANIFEST_UNKNOWN", "manifest unknown: " + reference);
            return;
        }
        List<String> versions = new ArrayList<>(tags);
        if (digest) {
            versions.add("sha256:" + hex);
        }
        for (String version : versions) {
            if (removal.pinned(store, ecosystem(), name, version)) {
                error(exchange, 403, "DENIED", name + ":" + version + " is pinned; an operator unpins it before it "
                        + "can be deleted");
                return;
            }
        }
        for (String version : versions) {
            removal.remove(store, ecosystem(), name, version);
        }
        for (String tag : tags) {
            OciTagIndex.retire(store, "oci/" + name + "/tags/" + tag, hex);
        }
        if (digest) {
            new OciReferrers(store).forget(name, hex);
        }
        exchange.audit(AuditActions.ARTIFACT_DELETE, digest ? name + "@sha256:" + hex : name + ":" + reference);
        exchange.respond(202);
    }

    /** The installed removal, resolved once: {@link VersionRemoval#installed} runs the discovery on every call. */
    private static final class Removal {
        static final VersionRemoval INSTALLED = VersionRemoval.installed();
    }

    /**
     * {@code GET /v2/<name>/tags/list} with the optional {@code n} and {@code last} paging, cut from the image's stored
     * tag list ({@link OciListings}), from which a hold retracts a tag, so a held tag is never disclosed.
     */
    private void tags(String name, ArtifactStore store, FormatExchange exchange) throws IOException {
        if (!isImageName(name)) {
            exchange.respond(404);                              // a traversal-laced image name lists no tags
            return;
        }
        Integer limit = pageSize(exchange);
        if (limit == null) {
            return;                                             // a non-numeric or non-positive n is a 400, already sent
        }
        String parameter = exchange.queryParameter("last");
        String last = parameter == null || parameter.isEmpty() ? null : parameter;   // ?last= is "from the beginning"
        if (last != null && !OciTags.isTag(last)) {
            exchange.respond(400);
            return;
        }
        // Streamed and stopped at the window's edge, so a window costs its names.
        Optional<StoredListing.Served> served = StoredListing.open(store, new OciListings(store).tagsSpec(name));
        if (exchange.queryParameter("n") == null) {
            stream(exchange, served, "tags", name);
            return;
        }
        List<String> tags = new ArrayList<>();
        boolean[] more = {false};
        if (served.isPresent()) {
            try (StoredListing.Served document = served.get()) {
                OciListings.names(document.body(), "tags", tag -> {
                    if (last != null && tag.compareTo(last) <= 0) {
                        return true;                            // still before the client's window
                    }
                    if (tags.size() == limit) {
                        more[0] = true;
                        return false;                           // the window is full - stop reading here
                    }
                    tags.add(tag);
                    return true;
                });
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", addressed(exchange, name));
        body.put("tags", tags);
        if (more[0]) {
            String size = exchange.queryParameter("n") == null ? "" : "n=" + limit + "&";
            exchange.setResponseHeader("Link", "<" + exchange.external("/v2/" + name + "/tags/list") + "?" + size
                    + "last=" + URLEncoder.encode(tags.getLast(), StandardCharsets.UTF_8) + ">; rel=\"next\"");
        }
        exchange.setResponseHeader("Content-Type", "application/json");
        exchange.respond(200, JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The image's name as the client addressed it. A repository's images are named within it, and the client names
     * the repository in front - {@code /v2/<repository>/<image>} - so the name a tag list reports is the one the
     * request carried, put back together by the exchange exactly as a {@code Location} is.
     */
    private static String addressed(FormatExchange exchange, String name) {
        String external = exchange.external("/v2/" + name);
        int at = external.indexOf("/v2/");
        return at < 0 ? name : external.substring(at + "/v2/".length());
    }

    /**
     * The whole of a stored name listing, written to the socket as the names arrive.
     *
     * <p>The answer to an unqualified {@code tags/list} or {@code _catalog}, which the specification says returns every
     * name. It has no next page to name in a {@code Link}, so nothing need be gathered before the body starts.
     *
     * <p>{@code name} is the image the tags belong to, written as the document's {@code name} member; the catalog
     * names no subject and passes {@code null}.
     */
    private static void stream(FormatExchange exchange, Optional<StoredListing.Served> served, String member,
                               String name) throws IOException {
        if (served.isEmpty()) {
            // Nothing stored: the empty document, handed over whole.
            Map<String, Object> body = new LinkedHashMap<>();
            if (name != null) {
                body.put("name", addressed(exchange, name));
            }
            body.put(member, List.of());
            exchange.setResponseHeader("Content-Type", "application/json");
            exchange.respond(200, JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8));
            return;
        }
        // Rendered from the stored document, so the length is unknown, but its sha256 is the validator.
        try (StoredListing.Served document = served.get()) {
            Listings.serve(exchange, document, "application/json", -1L, out -> {
                try (JsonGenerator json = JSON.createGenerator(out)) {
                    json.writeStartObject();
                    if (name != null) {
                        json.writeStringProperty("name", addressed(exchange, name));
                    }
                    json.writeArrayPropertyStart(member);
                    OciListings.names(document.body(), member, entry -> {
                        json.writeString(entry);
                        return true;
                    });
                    json.writeEndArray();
                    json.writeEndObject();
                }
            });
        }
    }

    /** The {@code n} page size of {@code tags/list} and {@code _catalog}: absent is {@link Integer#MAX_VALUE}, and a
     *  non-numeric or non-positive one is answered {@code 400}, after which this returns {@code null}. */
    private static Integer pageSize(FormatExchange exchange) throws IOException {
        String limit = exchange.queryParameter("n");
        int page;
        try {
            page = limit == null ? Integer.MAX_VALUE : Integer.parseInt(limit);
        } catch (NumberFormatException invalid) {
            exchange.respond(400);
            return null;
        }
        if (page <= 0) {
            exchange.respond(400);
            return null;
        }
        return page;
    }

    /**
     * {@code GET /v2/_catalog}: every image name with a servable tag, in lexicographic order, with {@code n} and
     * {@code last} paging, cut from the stored catalog ({@link OciListings}) rather than a walk of the name tree.
     */
    private void catalog(ArtifactStore store, FormatExchange exchange) throws IOException {
        Integer limit = pageSize(exchange);
        if (limit == null) {
            return;                                             // a non-numeric or non-positive n is a 400, already sent
        }
        String last = exchange.queryParameter("last");
        Optional<StoredListing.Served> served = StoredListing.open(store, new OciListings(store).catalogSpec());
        if (exchange.queryParameter("n") == null) {
            stream(exchange, served, "repositories", null);
            return;
        }
        List<String> repositories = new ArrayList<>();
        boolean[] more = {false};
        if (served.isPresent()) {
            try (StoredListing.Served document = served.get()) {
                OciListings.names(document.body(), "repositories", name -> {
                    if (last != null && !last.isEmpty() && name.compareTo(last) <= 0) {
                        return true;                            // still before the client's window
                    }
                    if (repositories.size() == limit) {
                        more[0] = true;
                        return false;                           // the window is full - stop reading here
                    }
                    repositories.add(name);
                    return true;
                });
            }
        }
        if (more[0]) {
            exchange.setResponseHeader("Link", "<" + exchange.external("/v2/_catalog") + "?n=" + limit + "&last="
                    + URLEncoder.encode(repositories.getLast(), StandardCharsets.UTF_8) + ">; rel=\"next\"");
        }
        exchange.setResponseHeader("Content-Type", "application/json");
        exchange.respond(200, JSON.writeValueAsString(Map.of("repositories", repositories))
                .getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Proxy a {@code /v2/} manifest or blob miss to the upstream registry (Docker Hub by default). Blobs and
     * manifests are immutable by digest, so they are stored exactly as a push would and re-served locally; a
     * manifest by tag also records the tag pointer. Authentication follows the Distribution token flow: a
     * {@code 401} carries a {@code Bearer} challenge, the realm is exchanged for a token, and the fetch is retried.
     * The client {@code Accept} is forwarded so the upstream returns the right manifest media type (and image
     * index for multi-arch, whose per-architecture manifests are then proxied by digest in turn).
     */
    /** A registry's manifests and blobs are the upstream's; a tag list is relayed, never merged with what is held here. */
    @Override
    public boolean mergesUpstream(FormatExchange exchange) {
        return false;
    }

    @Override
    public boolean proxy(FormatExchange exchange, ArtifactStore store, URI upstream, ProxyFormat.Fetcher fetcher)
            throws IOException {
        String path = exchange.path();
        if (!path.startsWith("/v2/")) {
            return false;
        }
        String rest = path.substring("/v2/".length());
        int blobs = rest.indexOf("/blobs/");
        if (blobs >= 0 && !rest.contains("/blobs/uploads")) {
            return proxyDigest(rest.substring(0, blobs), rest.substring(blobs + "/blobs/".length()), false,
                    null, exchange, store, upstream, fetcher);
        }
        int manifests = rest.indexOf("/manifests/");
        if (manifests >= 0) {
            return proxyDigest(rest.substring(0, manifests), rest.substring(manifests + "/manifests/".length()), true,
                    exchange.requestHeader("Accept"), exchange, store, upstream, fetcher);
        }
        return false;
    }

    private boolean proxyDigest(String name, String reference, boolean manifest, String accept,
                                FormatExchange exchange, ArtifactStore store, URI upstream, ProxyFormat.Fetcher fetcher)
            throws IOException {
        if (!isImageName(name)) {
            return false;                                       // the guard every other leg carries
        }
        URI url = upstreamUrl(upstream, name, (manifest ? "/manifests/" : "/blobs/") + reference);
        if (manifest) {
            return proxyManifest(name, reference, accept, exchange, store, url, fetcher);
        }
        // A sha256 reference holds the fetched bytes to it: on a mismatch they stay under their own hash, unlinked,
        // and nothing is served. A digest in another algorithm cannot be a store key, so the serve path 404s it.
        Optional<ProxyFormat.Download> fetched = download(url, accept, fetcher);
        if (fetched.isEmpty()) {
            return false;
        }
        try (ProxyFormat.Download download = fetched.get()) {
            if (download.status() != 200) {
                return false;
            }
            String hex = store.writeBlob(download.body());
            if (reference.startsWith("sha256:") && !hex.equals(hex(reference))) {
                return false;
            }
        }
        handle(exchange, store);
        return true;
    }

    /**
     * Where an image path is asked for upstream: the registry API at the upstream's root, with the upstream's own path
     * as a namespace ahead of the image's name. {@code https://ghcr.io/homebrew/core} proxies ghcr.io's
     * {@code homebrew/core/<name>} - which is what lets Homebrew's bottles, stored there as OCI blobs, pull through a
     * repository whose images are named by formula alone. A leading {@code v2} segment is the API's own prefix written
     * into the URL rather than a namespace, and is dropped, so {@code https://registry/v2/acme} names {@code acme}; an
     * upstream at the root names none, as it always did.
     */
    static URI upstreamUrl(URI upstream, String name, String rest) {
        String namespace = upstream.getRawPath() == null ? "" : upstream.getRawPath().replaceAll("^/+|/+$", "");
        if (namespace.equals("v2")) {
            namespace = "";
        } else if (namespace.startsWith("v2/")) {
            namespace = namespace.substring("v2/".length());
        }
        return URI.create(upstream.getScheme() + "://" + upstream.getRawAuthority() + "/v2/"
                + (namespace.isEmpty() ? "" : namespace + "/") + name + rest);
    }

    /** A manifest, fetched buffered - it is small and its media type comes from the response headers - and ingested
     *  as a push is. */
    private boolean proxyManifest(String name, String reference, String accept, FormatExchange exchange,
                                  ArtifactStore store, URI url, ProxyFormat.Fetcher fetcher) throws IOException {
        Optional<ProxyFormat.Fetched> fetched = fetch(url, accept, fetcher);
        if (fetched.isEmpty() || fetched.get().status() != 200) {
            return false;
        }
        byte[] body = fetched.get().body();
        String hex = Checksums.sha256(body);
        // The received manifest is held to every digest knowable here, letting the local 404 stand on a mismatch:
        // the reference itself when pulled by digest,
        if (reference.startsWith("sha256:") && !hex.equals(hex(reference))) {
            return false;
        }
        // and the upstream's Docker-Content-Digest when pulled by tag. Without that header nothing is verifiable and
        // the upstream is trusted; a later by-digest pull is verified against the digest ingest records.
        String contentDigest = fetched.get().header("Docker-Content-Digest");
        if (contentDigest != null && contentDigest.startsWith("sha256:") && !hex.equals(hex(contentDigest))) {
            return false;
        }
        if (!reference.startsWith("sha256:") && !OciTags.isTag(reference)) {
            return false; // a non-tag reference must not become a tags/ store key - let the local 404 stand
        }
        // Screened as a push is; the local serve that follows is the response, so a withheld manifest 404s.
        try {
            OciManifests.ingest(name, reference, body, fetched.get().header("Content-Type"), store);
        } catch (OciManifests.InvalidManifest invalid) {
            // An oversized or unparseable upstream manifest is served through without being stored.
            String type = fetched.get().header("Content-Type");
            if (type != null) {
                exchange.setResponseHeader("Content-Type", type);
            }
            exchange.setResponseHeader("Docker-Content-Digest", "sha256:" + hex);
            exchange.respond(200, body);
            return true;
        }
        handle(exchange, store);
        return true;
    }

    /** A buffered fetch through the bearer flow: on a {@code 401} challenge, the realm is exchanged for a token and the
     *  fetch retried once. */
    private Optional<ProxyFormat.Fetched> fetch(URI url, String accept, ProxyFormat.Fetcher fetcher) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        if (accept != null) {
            headers.put("Accept", accept);
        }
        Optional<ProxyFormat.Fetched> first = fetcher.fetch(url, headers);
        if (first.isEmpty() || first.get().status() != 401) {
            return first;
        }
        String challenge = first.get().header("WWW-Authenticate");
        if (challenge == null || !challenge.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
            return first;
        }
        String token = token(challenge.substring("Bearer ".length()), fetcher, url.getHost());
        if (token == null) {
            return first;
        }
        headers.put("Authorization", "Bearer " + token);
        return fetcher.fetch(url, headers);
    }

    /** A streamed download through the bearer flow; empty when the fetch fails or the challenge cannot be met, so the
     *  local 404 stands. */
    private Optional<ProxyFormat.Download> download(URI url, String accept, ProxyFormat.Fetcher fetcher)
            throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        if (accept != null) {
            headers.put("Accept", accept);
        }
        Optional<ProxyFormat.Download> first = fetcher.download(url, headers);
        if (first.isEmpty() || first.get().status() != 401) {
            return first;
        }
        String token;
        try (ProxyFormat.Download unauthorized = first.get()) {
            String challenge = unauthorized.header("WWW-Authenticate");
            if (challenge == null || !challenge.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
                return Optional.empty();
            }
            token = token(challenge.substring("Bearer ".length()), fetcher, url.getHost());
        }
        if (token == null) {
            return Optional.empty();
        }
        headers.put("Authorization", "Bearer " + token);
        return fetcher.download(url, headers);
    }

    /**
     * The registry root an enumeration's requests go under: {@code <source>/v2/} for a registry named by its host,
     * and the source itself when it already names one - {@code https://host/v2/<repository>/}, a Jenesis repository's
     * own registry, whose catalog, tag lists and manifests all sit under it and whose images are named within it.
     */
    private static String registry(URI base) {
        String root = base.toString();
        return root.contains("/v2/") ? root : root + "v2/";
    }

    /**
     * Walks an upstream registry through its own index: the paged {@code _catalog}, each image's paged
     * {@code tags/list}, and each tagged manifest expanded so every blob and child manifest is emitted before the
     * manifest that references it. The stream is lazy, and digests are deduplicated through a bounded
     * {@link BoundedDigests}, so the import's heap does not grow with the source registry. A registry that disables
     * the catalog (Docker Hub does) fails the walk up front.
     */
    @Override
    public Stream<Coordinate> enumerate(ProxyFormat.Fetcher fetcher, URI upstream) throws IOException {
        String root = upstream.toString();
        URI base = URI.create(root.endsWith("/") ? root : root + "/");
        Iterator<String> repositories = paged(base, URI.create(registry(base) + "_catalog"), "repositories", fetcher);
        BoundedDigests emitted = new BoundedDigests();
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(repositories, Spliterator.ORDERED), false)
                .flatMap(name -> {
                    try {
                        Iterator<String> tags = paged(base, URI.create(registry(base) + name + "/tags/list"), "tags",
                                fetcher);
                        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(tags, Spliterator.ORDERED), false)
                                .map(tag -> Map.entry(name, tag));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                })
                .flatMap(tagged -> {
                    try {
                        List<Coordinate> coordinates = new ArrayList<>();
                        expand(base, tagged.getKey(), manifest(base, tagged.getKey(), tagged.getValue(), fetcher),
                                coordinates, emitted, fetcher);
                        coordinates.add(new Coordinate("v2/" + tagged.getKey() + "/manifests/" + tagged.getValue(),
                                URI.create(registry(base) + tagged.getKey() + "/manifests/" + tagged.getValue()),
                                Map.of("Accept", MANIFEST_ACCEPT)));
                        return coordinates.stream();
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
    }

    /**
     * Add the coordinates one manifest transitively references, depth-first: an index's per-platform manifests (each
     * expanded then added by digest), a manifest's config and layers as blobs - each digest once per walk.
     *
     * <p>Walked with an explicit work-list, since a hostile upstream controls how deeply indices nest. The
     * {@link Step}s keep post-order: everything a manifest references is emitted before it.
     */
    private void expand(URI base, String name, byte[] manifest, List<Coordinate> coordinates, BoundedDigests emitted,
                        ProxyFormat.Fetcher fetcher) throws IOException {
        Deque<Step> pending = new ArrayDeque<>();
        pending.push(new Step(manifest, null, null));
        while (!pending.isEmpty()) {
            Step step = pending.pop();
            if (step.emit() != null) {
                coordinates.add(step.emit());               // the trailing manifest coordinate, after its subtree
                continue;
            }
            byte[] body = step.manifest() != null ? step.manifest() : manifest(base, name, step.digest(), fetcher);
            JsonNode node = JSON.readTree(new String(body, StandardCharsets.UTF_8));
            if (node.has("manifests")) {
                List<JsonNode> children = new ArrayList<>();
                node.path("manifests").forEach(children::add);
                // Push children in reverse so the first is walked first; per child its emit sits under its expand, so
                // the nested manifest's coordinate is added after everything that manifest references.
                for (int index = children.size() - 1; index >= 0; index--) {
                    String digest = children.get(index).path("digest").asString(null);
                    if (digest == null || !emitted.add(digest)) {
                        continue;
                    }
                    pending.push(new Step(null, null, new Coordinate("v2/" + name + "/manifests/" + digest,
                            URI.create(registry(base) + name + "/manifests/" + digest),
                            Map.of("Accept", MANIFEST_ACCEPT))));
                    pending.push(new Step(null, digest, null));
                }
                continue;
            }
            String config = node.path("config").path("digest").asString(null);
            if (config != null && emitted.add(config)) {
                coordinates.add(blob(base, name, config));
            }
            for (JsonNode layer : node.path("layers")) {
                String digest = layer.path("digest").asString(null);
                if (digest != null && emitted.add(digest)) {
                    coordinates.add(blob(base, name, digest));
                }
            }
            for (JsonNode layer : node.path("fsLayers")) {
                String digest = layer.path("blobSum").asString(null);
                if (digest != null && emitted.add(digest)) {
                    coordinates.add(blob(base, name, digest));
                }
            }
        }
    }

    /**
     * The bounded dedup an upstream enumeration remembers digests in: a fixed-capacity, least-recently-used
     * set of the blob and by-digest-manifest digests already emitted, so the walk still skips the layers tags share
     * without retaining one entry per digest of the <em>source registry</em> for the whole import.
     *
     * <p>Eviction costs only a re-fetch: an evicted digest is emitted again and re-stored, an idempotent
     * content-addressed write. Least-recently-used, so a base layer many tags share stays resident.
     */
    private static final class BoundedDigests {

        /** How many digests one enumeration remembers - a few tens of megabytes. */
        private static final int CAPACITY = 50_000;

        private final Cache<String, Boolean> seen = Caffeine.newBuilder().maximumSize(CAPACITY).build();

        /** Remembers {@code digest} and reports whether it is new, as {@code Set#add} does - an evicted one reads as
         *  new again. */
        private boolean add(String digest) {
            return seen.asMap().putIfAbsent(digest, Boolean.TRUE) == null;
        }
    }

    /** One step of the iterative {@link #expand} walk: exactly one field is set - {@code manifest} for the already-
     *  fetched root body, {@code digest} for a nested manifest to fetch and expand when it is popped, or {@code emit}
     *  for a manifest coordinate to add once its subtree has been emitted. */
    private record Step(byte[] manifest, String digest, Coordinate emit) {
    }

    private static Coordinate blob(URI base, String name, String digest) {
        return new Coordinate("v2/" + name + "/blobs/" + digest,
                URI.create(registry(base) + name + "/blobs/" + digest));
    }

    /** One manifest, by tag or digest, negotiated with the manifest media types and fetched buffered (a manifest is
     *  small metadata) through the bearer-challenge flow. */
    private byte[] manifest(URI base, String name, String reference, ProxyFormat.Fetcher fetcher) throws IOException {
        URI url = URI.create(registry(base) + name + "/manifests/" + reference);
        Optional<ProxyFormat.Fetched> fetched = fetch(url, MANIFEST_ACCEPT, fetcher);
        if (fetched.isEmpty()) {
            throw new IOException("No response from " + url);
        }
        if (fetched.get().status() != 200) {
            throw new IOException("Manifest fetch failed (" + fetched.get().status() + ") for " + url);
        }
        return fetched.get().body();
    }

    /** Iterate one string-array field across the Distribution API's pages, following each page's
     *  {@code Link; rel="next"}. The first page is read eagerly (so an unreachable or catalog-less source fails
     *  the walk up front); later pages are read as the iteration reaches them. */
    private Iterator<String> paged(URI origin, URI first, String field, ProxyFormat.Fetcher fetcher) throws IOException {
        Page initial = page(origin, first, field, fetcher);
        return new Iterator<>() {
            private Page current = initial;
            private int index;

            @Override
            public boolean hasNext() {
                while (index == current.values().size() && current.next() != null) {
                    try {
                        current = page(origin, current.next(), field, fetcher);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    index = 0;
                }
                return index < current.values().size();
            }

            @Override
            public String next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return current.values().get(index++);
            }
        };
    }

    private record Page(List<String> values, URI next) {
    }

    private Page page(URI origin, URI url, String field, ProxyFormat.Fetcher fetcher) throws IOException {
        // The next page comes from the upstream's Link header and reaches the fetcher as an initial request, which its
        // redirect screen never inspects, so a cross-origin page aimed at a private host is refused here.
        if (!Origins.same(origin, url) && PrivateHosts.resolvesToPrivate(url.getHost())) {
            throw new IOException("Refusing a cross-origin catalog/tags page to a private/loopback host: " + url);
        }
        Optional<ProxyFormat.Fetched> fetched = fetch(url, "application/json", fetcher);
        if (fetched.isEmpty()) {
            throw new IOException("No response from " + url);
        }
        if (fetched.get().status() != 200) {
            throw new IOException("Index fetch failed (" + fetched.get().status() + ") for " + url);
        }
        List<String> values = new ArrayList<>();
        for (JsonNode value : JSON.readTree(new String(fetched.get().body(), StandardCharsets.UTF_8)).path(field)) {
            String name = value.asString(null);
            if (name != null) {
                values.add(name);
            }
        }
        String link = fetched.get().header("Link");
        URI next = null;
        if (link != null && link.contains("rel=\"next\"")) {
            int open = link.indexOf('<');
            int close = link.indexOf('>');
            if (open >= 0 && close > open) {
                next = url.resolve(link.substring(open + 1, close));
            }
        }
        return new Page(List.copyOf(values), next);
    }

    private String token(String challenge, ProxyFormat.Fetcher fetcher, String upstreamHost) throws IOException {
        Map<String, String> params = new LinkedHashMap<>();
        for (String part : challenge.split(",")) {
            int equals = part.indexOf('=');
            if (equals > 0) {
                params.put(part.substring(0, equals).trim(),
                        part.substring(equals + 1).trim().replace("\"", ""));
            }
        }
        String realm = params.get("realm");
        if (realm == null) {
            return null;
        }
        StringBuilder url = new StringBuilder(realm);
        char separator = '?';
        for (String key : new String[]{"service", "scope"}) {
            String value = params.get(key);
            if (value != null) {
                url.append(separator).append(key).append('=')
                        .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
                separator = '&';
            }
        }
        URI realmUri;
        try {
            realmUri = URI.create(url.toString());
        } catch (IllegalArgumentException malformed) {
            return null;   // a realm that is not a valid URI cannot be exchanged for a token
        }
        // The upstream names the realm. The configured host's own realm may be private (an internal mirror); another
        // host resolving privately would steer the proxy into its own network, and gets no token.
        String realmHost = realmUri.getHost();
        if (realmHost == null
                || (!realmHost.equalsIgnoreCase(upstreamHost) && PrivateHosts.resolvesToPrivate(realmHost))) {
            return null;
        }
        Optional<ProxyFormat.Fetched> response = fetcher.beside().fetch(realmUri, Map.of());
        if (response.isEmpty() || response.get().status() != 200) {
            return null;
        }
        JsonNode token = JSON.readTree(new String(response.get().body(), StandardCharsets.UTF_8));
        String bearer = token.path("token").asString(null);
        return bearer != null ? bearer : token.path("access_token").asString(null);
    }

    // ---- inbound signatures ----

    @Override
    public String ecosystem() {
        // The ecosystem the manifest screen's descriptors and the inventory layout use.
        return "oci";
    }

    @Override
    public List<ArtifactSignatures.Expectation> expects(String path) {
        // Optional: most images are unsigned, and a deployment that wants them signed raises the dial.
        return manifest(path).filter(reference -> !isSignatureTag(reference[1])).isPresent()
                ? List.of(ArtifactSignatures.Expectation.optional(ArtifactSignatures.Scheme.SIGSTORE_BUNDLE))
                : List.of();
    }

    /** The signature rides beside the artifact, where it is already visible, not inside its bytes. */
    @Override
    public boolean embedsEvidence(String path) {
        return false;
    }

    @Override
    public Optional<String> covers(String path) {
        return manifest(path)
                .filter(reference -> isSignatureTag(reference[1]))
                .map(reference -> "/v2/" + reference[0] + "/manifests/sha256:" + reference[1].substring(
                        SIGNATURE_TAG_PREFIX.length(), reference[1].length() - SIGNATURE_TAG_SUFFIX.length()));
    }

    /**
     * The signature material an image carries, found both ways a client attaches it: under cosign's tag convention
     * ({@code sha256-<hex>.sig}), and among the image's referrers - a cosign signature pushed with a {@code subject},
     * or a Sigstore bundle - read from the subject's stored index. Both are found by the image's own digest.
     */
    @Override
    public List<ArtifactSignatures.Evidence> evidence(String path, ArtifactSignatures.Material material)
            throws IOException {
        Optional<String[]> reference = manifest(path).filter(named -> !isSignatureTag(named[1]));
        Optional<ArtifactSignatures.Signed> body = material.body();
        if (reference.isEmpty() || body.isEmpty()) {
            return List.of();
        }
        String name = reference.get()[0];
        // The body is hashed rather than the tag pointer read: the one derivation that cannot disagree with cosign.
        String hex = digest(body.get());
        List<ArtifactSignatures.Evidence> evidence = new ArrayList<>();
        String signaturePath = "/v2/" + name + "/manifests/" + SIGNATURE_TAG_PREFIX + hex + SIGNATURE_TAG_SUFFIX;
        Optional<byte[]> signatureManifest = material.sibling(signaturePath, ArtifactSignatures.Material.LARGEST_SIGNATURE)
                .filter(bounded -> !bounded.truncated())
                .map(bounded -> bounded.content());
        if (signatureManifest.isPresent()) {
            evidence.addAll(cosign(name, signaturePath, signatureManifest.get(), material));
        }
        evidence.addAll(referred(name, hex, body.get(), material));
        return evidence;
    }

    /**
     * The evidence among the image's referrers: each one its subject's stored index lists as a cosign signature or a
     * Sigstore bundle, in the index's order. An index or a listed referrer that is there and cannot be read is
     * material present and unreadable (the seam's clause 6), never an unsigned image.
     */
    private static List<ArtifactSignatures.Evidence> referred(String name, String hex, ArtifactSignatures.Signed body,
                                                              ArtifactSignatures.Material material)
            throws IOException {
        Optional<PublishInterceptor.Content.Bounded> index = material.recorded(
                StoredListing.key(OciReferrers.listing(name, hex)), ArtifactSignatures.Material.LARGEST_SIGNATURE);
        if (index.isEmpty()) {
            return List.of();
        }
        if (index.get().truncated()) {
            throw new IOException("the referrers of sha256:" + hex + " under " + name + " exceed "
                    + ArtifactSignatures.Material.LARGEST_SIGNATURE + " bytes");
        }
        List<ArtifactSignatures.Evidence> evidence = new ArrayList<>();
        for (JsonNode descriptor : OciReferrers.descriptors(index.get().content())) {
            String type = descriptor.path("artifactType").asString("");
            if (!type.equals(OciReferrers.COSIGN_SIGNATURE) && !type.startsWith(OciReferrers.SIGSTORE_BUNDLE)) {
                continue;
            }
            String referrer = "/v2/" + name + "/manifests/" + descriptor.path("digest").asString("");
            byte[] manifest = material.sibling(referrer, ArtifactSignatures.Material.LARGEST_SIGNATURE)
                    .filter(bounded -> !bounded.truncated())
                    .map(bounded -> bounded.content())
                    .orElseThrow(() -> new IOException("the referrer " + referrer + " the index lists is not held"));
            if (type.equals(OciReferrers.COSIGN_SIGNATURE)) {
                evidence.addAll(cosign(name, referrer, manifest, material));
                continue;
            }
            OciReferrers.Manifest bundle = OciReferrers.Manifest.of(manifest)
                    .orElseThrow(() -> new IOException("the referrer " + referrer + " is not a JSON manifest"));
            int layer = 0;
            for (OciReferrers.Manifest.Layer carried : bundle.layers()) {
                if (carried.mediaType().startsWith(OciReferrers.SIGSTORE_BUNDLE)) {
                    String blob = "/v2/" + name + "/blobs/sha256:" + carried.hex();
                    byte[] content = material.sibling(blob, ArtifactSignatures.Material.LARGEST_SIGNATURE)
                            .filter(bounded -> !bounded.truncated())
                            .map(bounded -> bounded.content())
                            .orElseThrow(() -> new IOException("the referrer " + referrer + " names a bundle "
                                    + blob + " the registry does not hold"));
                    // The bundle signs the image manifest itself - a message signature over its digest, or a DSSE
                    // statement naming it as a subject - so the bytes it covers are the manifest's own.
                    evidence.add(new ArtifactSignatures.Evidence(ArtifactSignatures.Scheme.SIGSTORE_BUNDLE, content,
                            body, referrer + "#" + layer));
                }
                layer++;
            }
        }
        return evidence;
    }

    /** The evidence in a cosign signature manifest: each layer carrying a signature annotation, its payload the
     *  signed document naming the image by digest. */
    private static List<ArtifactSignatures.Evidence> cosign(String name, String signaturePath, byte[] signatureManifest,
                                                            ArtifactSignatures.Material material) throws IOException {
        JsonNode layers;
        try {
            layers = JSON.readTree(new String(signatureManifest, StandardCharsets.UTF_8)).path("layers");
        } catch (RuntimeException notJson) {
            throw new IOException("the signature manifest at " + signaturePath + " is not a JSON manifest");
        }
        List<ArtifactSignatures.Evidence> evidence = new ArrayList<>();
        int index = 0;
        for (JsonNode layer : layers) {
            JsonNode annotations = layer.path("annotations");
            String layerHex = hex(layer.path("digest").asString(""));
            if (annotations.path(COSIGN_SIGNATURE).asString("").isEmpty() || !ServableNames.isSha256Hex(layerHex)) {
                index++;
                continue;   // a layer that is not a signature - cosign's manifest carries nothing else, but a
            }               // hand-made one may
            Optional<byte[]> payload = material.sibling("/v2/" + name + "/blobs/sha256:" + layerHex,
                            ArtifactSignatures.Material.LARGEST_SIGNATURE)
                    .filter(bounded -> !bounded.truncated())
                    .map(bounded -> bounded.content());
            if (payload.isEmpty()) {
                // Material present and unreadable, never an unsigned image.
                throw new IOException("the signature manifest at " + signaturePath + " names a payload sha256:"
                        + layerHex + " the registry does not hold");
            }
            byte[] document = payload.get();
            evidence.add(ArtifactSignatures.Evidence.covering(ArtifactSignatures.Scheme.SIGSTORE_BUNDLE,
                    JSON.writeValueAsBytes(annotations), () -> new ByteArrayInputStream(document),
                    signaturePath + "#" + index, OciFormat::imageNamed));
            index++;
        }
        return evidence;
    }

    /**
     * What a referrer pushed by digest covers: its {@code subject}, when it is signature material - a cosign
     * signature or a Sigstore bundle - so the image's verdict is re-derived when its signature lands after it, as a
     * late {@code .sig} tag is. The path names no subject, so the manifest's own bytes are read, bounded.
     */
    @Override
    public Optional<String> covers(String path, ArtifactSignatures.Signed published) throws IOException {
        Optional<String> tagged = covers(path);
        if (tagged.isPresent()) {
            return tagged;
        }
        Optional<String[]> reference = manifest(path);
        if (reference.isEmpty()) {
            return Optional.empty();
        }
        byte[] head;
        try (InputStream in = published.open()) {
            head = in.readNBytes(ArtifactSignatures.Material.LARGEST_SIGNATURE + 1);
        }
        if (head.length > ArtifactSignatures.Material.LARGEST_SIGNATURE) {
            return Optional.empty();
        }
        return OciReferrers.Manifest.of(head)
                .filter(OciReferrers.Manifest::signature)
                .map(manifest -> "/v2/" + reference.get()[0] + "/manifests/sha256:" + manifest.subject().orElseThrow());
    }

    /** The manifest digest a cosign simple-signing payload names, {@code critical.image.docker-manifest-digest}, as
     *  bare hex - empty for bytes that are no such payload. */
    static Optional<String> imageNamed(byte[] payload) {
        try {
            String digest = JSON.readTree(new String(payload, StandardCharsets.UTF_8))
                    .path("critical").path("image").path("docker-manifest-digest").asString("");
            String hex = hex(digest);
            return ServableNames.isSha256Hex(hex) ? Optional.of(hex.toLowerCase(Locale.ROOT)) : Optional.empty();
        } catch (RuntimeException notAPayload) {
            return Optional.empty();
        }
    }

    /** The image name and reference of a manifest request path, both well-formed, or empty for any other path. */
    private static Optional<String[]> manifest(String path) {
        if (!path.startsWith("/v2/")) {
            return Optional.empty();
        }
        int manifests = path.indexOf("/manifests/");
        if (manifests < "/v2/".length()) {
            return Optional.empty();
        }
        String name = path.substring("/v2/".length(), manifests);
        String reference = path.substring(manifests + "/manifests/".length());
        boolean digest = reference.startsWith("sha256:");
        if (!isImageName(name) || (digest ? !ServableNames.isSha256Hex(hex(reference)) : !OciTags.isTag(reference))) {
            return Optional.empty();
        }
        return Optional.of(new String[] {name, reference});
    }

    private static boolean isSignatureTag(String reference) {
        return reference.startsWith(SIGNATURE_TAG_PREFIX) && reference.endsWith(SIGNATURE_TAG_SUFFIX)
                && ServableNames.isSha256Hex(reference.substring(SIGNATURE_TAG_PREFIX.length(),
                        reference.length() - SIGNATURE_TAG_SUFFIX.length()));
    }

    /** The SHA-256 of a signed body, streamed, lower-case hex. */
    private static String digest(ArtifactSignatures.Signed body) throws IOException {
        try (InputStream in = body.open()) {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            for (int n = in.read(buffer); n != -1; n = in.read(buffer)) {
                sha256.update(buffer, 0, n);
            }
            return HexFormat.of().formatHex(sha256.digest());
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IOException(unavailable);
        }
    }

    static String hex(String digest) {
        int colon = digest.indexOf(':');
        return colon < 0 ? digest : digest.substring(colon + 1);
    }

    /**
     * Whether an image name may become the {@code oci/<name>/...} key it addresses: the store's own path rule
     * plus the one thing that rule deliberately allows and the Distribution grammar does not - an empty segment.
     *
     * <p>The character rule is {@link ArtifactStore#traversalFree}'s, so this screen and the store's never disagree
     * about which names are addressable. A name segment may not be empty, which that rule allows, nor begin with a
     * dot - the grammar's rule, which also keeps every image off this format's own dot-prefixed spaces under
     * {@code oci/}.
     */
    static boolean isImageName(String name) {
        if (name.isEmpty() || !ArtifactStore.traversalFree(name)) {
            return false;
        }
        for (String segment : name.split("/", -1)) {
            if (segment.isEmpty() || segment.startsWith(".")) {
                return false;
            }
        }
        return true;
    }

    /** Points a tag at a digest with the bounded compare-and-set retry: concurrent re-tags resolve last-writer-wins,
     *  and a write that cannot land raises an {@link IOException} rather than answering a false success.
     *
     *  <p>The digest-to-tags index is entered before the pointer and the digest the tag named before is retired after
     *  it, so the index never misses a live tag ({@link OciTagIndex}). */
    static void linkTag(ArtifactStore store, String key, String digest) throws IOException {
        String hex = hex(digest);
        OciTagIndex.enter(store, key, hex);
        String[] before = {null};
        Retries.update(store, key, current -> {
            before[0] = current.map(pointer -> hex(new String(pointer.content(), StandardCharsets.UTF_8).trim()))
                    .orElse(null);
            return digest.getBytes(StandardCharsets.UTF_8);
        });
        if (before[0] != null && !before[0].equals(hex)) {
            OciTagIndex.retire(store, key, before[0]);
        }
    }

    // --- RepositoryImporter capability, delegated to OciImporter ---

    /** An image is exported tag by tag through the Distribution API, as {@code docker push} sends it; see
     *  {@link OciExport}. */
    @Override
    public Exported export(ArtifactStore repository, String coordinate, String version, ExportTarget target)
            throws IOException {
        return OciExport.export(repository, coordinate, version, target);
    }

    /** The versions of an image are recorded under the ecosystem its inventory layout declares. */
    @Override
    public Optional<String> inventory() {
        return Optional.of("oci");
    }
}
