package build.jenesis.repository.format.oci;

import module java.base;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.walk.BoundedChildren;
import build.jenesis.repository.store.ServableNames;
import build.jenesis.repository.store.StoredListing;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.json.JsonMapper;

/**
 * An OCI registry's enumerations as stored listings: one tag list per image ({@code tags/list}, entries by tag) and
 * the catalog ({@code _catalog}, entries by image name), the latter re-derived from each image's tag list on every
 * write, so a push costs one rewrite of the image's tag list and one of the catalog, never a walk of the name tree.
 * A tag is listed exactly when the manifest it points at is not withheld - the screen the on-read enumeration
 * applied per tag - and an image exactly when it has a listed tag. A client's {@code n}/{@code last} window is cut
 * from the stored document as it streams, and an unqualified request - which the Distribution specification says
 * answers every name - is written to the socket as the names arrive; neither holds the document.
 */
final class OciListings {

    static final String CATALOG = "oci/_catalog";

    /** A tag list or the catalog: a JSON array of quoted names under one member. */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Told a name; answers whether the walk should carry on. */
    interface NameVisitor {

        boolean accept(String name) throws IOException;
    }

    /**
     * Hand each name in the document's {@code member} array to {@code visitor}, in stored order, stopping as soon
     * as it answers {@code false}.
     *
     * <p>The document is consumed in the parser's bounded read buffer and never materialised. That is the whole
     * point: {@code tags/list} and {@code _catalog} answer a window of a few names, and the code that cut that
     * window used to read the entire document into a byte array and split it into a map of every name in it - so
     * a repository with half a million tags paid for all of them to answer a request for a hundred. A visitor that
     * stops also means the parse ends at the window rather than at the end of the document.
     */
    static void names(InputStream body, String member, NameVisitor visitor) throws IOException {
        // The codec's own reader, driven to exhaustion rather than to a window: one parser, two shapes, so the
        // walk a request makes and the read an update makes cannot decode the same document differently.
        try (StoredListing.Codec.Reader reader = names(member).read(body, -1L)) {
            for (Optional<Map.Entry<String, byte[]>> entry = reader.next(); entry.isPresent(); entry = reader.next()) {
                if (!visitor.accept(entry.get().getKey())) {
                    return;
                }
            }
        }
    }

    static StoredListing.Codec names(String member) {
        return new StoredListing.Codec() {
            @Override
            public SortedMap<String, byte[]> split(byte[] document) {
                SortedMap<String, byte[]> entries = new TreeMap<>();
                try (Reader reader = read(new ByteArrayInputStream(document), document.length)) {
                    for (Optional<Map.Entry<String, byte[]>> entry = reader.next();
                         entry.isPresent(); entry = reader.next()) {
                        entries.put(entry.get().getKey(), entry.get().getValue());
                    }
                } catch (IOException unreadable) {
                    throw new UncheckedIOException(unreadable);
                }
                return entries;
            }

            @Override
            public byte[] join(SortedMap<String, byte[]> entries) {
                StringBuilder json = new StringBuilder("{").append(quoted(member)).append(":[");
                boolean first = true;
                for (byte[] entry : entries.values()) {
                    if (!first) {
                        json.append(',');
                    }
                    first = false;
                    json.append(new String(entry, StandardCharsets.UTF_8));
                }
                return json.append("]}").toString().getBytes(StandardCharsets.UTF_8);
            }

            /**
             * The same document, written as the entries arrive.
             *
             * <p>Without this the inherited appender collects every entry into a map and calls {@link #join} at
             * close - so a streaming generator above it streamed into a buffer, and a tag push rewrote the whole
             * document in heap. A registry with 200,000 tags died in {@code join}'s {@code StringBuilder}, which
             * is the one place all of that ends up.
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
                        open();                                  // an empty document is still {"member":[]}
                        out.write("]}".getBytes(StandardCharsets.UTF_8));
                    }

                    private void open() throws IOException {
                        if (!opened) {
                            out.write(("{" + quoted(member) + ":[").getBytes(StandardCharsets.UTF_8));
                            opened = true;
                        }
                    }
                };
            }

            /** The entries of a stored document, pulled one at a time out of the parser's bounded buffer. The
             *  length is not consulted: the array's end is a token, not an offset. */
            @Override
            public Reader read(InputStream in, long ignored) throws IOException {
                JsonParser parser = JSON.createParser(in);
                boolean found = false;
                if (parser.nextToken() == JsonToken.START_OBJECT) {
                    while (!found && parser.nextToken() == JsonToken.PROPERTY_NAME) {
                        boolean wanted = member.equals(parser.currentName());
                        parser.nextToken();                      // advance onto the field's value
                        if (wanted && parser.currentToken() == JsonToken.START_ARRAY) {
                            found = true;
                        } else {
                            parser.skipChildren();               // scalar (no-op) or an unrelated subtree
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
    }

    /**
     * One JSON string, quoted and escaped by Jackson.
     *
     * <p>An entry of these documents is a bare name, and a document is those names in an array - so the fragment
     * stored per entry is the name as a JSON string. It is written by the mapper rather than by wrapping quotes
     * around it, because escaping is exactly the part a hand-rolled version gets wrong on the one input nobody
     * tested with.
     */
    private static String quoted(String value) {
        return JSON.writeValueAsString(value);
    }

    static final StoredListing.Codec TAGS = names("tags");

    static final StoredListing.Codec REPOSITORIES = names("repositories");

    private final ArtifactStore store;
    private final ServableNames names;

    OciListings(ArtifactStore store) {
        this.store = store;
        this.names = new ServableNames(store);
    }

    static String tags(String name) {
        return "oci/" + name + "/tags/list";
    }

    StoredListing.Spec tagsSpec(String name) {
        return StoredListing.Spec.of(tags(name), TAGS, sink -> generateTags(name, sink)).deriving(document -> {
            // The header already counts the entries, so this asks it rather than splitting the body into a map of
            // every tag to see whether the map is empty - a whole document parsed, on every tag push, to answer a
            // question that is one field of the header the same write just computed.
            if (document.header().entries() == 0) {
                StoredListing.remove(store, catalogSpec(), name);
            } else {
                StoredListing.put(store, catalogSpec(), name, quoted(name).getBytes(StandardCharsets.UTF_8));
            }
        });
    }

    StoredListing.Spec catalogSpec() {
        return StoredListing.Spec.materialising(CATALOG, REPOSITORIES, this::generateCatalog);
    }

    /**
     * Every servable tag of one image, emitted as the store pages them.
     *
     * <p>Paged rather than {@code list}ed, and emitted rather than collected: a tag list is bounded by nothing but
     * how many tags a user has pushed at one image, so the map this used to build was sized by that and so was the
     * {@code list} that filled it. The scan hands back children in the store's lexicographic order, which is the
     * ascending order a {@code Sink} owes and the order the codec and the cursor paging both read the document in.
     */
    private void generateTags(String name, StoredListing.Generator.Sink sink) throws IOException {
        BoundedChildren.bounded().entries(Integer.MAX_VALUE).page(1_000)
                .scan(store, "oci/" + name + "/tags", tag -> {
                    // disclosable, not servable: the scan just delivered this name out of the tag container, so
                    // the pointer is there by construction and re-reading it to learn that is pure cost.
                    if (disclosable(name, tag)) {
                        sink.accept(tag, quoted(tag).getBytes(StandardCharsets.UTF_8));
                    }
                });
    }

    /**
     * <b>This one collects deliberately, and the sorted map is doing real work.</b> Every other
     * repository-wide generator streams into a {@code Sink}, which owes its entries in ascending key
     * order and takes that order from the scan.
     *
     * <p>That does not hold here: {@link #collect} walks the name tree <em>recursively</em>, composing
     * {@code name + "/" + child} - so the nesting, and the same ordering mismatch a two-level scan has, is
     * hidden inside the recursion rather than visible as a loop.
     *
     * <p>So the map is what puts these entries in order. Removing it would write a misordered
     * document - which the codecs and the cursor paging both assume is ascending, and which nothing
     * would report.
     */
    private SortedMap<String, byte[]> generateCatalog() throws IOException {
        SortedMap<String, byte[]> entries = new TreeMap<>();
        collect("oci", "", entries);
        return entries;
    }

    /** Every image name under the {@code oci/} tree - a name is a node carrying a {@code tags} container - with a
     *  listed tag; the tree is walked once here, on first materialisation, never per request. */
    private void collect(String prefix, String name, SortedMap<String, byte[]> entries) throws IOException {
        for (String child : store.list(prefix)) {
            if (child.equals("uploads") || child.equals("upload-sessions") || child.equals("types")) {
                continue;
            }
            String childName = name.isEmpty() ? child : name + "/" + child;
            if (child.equals("tags") && !name.isEmpty()) {
                // The image is catalogued when its tag list has an entry, and the header counts them - so the tag
                // list is materialised if absent and then not read. Splitting its body into a map of every tag to
                // ask whether the map is empty made building the catalogue cost every tag in the registry.
                // Deliberately not tagsSpec(name): that one derives the catalogue, which is what is being built.
                Optional<StoredListing.Served> document = StoredListing.open(store,
                        StoredListing.Spec.of(tags(name), TAGS, sink -> generateTags(name, sink)));
                if (document.isPresent()) {
                    try (StoredListing.Served served = document.get()) {
                        if (served.header().entries() > 0) {
                            entries.put(name, quoted(name).getBytes(StandardCharsets.UTF_8));
                        }
                    }
                }
                continue;
            }
            if (child.equals("manifests")) {
                continue;
            }
            collect(prefix + "/" + child, childName, entries);
        }
    }

    /**
     * Create the tag lists this registry's stored pointers imply but which do not exist yet, and the catalogue
     * over them.
     *
     * <p>The case this exists for is a <b>migration</b>. {@link OciImporter} lays a source registry out directly -
     * a tag becomes an {@code oci/<name>/tags/<tag>} pointer without a push through this format - so after an
     * import every pointer is present and no tag list is. The repair pass regenerates listings that exist and so
     * has nothing to claim, and the first {@code tags/list} generates the document inline: measured at 200,000
     * tags, <b>35 seconds</b> on a request thread against 186 ms once it exists.
     *
     * <p>Under {@link StoredListing.Rebuilder.Scope#MISSING} an image whose tag list is already stored is skipped
     * on a header probe, so a converged registry does no work and the repair pass stays read-first. Under
     * {@code ALL} every tag list is regenerated, which is what an import needs: it laid the pointers out without
     * the observers, so a read that raced the walk may have left a document that exists and is short.
     */
    int materialise(StoredListing.Rebuilder.Scope scope) throws IOException {
        List<String> images = new ArrayList<>();
        images(store, "oci", "", images);
        int built = 0;
        for (String name : images) {
            if (scope == StoredListing.Rebuilder.Scope.ALL
                    || StoredListing.header(store, tags(name)).isEmpty()) {
                StoredListing.rebuild(store, tagsSpec(name));
                built++;
            }
        }
        if (built > 0 || StoredListing.header(store, CATALOG).isEmpty()) {
            StoredListing.rebuild(store, catalogSpec());
        }
        return built;
    }

    /** Every image name under the {@code oci/} tree - a name is a node carrying a {@code tags} container. */
    private static void images(ArtifactStore store, String prefix, String name, List<String> images)
            throws IOException {
        for (String child : store.list(prefix)) {
            if (child.equals("uploads") || child.equals("upload-sessions") || child.equals("types")
                    || child.equals("manifests")) {
                continue;
            }
            if (child.equals("tags")) {
                if (!name.isEmpty()) {
                    images.add(name);
                }
                continue;
            }
            images(store, prefix + "/" + child, name.isEmpty() ? child : name + "/" + child, images);
        }
    }

    /** Regenerate the listing at this key if it is an OCI one: an image's tag list or the catalog. */
    boolean rebuild(String listing) throws IOException {
        if (listing.equals(CATALOG)) {
            StoredListing.rebuild(store, catalogSpec());
            return true;
        }
        if (listing.startsWith("oci/") && listing.endsWith("/tags/list")) {
            StoredListing.rebuild(store, tagsSpec(listing.substring("oci/".length(), listing.length() - "/tags/list".length())));
            return true;
        }
        return false;
    }

    /**
     * Whether a tag is listed: it is disclosable, and its pointer is there.
     *
     * <p>For a caller naming a tag that may not exist - a refresh after a push, a hold or a removal. A generator
     * walking the tag container has already been told the name by the scan, so it uses {@link #disclosable}
     * instead and does not pay to discover what the scan just said.
     */
    private boolean servable(String name, String tag) throws IOException {
        return disclosable(name, tag) && store.exists("oci/" + name + "/tags/" + tag);
    }

    /**
     * The disclosure half alone, for a tag the caller already knows is stored.
     *
     * <p>Split out because the existence half was measurably not free: {@code readVersioned} reads the pointer's
     * bytes to answer a question about its presence, and generation asked it once per tag over a container the
     * scan had just enumerated - so an 800,000-tag image paid 800,000 whole-object reads to confirm 800,000
     * names the store had already handed it. Serving that image's tag list took nine minutes.
     */
    private boolean disclosable(String name, String tag) throws IOException {
        return names.disclosableKey("oci/" + name + "/tags/" + tag, ServableNames.Policy.HIDE_WITHHELD);
    }

    /** Re-decide one tag's membership from the store's current state - after a push, a hold or a release. */
    void refresh(String name, String tag) throws IOException {
        if (servable(name, tag)) {
            StoredListing.put(store, tagsSpec(name), tag, quoted(tag).getBytes(StandardCharsets.UTF_8));
        } else {
            StoredListing.remove(store, tagsSpec(name), tag);
        }
    }

    /** Re-decide every tag of an image - after a hold on a manifest addressed by digest, whose tags are unknown. */
    void refreshImage(String name) throws IOException {
        StoredListing.Changes changes = new StoredListing.Changes();
        for (String tag : store.list("oci/" + name + "/tags")) {
            if (servable(name, tag)) {
                changes.put(tag, quoted(tag).getBytes(StandardCharsets.UTF_8));
            } else {
                changes.remove(tag);
            }
        }
        if (!changes.isEmpty()) {
            StoredListing.update(store, tagsSpec(name), changes);
        }
    }
}
