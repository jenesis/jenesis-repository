package build.jenesis.repository.cleanup.task;

import module java.base;

import build.jenesis.repository.cleanup.Release;
import build.jenesis.repository.cleanup.RetentionPolicy;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Features;
import build.jenesis.repository.walk.WalkConsumer;
import build.jenesis.repository.walk.WalkPass;

/**
 * Retention as a listener of the walk: each repository's stored policy (or the deployment default from the
 * effective configuration) judges the releases as the inventory rows stream past, coordinate group by coordinate
 * group in the order the rows come, and evicts what the policy says - exactly the sweep the hourly cleanup pass
 * ran over a walk of its own. A retention policy is a configuration that runs, so this consumer rides the
 * {@code retention} walk entry by default, daily; a deployment that wants no retention removes the entry.
 *
 * <p>Per store and per pass it keeps the policy's planner, which buffers one coordinate's versions until the next
 * coordinate begins; a resumed pass re-judges the group the crash split conservatively and the next pass completes
 * it, as the walk-based sweep did.
 */
public final class RetentionConsumer implements WalkConsumer {

    /** The consumer's name: its toggle ({@code jenreg.retention-sweep}) and how a walk entry names it - not
     *  {@code retention}, which is the engine-selection dial. */
    public static final String NAME = "retention-sweep";

    private final Map<Object, Sweep> sweeps = new ConcurrentHashMap<>();

    private static final class Sweep {
        private final StoreRepositoryInventory inventory;
        private final RetentionPolicy.Planner planner;

        private Sweep(ArtifactStore store) throws IOException {
            this.inventory = new StoreRepositoryInventory(store);
            RetentionPolicy policy = inventory.readRetention().orElse(RetentionPolicy.fromConfig(Features.settings()));
            this.planner = policy.planner(Instant.now(), eviction -> inventory.evict(eviction.release()));
        }
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Applies each repository's retention policy - keep-last, max-age, prerelease-expiry, not-downloaded-for - "
                + "to the rows the walk hands it and evicts what falls outside it at the end; deletes are its cost.";
    }

    @Override
    public List<String> settings() {
        return List.of("retention", "keep-last", "max-age", "prerelease-expiry", "not-downloaded-for");
    }

    @Override
    public Set<Family> families() {
        return Set.of(Family.INVENTORY);
    }

    @Override
    public void onRetained(ArtifactDescriptor artifact, ArtifactStore store) {
        // The inventory rows carry what retention judges; no pointer is listened on.
    }

    @Override
    public void onWalked(Walked entry, ArtifactStore store) throws IOException {
        if (entry.family() != Family.INVENTORY) {
            return;
        }
        Sweep sweep = sweep(store);
        Optional<Release> release = sweep.inventory.release(entry.key());
        if (release.isPresent()) {
            sweep.planner.offer(release.get());
        }
    }

    @Override
    public void onPassCompleted(WalkPass pass, ArtifactStore store) {
        Sweep sweep = sweeps.remove(store.identity());
        if (sweep == null) {
            return;
        }
        try {
            sweep.planner.finish();
        } catch (IOException unfinished) {
            throw new UncheckedIOException(unfinished);
        }
    }

    private Sweep sweep(ArtifactStore store) throws IOException {
        Sweep sweep = sweeps.get(store.identity());
        if (sweep == null) {
            sweep = new Sweep(store);
            sweeps.put(store.identity(), sweep);
        }
        return sweep;
    }
}
