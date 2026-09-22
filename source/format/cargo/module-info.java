/**
 * The Cargo registry format as a plugin module: it provides {@link build.jenesis.repository.format.RepositoryFormat}
 * for the {@code /cargo/...} sparse-index layout - a streaming {@code cargo publish} frame
 * ({@code PUT /cargo/<repo>/api/v1/crates/new}, whose length-prefixed {@code .crate} archive streams into the
 * content-addressed store while only its JSON metadata is materialised), the sparse index (a generated
 * {@code config.json} + the name-sharded per-crate index files, stored listings the publish maintains from a
 * precomputed per-version line), and the crate downloads.
 * It is also a {@link build.jenesis.repository.format.ProxyFormat}, pull-through mirroring an upstream sparse-index
 * registry (crates.io by default): a mutable per-crate index file streams through fresh, and an immutable {@code .crate}
 * streams into the CAS and is cached, its upstream URL resolved from the upstream {@code config.json} {@code dl} template.
 * It also provides {@link build.jenesis.repository.format.ArtifactLayout}, declaring the {@code "crates.io"} ecosystem
 * and resolving a crate download path to its {@code name}/{@code version} coordinate. Finally it provides a
 * {@link build.jenesis.repository.format.RepositoryImporter} that migrates a Nexus/Artifactory {@code cargo} registry,
 * streaming each {@code .crate} into the CAS as a hosted import. It parses the publish metadata and emits the index
 * lines with the Jackson databind already on the server's module path. Discovered through {@code provides}.
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
    // Exported to test modules only. The unit suite is named here because its assertions are about
    // the path grammar - a pure function of a string, which belongs in the fastest lane.
    exports build.jenesis.repository.format.cargo to
            build.jenesis.repository.gateway.test,
            build.jenesis.repository.format.cargo.test;
    provides build.jenesis.repository.format.RepositoryFormat
            with build.jenesis.repository.format.cargo.CargoFormat;
    provides build.jenesis.repository.store.PublicationObserver
            with build.jenesis.repository.format.cargo.CargoListingObserver;
}
