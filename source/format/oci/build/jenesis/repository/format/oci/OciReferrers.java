package build.jenesis.repository.format.oci;

import module java.base;

import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.OciTags;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.StoredListing;
import build.jenesis.repository.store.Withheld;
import build.jenesis.repository.walk.BoundedChildren;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import build.jenesis.repository.store.ServableNames;

/**
 * The referrers of a manifest - every manifest pushed with a {@code subject} naming it - as the Distribution API's
 * {@code GET /v2/<name>/referrers/<digest>} answers them: an image index whose entries are the referrers'
 * descriptors, each with its {@code artifactType} and annotations, so a client finds a signature, an SBOM or an
 * attestation attached to an image without knowing its digest.
 *
 * <p><b>Maintained on the write path.</b> Accepting a referrer writes an empty marker at
 * {@code oci/<name>/.manifests/<subject>/referrers/<referrer>}, the durable fact, and puts its descriptor into the
 * subject's {@link StoredListing}. A read streams that document; only the listing's generator enumerates the markers.
 * A marker names no blob and keeps nothing alive: a manifest is kept by its media-type sidecar.
 *
 * <p><b>Listed exactly while it serves</b>: while its {@code oci/.types/<hex>} sidecar exists and no hold withholds
 * it. A held referrer is recorded but not listed; {@link OciListingObserver} re-decides the entry on each transition.
 *
 * <p>The generator also reads the specification's tag-schema fallback - an index tagged {@code sha256-<subject hex>}
 * that a client maintains where a registry does not answer referrers - so such attachments are found through the
 * API.
 */
final class OciReferrers {

    /** The media type of the index a referrers request answers. */
    static final String INDEX = "application/vnd.oci.image.index.v1+json";

    /** The artifact type cosign stamps on a signature it attaches as a referrer rather than under the tag convention. */
    static final String COSIGN_SIGNATURE = "application/vnd.dev.cosign.artifact.sig.v1+json";

    /** The media-type prefix of a Sigstore bundle, whichever version, as the artifact type of the referrer carrying one
     *  and as the media type of the layer that holds it. */
    static final String SIGSTORE_BUNDLE = "application/vnd.dev.sigstore.bundle";

    /** How many referrers one answer carries at most when the client asks for no smaller page; the rest are a
     *  {@code Link} away. */
    static final int PAGE = 1_000;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The stored index: the entries of an image index's {@code manifests} array, keyed by the referrer's hex. */
    static final StoredListing.Codec CODEC = new StoredListing.Codec.Streaming() {
        @Override
        public byte[] join(SortedMap<String, byte[]> entries) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (Appender appender = append(out)) {
                for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                    appender.append(entry.getKey(), entry.getValue());
                }
            } catch (IOException impossible) {
                throw new UncheckedIOException(impossible);
            }
            return out.toByteArray();
        }

        @Override
        public Appender append(OutputStream out) {
            return new Appender() {

                private boolean opened, written;

                @Override
                public void append(String id, byte[] entry) throws IOException {
                    open();
                    if (written) {
                        out.write(',');
                    }
                    written = true;
                    out.write(entry);
                }

                @Override
                public void close() throws IOException {
                    open();
                    out.write("]}".getBytes(StandardCharsets.UTF_8));
                }

                private void open() throws IOException {
                    if (!opened) {
                        out.write(("{\"schemaVersion\":2,\"mediaType\":" + JSON.writeValueAsString(INDEX)
                                + ",\"manifests\":[").getBytes(StandardCharsets.UTF_8));
                        opened = true;
                    }
                }
            };
        }

        @Override
        public Reader read(InputStream in, long ignored) throws IOException {
            JsonParser parser = JSON.createParser(in);
            boolean found = false;
            if (parser.nextToken() == JsonToken.START_OBJECT) {
                while (!found && parser.nextToken() == JsonToken.PROPERTY_NAME) {
                    boolean wanted = "manifests".equals(parser.currentName());
                    parser.nextToken();
                    if (wanted && parser.currentToken() == JsonToken.START_ARRAY) {
                        found = true;
                    } else {
                        parser.skipChildren();
                    }
                }
            }
            boolean inArray = found;
            return new Reader() {

                private boolean drained = !inArray;

                @Override
                public Optional<Map.Entry<String, byte[]>> next() throws IOException {
                    while (!drained) {
                        JsonToken token = parser.nextToken();
                        if (token == null || token == JsonToken.END_ARRAY) {
                            drained = true;
                            return Optional.empty();
                        }
                        if (token == JsonToken.START_OBJECT) {
                            JsonNode descriptor = parser.readValueAsTree();
                            String hex = OciFormat.hex(descriptor.path("digest").asString(""));
                            if (ServableNames.isSha256Hex(hex)) {
                                return Optional.of(Map.entry(hex, JSON.writeValueAsBytes(descriptor)));
                            }
                        } else {
                            parser.skipChildren();
                        }
                    }
                    return Optional.empty();
                }

                @Override
                public void close() {
                    parser.close();
                }
            };
        }
    };

    /**
     * The fields of a pushed manifest the referrers index is built from, read once: its {@code subject}, what its
     * descriptor in a subject's index says it is ({@code artifactType}, falling back to its config's media type as
     * the specification says), and its annotations.
     */
    record Manifest(Optional<String> subject, Optional<String> artifactType, String mediaType, JsonNode annotations,
                    List<Layer> layers) {

        /** A layer of a manifest: its media type and digest hex. */
        record Layer(String mediaType, String hex) {
        }

        /** The manifest these bytes hold, or empty when they are not a JSON object. */
        static Optional<Manifest> of(byte[] content) {
            JsonNode node;
            try {
                node = JSON.readTree(new String(content, StandardCharsets.UTF_8));
            } catch (RuntimeException notJson) {
                return Optional.empty();
            }
            return node != null && node.isObject() ? Optional.of(of(node)) : Optional.empty();
        }

        static Manifest of(JsonNode node) {
            String subject = OciFormat.hex(node.path("subject").path("digest").asString(""));
            String artifactType = node.path("artifactType").asString("");
            if (artifactType.isEmpty()) {
                artifactType = node.path("config").path("mediaType").asString("");
            }
            List<Layer> layers = new ArrayList<>();
            for (JsonNode layer : node.path("layers")) {
                String hex = OciFormat.hex(layer.path("digest").asString(""));
                if (ServableNames.isSha256Hex(hex)) {
                    layers.add(new Layer(layer.path("mediaType").asString(""), hex));
                }
            }
            return new Manifest(ServableNames.isSha256Hex(subject) ? Optional.of(subject) : Optional.empty(),
                    artifactType.isEmpty() ? Optional.empty() : Optional.of(artifactType),
                    node.path("mediaType").asString(""), node.path("annotations"), List.copyOf(layers));
        }

        /** Whether this manifest is signature material a signature scheme reads: a cosign signature, or a Sigstore
         *  bundle, attached to its subject. */
        boolean signature() {
            return subject.isPresent() && artifactType
                    .filter(type -> type.equals(COSIGN_SIGNATURE) || type.startsWith(SIGSTORE_BUNDLE)).isPresent();
        }

        /** This manifest's descriptor as its subject's index lists it. */
        byte[] descriptor(String hex, long size, String servedType) {
            ObjectNode descriptor = JSON.createObjectNode();
            descriptor.put("mediaType", servedType.isEmpty() ? (mediaType.isEmpty() ? OciFormat.OCI_MANIFEST
                    : mediaType) : servedType);
            descriptor.put("digest", "sha256:" + hex);
            descriptor.put("size", size);
            artifactType.ifPresent(type -> descriptor.put("artifactType", type));
            if (annotations.isObject() && !annotations.isEmpty()) {
                descriptor.set("annotations", annotations);
            }
            return JSON.writeValueAsBytes(descriptor);
        }
    }

    private final ArtifactStore store;

    OciReferrers(ArtifactStore store) {
        this.store = store;
    }

    /** The listing a subject's index is stored as. */
    static String listing(String name, String subject) {
        return "oci/" + name + "/.manifests/" + subject + "/referrers";
    }

    /** The name and subject hex of a referrers listing key, or empty for any other key. */
    static Optional<String[]> parse(String listing) {
        if (!listing.startsWith("oci/") || !listing.endsWith("/referrers")) {
            return Optional.empty();
        }
        String rest = listing.substring("oci/".length(), listing.length() - "/referrers".length());
        int manifests = rest.lastIndexOf("/.manifests/");
        if (manifests <= 0) {
            return Optional.empty();
        }
        String name = rest.substring(0, manifests);
        String subject = rest.substring(manifests + "/.manifests/".length());
        return OciFormat.isImageName(name) && ServableNames.isSha256Hex(subject)
                ? Optional.of(new String[] {name, subject}) : Optional.empty();
    }

    private static String markers(String name, String subject) {
        return "oci/" + name + "/.manifests/" + subject + "/referrers";
    }

    StoredListing.Spec spec(String name, String subject) {
        return StoredListing.Spec.materialising(listing(name, subject), CODEC, () -> generate(name, subject));
    }

    /**
     * Record that the manifest {@code hex}, just accepted or held under {@code name}, refers to its subject: the
     * marker always, and its descriptor in the subject's index when it is {@code listed} - accepted, so its sidecar
     * is written and no hold withholds it.
     */
    void record(String name, String hex, byte[] content, Manifest manifest, String servedType, boolean listed)
            throws IOException {
        if (manifest.subject().isEmpty()) {
            return;
        }
        String subject = manifest.subject().get();
        store.write(markers(name, subject) + "/" + hex, InputStream.nullInputStream());
        if (listed) {
            StoredListing.put(store, spec(name, subject), hex, manifest.descriptor(hex, content.length, servedType));
        }
    }

    /** Re-decide the manifest {@code hex}'s entry in its subject's index from the store as it now stands - after a
     *  hold, a release or a removal. A manifest that names no subject is no referrer and changes nothing. */
    void refresh(String name, String hex) throws IOException {
        Optional<Manifest> manifest = stored(hex);
        if (manifest.isEmpty() || manifest.get().subject().isEmpty()) {
            return;
        }
        String subject = manifest.get().subject().get();
        if (!StoredListing.present(store, listing(name, subject)) && store.isEmpty(markers(name, subject))) {
            return;                                     // nothing recorded for this subject: nothing to re-decide
        }
        Optional<byte[]> descriptor = listed(name, subject, hex);
        if (descriptor.isPresent()) {
            StoredListing.put(store, spec(name, subject), hex, descriptor.get());
        } else {
            StoredListing.remove(store, spec(name, subject), hex);
        }
    }

    /** Forget that the manifest {@code hex} refers to anything under {@code name}: a client removed it. */
    void forget(String name, String hex) throws IOException {
        Optional<Manifest> manifest = stored(hex);
        if (manifest.isEmpty() || manifest.get().subject().isEmpty()) {
            return;
        }
        String subject = manifest.get().subject().get();
        store.delete(markers(name, subject) + "/" + hex);
        if (StoredListing.present(store, listing(name, subject))) {
            StoredListing.remove(store, spec(name, subject), hex);
        }
    }

    /**
     * Every referrer of {@code subject} under {@code name}: the recorded markers, and the entries of the tag-schema
     * index an older client maintained instead - each listed while it serves. Collected rather than streamed, since
     * a sink owes its entries in order and two sources are merged; the map holds one subject's referrers.
     */
    private SortedMap<String, byte[]> generate(String name, String subject) throws IOException {
        SortedMap<String, byte[]> entries = new TreeMap<>();
        BoundedChildren.draining().scan(store, markers(name, subject), hex -> {
            if (ServableNames.isSha256Hex(hex)) {
                listed(name, subject, hex).ifPresent(descriptor -> entries.put(hex, descriptor));
            }
        });
        String fallback = "sha256-" + subject;
        if (OciTags.isTag(fallback)) {
            Optional<ArtifactStore.Versioned> pointer = store.readVersioned("oci/" + name + "/tags/" + fallback);
            String index = pointer.map(versioned -> OciFormat.hex(
                    new String(versioned.content(), StandardCharsets.UTF_8).trim())).orElse("");
            if (ServableNames.isSha256Hex(index)) {
                Optional<byte[]> body = bounded("blobs/" + index);
                JsonNode node = body.flatMap(bytes -> {
                    try {
                        return Optional.ofNullable(JSON.readTree(new String(bytes, StandardCharsets.UTF_8)));
                    } catch (RuntimeException notJson) {
                        return Optional.empty();
                    }
                }).orElse(null);
                if (node != null) {
                    for (JsonNode entry : node.path("manifests")) {
                        String hex = OciFormat.hex(entry.path("digest").asString(""));
                        if (ServableNames.isSha256Hex(hex) && !entries.containsKey(hex)) {
                            listed(name, subject, hex).ifPresent(descriptor -> entries.put(hex, descriptor));
                        }
                    }
                }
            }
        }
        return entries;
    }

    /** The descriptor of the referrer {@code hex} of {@code subject} when it is listed: a manifest this registry
     *  records and serves, whose own {@code subject} is that one; empty otherwise. */
    private Optional<byte[]> listed(String name, String subject, String hex) throws IOException {
        Optional<ArtifactStore.Versioned> sidecar = store.readVersioned("oci/.types/" + hex);
        if (sidecar.isEmpty() || Withheld.is(store, hex)) {
            return Optional.empty();
        }
        Optional<byte[]> content = bounded("blobs/" + hex);
        if (content.isEmpty()) {
            return Optional.empty();
        }
        Optional<Manifest> manifest = Manifest.of(content.get());
        if (manifest.isEmpty() || !manifest.get().subject().equals(Optional.of(subject))) {
            return Optional.empty();
        }
        String served = new String(sidecar.get().content(), StandardCharsets.UTF_8).trim();
        return Optional.of(manifest.get().descriptor(hex, content.get().length, served));
    }

    /** The manifest stored as {@code hex}, bounded as a pushed manifest is; empty when it is not stored or is none. */
    private Optional<Manifest> stored(String hex) throws IOException {
        return bounded("blobs/" + hex).flatMap(Manifest::of);
    }

    /** A manifest's bytes, whole, when they are stored and within the manifest bound. */
    private Optional<byte[]> bounded(String key) throws IOException {
        byte[] body;
        try (InputStream in = store.open(key)) {
            body = in.readNBytes(OciFormat.MAX_MANIFEST + 1);
        } catch (NoSuchFileException absent) {
            return Optional.empty();
        }
        return body.length > OciFormat.MAX_MANIFEST ? Optional.empty() : Optional.of(body);
    }

    /**
     * {@code GET /v2/<name>/referrers/<digest>}: one page of the subject's index, filtered to one
     * {@code artifactType} when asked - which the answer confirms in {@code OCI-Filters-Applied} - with a
     * {@code Link} to the next page when more remain. An unknown subject answers the empty index, as the
     * specification says; a digest that is not one is a {@code 400}.
     */
    void serve(String name, String reference, FormatExchange exchange) throws IOException {
        String subject = OciFormat.hex(reference);
        if (!reference.startsWith("sha256:") || !ServableNames.isSha256Hex(subject)) {
            OciFormat.error(exchange, 400, "DIGEST_INVALID", "the referrers of a manifest are asked for by its digest");
            return;
        }
        String size = exchange.queryParameter("n");
        int limit;
        try {
            limit = size == null ? PAGE : Math.min(Integer.parseInt(size), PAGE);
        } catch (NumberFormatException invalid) {
            limit = -1;
        }
        String last = exchange.queryParameter("last");
        if (limit <= 0 || (last != null && !last.isEmpty() && !ServableNames.isSha256Hex(last))) {
            OciFormat.error(exchange, 400, "UNSUPPORTED", "n is a positive page size and last a referrer's hex");
            return;
        }
        String filter = exchange.queryParameter("artifactType");
        ArrayNode page = JSON.createArrayNode();
        boolean more = false;
        String through = null;
        // A subject nothing refers to costs two probes and stores nothing.
        Optional<StoredListing.Served> served = StoredListing.served(store, listing(name, subject));
        if (served.isEmpty() && (!store.isEmpty(markers(name, subject))
                || store.exists("oci/" + name + "/tags/sha256-" + subject))) {
            served = StoredListing.open(store, spec(name, subject));
        }
        if (served.isPresent()) {
            try (StoredListing.Served document = served.get();
                 StoredListing.Codec.Reader entries = CODEC.read(document.body(), document.header().size())) {
                for (Optional<Map.Entry<String, byte[]>> entry = entries.next(); entry.isPresent();
                     entry = entries.next()) {
                    if (last != null && !last.isEmpty() && entry.get().getKey().compareTo(last) <= 0) {
                        continue;
                    }
                    JsonNode descriptor = JSON.readTree(entry.get().getValue());
                    if (filter != null && !filter.equals(descriptor.path("artifactType").asString(""))) {
                        continue;
                    }
                    if (page.size() == limit) {
                        more = true;
                        break;
                    }
                    page.add(descriptor);
                    through = entry.get().getKey();
                }
            }
        }
        ObjectNode index = JSON.createObjectNode();
        index.put("schemaVersion", 2);
        index.put("mediaType", INDEX);
        index.set("manifests", page);
        if (filter != null) {
            exchange.setResponseHeader("OCI-Filters-Applied", "artifactType");
        }
        if (more) {
            exchange.setResponseHeader("Link", "<" + exchange.external("/v2/" + name + "/referrers/sha256:" + subject)
                    + "?n=" + limit + "&last=" + through
                    + (filter == null ? "" : "&artifactType=" + URLEncoder.encode(filter, StandardCharsets.UTF_8))
                    + ">; rel=\"next\"");
        }
        exchange.setResponseHeader("Content-Type", INDEX);
        exchange.answer(JSON.writeValueAsBytes(index));
    }

    /**
     * The descriptors a subject's index lists, read from the stored document's bytes, for a caller that reads the
     * format's records by key and knows nothing of listings.
     */
    static List<JsonNode> descriptors(byte[] stored) throws IOException {
        StoredListing.Document document = StoredListing.parse(stored);
        List<JsonNode> descriptors = new ArrayList<>();
        try (StoredListing.Codec.Reader entries = CODEC.read(document.open(), document.header().size())) {
            for (Optional<Map.Entry<String, byte[]>> entry = entries.next(); entry.isPresent(); entry = entries.next()) {
                descriptors.add(JSON.readTree(entry.get().getValue()));
            }
        }
        return descriptors;
    }
}
