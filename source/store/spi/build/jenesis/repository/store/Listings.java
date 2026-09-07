package build.jenesis.repository.store;

import module java.base;

import build.jenesis.repository.store.ArtifactStore.Listed;

/**
 * {@link Names} with what the backend's listing already said about each child: a level's {@link Listed} entries
 * pulled one at a time over the store's {@link ArtifactStore#pageListed pages}, holding one page, at the same drain
 * width. A pass that needs each child's size or age reads it here rather than asking the store once per child - over
 * an object store that is one HEAD per object, and the quota recompute paid it for every blob it summed although the
 * listing that named the blob had carried its size. A pass that needs only names keeps {@link Names}; the two are one
 * shape, and neither is the other's default.
 */
@FunctionalInterface
public interface Listings {

    /** The next entry in key order, or {@code null} once the level is exhausted. */
    Listed next() throws IOException;

    static Listings over(ArtifactStore store, String prefix) {
        return over(store, prefix, ArtifactStore.DRAIN_PAGE);
    }

    static Listings over(ArtifactStore store, String prefix, int page) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(prefix, "prefix");
        if (page <= 0) {
            throw new IllegalArgumentException("a page holds at least one entry: " + page);
        }
        return new Listings() {
            private final ArrayDeque<Listed> entries = new ArrayDeque<>();
            private String after = "";
            private boolean exhausted;

            @Override
            public Listed next() throws IOException {
                while (entries.isEmpty() && !exhausted) {
                    List<Listed> fetched = new ArrayList<>();
                    store.pageListed(prefix, after, page, fetched::add);
                    if (fetched.size() < page) {
                        exhausted = true;
                    }
                    if (!fetched.isEmpty()) {
                        after = ArtifactStore.name(fetched.getLast().key());
                        entries.addAll(fetched);
                    }
                }
                return entries.poll();
            }
        };
    }
}
