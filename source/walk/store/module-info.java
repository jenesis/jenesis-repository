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
 * @jenesis.pin com.google.errorprone/error_prone_annotations 2.50.0 SHA-256/4667724877f1d37a689202da191e23efa7657c62eef93ccdac406eccfe5cdd0a
 * @jenesis.pin org.jspecify/jspecify 1.0.1 SHA-256/070d75f261fe4c5b8202508366715f7f2d4660f88c8ef7e6d3575e48c9683b66
 * @jenesis.pin org.slf4j/slf4j-api 2.0.19 SHA-256/e91ff6d720609e7a194ffe758c3ed5c84e798617ae07b0a0f6a4fe229741b4bb
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
