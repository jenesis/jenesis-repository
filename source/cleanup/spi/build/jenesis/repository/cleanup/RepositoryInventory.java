package build.jenesis.repository.cleanup;

import module java.base;
import build.jenesis.repository.bounds.InheritedBound;

/**
 * The cleanup's view of the stored repository: the published releases it can enumerate, and the removal of one.
 * Production backs this with the artifact store - {@link #evict} deletes a version's layout pointers, leaving any
 * content blob no surviving pointer still references to the discovered garbage collector - while a test backs it
 * with a list, so the retention policy and the sweep are exercised without a store.
 */
public interface RepositoryInventory {

    Collection<Release> releases() throws IOException;

    /**
     * Stream every published release to {@code visitor}, delivered <em>grouped by coordinate</em>: all versions of
     * one (ecosystem, coordinate) arrive consecutively, in no promised order otherwise. The grouping is the contract
     * a streaming consumer needs - the retention plan judges the versions of one coordinate together (keep-last,
     * never-empty), so grouped delivery lets it hold at most one coordinate's versions instead of the repository's
     * whole release list.
     *
     * <p><strong>An inventory streams its own key tree; the inherited body is a small-inventory fallback and says so
     * out loud.</strong> The {@code default} delegates to {@link #groupByListing}, which buffers the whole
     * {@link #releases()} list and regroups it - so an implementation that inherits it holds the repository's entire
     * published set at once, which is the single thing grouped delivery exists to avoid. So it refuses rather than
     * pretending: past the ceiling {@link InheritedBound} states it throws an {@link IllegalStateException} naming
     * the inheriting class and the remedy. The store-backed production inventory overrides it to stream its key tree
     * (and, constructed with the shared artifact walk, to ride a resumable, segmented, multi-node pass that delivers
     * this caller's share of the current walk pass rather than re-listing everything); a list-backed inventory whose
     * releases genuinely <em>are</em> in memory calls {@link #groupByListing} by name.
     *
     * @throws IllegalStateException when the inherited fallback holds more releases than {@link InheritedBound}
     *                               permits an inherited default to materialise
     */
    default void releases(ReleaseVisitor visitor) throws IOException {
        groupByListing(this, visitor);
    }

    /**
     * Regroup {@code inventory}'s whole {@link #releases()} list by coordinate and emit it - the explicit, named form
     * of the fallback {@link #releases(ReleaseVisitor)} inherits, for an inventory whose release list is already in
     * memory (the list-backed test inventory) and for which a "streaming" walk would be this code anyway. It is
     * bounded, and the bound throws: see {@link InheritedBound}, which holds the ceiling and the refusal for every
     * SPI that ships this shape.
     *
     * @throws IllegalStateException when the inventory holds more releases than {@link InheritedBound} permits an
     *                               inherited default to materialise
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
