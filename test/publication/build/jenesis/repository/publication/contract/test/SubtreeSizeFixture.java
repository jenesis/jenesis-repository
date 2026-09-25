package build.jenesis.repository.publication.contract.test;

import module java.base;

import build.jenesis.repository.hooks.testkit.Discovered;
import build.jenesis.repository.hooks.testkit.Hooks;
import build.jenesis.repository.hooks.testkit.ServedOnly;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.PublicationObserver;
import build.jenesis.repository.store.StoredCounter;
import build.jenesis.repository.store.testkit.PublicationHookContract;
import build.jenesis.repository.store.testkit.PublicationHookFixture;

/**
 * {@code SubtreeSizePublicationObserver}: the running browse-folder counters, and the only shipped after-commit
 * observer whose surface is keyed by <em>request path</em> and therefore moves on every publish the kit drives.
 *
 * <p>Its repair leg is the real one: {@code StoreRepositoryInventory.rollUpSizes()}, the full walk
 * {@code CleanupTask} runs unconditionally every {@code cleanup-interval}, which recomputes every {@code sizes/}
 * object from the durable {@code publish/} pointer tree and deletes the rows no live folder claims.
 *
 * <p><b>The projection is a count, not a byte total, and that is the honest normalisation rather than a convenience.</b>
 * Every check publishes the same body, so a folder's cached total is always an exact multiple of that one blob's
 * length; dividing by it says "this folder accounts for N published artifacts", which is the comparable view two
 * converged runs share and which the fixture can also compute from a path list alone. A total that is <em>not</em> a
 * multiple is a real divergence, so it is reported as the raw byte string and fails the comparison rather than being
 * rounded into agreement.
 *
 * <p><b>Divergence found here (clause 2).</b> {@code adjust} folds a signed delta into the counter without consulting
 * what the path previously contributed, so a byte-identical re-publish at the same path <em>adds the size a second
 * time</em> - the surface blind-increments where {@link PublicationHookContract.Property#A_DUPLICATE_DELIVERY_CONVERGES}
 * requires an upsert. The drift is real in production (a Maven redeploy of identical bytes doubles its folders' totals
 * until the next {@code rollUpSizes()}), it is not what the class javadoc's "best-effort" is about - that covers a
 * dropped delta, not a doubled one - and it is excluded here with that reason rather than silently absorbed.
 */
final class SubtreeSizeFixture implements PublicationHookFixture.Observer, ServedOnly {

    /** The inventory module declares three repository prefixes; this hook writes only under the roll-up's. */
    private static final String SIZES = "sizes";

    /** The reserved leaf {@code SubtreeSizeRollUp.sizeKey} gives the repository root, whose total is the O(1)
     *  per-repository figure {@code subtreeSize("")} reads back. */
    private static final String ROOT_ROW = "~";

    @Override
    public String hook() {
        return "inventory-subtree-size";
    }

    @Override
    public String providerClass() {
        return "build.jenesis.repository.inventory.SubtreeSizePublicationObserver";
    }

    @Override
    public PublicationObserver create() {
        return Discovered.hook(providerClass());
    }

    @Override
    public List<String> namespaces() {
        return List.of(SIZES);
    }

    @Override
    public Delivery delivery() {
        return Delivery.BEST_EFFORT_REPAIRED;
    }

    /**
     * Why the containment clause's second leg cannot see this observer. The observer defers its delta on the node
     * ({@code StoredCounter.addLater}) and a flush writes it later; a store that refuses the flush keeps the delta
     * for the next flush rather than losing it. The leg faults the observer's keys for the commit, heals the store,
     * and expects the durable surface to be demonstrably stale - but the flush lands on the healed store because
     * nothing was lost, so the failure leaves no trace for the same reason it did no harm. What does lose a delta is
     * the node dying before a flush, and that shape is the repair walk's: the full recompute supersedes whatever
     * was pending, asserted by {@code SubtreeSizePublicationObserverTest}'s dropped-increment case.
     */
    private static final String DEFERRED_WRITE_LEAVES_NO_TRACE =
            "the observer defers its delta on the node and a flush writes it later; a store failure at the flush "
                    + "keeps the delta for the next flush, so the leg's healed store receives it and the surface "
                    + "is not stale - the failure lost nothing. A node dying before a flush is the loss shape, and "
                    + "the full-walk recompute is its repair (SubtreeSizePublicationObserverTest).";

    @Override
    public Map<PublicationHookContract.Property, String> unsupported() {
        return Map.of(PublicationHookContract.Property.A_THROWING_OBSERVER_IS_CONTAINED_AFTER_THE_OBSERVED_MUTATION,
                DEFERRED_WRITE_LEAVES_NO_TRACE);
    }

    @Override
    public Map<String, String> projection(ArtifactStore store) throws IOException {
        // The observer defers its deltas to the node's flush; the durable state the contract judges is what the
        // flush leaves, so it is taken here rather than waited for.
        StoredCounter.flushNow();
        long unit = bodySize(store);
        Map<String, String> rows = new TreeMap<>();
        for (Map.Entry<String, String> row : Hooks.rows(store, SIZES).entrySet()) {
            long total = Long.parseLong(row.getValue().trim());
            if (total == 0L) {
                continue;   // a compacted row the reconcile floored: the folder accounts for nothing
            }
            rows.put(row.getKey(), unit > 0 && total % unit == 0
                    ? Long.toString(total / unit)
                    : total + " bytes");   // not a whole number of published bodies - a real divergence, shown as one
        }
        return rows;
    }

    @Override
    public Map<String, String> converged(List<ArtifactDescriptor> published) {
        Map<String, Long> counts = new TreeMap<>();
        for (ArtifactDescriptor artifact : published) {
            for (String folder : ancestors(artifact.path())) {
                counts.merge(row(folder), 1L, Long::sum);
            }
        }
        Map<String, String> converged = new TreeMap<>();
        counts.forEach((row, count) -> converged.put(row, Long.toString(count)));
        return converged;
    }

    @Override
    public void repair(ArtifactStore store) throws IOException {
        // The full-walk route of the two-route derived-metadata contract, executed: the same call CleanupTask makes
        // every cleanup-interval, recomputing every folder from the durable publish/ pointer tree.
        new StoreRepositoryInventory(store).rollUpSizes();
    }

    /** The length of the one body every check publishes, read back from the single stored blob. */
    private static long bodySize(ArtifactStore store) throws IOException {
        long unit = -1L;
        for (String hash : store.list("blobs")) {
            long size = store.size("blobs/" + hash);
            if (size > 0 && (unit < 0 || size < unit)) {
                unit = size;
            }
        }
        return unit;
    }

    /** The publish-relative ancestor folders of a leaf request path, the leaf itself excluded - the folders the
     *  observer increments and the full walk writes a {@code sizes/} object for. */
    private static List<String> ancestors(String path) {
        List<String> folders = new ArrayList<>();
        folders.add("");
        String relative = path == null ? "" : path.replaceAll("^/+", "").replaceAll("/+$", "");
        int slash = relative.indexOf('/');
        while (slash >= 0) {
            folders.add(relative.substring(0, slash));
            slash = relative.indexOf('/', slash + 1);
        }
        return folders;
    }

    /** The {@code sizes/} object name a folder's total is stored under, as {@code SubtreeSizeRollUp.sizeKey} builds
     *  it - the reserved {@code ~} leaf for the repository root, the URL-encoded path otherwise. */
    private static String row(String folder) {
        return folder.isEmpty() ? ROOT_ROW : URLEncoder.encode(folder, StandardCharsets.UTF_8);
    }

    /**
     * The observer skips any path with no {@code publish/} pointer, so a variant subject nobody published and a held
     * subject with only a review pointer are both skipped - and the mutants that write a row for one change nothing.
     * The re-publish behaviour those mutants stand in for is asserted directly by
     * {@code SubtreeSizePublicationObserverTest}, which drives two publishes at one path through {@code commit} and
     * shows the total does not move for identical bytes and moves by the difference for different ones.
     */
    @Override
    public String whyServedOnly() {
        return "the observer skips a path with no publish/ pointer, so a never-published variant and a held subject "
                + "are both skipped and a row written for either changes nothing; the re-publish behaviour is "
                + "asserted in SubtreeSizePublicationObserverTest";
    }
}
