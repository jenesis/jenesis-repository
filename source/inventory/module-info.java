/**
 * The store-backed repository inventory: publish/download/pin recording keyed by the format-neutral ecosystem and
 * coordinate an {@code ArtifactLayout} supplies, release enumeration, per-repository retention-policy storage, and
 * the {@code pointerRoots()} union (the {@code publish/} namespace plus every installed blobs-namespace format's
 * declared roots) the discovered garbage collector and rebuild pass judge references from - the reclamation itself
 * moved onto the free {@code GarbageCollector} SPI. Core plumbing shared by the gate, staging, retention and
 * the console - deliberately a direct seam, not an SPI - in its own module so a feature module reaches it without
 * dragging in the gateway wiring. It also carries its own convergence backstop: a discovered
 * {@link build.jenesis.repository.maintenance.MaintenanceTaskProvider} answering to {@code reconcile} that rebuilds the
 * publish-time sidecars from the live pointer tree in both directions, so a crash that skipped a sidecar write (a
 * served artifact invisible to retention and the search/license index) converges on the next sweep instead of drifting
 * permanently; off unless the {@code reconcile} setting is enabled. The reconcile legs ride the shared artifact walk
 * ({@code ArtifactWalk}, resolved through {@code WalkProvider}), so the sweep is resumable, range-segmented and
 * multi-node-cooperative; with no walk implementation installed the pass resolves to nothing rather than enumerating
 * its own way. A walk-carrying inventory also rides the subtree-size roll-up over its own {@code walks/rollup} pass
 * (post-order folder totals folded over the ordered stream with an O(depth) stack, partials flushed with every
 * cursor commit), while the walk-less construction keeps the complete-per-call recursion for on-demand callers.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.inventory {
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.walk;
    requires build.jenesis.repository.observation;
    requires build.jenesis.repository.blobs;
    requires build.jenesis.repository.cleanup;
    requires build.jenesis.repository.findings;
    requires build.jenesis.repository.health;
    requires build.jenesis.repository.maintenance;
    requires build.jenesis.repository.metadata;
    requires build.jenesis.repository.settings;
    exports build.jenesis.repository.inventory;
    uses build.jenesis.repository.inventory.DownloadTrackerProvider;
    provides build.jenesis.repository.walk.WalkConsumer
            with build.jenesis.repository.inventory.InventoryReconcileConsumer,
                    build.jenesis.repository.inventory.InventoryBackfillConsumer,
                    build.jenesis.repository.inventory.TornWriteConsumer;
    provides build.jenesis.repository.observation.ObservabilitySource
            with build.jenesis.repository.inventory.TornWriteConsumer.Observability;
    provides build.jenesis.repository.maintenance.StorageNamespace
            with build.jenesis.repository.inventory.InventoryStorageNamespace,
                    build.jenesis.repository.inventory.FormatStorageNamespaces;
    provides build.jenesis.repository.store.PublicationObserver
            with build.jenesis.repository.inventory.SubtreeSizePublicationObserver;
    provides build.jenesis.repository.settings.SettingsContributor
            with build.jenesis.repository.inventory.ReconcileSettingsContributor,
                    build.jenesis.repository.inventory.TornWriteReconcileSettingsContributor,
                    build.jenesis.repository.inventory.InventoryBackfillSettingsContributor;
}
