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
 * the catalog ({@code _catalog}, entries by image name), re-derived from each image's tag list on every write, so a
 * push costs one rewrite of each and never a walk of the name tree. A tag is listed exactly when the manifest it
 * points at is not withheld, and an image exactly when it has a listed tag. A client's {@code n}/{@code last} window
 * is cut from the stored document as it streams, and an unqualified request is written as the names arrive; neither
 * holds the document.
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
     * <p>The document is consumed in the parser's bounded buffer and never materialised, so answering a window of a
     * hundred names costs those names, and a visitor that stops ends the parse there.
     */
    static void names(InputStream body, String member, NameVisitor visitor) throws IOException {
        // The codec's own reader, so a request and an update cannot decode the same document differently.
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
             * <p>The inherited appender collects every entry and calls {@link #join} at close, which holds the whole
             * document in heap.
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
            // The header counts the entries, so no body is parsed. Stated at the tag list's sequence, so a catalogue
            // regeneration a beat behind this write cannot undo a later one.
            if (document.header().entries() == 0) {
                StoredListing.remove(store, catalogSpec(), name, document.header().seq());
            } else {
                StoredListing.put(store, catalogSpec(), name, quoted(name).getBytes(StandardCharsets.UTF_8),
                        document.header().seq());
            }
        });
    }

    StoredListing.Spec catalogSpec() {
        return StoredListing.Spec.of(CATALOG, REPOSITORIES, sink -> collect("oci", "", sink));
    }

    /**
     * Every servable tag of one image, emitted as the store pages them.
     *
     * <p>Paged and emitted, since a tag list is bounded only by what users push; the store's lexicographic order is the
     * ascending order a {@code Sink} owes. It drains: the default round-trip cap would stop at a million tags with a
     * {@code 500}.
     */
    private void generateTags(String name, StoredListing.Generator.Sink sink) throws IOException {
        BoundedChildren.draining()
                .scan(store, "oci/" + name + "/tags", tag -> {
                    // The scan just delivered the pointer, so only disclosure is asked.
                    if (disclosable(name, tag)) {
                        sink.accept(tag, quoted(tag).getBytes(StandardCharsets.UTF_8));
                    }
                });
    }

    /**
     * Every image name under the {@code oci/} tree - a node carrying a {@code tags} container - with a listed tag, each
     * stated at the sequence of its tag list. Walked once, on first materialisation or by the rebuild pass, never per
     * request. Names are emitted depth first: a node's own entry, then its children by name.
     */
    private void collect(String prefix, String name, StoredListing.Generator.Sink sink) throws IOException {
        List<String> children = new ArrayList<>(store.list(prefix));
        children.sort(Comparator.comparing((String child) -> !child.equals("tags")).thenComparing(child -> child));
        for (String child : children) {
            // A dot-prefixed child is one of the format's own spaces: no image name may begin with a dot.
            if (child.startsWith(".")) {
                continue;
            }
            String childName = name.isEmpty() ? child : name + "/" + child;
            if (child.equals("tags") && !name.isEmpty()) {
                // The header counts the tags, so the list is materialised if absent and its body never read. Not
                // tagsSpec(name): that one derives the catalogue being built here.
                Optional<StoredListing.Served> document = StoredListing.open(store,
                        StoredListing.Spec.of(tags(name), TAGS, tagged -> generateTags(name, tagged)));
                if (document.isPresent()) {
                    try (StoredListing.Served served = document.get()) {
                        if (served.header().entries() > 0) {
                            sink.accept(name, quoted(name).getBytes(StandardCharsets.UTF_8), served.header().seq());
                        } else {
                            sink.absent(name, served.header().seq());
                        }
                    }
                }
                continue;
            }
            if (child.equals("manifests")) {
                continue;
            }
            collect(prefix + "/" + child, childName, sink);
        }
    }

    /**
     * Create the tag lists this registry's stored pointers imply but which do not exist yet, and the catalogue
     * over them.
     *
     * <p>For a migration: {@link OciImporter} lays tag pointers out without a push, so no tag list exists after it,
     * and the first {@code tags/list} would otherwise generate a large one on a request thread. Under
     * {@link StoredListing.Rebuilder.Scope#MISSING} a stored tag list is skipped on a header probe; under {@code ALL}
     * every one is regenerated, since a read racing the import may have stored a short one.
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
            if (child.startsWith(".")) {
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

    /** Regenerate the listing at this key if it is an OCI one: an image's tag list, the catalog, or a manifest's
     *  referrers index. */
    boolean rebuild(String listing) throws IOException {
        if (listing.equals(CATALOG)) {
            StoredListing.rebuild(store, catalogSpec());
            return true;
        }
        Optional<String[]> referrers = OciReferrers.parse(listing);
        if (referrers.isPresent()) {
            StoredListing.rebuild(store, new OciReferrers(store).spec(referrers.get()[0], referrers.get()[1]));
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
     * <p>For a caller naming a tag that may not exist; a generator the scan has just told the name uses
     * {@link #disclosable}.
     */
    private boolean servable(String name, String tag) throws IOException {
        return disclosable(name, tag) && store.exists("oci/" + name + "/tags/" + tag);
    }

    /**
     * The disclosure half alone, for a tag the caller already knows is stored.
     *
     * <p>The existence half is a whole-object read per tag, which a generation over a just-enumerated container would
     * pay for every tag of the image.
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
