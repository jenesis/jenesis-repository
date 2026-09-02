package build.jenesis.repository.format.raw;

import module java.base;

import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.ServableNames;
import build.jenesis.repository.store.StoredListing;
import build.jenesis.repository.walk.BoundedChildren;
import build.jenesis.repository.walk.ScreenedNames;
import build.jenesis.repository.format.Listings;

/**
 * The raw format's directory pages as stored listings: one page per folder, its entries the folder's servable
 * children - a file whose blob is present and not withheld, or a sub-folder - keyed by name. A publish adds its
 * file to its folder's page and the folder to every ancestor's; a removal, a hold or a release re-decides the one
 * entry; so a directory page is one read however many files the folder holds.
 */
final class RawListings {

    private static final StoredListing.Codec LINKS = StoredListing.Codec.delimited("\n", link -> {
        int start = link.indexOf('>') + 1;
        int end = link.indexOf("</a>");
        return start > 0 && end > start ? unescape(link.substring(start, end)) : "";
    });

    static final StoredListing.Codec PAGE = StoredListing.framed("<!DOCTYPE html><html><body>\n", "</body></html>",
            LINKS);

    private final ArtifactStore store;
    private final ServableNames names;

    RawListings(ArtifactStore store) {
        this.store = store;
        this.names = new ServableNames(store, new Publication(store));
    }

    /** The listing key of a folder ({@code /raw/a/b/} is {@code raw/%2Fraw%2Fa%2Fb%2F}): flat, so a folder and its
     *  sub-folder never nest one document under another. */
    static String page(String folder) {
        return "raw/" + URLEncoder.encode(folder, StandardCharsets.UTF_8);
    }

    /** The folder of a served path ({@code /raw/a/b/c.txt} is {@code /raw/a/b/}), or {@code null} at the root. */
    static String folderOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash < "/raw".length() ? null : path.substring(0, slash + 1);
    }

    StoredListing.Spec spec(String folder) {
        return StoredListing.Spec.of(page(folder), PAGE, sink -> generate(folder, sink));
    }

    /**
     * Emit a link per served child, in the order the scan yields them.
     *
     * <p>The scan was already paged - a thousand names at a time, by cursor - so the names were never all in hand;
     * what was, until this emitted instead of collecting, is the <em>page</em>: an entry per child of the folder,
     * held in a map until the last one arrived. A raw folder has no bound on its children, and this generator runs
     * on the first read of a folder page that does not exist yet, so that map was the whole folder on a request
     * path. Emitting hands each link to the codec, which writes it and lets it go.
     *
     * <p>The scan's order is the sink's order, which is what the contract requires: the cursor it pages by is only
     * meaningful over one, so ascending is what it already yields.
     */
    private void generate(String folder, StoredListing.Generator.Sink sink) throws IOException {
        String prefix = ServableNames.PUBLISHED + folder.substring(0, folder.length() - 1);
        ScreenedNames.paths(names, ServableNames.Policy.HIDE_WITHHELD_AND_GONE)
                .containers(childKey -> hasChild(childKey))
                .scanning(BoundedChildren.draining(1000))
                .scan(store, prefix, (child, _) -> {
                    try {
                        sink.accept(child, link(child));
                    } catch (IOException cause) {
                        throw new UncheckedIOException(cause);
                    }
                });
    }

    private boolean hasChild(String prefix) {
        boolean[] any = {false};
        store.page(prefix, "", 1, _ -> any[0] = true);
        return any[0];
    }

    private static byte[] link(String child) {
        return ("<a href=\"" + Listings.html(child) + "\">" + Listings.html(child) + "</a><br/>").getBytes(StandardCharsets.UTF_8);
    }

    /** A file was published, removed, held or released: re-decide its entry in its folder's page, and the folder's
     *  in every ancestor's (a folder is listed while it has a child). */
    void refresh(String path) throws IOException {
        String folder = folderOf(path);
        if (folder == null) {
            return;
        }
        String child = path.substring(folder.length());
        boolean servable = names.state(path) == ServableNames.State.SERVABLE;
        if (servable) {
            StoredListing.put(store, spec(folder), child, link(child));
        } else {
            StoredListing.remove(store, spec(folder), child);
        }
        // Ancestors: a folder is listed in its parent while it has any child at all.
        for (String current = folder; current != null; ) {
            String parent = folderOf(current.substring(0, current.length() - 1));
            if (parent == null) {
                break;
            }
            String name = current.substring(parent.length(), current.length() - 1);
            if (hasChild(ServableNames.PUBLISHED + current.substring(0, current.length() - 1))) {
                StoredListing.put(store, spec(parent), name, link(name));
                break;   // the folder exists, so every ancestor already lists its own child
            }
            StoredListing.remove(store, spec(parent), name);
            current = parent;
        }
    }

    /** Regenerate the page at this listing key if it is a raw folder page. */
    boolean rebuild(String listing) throws IOException {
        if (!listing.startsWith("raw/") || listing.indexOf('/', "raw/".length()) >= 0) {
            return false;
        }
        String folder = URLDecoder.decode(listing.substring("raw/".length()), StandardCharsets.UTF_8);
        if (!folder.startsWith("/raw/") || !folder.endsWith("/")) {
            return false;
        }
        StoredListing.rebuild(store, spec(folder));
        return true;
    }


    static String unescape(String text) {
        if (text.indexOf('&') < 0) {
            return text;
        }
        return text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&");
    }
}
