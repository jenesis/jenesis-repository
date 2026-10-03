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
 * A NuGet package's served documents as stored listings: the flat-container version list ({@code index.json}), the
 * registration index (one page of version leaves whose URLs carry the {@value #BASE} placeholder, completed on the way
 * out), and the repository-wide search document, a record per package id naming its servable versions.
 *
 * <p>A version is listed exactly when its {@code .nupkg} pointer is not withheld; a lifecycle mark renders its leaf
 * unlisted or deprecated. A write to a package's version list re-derives its search record, so a publish rewrites the
 * package's two documents and the search document, never scanning other packages.
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
        return new StoredListing.Codec.Streaming() {
            @Override
            public byte[] join(SortedMap<String, byte[]> entries) {
                return ("{" + NuGetFormat.JSON.writeValueAsString(member) + ":" + array(entries.values()) + "}")
                        .getBytes(StandardCharsets.UTF_8);
            }

            /** The same document, written as the elements arrive: the search document is every package in the
             *  repository, so it is never collected into a map. */
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

            /** The stored elements one at a time out of the parser's bounded buffer, each read as a tree and written
             *  back compactly. For a document this codec wrote that is the same bytes, since {@link #join} emits
             *  compact JSON and Jackson keeps member order. */
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

    /** The registration index of one package: one page of version leaves in semantic-version order, with
     *  {@code lower}/{@code upper} and the counts computed on join. */
    static StoredListing.Codec registrationCodec(String id) {
        String self = BASE + "/v3/registrations/" + id + "/index.json";
        return new StoredListing.Codec.Streaming() {
            /** The leaves one at a time through a streaming parser, since the index is read on every publish of the
             *  package. The join still collects, for the reason below. */
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

            /** <b>This one collects, and no appender can replace it.</b> A registration lists its leaves in
             *  {@link Semver} order, not the ascending order a {@code Sink} delivers, and its page names its own bounds
             *  and counts, so order and frame depend on every entry. What is held is one package's registration,
             *  bounded by a publisher rather than by the repository. */
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
            // Stated at the version list's sequence, so the rebuild pass's regeneration of the search document, which
            // can lag this write, never puts an older record over this one.
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

    /** Emit a search record per package in the scan's order, the store's lexicographic child order, which is the order
     *  the document needs; the document is every package, so it is never collected. */
    private void generateSearch(StoredListing.Generator.Sink sink) throws IOException {
        ENTRIES.scan(store, "nuget", id -> {
            if (id.startsWith(".") || !id.equals(id.toLowerCase(Locale.ROOT))) {
                return;     // the reserved hosted-publish marker (nuget/.hosted) is not a package id
            }
            // Each package's version list, materialised without the derivation that would update this very document.
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

    /** The stride the repository-wide index is enumerated in. It drains: the search document names every package, so
     *  neither names nor round-trips are capped - a cap would omit packages or throw and never materialise the document
     *  - and only the names in hand are bounded. */
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
