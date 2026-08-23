package build.jenesis.repository.format.oci;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.JsonMembers;
import build.jenesis.repository.store.ServableNames;
import build.jenesis.repository.store.StoredListing;

import module java.base;

/**
 * An OCI registry's enumerations as stored listings: one tag list per image ({@code tags/list}, entries by tag) and
 * the catalog ({@code _catalog}, entries by image name), the latter re-derived from each image's tag list on every
 * write, so a push costs one rewrite of the image's tag list and one of the catalog, never a walk of the name tree.
 * A tag is listed exactly when the manifest it points at is not withheld - the screen the on-read enumeration
 * applied per tag - and an image exactly when it has a listed tag. Both documents are stored whole and paged in
 * memory for a client's {@code n}/{@code last} window.
 */
final class OciListings {

    static final String CATALOG = "oci/_catalog";

    /** A tag list or the catalog: a JSON array of quoted names under one member. */
    static StoredListing.Codec names(String member) {
        return new StoredListing.Codec() {
            @Override
            public SortedMap<String, byte[]> split(byte[] document) {
                SortedMap<String, byte[]> entries = new TreeMap<>();
                String array = JsonMembers.split(new String(document, StandardCharsets.UTF_8)).get(member);
                if (array != null) {
                    int at = array.indexOf('[') + 1;
                    while (true) {
                        while (at < array.length() && " \t\r\n,".indexOf(array.charAt(at)) >= 0) {
                            at++;
                        }
                        if (at >= array.length() || array.charAt(at) == ']') {
                            break;
                        }
                        int end = JsonMembers.valueEnd(array, at);
                        String element = array.substring(at, end);
                        entries.put(JsonMembers.unquote(element), element.getBytes(StandardCharsets.UTF_8));
                        at = end;
                    }
                }
                return entries;
            }

            @Override
            public byte[] join(SortedMap<String, byte[]> entries) {
                StringBuilder json = new StringBuilder("{").append(JsonMembers.quote(member)).append(":[");
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
        };
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
        return StoredListing.Spec.of(tags(name), TAGS, () -> generateTags(name)).deriving(document -> {
            if (TAGS.split(document.body()).isEmpty()) {
                StoredListing.remove(store, catalogSpec(), name);
            } else {
                StoredListing.put(store, catalogSpec(), name, JsonMembers.quote(name).getBytes(StandardCharsets.UTF_8));
            }
        });
    }

    StoredListing.Spec catalogSpec() {
        return StoredListing.Spec.of(CATALOG, REPOSITORIES, this::generateCatalog);
    }

    private SortedMap<String, byte[]> generateTags(String name) throws IOException {
        SortedMap<String, byte[]> entries = new TreeMap<>();
        for (String tag : store.list("oci/" + name + "/tags")) {
            if (servable(name, tag)) {
                entries.put(tag, JsonMembers.quote(tag).getBytes(StandardCharsets.UTF_8));
            }
        }
        return entries;
    }

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
                Optional<StoredListing.Document> document = StoredListing.read(store,
                        StoredListing.Spec.of(tags(name), TAGS, () -> generateTags(name)));
                if (document.isPresent() && !TAGS.split(document.get().body()).isEmpty()) {
                    entries.put(name, JsonMembers.quote(name).getBytes(StandardCharsets.UTF_8));
                }
                continue;
            }
            if (child.equals("manifests")) {
                continue;
            }
            collect(prefix + "/" + child, childName, entries);
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

    private boolean servable(String name, String tag) throws IOException {
        return names.disclosableKey("oci/" + name + "/tags/" + tag, ServableNames.Policy.HIDE_WITHHELD)
                && store.readVersioned("oci/" + name + "/tags/" + tag).isPresent();
    }

    /** Re-decide one tag's membership from the store's current state - after a push, a hold or a release. */
    void refresh(String name, String tag) throws IOException {
        if (servable(name, tag)) {
            StoredListing.put(store, tagsSpec(name), tag, JsonMembers.quote(tag).getBytes(StandardCharsets.UTF_8));
        } else {
            StoredListing.remove(store, tagsSpec(name), tag);
        }
    }

    /** Re-decide every tag of an image - after a hold on a manifest addressed by digest, whose tags are unknown. */
    void refreshImage(String name) throws IOException {
        StoredListing.Changes changes = new StoredListing.Changes();
        for (String tag : store.list("oci/" + name + "/tags")) {
            if (servable(name, tag)) {
                changes.put(tag, JsonMembers.quote(tag).getBytes(StandardCharsets.UTF_8));
            } else {
                changes.remove(tag);
            }
        }
        if (!changes.isEmpty()) {
            StoredListing.update(store, tagsSpec(name), changes);
        }
    }
}
