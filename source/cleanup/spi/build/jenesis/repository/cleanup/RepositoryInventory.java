package build.jenesis.repository.cleanup;

import module java.base;
import build.jenesis.repository.bounds.InheritedBound;

/**
 * The cleanup's view of the stored repository: the published releases it can enumerate, and the removal of one. The
 * store-backed inventory's {@link #evict} deletes a version's layout pointers and leaves unreferenced blobs to the
 * garbage collector; a list-backed one lets the policy and the sweep be exercised without a store.
 */
public interface RepositoryInventory {

    Collection<Release> releases() throws IOException;

    /**
     * Stream every published release to {@code visitor}, <em>grouped by coordinate</em>: all versions of one
     * (ecosystem, coordinate) arrive consecutively, in no other promised order. The retention plan judges one
     * coordinate's versions together, so grouping lets it hold one coordinate's versions rather than the repository's.
     *
     * <p><strong>An inventory streams its own key tree.</strong> The inherited default buffers the whole
     * {@link #releases()} list through {@link #groupByListing}, so past the ceiling {@link InheritedBound} states it
     * throws, naming the inheriting class and the remedy. The store-backed inventory overrides it (and, given the
     * shared artifact walk, delivers this caller's share of a resumable, segmented pass); a list-backed inventory calls
     * {@link #groupByListing} by name.
     *
     * @throws IllegalStateException when the inherited fallback holds more releases than {@link InheritedBound} permits
     *     an inherited default to materialise
     */
    default void releases(ReleaseVisitor visitor) throws IOException {
        groupByListing(this, visitor);
    }

    /**
     * Regroup {@code inventory}'s whole {@link #releases()} list by coordinate and emit it - the named form of the
     * inherited fallback, for an inventory whose releases are already in memory. Bounded by {@link InheritedBound}.
     *
     * @throws IllegalStateException when the inventory holds more releases than {@link InheritedBound} permits an
     *     inherited default to materialise
     */
    static void groupByListing(RepositoryInventory inventory, ReleaseVisitor visitor) throws IOException {
        Map<List<String>, List<Release>> grouped = new LinkedHashMap<>();
        for (Release release : InheritedBound.bounded(inventory, "releases(ReleaseVisitor)", "releases()",
                inventory.releases())) {
            grouped.computeIfAbsent(Arrays.asList(release.ecosystem(), release.coordinate()),
                    _ -> new ArrayList<>()).add(release);
        }
        for (List<Release> group : grouped.values()) {
            for (Release release : group) {
                visitor.visit(release);
            }
        }
    }

    void evict(Release release) throws IOException;

    /** A visitor over a streamed release enumeration, allowed the per-release store I/O a sweep does. */
    @FunctionalInterface
    interface ReleaseVisitor {
        void visit(Release release) throws IOException;
    }
}
