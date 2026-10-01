package build.jenesis.repository.findings.store;

import module java.base;
import build.jenesis.repository.findings.Findings;
import build.jenesis.repository.gate.HoldReleaseObserver;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.metadata.MetadataDocument;
import build.jenesis.repository.metadata.MetadataKey;
import build.jenesis.repository.metadata.MetadataProvider;
import build.jenesis.repository.metadata.MetadataStore;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.gate.HeldElsewhere;

/**
 * Reclaims a discarded quarantine hold's findings: the reviewer threw the artifact away, so the gate findings recorded
 * beside the hold go with it, as the {@code QuarantineLog}'s rows do - a discarded version was never published, so no
 * eviction or sweep would reach its document. A released path keeps its findings, reclaimed with the artifact on
 * eviction. A path the layout cannot map to a coordinate is left alone rather than deleted against a guessed key.
 *
 * <p>Only the {@code findings} section of the metadata document is dropped, so other sections (declared licences) stay;
 * when it was the only content, the whole document goes.
 */
public final class DiscardedHoldFindingsObserver implements HoldReleaseObserver {

    @Override
    public void onReleased(ArtifactStore store, String path) {
        // A released artifact serves; its findings are reclaimed with it on eviction.
    }

    @Override
    public void onDiscarded(ArtifactStore store, String path) throws IOException {
        Optional<ArtifactDescriptor> described = new StoreRepositoryInventory(store).describe(path);
        if (described.isEmpty() || described.get().coordinate() == null || described.get().version() == null) {
            return;
        }
        if (HeldElsewhere.othersStillHeld(store, described.get(), path)) {
            return;   // the findings document is per VERSION (it carries the operator's waivers and review labels):
                      // the remaining held paths are still reviewed against it - the last discard reaps it
        }
        String ecosystem = described.get().ecosystem();
        String coordinate = described.get().coordinate();
        String version = described.get().version();
        MetadataStore metadata = MetadataProvider.installed().over(store);
        Optional<MetadataDocument> document = metadata.read(ecosystem, coordinate, version);
        boolean droppedFindings = document.isPresent() && document.get().has(FindingsSection.TAG);
        if (droppedFindings) {
            if (document.get().tags().size() == 1) {
                // The findings section is the only content, so the whole never-published document goes.
                store.delete(MetadataKey.version(ecosystem, coordinate, version));
            } else {
                // Other sections stay; only the findings section is removed.
                metadata.mutate(ecosystem, coordinate, version, FindingsSection.TAG, current -> null);
            }
        }
        if (droppedFindings) {
            // No eviction reclaims a discarded hold's findings, so bump the eviction epoch the rank index folds into
            // its rebuild stamp: the discarded line drops on the next rank-index pass rather than the next scan.
            Findings.evictions(store).bump();
        }
    }
}
