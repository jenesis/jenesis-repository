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
 * Reclaims a discarded quarantine hold's findings: the reviewer threw the artifact away, so the gate findings
 * recorded beside the hold go with it - exactly as the {@code QuarantineLog}'s rows do - or they would dangle
 * forever, since a discarded version was never published and so no eviction or reconcile sweep would ever reach its
 * document. A <em>released</em> path keeps its findings: the artifact now serves, its gate history is part of the
 * ledger, and its rows are reclaimed with the artifact when it is eventually evicted. A path the layout cannot map
 * back to a coordinate is left alone - better a bounded stray document than a delete against a guessed key.
 *
 * <p>Since (§5.4) the findings live in the {@code findings} section of the consolidated metadata document, so a
 * discard drops just that section - surgically, so a coordinate whose document also carries other sections (declared
 * licenses read at inspection) keeps them; when the findings section was the document's only content the whole
 * document goes. The {@code findings/} sidecar of a graceful-absence deployment is removed too. With no metadata store installed the
 * findings never left the sidecar, so only the sidecar is dropped - the graceful-absence path.
 */
public final class DiscardedHoldFindingsObserver implements HoldReleaseObserver {

    @Override
    public void onReleased(ArtifactStore store, String path) {
        // A released artifact serves; its findings are living history, reclaimed with the artifact on eviction.
    }

    @Override
    public void onDiscarded(ArtifactStore store, String path) throws IOException {
        Optional<ArtifactDescriptor> described = new StoreRepositoryInventory(store).describe(path);
        if (described.isEmpty() || described.get().coordinate() == null || described.get().version() == null) {
            return;
        }
        if (HeldElsewhere.othersStillHeld(store, described.get(), path)) {
            return;   // the findings document is per VERSION (it carries the operator's waivers and review labels):
                      // discarding one path of a multi-path hold must not strip what the remaining held paths are
                      // reviewed against - the last discard reaps it
        }
        String ecosystem = described.get().ecosystem();
        String coordinate = described.get().coordinate();
        String version = described.get().version();
        boolean droppedFindings = false;
        Optional<MetadataStore> metadata = MetadataProvider.installed().map(provider -> provider.over(store));
        if (metadata.isPresent()) {
            Optional<MetadataDocument> document = metadata.get().read(ecosystem, coordinate, version);
            if (document.isPresent() && document.get().has(FindingsSection.TAG)) {
                if (document.get().tags().size() == 1) {
                    // The findings section is the document's only content - the whole discarded-and-never-published
                    // document goes, so no empty envelope is left to dangle.
                    store.delete(MetadataKey.version(ecosystem, coordinate, version));
                } else {
                    // Other sections (declared licenses) stay; only the findings section is removed.
                    metadata.get().mutate(ecosystem, coordinate, version, FindingsSection.TAG, current -> null);
                }
                droppedFindings = true;
            }
        }
        // The graceful-absence sidecar goes too, or it would dangle after the document's
        // section is dropped. A presence probe, not a full read.
        String sidecar = Findings.key(ecosystem, coordinate, version);
        if (store.exists(sidecar)) {
            store.delete(sidecar);
            droppedFindings = true;
        }
        if (droppedFindings) {
            // A discarded hold's findings never rode an artifact eviction (the version was never published), so this is
            // their only reclamation - and the vulnerability rank index would keep paging the discarded coordinate's
            // line until the next scan. Bump the findings eviction epoch (the dirty signal the rank index folds into its
            // rebuild stamp, never the scan stamp) so the line drops on the next rank-index pass instead.
            Findings.evictions(store).bump();
        }
    }
}
