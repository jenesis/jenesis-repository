/**
 * The metered artifact store: every store operation counted by name on this node and summed into the read and write
 * classes the operation-count suites, the soaks and the walks screen read ({@code jenrepo.store.ops.<op>},
 * {@code .reads}, {@code .writes}), and timed on the node's meter registry where one is wired. One module, so the
 * repository node and the build-cache node - which delegates into a segment of the repository's store - meter the same
 * way and their soaks compare.
 *
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
