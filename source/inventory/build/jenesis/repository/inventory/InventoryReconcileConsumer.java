package build.jenesis.repository.inventory;

import module java.base;

import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.walk.WalkConsumer;
import build.jenesis.repository.walk.WalkPass;

/**
 * The inventory reconcile as a listener of the one walk: the forward leg on every served pointer, withheld ones
 * included (a held artifact still has its facts), the reverse leg on every row under the published root, the
 * derived leg on every download stamp, license record, override and pin - and, once a pass completed, the rollup
 * identity rebuilt from the converged set. It used to be three walks of its own every hour; a healthy repository
 * never needs any of them, and a crash asks for the walk that carries this.
 *
 * <p>Per key it does exactly what the three legs did, through {@link InventoryReconciler}; per store it keeps the
 * inventory it judges through for the length of one pass, keyed by the store's identity, and lets it go at the end.
 */
public final class InventoryReconcileConsumer implements WalkConsumer {

    /** The consumer's name: its toggle ({@code jenreg.reconcile}), its scenario, its settings row. */
    public static final String NAME = "reconcile";

    private final Map<Object, StoreRepositoryInventory> inventories = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Restores the inventory facts of every served pointer and removes the rows of pointers that are gone, then "
                + "rebuilds the repository identity; reads each pointer, row and derived row the walk hands it.";
    }

    @Override
    public Set<Family> families() {
        return Set.of(Family.POINTERS, Family.INVENTORY, Family.DERIVED);
    }

    /** A withheld pointer is still a served coordinate's pointer: its facts are restored like any other's. */
    @Override
    public boolean seesWithheld() {
        return true;
    }

    @Override
    public void onRetained(ArtifactDescriptor artifact, ArtifactStore store) throws IOException {
        restore(artifact, store);
    }

    @Override
    public void onWithheld(ArtifactDescriptor artifact, ArtifactStore store) throws IOException {
        restore(artifact, store);
    }

    private void restore(ArtifactDescriptor artifact, ArtifactStore store) throws IOException {
        // The forward leg reads the publish/ namespace only: a blobs-namespace root's raw key is not a request path
        // and its lost section is not rebuilt here - the gap the walk-based leg documented, unchanged.
        if (!artifact.path().startsWith("/")) {
            return;
        }
        inventory(store).reconciler().restore("publish" + artifact.path(), Instant.now());
    }

    @Override
    public void onWalked(Walked entry, ArtifactStore store) throws IOException {
        switch (entry.family()) {
            case INVENTORY -> inventory(store).reconciler().judgePublished(entry.key());
            case DERIVED -> inventory(store).reconciler().judgeDerived(entry.key());
            default -> {
                // pointers arrive through the descriptor hooks; no other family is listened on
            }
        }
    }

    @Override
    public void onPassCompleted(WalkPass pass, ArtifactStore store) {
        // The published set is converged for this store: recompute the rollup identity from that truth, so a lost
        // incremental fold is repaired on the schedule the sidecars are - what the walk-based reconcile did last.
        StoreRepositoryInventory inventory = inventories.remove(store.identity());
        if (inventory == null) {
            inventory = new StoreRepositoryInventory(store);   // an empty pass delivered nothing; the identity still converges
        }
        try {
            inventory.rebuildIdentity();
        } catch (IOException unrebuildable) {
            throw new UncheckedIOException(unrebuildable);
        }
    }

    private StoreRepositoryInventory inventory(ArtifactStore store) {
        return inventories.computeIfAbsent(store.identity(), _ -> new StoreRepositoryInventory(store));
    }
}
