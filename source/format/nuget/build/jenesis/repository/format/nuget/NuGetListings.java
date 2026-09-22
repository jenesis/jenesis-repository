package build.jenesis.repository.format.nuget;

import module java.base;

import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.format.lifecycle.Lifecycle;
import build.jenesis.repository.store.ArtifactStore;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.core.JsonToken;
import build.jenesis.repository.store.StoredListing;
import build.jenesis.repository.walk.BoundedChildren;
import tools.jackson.databind.JsonNode;
import build.jenesis.repository.format.Semver;

/**
 * A NuGet package's served documents as stored listings: the flat-container version list ({@code index.json},
 * entries by version), the registration index (one page, entries by version, each a leaf whose URLs name the
 * registry's base and so carry the {@value #BASE} placeholder completed on the way out), and the repository-wide
 * search document, whose entries are one record per package id naming its servable versions - the document the
 * search service filters and windows in memory instead of scanning the id space per request.
 *
 * <p>A version is listed exactly when its {@code .nupkg} pointer is not withheld - the screen the on-read generation
 * applied per version; a lifecycle mark does not unlist a version but renders its registration leaf unlisted or
 * deprecated. A write to a package's version list re-derives its search record from the stored list, so a publish
 * costs one rewrite of the package's two documents and one of the search document, never a scan of the other
 * packages.
 */
final class NuGetListings {

    static final String BASE = "{{jenesis-base}}";

    static final String SEARCH = "nuget/search.json";

    private final Blobs blobs;
    private final ArtifactStore store;

    NuGetListings(Blobs blobs) {
        this.blobs = blobs;
        this.store = blobs.store();
    }

    static String versions(String id) {
        return "nuget/" + id + "/index.json";
    }

    static String registration(String id) {
        return "nuget/" + id + "/registration.json";
    }

    /** {@code {"versions":[...]}}, entries by version, each its quoted version. */
    static final StoredListing.Codec VERSIONS = array("versions", element -> NuGetFormat.JSON.readTree(element).asString(""));

    /** {@code {"data":[...]}}, entries by package id, each its search record. */
    static final StoredListing.Codec RECORDS = array("data", element -> {
        return NuGetFormat.JSON.readTree(element).path("id").asString("");
    });

    private static StoredListing.Codec array(String member, Function<String, String> idOf) {
        return new StoredListing.Codec() {
            @Override
            public SortedMap<String, byte[]> split(byte[] document) {
                SortedMap<String, byte[]> entries = new TreeMap<>();
                for (JsonNode element : NuGetFormat.JSON.readTree(document).path(member)) {
                    String text = NuGetFormat.JSON.writeValueAsString(element);
                    entries.put(idOf.apply(text), text.getBytes(StandardCharsets.UTF_8));
                }
                return entries;
            }

            @Override
            public byte[] join(SortedMap<String, byte[]> entries) {
                return ("{" + NuGetFormat.JSON.writeValueAsString(member) + ":" + array(entries.values()) + "}")
                        .getBytes(StandardCharsets.UTF_8);
            }

            /**
             * The same document, written as the elements arrive.
             *
             * <p>{@code RECORDS} is the search document, which is every package in the repository - so without
             * this the inherited appender collected all of them into a map and joined that into one string. A
             * codec implementing only {@code split} and {@code join} silently turns a streaming generator above it
             * back into a buffering one, which is what made the streamed search generator worth nothing.
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
                        open();                                 // an empty document is still {"member":[]}
                        out.write("]}".getBytes(StandardCharsets.UTF_8));
                    }

                    private void open() throws IOException {
                        if (!opened) {
                            out.write(("{" + NuGetFormat.JSON.writeValueAsString(member) + ":[")
                                    .getBytes(StandardCharsets.UTF_8));
                            opened = true;
                        }
                    }
                };
            }

            /**
             * The stored elements, one at a time, out of the parser's bounded buffer.
             *
             * <p>An element here is an arbitrary JSON value rather than a name, so each is read as a tree and
             * written back compactly rather than cut out of the source text as {@link #split} cuts it. That is the
             * same bytes for a document this codec wrote - {@link #join} emits compact JSON and Jackson preserves
             * member order - and it is what lets the read stay bounded, which cutting from source text cannot,
             * since the text is the document.
             */
            @Override
            public Reader read(InputStream in, long ignored) throws IOException {
                JsonParser parser = NuGetFormat.JSON.createParser(in);
                boolean found = false;
                if (parser.nextToken() == JsonToken.START_OBJECT) {
                    while (!found && parser.nextToken() == JsonToken.PROPERTY_NAME) {
                        boolean wanted = member.equals(parser.currentName());
                        parser.nextToken();                     // advance onto the field's value
                        if (wanted && parser.currentToken() == JsonToken.START_ARRAY) {
                            found = true;
                        } else {
                            parser.skipChildren();              // scalar (no-op) or an unrelated subtree
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
                        byte[] element = NuGetFormat.JSON.writeValueAsBytes(parser.readValueAsTree());
                        return Optional.of(Map.entry(
                                idOf.apply(new String(element, StandardCharsets.UTF_8)), element));
                    }

                    @Override
                    public void close() {
                        parser.close();
                    }
                };
            }
        };
    }

    /** The registration index of one package: one page whose items are the version leaves, in semantic-version order,
     *  with {@code lower}/{@code upper} and the counts computed on join. */
    static StoredListing.Codec registrationCodec(String id) {
        String self = BASE + "/v3/registrations/" + id + "/index.json";
        return new StoredListing.Codec() {
            @Override
            public SortedMap<String, byte[]> split(byte[] document) {
                SortedMap<String, byte[]> entries = new TreeMap<>();
                for (JsonNode page : NuGetFormat.JSON.readTree(document).path("items")) {
                    for (JsonNode leaf : page.path("items")) {
                        entries.put(leaf.path("catalogEntry").path("version").asString(""),
                                NuGetFormat.JSON.writeValueAsBytes(leaf));
                    }
                }
                return entries;
            }

            /** The leaves one at a time through a streaming parser, page by page: the listing mechanism reads the
             *  index through this on every publish of the package, and without it fell back to the whole document
             *  in heap - the fallback the npm-packument canary showed failing a publish at fifty thousand versions
             *  in a 512 MiB container, which this codec shared. The join still collects, for the reason below. */
            @Override
            public Reader read(InputStream in, long ignored) throws IOException {
                JsonParser parser = NuGetFormat.JSON.createParser(in);
                return new Reader() {
                    private int depth;                          // 1 inside the root, 2 inside a page, 3 inside its items
                    private boolean inPages;
                    private boolean inLeaves;
                    private boolean drained = parser.nextToken() != JsonToken.START_OBJECT;

                    @Override
                    public Optional<Map.Entry<String, byte[]>> next() {
                        while (!drained) {
                            JsonToken token = parser.nextToken();
                            if (token == null) {
                                drained = true;
                                break;
                            }
                            if (inLeaves) {
                                if (token == JsonToken.END_ARRAY) {
                                    inLeaves = false;
                                    continue;
                                }
                                JsonNode leaf = parser.readValueAsTree();
                                return Optional.of(Map.entry(leaf.path("catalogEntry").path("version").asString(""),
                                        NuGetFormat.JSON.writeValueAsBytes(leaf)));
                            }
                            if (token == JsonToken.PROPERTY_NAME && "items".equals(parser.currentName())) {
                                parser.nextToken();
                                if (parser.currentToken() != JsonToken.START_ARRAY) {
                                    parser.skipChildren();
                                } else if (!inPages) {
                                    inPages = true;             // the root's pages: descend into each
                                } else {
                                    inLeaves = true;            // a page's leaves: deliver each
                                }
                                continue;
                            }
                            if (token == JsonToken.PROPERTY_NAME) {
                                parser.nextToken();
                                if (parser.currentToken() == JsonToken.START_OBJECT
                                        || parser.currentToken() == JsonToken.START_ARRAY) {
                                    parser.skipChildren();      // the root's or a page's scalar-or-subtree members
                                }
                                continue;
                            }
                            if (token == JsonToken.END_ARRAY && inPages) {
                                inPages = false;
                            }
                            if (token == JsonToken.END_OBJECT && !inPages) {
                                drained = true;
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

            /**
             * <b>This one collects, and no appender can replace it.</b>
             *
             * <p>A registration index lists its leaves in {@link Semver} order rather than the ascending id order
             * a {@code Sink} delivers, and its page object names its own {@code lower} and {@code upper} bounds
             * and counts - so both the order and the frame are functions of every entry. {@code spooling} defers
             * opening bytes and does not reorder a body, so it does not apply.
             *
             * <p>What is held is one package's registration, bounded by a publisher rather than by the
             * repository. That bound is the reason this is acceptable, and it is stated because a codec quietly
             * lacking an appender is exactly the oversight that turned a streaming generator into a buffered one
             * in six other formats - including {@code RECORDS} in this same file, which is the repository-wide
             * search document and does stream.
             */
            @Override
            public byte[] join(SortedMap<String, byte[]> entries) {
                List<String> versions = new ArrayList<>(entries.keySet());
                versions.sort(Semver::compare);
                ObjectNode root = NuGetFormat.JSON.createObjectNode();
                root.put("@id", self);
                root.put("count", versions.isEmpty() ? 0 : 1);
                ArrayNode pages = root.putArray("items");
                if (!versions.isEmpty()) {
                    ObjectNode page = pages.addObject();
                    page.put("@id", self + "#page");
                    page.put("count", versions.size());
                    page.put("lower", versions.getFirst());
                    page.put("upper", versions.getLast());
                    ArrayNode leaves = page.putArray("items");
                    for (String version : versions) {
                        leaves.add(NuGetFormat.JSON.readTree(entries.get(version)));
                    }
                }
                return NuGetFormat.JSON.writeValueAsBytes(root);
            }
        };
    }


    private static String array(Collection<byte[]> elements) {
        StringBuilder array = new StringBuilder("[");
        boolean first = true;
        for (byte[] element : elements) {
            if (!first) {
                array.append(',');
            }
            first = false;
            array.append(new String(element, StandardCharsets.UTF_8));
        }
        return array.append(']').toString();
    }

    // ---- specs ----

    StoredListing.Spec versionsSpec(String id) {
        return StoredListing.Spec.materialising(versions(id), VERSIONS, () -> generateVersions(id)).deriving(document -> {
            // Stated at the version list's sequence, so the rebuild pass's regeneration of the search document - a
            // walk over every package's list, which can be a beat behind this write - never puts an older record
            // over the one this derivation wrote.
            SortedMap<String, byte[]> listed = VERSIONS.split(document.body());
            if (listed.isEmpty()) {
                StoredListing.remove(store, searchSpec(), id, document.header().seq());
            } else {
                StoredListing.put(store, searchSpec(), id, record(id, listed.keySet()), document.header().seq());
            }
        });
    }

    StoredListing.Spec registrationSpec(String id) {
        return StoredListing.Spec.materialising(registration(id), registrationCodec(id), () -> generateRegistration(id));
    }

    StoredListing.Spec searchSpec() {
        return StoredListing.Spec.of(SEARCH, RECORDS, this::generateSearch);
    }

    // ---- generation ----

    private SortedMap<String, byte[]> generateVersions(String id) throws IOException {
        SortedMap<String, byte[]> entries = new TreeMap<>();
        for (String version : blobs.list("nuget/" + id)) {
            if (!version.startsWith(".") && !blobs.withheld(NuGetFormat.nupkgKey(id, version))
                    && blobs.exists(NuGetFormat.nupkgKey(id, version))) {
                entries.put(version, NuGetFormat.JSON.writeValueAsString(version).getBytes(StandardCharsets.UTF_8));
            }
        }
        return entries;
    }

    private SortedMap<String, byte[]> generateRegistration(String id) throws IOException {
        SortedMap<String, byte[]> entries = new TreeMap<>();
        Map<String, Lifecycle.Flag> marks = Lifecycle.versions(store, id);
        for (String version : generateVersions(id).keySet()) {
            entries.put(version, leaf(id, version, marks.get(version)));
        }
        return entries;
    }

    /**
     * Emit a search record per package, in the order the scan yields them.
     *
     * <p>The search document names every package in the repository, so collecting the records into a map held the
     * repository. The scan's order is the sink's order - the store's lexicographic child order, which is what the
     * sorted map used to supply.
     */
    private void generateSearch(StoredListing.Generator.Sink sink) throws IOException {
        ENTRIES.scan(store, "nuget", id -> {
            if (id.startsWith(".") || !id.equals(id.toLowerCase(Locale.ROOT))) {
                return;     // the reserved hosted-publish marker (nuget/.hosted) is not a package id
            }
            // Each package's version list, materialised if need be - without the derivation that would update the
            // very document this generation is producing.
            Optional<StoredListing.Document> document = StoredListing.read(store,
                    StoredListing.Spec.materialising(versions(id), VERSIONS, () -> generateVersions(id)));
            if (document.isEmpty()) {
                return;     // nothing to list for this package
            }
            SortedMap<String, byte[]> listed = VERSIONS.split(document.get().body());
            if (listed.isEmpty()) {
                sink.absent(id, document.get().header().seq());
            } else {
                sink.accept(id, record(id, listed.keySet()), document.get().header().seq());
            }
        });
    }

    /** The stride the repository-wide index is enumerated in. It <b>drains</b>: the search document names every
     *  package by definition, so neither the names nor the round-trips that fetch them may cap it, and what is
     *  bounded is how many names are in hand at once. Capping either one silently omits packages - or, once the
     *  entry cap alone was lifted, stopped omitting them and started throwing instead, at exactly
     *  {@code steps x page} names. That is the ceiling the OCI tag canary hit at a million: a generator that raises
     *  {@code TraversalException} does not answer short, it never materialises the document at all. */
    private static final BoundedChildren ENTRIES = BoundedChildren.draining();

    // ---- rendering ----

    private static byte[] record(String id, Collection<String> versions) throws IOException {
        List<String> ordered = new ArrayList<>(versions);
        ordered.sort(Semver::compare);
        List<Map<String, Object>> listed = new ArrayList<>();
        for (String version : ordered) {
            listed.add(Map.of("version", version, "downloads", 0));
        }
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", id);
        record.put("version", ordered.getLast());
        record.put("versions", listed);
        return NuGetFormat.JSON.writeValueAsBytes(record);
    }

    private byte[] leaf(String id, String version, Lifecycle.Flag flag) throws IOException {
        String self = BASE + "/v3/registrations/" + id + "/index.json";
        JsonNode groups = NuGetFormat.dependencyGroupsFor(id, version, blobs);
        Map<String, Object> catalogEntry = new LinkedHashMap<>();
        catalogEntry.put("@id", self + "#" + version);
        catalogEntry.put("id", id);
        catalogEntry.put("version", version);
        catalogEntry.put("dependencyGroups", groups);
        if (flag != null) {
            switch (flag.state()) {
                case YANKED -> catalogEntry.put("listed", false);
                case DEPRECATED -> {
                    Map<String, Object> deprecation = new LinkedHashMap<>();
                    deprecation.put("reasons", List.of("Other"));
                    if (flag.message() != null && !flag.message().isBlank()) {
                        deprecation.put("message", flag.message());
                    }
                    catalogEntry.put("deprecation", deprecation);
                }
            }
        }
        Map<String, Object> leaf = new LinkedHashMap<>();
        leaf.put("@id", self + "#" + version);
        leaf.put("catalogEntry", catalogEntry);
        leaf.put("packageContent", BASE + "/v3-flatcontainer/" + id + "/" + version + "/" + id + "." + version
                + ".nupkg");
        return NuGetFormat.JSON.writeValueAsBytes(leaf);
    }

    /** Regenerate the listing at this key if it is a NuGet one: a version list, a registration index or the search
     *  document. */
    boolean rebuild(String listing) throws IOException {
        if (listing.equals(SEARCH)) {
            StoredListing.rebuild(store, searchSpec());
            return true;
        }
        String[] segments = listing.split("/");
        if (segments.length != 3 || !segments[0].equals("nuget")) {
            return false;
        }
        if (segments[2].equals("index.json")) {
            StoredListing.rebuild(store, versionsSpec(segments[1]));
            return true;
        }
        if (segments[2].equals("registration.json")) {
            StoredListing.rebuild(store, registrationSpec(segments[1]));
            return true;
        }
        return false;
    }

    // ---- the write path ----

    /** Re-decide one version's entries from the store's current state - after a push, a hold, a release or a mark. */
    void refresh(String id, String version) throws IOException {
        boolean servable = !blobs.withheld(NuGetFormat.nupkgKey(id, version))
                && blobs.exists(NuGetFormat.nupkgKey(id, version));
        if (servable) {
            StoredListing.put(store, versionsSpec(id), version,
                    NuGetFormat.JSON.writeValueAsString(version).getBytes(StandardCharsets.UTF_8));
            StoredListing.put(store, registrationSpec(id), version,
                    leaf(id, version, Lifecycle.read(store, id, version).orElse(null)));
        } else {
            StoredListing.remove(store, versionsSpec(id), version);
            StoredListing.remove(store, registrationSpec(id), version);
        }
    }
}
