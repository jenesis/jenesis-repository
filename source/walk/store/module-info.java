/**
 * The default {@code ArtifactWalk} reference implementation over the store's own key layout - the generalisation of
 * the private depth-first inventory walk into the shared, totally ordered, resumable, range-segmented, multi-node
 * walk the SPI promises, provided as {@code paged-descent} (the default) and discovered with {@code ServiceLoader}.
 * It enumerates exclusively through the {@code ArtifactStore.page} ordered-paging primitive, so a flat millions-entry
 * namespace is never materialised as one list and a resume deep inside it is a seek on a backend that pages natively.
 * All pass state lives in the walked store ({@code walks/<consumer>/...}, compare-and-set objects only) - persist
 * only through the store, so a pass survives process death on any node sharing it. Settings:
 * {@code jenreg.walk.checkpoint} (cursor-commit stride, default 1000), {@code jenreg.walk.segments} (target
 * segment count per pass, default 32), {@code jenreg.walk.ttl} (claim lease seconds, default 900).
 *
 * @jenesis.release 25
 * @jenesis.pin com.github.ben-manes.caffeine/caffeine 3.2.4 SHA-256/9d9d2cfd681fd9272ded3d27c9930db12f89f732345975aa113ebc223bbf1224
 * @jenesis.pin com.google.errorprone/error_prone_annotations 2.49.0 SHA-256/3b1003e51b8ae56fdbd7c71073e81d1683b97e6c4dff5a9151164d59b769d13c
 * @jenesis.pin org.jspecify/jspecify 1.0.0 SHA-256/1fad6e6be7557781e4d33729d49ae1cdc8fdda6fe477bb0cc68ce351eafdfbab
 * @jenesis.pin org.slf4j/slf4j-api 2.0.18 SHA-256/44508fd1576500688c790b190acdd16fec4f8c79a3e0b900afd70503cf055f55
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
