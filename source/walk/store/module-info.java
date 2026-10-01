/**
 * The default {@code ArtifactWalk} over the store's own key layout - the shared, totally ordered, resumable,
 * range-segmented, multi-node walk the SPI promises - provided as {@code paged-descent}. It enumerates only through
 * {@code ArtifactStore.page}, so a flat namespace of millions is never one list and a deep resume is a seek. All pass
 * state lives in the walked store as compare-and-set objects ({@code walks/<consumer>/...}), so a pass survives process
 * death on any node sharing it. Settings: {@code jenrepo.walk.checkpoint} (default 1000), {@code jenrepo.walk.segments}
 * (default 32), {@code jenrepo.walk.ttl} (seconds, default 900).
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.walk.store {
    requires build.jenesis.repository.walk;
    requires build.jenesis.repository.observation;
    exports build.jenesis.repository.walk.store;
    provides build.jenesis.repository.walk.WalkProvider
            with build.jenesis.repository.walk.store.StoreWalkProvider;
    provides build.jenesis.repository.observation.ObservabilitySource
            with build.jenesis.repository.walk.store.ArtifactWalkObservability;
}
