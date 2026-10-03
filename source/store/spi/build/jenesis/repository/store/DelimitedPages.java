package build.jenesis.repository.store;

import module java.base;

/**
 * One page of immediate children assembled from an object store's delimited listing, in the order {@link
 * ArtifactStore#pageListed} promises. The listing arrives in raw key order, where a container shows up as a grouped
 * prefix at {@code name + "/"} - after any sibling whose name extends it past a character below {@code '/'}: the
 * object {@code app.txt} precedes the prefix {@code app/}, yet the child {@code app} must page first. So every name is
 * parked and the smallest released only once no smaller-named child can still arrive. A released name at or below the
 * cursor is dropped, since a backend's start offset may be inclusive and a prefix-child of the boundary re-arrives
 * although the previous page already carried it. A leaf and a same-named container page as one child, keeping the
 * leaf's metadata, which is what a read of that key resolves to.
 */
public final class DelimitedPages {

    private final String startAfter;
    private final int limit;
    private final Consumer<ArtifactStore.Listed> consumer;
    private final TreeMap<String, ArtifactStore.Listed> pending = new TreeMap<>();
    private int emitted;
    private String last;

    /** A page of at most {@code limit} children after {@code startAfter}, handed to {@code consumer}. */
    public DelimitedPages(String startAfter, int limit, Consumer<ArtifactStore.Listed> consumer) {
        this.startAfter = startAfter;
        this.limit = limit;
        this.consumer = consumer;
    }

    /** What the listing said about one child: its name relative to the listed container (a container's with its
     *  trailing slash) and its name as a child. */
    @FunctionalInterface
    public interface Described {

        ArtifactStore.Listed listed(String relative, String name);
    }

    /**
     * One response of the listing: the relative names it carried, objects and grouped prefixes alike, in any order.
     * Answers {@code true} once the page is full, when the caller stops listing.
     */
    public boolean page(List<String> relatives, Described described) {
        List<String> ordered = new ArrayList<>(relatives);
        Collections.sort(ordered);
        for (String relative : ordered) {
            while (!pending.isEmpty() && !held(pending.firstKey(), relative)) {
                Map.Entry<String, ArtifactStore.Listed> entry = pending.pollFirstEntry();
                if (entry.getKey().compareTo(startAfter) > 0) {
                    consumer.accept(entry.getValue());
                    last = entry.getKey();
                    if (++emitted == limit) {
                        return true;
                    }
                }
            }
            String name = relative.endsWith("/") ? relative.substring(0, relative.length() - 1) : relative;
            if (!name.equals(last)) {
                pending.merge(name, described.listed(relative, name),
                        (kept, arriving) -> kept.size().isPresent() ? kept : arriving);
            }
        }
        return false;
    }

    /** The listing is exhausted: release what is still parked, up to the page's limit. */
    public void finish() {
        for (Map.Entry<String, ArtifactStore.Listed> entry : pending.entrySet()) {
            if (entry.getKey().compareTo(startAfter) > 0) {
                consumer.accept(entry.getValue());
                if (++emitted == limit) {
                    return;
                }
            }
        }
    }

    /** Whether {@code name} may not be paged out yet at stream position {@code relative}: a proper prefix of it
     *  whose next character sorts below {@code '/'} could still arrive as a grouped prefix (its container key
     *  {@code prefix + "/"} sorts at or past the position), and that shorter child name must page first. */
    private static boolean held(String name, String relative) {
        for (int index = 1; index < name.length(); index++) {
            if (name.charAt(index) < '/' && relative.compareTo(name.substring(0, index) + "/") <= 0) {
                return true;
            }
        }
        return false;
    }
}
