/**
 * The metered artifact store: every operation the store is asked for is counted by name on this node and summed as
 * the read and write classes the operation-count suites, the soaks and the walks screen read
 * ({@code jenreg.store.ops.<op>}, {@code .reads}, {@code .writes}), and timed on the node's meter registry where one is
 * wired. One module, so the repository node and the build-cache node meter the same way: the cache has no backend of
 * its own and delegates into a segment of the repository's store, and a figure for one that the other did not keep
 * would make the two soaks incomparable.
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.store.metering {
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.observation;
    requires micrometer.core;
    exports build.jenesis.repository.store.metering;
    provides build.jenesis.repository.observation.ObservabilitySource
            with build.jenesis.repository.store.metering.StoreOperationsObservability;
}
