package build.jenesis.repository.format.composer;

import module java.base;

import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.format.lifecycle.Lifecycle;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.StoredListing;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A Composer registry's served metadata as stored listings: the per-package Composer-v2 files
 * ({@code p2/<vendor>/<package>.json} for release versions, its {@code ~dev} companion for dev versions), whose
 * entries are the per-version stanzas the publish stored, completed with their download URL and lifecycle
 * {@code abandoned} flag; and the {@code list.json} of package names. The download URL names the host the registry
 * is reached at, so it is stored as the {@value #BASE} placeholder and completed on the way out.
 *
 * <p>A version is listed exactly when its archive pointer is not withheld - the screen the on-read generation applied
 * per version; a lifecycle mark does not unlist a version but marks its stanza {@code abandoned}, which is why a mark
 * re-renders the one stanza rather than removing it.
 */
final class ComposerListings {

    /** The placeholder a stored download URL carries for the registry's external base. */
    static final String BASE = "{{jenesis-base}}";

    private final Blobs blobs;
    private final ArtifactStore store;

    ComposerListings(Blobs blobs) {
        this.blobs = blobs;
        this.store = blobs.store();
    }

    static String metadata(String repo, String vendor, String pkg, boolean dev) {
        return "composer/" + repo + "/p2/" + vendor + "/" + pkg + (dev ? "~dev" : "") + ".json";
    }

    static String list(String repo) {
        return "composer/" + repo + "/list.json";
    }

    /** The p2 document of one coordinate: {@code {"packages":{"<vendor>/<package>":[...]}}}, entries by version. */
    static StoredListing.Codec codec(String coordinate) {
        return new StoredListing.Codec() {
            @Override
            public SortedMap<String, byte[]> split(byte[] document) {
                return collected(this, document);
            }

            @Override
            public byte[] join(SortedMap<String, byte[]> entries) {
                StringBuilder array = new StringBuilder("[");
                boolean first = true;
                for (byte[] entry : entries.values()) {
                    if (!first) {
                        array.append(',');
                    }
                    first = false;
                    array.append(new String(entry, StandardCharsets.UTF_8));
                }
                array.append(']');
                return ("{\"packages\":{" + quoted(coordinate) + ":" + array + "}}")
                        .getBytes(StandardCharsets.UTF_8);
            }

            /**
             * The stored versions, pulled one at a time rather than split out of the whole document.
             *
             * <p>An element is an arbitrary JSON object rather than a name, so each is read as a tree and written
             * back compactly rather than cut out of the source text as {@link #split} cuts it - the same trade
             * {@code NuGetListings} makes, and for the same reason: cutting from source text cannot be bounded,
             * because the text is the document. One package's versions is bounded by a publisher, so this is
             * parity with the appender below rather than a memory fix.
             */
            @Override
            public Reader read(InputStream in, long ignored) throws IOException {
                JsonParser parser = ComposerFormat.MAPPER.createParser(in);
                boolean found = false;
                if (parser.nextToken() == JsonToken.START_OBJECT) {
                    while (!found && parser.nextToken() == JsonToken.PROPERTY_NAME) {
                        boolean packages = "packages".equals(parser.currentName());
                        parser.nextToken();
                        if (packages && parser.currentToken() == JsonToken.START_OBJECT) {
                            while (!found && parser.nextToken() == JsonToken.PROPERTY_NAME) {
                                boolean mine = coordinate.equals(parser.currentName());
                                parser.nextToken();
                                if (mine && parser.currentToken() == JsonToken.START_ARRAY) {
                                    found = true;
                                } else {
                                    parser.skipChildren();
                                }
                            }
                        } else {
                            parser.skipChildren();
                        }
                    }
                }
                boolean inArray = found;
                return new Reader() {

                    private boolean drained = !inArray;

                    @Override
                    public Optional<Map.Entry<String, byte[]>> next() {
                        if (drained || parser.nextToken() == JsonToken.END_ARRAY
                                || parser.currentToken() == null) {
                            drained = true;
                            return Optional.empty();
                        }
                        JsonNode element = parser.readValueAsTree();
                        String version = element.path("version").asString("");
                        return Optional.of(Map.entry(version, ComposerFormat.MAPPER.writeValueAsBytes(element)));
                    }

                    @Override
                    public void close() {
                        parser.close();
                    }
                };
            }

            /** The same document, written as the versions arrive - a fixed frame around comma-joined elements, in
             *  the id order a {@code Sink} delivers, so nothing is held. */
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
                        open();                                 // an empty package is still its frame
                        out.write("]}}".getBytes(StandardCharsets.UTF_8));
                    }

                    private void open() throws IOException {
                        if (!opened) {
                            out.write(("{\"packages\":{" + quoted(coordinate) + ":[")
                                    .getBytes(StandardCharsets.UTF_8));
                            opened = true;
                        }
                    }
                };
            }
        };
    }

    /**
     * A codec's whole-document form, driven by its own streaming one.
     *
     * <p>Two parsers for one grammar is two things to keep in step, and they were: the array scanning here used
     * to walk characters counting brackets while {@code read} used a parser. Now the document form is the
     * streaming form run to exhaustion, so a disagreement between them is not possible rather than merely
     * unlikely.
     */
    private static SortedMap<String, byte[]> collected(StoredListing.Codec codec, byte[] document) {
        SortedMap<String, byte[]> entries = new TreeMap<>();
        try (StoredListing.Codec.Reader reader = codec.read(new ByteArrayInputStream(document), document.length)) {
            for (Optional<Map.Entry<String, byte[]>> entry = reader.next();
                 entry.isPresent(); entry = reader.next()) {
                entries.put(entry.get().getKey(), entry.get().getValue());
            }
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
        return entries;
    }

    /** One JSON string, quoted and escaped by the mapper rather than by wrapping quotes around it. */
    private static String quoted(String value) {
        return ComposerFormat.MAPPER.writeValueAsString(value);
    }

    /** The list document: {@code {"packageNames":[...]}}, entries by coordinate, each its quoted name. */
    static final StoredListing.Codec NAMES = new StoredListing.Codec() {
        @Override
        public SortedMap<String, byte[]> split(byte[] document) {
            return collected(this, document);
        }

        @Override
        public byte[] join(SortedMap<String, byte[]> entries) {
            StringBuilder array = new StringBuilder("{\"packageNames\":[");
            boolean first = true;
            for (byte[] entry : entries.values()) {
                if (!first) {
                    array.append(',');
                }
                first = false;
                array.append(new String(entry, StandardCharsets.UTF_8));
            }
            return array.append("]}").toString().getBytes(StandardCharsets.UTF_8);
        }

        /**
         * The same document, written as the names arrive.
         *
         * <p>The list is every package in the repository, so without this the inherited appender collected all of
         * them into a map and called {@link #join}, which is the whole document again as a {@code StringBuilder}.
         * A codec that implements only {@code split} and {@code join} silently converts a streaming generator
         * above it back into a buffering one - which is how the OCI tag list still died after its generator, its
         * response and its derivation had all been fixed.
         */
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
                    open();                                     // an empty list is still {"packageNames":[]}
                    out.write("]}".getBytes(StandardCharsets.UTF_8));
                }

                private void open() throws IOException {
                    if (!opened) {
                        out.write("{\"packageNames\":[".getBytes(StandardCharsets.UTF_8));
                        opened = true;
                    }
                }
            };
        }

        /** The stored names, pulled one at a time out of the parser's bounded buffer rather than split out of the
         *  whole document. The length is not consulted: the array's end is a token, not an offset. */
        @Override
        public Reader read(InputStream in, long ignored) throws IOException {
            JsonParser parser = ComposerFormat.MAPPER.createParser(in);
            boolean found = false;
            if (parser.nextToken() == JsonToken.START_OBJECT) {
                while (!found && parser.nextToken() == JsonToken.PROPERTY_NAME) {
                    boolean wanted = "packageNames".equals(parser.currentName());
                    parser.nextToken();                         // advance onto the field's value
                    if (wanted && parser.currentToken() == JsonToken.START_ARRAY) {
                        found = true;
                    } else {
                        parser.skipChildren();                  // scalar (no-op) or an unrelated subtree
                    }
                }
            }
            boolean inArray = found;
            return new Reader() {

                private boolean drained = !inArray;

                @Override
                public Optional<Map.Entry<String, byte[]>> next() {
                    while (!drained) {
                        JsonToken token = parser.nextToken();
                        if (token == null || token == JsonToken.END_ARRAY) {
                            drained = true;
                            return Optional.empty();
                        }
                        if (token == JsonToken.VALUE_STRING) {
                            String name = parser.getString();
                            return Optional.of(Map.entry(name,
                                    quoted(name).getBytes(StandardCharsets.UTF_8)));
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


    StoredListing.Spec metadataSpec(String repo, String vendor, String pkg, boolean dev) {
        return StoredListing.Spec.materialising(metadata(repo, vendor, pkg, dev), codec(vendor + "/" + pkg),
                () -> generateMetadata(repo, vendor, pkg, dev));
    }

    StoredListing.Spec listSpec(String repo) {
        return StoredListing.Spec.materialising(list(repo), NAMES, () -> generateList(repo));
    }

    private SortedMap<String, byte[]> generateMetadata(String repo, String vendor, String pkg, boolean dev)
            throws IOException {
        SortedMap<String, byte[]> entries = new TreeMap<>();
        Map<String, Lifecycle.Flag> marks = Lifecycle.versions(store, vendor + "/" + pkg);
        for (String version : blobs.list(ComposerFormat.indexPrefix(repo, vendor, pkg))) {
            if (ComposerFormat.isDev(version) != dev
                    || blobs.withheld(ComposerFormat.distKey(repo, vendor, pkg, version))) {
                continue;
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            if (blobs.read(ComposerFormat.indexKey(repo, vendor, pkg, version), buffer)) {
                entries.put(version, render(buffer.toByteArray(), vendor, pkg, version, marks.get(version)));
            }
        }
        return entries;
    }

    /**
     * <b>This one collects deliberately, and the sorted map is doing real work.</b> Every other
     * repository-wide generator streams into a {@code Sink}, which owes its entries in ascending key
     * order and takes that order from the scan.
     *
     * <p>That does not hold here: the key is {@code vendor + "/" + pkg}, composed from two nested scans, and
     * vendors-then-packages is not the order of the composed key - with vendors {@code a} and {@code a-b},
     * nesting yields {@code a/...} first, while {@code -} (0x2D) sorts before {@code /} (0x2F).
     *
     * <p>So the map is what puts these entries in order. Removing it would write a misordered
     * document - which the codecs and the cursor paging both assume is ascending, and which nothing
     * would report.
     */
    private SortedMap<String, byte[]> generateList(String repo) throws IOException {
        SortedMap<String, byte[]> entries = new TreeMap<>();
        for (String vendor : blobs.list("composer/" + repo + "/index")) {
            for (String pkg : blobs.list("composer/" + repo + "/index/" + vendor)) {
                if (ComposerFormat.servable(repo, vendor, pkg, blobs)) {
                    entries.put(vendor + "/" + pkg, quoted(vendor + "/" + pkg).getBytes(StandardCharsets.UTF_8));
                }
            }
        }
        return entries;
    }

    /** A version's p2 entry: its stored stanza with the download URL (the placeholder base) and the lifecycle
     *  {@code abandoned} flag a client prints when it selects a marked version. */
    private static byte[] render(byte[] stanza, String vendor, String pkg, String version, Lifecycle.Flag flag)
            throws IOException {
        JsonNode entry = ComposerFormat.MAPPER.readTree(stanza);
        if (entry instanceof ObjectNode object) {
            if (object.get("dist") instanceof ObjectNode dist) {
                dist.put("url", BASE + "/dists/" + vendor + "/" + pkg + "/" + version + ".zip");
            }
            if (flag != null) {
                String message = flag.message();
                if (message == null || message.isBlank()) {
                    object.put("abandoned", true);
                } else {
                    object.put("abandoned", message);
                }
            }
        }
        return ComposerFormat.MAPPER.writeValueAsBytes(entry);
    }

    /** A version was published: list it (and its package) if it is servable. */
    void published(String repo, String vendor, String pkg, String version, byte[] stanza) throws IOException {
        refresh(repo, vendor, pkg, version, stanza);
    }

    /** Re-decide one version's entry from the store's current state - after a hold, a release or a mark. */
    void refresh(String repo, String vendor, String pkg, String version) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        if (!blobs.read(ComposerFormat.indexKey(repo, vendor, pkg, version), buffer)) {
            StoredListing.remove(store, metadataSpec(repo, vendor, pkg, ComposerFormat.isDev(version)), version);
            refreshListed(repo, vendor, pkg);
            return;
        }
        refresh(repo, vendor, pkg, version, buffer.toByteArray());
    }

    private void refresh(String repo, String vendor, String pkg, String version, byte[] stanza) throws IOException {
        StoredListing.Spec spec = metadataSpec(repo, vendor, pkg, ComposerFormat.isDev(version));
        if (blobs.withheld(ComposerFormat.distKey(repo, vendor, pkg, version))) {
            StoredListing.remove(store, spec, version);
        } else {
            StoredListing.put(store, spec, version, render(stanza, vendor, pkg, version,
                    Lifecycle.read(store, vendor + "/" + pkg, version).orElse(null)));
        }
        refreshListed(repo, vendor, pkg);
    }

    private void refreshListed(String repo, String vendor, String pkg) throws IOException {
        String coordinate = vendor + "/" + pkg;
        if (ComposerFormat.servable(repo, vendor, pkg, blobs)) {
            StoredListing.put(store, listSpec(repo), coordinate,
                    quoted(coordinate).getBytes(StandardCharsets.UTF_8));
        } else {
            StoredListing.remove(store, listSpec(repo), coordinate);
        }
    }

    /** Regenerate the listing at this key if it is a Composer one: a p2 file or the package list. */
    boolean rebuild(String listing) throws IOException {
        String[] segments = listing.split("/");
        if (!segments[0].equals("composer") || segments.length < 3) {
            return false;
        }
        String repo = segments[1];
        if (segments.length == 3 && segments[2].equals("list.json")) {
            StoredListing.rebuild(store, listSpec(repo));
            return true;
        }
        if (segments.length == 5 && segments[2].equals("p2") && segments[4].endsWith(".json")) {
            String file = segments[4].substring(0, segments[4].length() - ".json".length());
            boolean dev = file.endsWith("~dev");
            StoredListing.rebuild(store, metadataSpec(repo, segments[3],
                    dev ? file.substring(0, file.length() - "~dev".length()) : file, dev));
            return true;
        }
        return false;
    }

}
