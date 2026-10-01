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
 * Retention as a listener of the walk: each repository's policy - its four rules resolved through the repository's
 * effective configuration (its own value over its tenant's over the deployment's), handed over by {@link #onRepository}
 * - judges the releases as the inventory rows stream past, one coordinate group at a time in row order, and evicts what
 * it condemns. A policy is a configuration that runs, so this consumer rides the {@code retention} walk entry, daily by
 * default; a deployment wanting no retention removes the entry.
 *
 * <p>Per store and pass it keeps the policy's planner, buffering one coordinate's versions; a resumed pass re-judges a
 * group a crash split conservatively and the next pass completes it.
 */
public final class RetentionConsumer implements WalkConsumer {

    /** The consumer's name: its toggle ({@code jenrepo.retention-sweep}) and walk-entry name - not {@code retention},
     *  which is the engine-selection dial. */
    public static final String NAME = "retention-sweep";

    private final Map<Object, Sweep> sweeps = new ConcurrentHashMap<>();

    /** Each walked store's effective configuration, as its driver handed it over, until its sweep is built. */
    private final Map<Object, UnaryOperator<String>> configurations = new ConcurrentHashMap<>();

    private static final class Sweep {
        private final StoreRepositoryInventory inventory;
        private final RetentionPolicy.Planner planner;

        private Sweep(ArtifactStore store, UnaryOperator<String> config) throws IOException {
            this.inventory = new StoreRepositoryInventory(store);
            RetentionPolicy policy = RetentionPolicy.fromConfig(config);
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
        return Stream.concat(Stream.of("retention"), RetentionPolicy.KEYS.stream()).toList();
    }

    @Override
    public Set<Family> families() {
        return Set.of(Family.INVENTORY);
    }

    /** The repository's own configuration, which its rules resolve through. */
    @Override
    public void onRepository(ArtifactStore store, UnaryOperator<String> config) {
        configurations.put(store.identity(), config);
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
        configurations.remove(store.identity());
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
            // A driver that names no repository hands nothing over; the deployment's configuration judges it.
            sweep = new Sweep(store, configurations.getOrDefault(store.identity(), Features.settings()));
            sweeps.put(store.identity(), sweep);
        }
        return sweep;
    }
}
