/**
 * The store-backed repository inventory in isolation over a real filesystem artifact store, with the consolidated
 * metadata document ({@code metadata.store}) installed so the enumeration, reconcile and per-coordinate section reads
 * run over the {@code meta} doc rather than the legacy sidecars. Five behaviours are pinned. First, the
 * reconciled view: {@link build.jenesis.repository.inventory.StoreRepositoryInventory#coordinates coordinates()} and
 * {@link build.jenesis.repository.inventory.StoreRepositoryInventory#releases() releases()} enumerate exactly what was
 * recorded, a newly published version appears, an evicted one disappears, and the publish facts (instant, prerelease,
 * pin) round-trip. Second, the reconcile sweep: its forward leg recreates a served pointer's missing published section,
 * its reverse leg removes an orphan section whose pointers are gone (the residue of a crashed eviction), a re-run over a
 * converged store restores and removes nothing (idempotent), and both directions converge a crash-drifted store. Third,
 * download tracking: a last-download marker persists store-backed and reads back, a bounded compare-and-set retries past
 * an injected conflict rather than dropping the write, and the {@code NONE} tracker plus provider-absent {@code resolve}
 * degrade cleanly. Fourth, the meta sections: the {@code published}, {@code provenance} and {@code licenses} sections
 * read their rolled-up per-coordinate view back from the document (the licenses section unions rather than replaces).
 * Fifth, the subtree-size roll-up folds artifact blob sizes into every browse folder's total. No network and no
 * framework - everything runs through the store SPI, with a minimal self-contained format supplying the coordinate
 * layout, discovered through the inventory's {@code ServiceLoader}.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.inventory
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.inventory.test {
    requires build.jenesis.repository.scope;
    requires build.jenesis.repository.inventory;
    requires build.jenesis.repository.cleanup;
    requires build.jenesis.repository.maintenance;
    requires build.jenesis.repository.metadata;
    requires build.jenesis.repository.metadata.store;
    requires build.jenesis.repository.compliance;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.store.filesystem;
    requires build.jenesis.repository.store.testkit;
    requires build.jenesis.repository.walk;
    requires build.jenesis.repository.walk.store;
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.blobs;
    requires org.junit.jupiter;
    requires org.assertj.core;
    provides build.jenesis.repository.format.RepositoryFormat
            with build.jenesis.repository.inventory.test.InventoryTestFormat,
                    build.jenesis.repository.inventory.test.InventoryTestBlobFormat,
                    build.jenesis.repository.inventory.test.InventoryUnplaceableFormat;
    provides build.jenesis.repository.store.PublicationObserver
            with build.jenesis.repository.inventory.test.InventoryTestScreen;
}
