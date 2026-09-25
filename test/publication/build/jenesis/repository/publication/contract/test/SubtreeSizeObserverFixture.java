package build.jenesis.repository.publication.contract.test;

import module java.base;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.PublicationObserver;
import build.jenesis.repository.store.StoredCounter;
import build.jenesis.repository.store.testkit.PublicationHookContract;
import build.jenesis.repository.store.testkit.PublicationHookFixture;

/**
 * The inventory's subtree-size observer: every accepted publish of a {@code publish/} leaf adds its size to a counter
 * under {@code sizes/} for the repository root and every folder above the path, and its repair is the inventory's own
 * full-walk roll-up. Its counters are deferred and flushed on a cadence, so the projection flushes before it reads.
 * The projection is each folder's byte total, and convergence is exact: every kit publish carries the kit's one body,
 * so a folder holds that body's length once for every artifact below it - which is what makes a dropped delta a
 * visibly smaller number rather than a folder that merely still reads as counted.
 */
final class SubtreeSizeObserverFixture implements PublicationHookFixture.Observer {

    private static final String SIZES = "sizes";

    @Override
    public String hook() {
        return "subtree-size";
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

    /** Folder to its byte total, for every counter holding a positive size: {@code ~} is the repository root, any
     *  other key the URL-encoded publish-relative folder path. */
    @Override
    public Map<String, String> projection(ArtifactStore store) throws IOException {
        // The observer folds its deltas into deferred counters, flushed on a cadence; what it recorded is what lands
        // once they are, and a deferral lost before that is the drift the roll-up repairs.
        StoredCounter.flushNow();
        Map<String, String> counted = new TreeMap<>();
        for (Map.Entry<String, String> row : Keys.rows(store, SIZES).entrySet()) {
            String leaf = row.getKey().substring(row.getKey().lastIndexOf('/') + 1);
            long size;
            try {
                size = Long.parseLong(row.getValue().trim());
            } catch (NumberFormatException notACounter) {
                continue;
            }
            if (size > 0) {
                counted.put(leaf.equals("~") ? "" : URLDecoder.decode(leaf, StandardCharsets.UTF_8),
                        Long.toString(size));
            }
        }
        return counted;
    }

    /** The root and every folder above each distinct published path hold the kit body's length once per path. */
    @Override
    public Map<String, String> converged(List<ArtifactDescriptor> published) {
        long body = PublicationHookContract.BODY.getBytes(StandardCharsets.UTF_8).length;
        Map<String, Long> totals = new TreeMap<>();
        for (String path : published.stream().map(ArtifactDescriptor::path).distinct().toList()) {
            totals.merge("", body, Long::sum);
            String relative = path.startsWith("/") ? path.substring(1) : path;
            for (int slash = relative.indexOf('/'); slash >= 0; slash = relative.indexOf('/', slash + 1)) {
                totals.merge(relative.substring(0, slash), body, Long::sum);
            }
        }
        Map<String, String> counted = new TreeMap<>();
        totals.forEach((folder, total) -> counted.put(folder, Long.toString(total)));
        return counted;
    }

    @Override
    public Map<PublicationHookContract.Property, String> unsupported() {
        return Map.of(
                PublicationHookContract.Property.A_THROWING_OBSERVER_IS_CONTAINED_AFTER_THE_OBSERVED_MUTATION,
                "the callback writes nothing to the store - it folds a deferred counter delta that a flush lands on a "
                        + "cadence - so a store failure injected during the callback cannot reach it and leaves no "
                        + "stale surface to see; a failed flush is StoredCounterTest's, and the drift it leaves is "
                        + "what the roll-up repair below converges",
                PublicationHookContract.Property.A_QUARANTINED_OR_REJECTED_PUBLISH_IS_NEVER_OBSERVED,
                "the observer counts only a path whose publish/ pointer it reads back, and a held artifact has none, "
                        + "so no misrouted callback can make it count one and the mutation that forwards a withhold as "
                        + "a publish has nothing to act on; SubtreeSizePublicationObserverTest holds the pointer "
                        + "precondition");
    }

    /** The full-walk route of the two-route contract: the inventory re-derives every folder's size from the durable
     *  {@code publish/} pointer tree. */
    @Override
    public void repair(ArtifactStore store) throws IOException {
        new StoreRepositoryInventory(store).rollUpSizes();
    }
}
