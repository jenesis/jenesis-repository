/**
 * The Cargo registry format: a {@link build.jenesis.repository.format.RepositoryFormat} for the {@code /cargo/...}
 * sparse-index layout - a streaming {@code cargo publish}, the generated {@code config.json} and stored per-crate index
 * files, and crate downloads - which is also a {@link build.jenesis.repository.format.ProxyFormat} mirroring an
 * upstream sparse-index registry (crates.io by default), an {@link build.jenesis.repository.format.ArtifactLayout} for
 * the {@code "crates.io"} ecosystem, and a {@link build.jenesis.repository.format.RepositoryImporter} for a Nexus or
 * Artifactory {@code cargo} registry.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.format.cargo {
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.format.lifecycle;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.walk;
    requires build.jenesis.repository.blobs;
    requires org.slf4j;
    requires tools.jackson.databind;
    // Exported to test modules only.
    exports build.jenesis.repository.format.cargo to
            build.jenesis.repository.gateway.test,
            build.jenesis.repository.format.cargo.test;
    provides build.jenesis.repository.format.RepositoryFormat
            with build.jenesis.repository.format.cargo.CargoFormat;
    provides build.jenesis.repository.store.PublicationObserver
            with build.jenesis.repository.format.cargo.CargoListingObserver;
}
