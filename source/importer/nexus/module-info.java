/**
 * The Sonatype Nexus 3 import connector as a plugin module: it {@code provides} an
 * {@link build.jenesis.repository.importer.ImportSourceProvider} that builds a {@code NexusSource} over the components
 * REST API, reading the listing responses with Jackson. Depends on the import SPI, the format SPI (for the shared
 * fetcher) and Jackson; the server discovers it with {@code ServiceLoader}, so Nexus support is present exactly when
 * this module is on the path.
 *
 * @jenesis.release 25
 *
 * @jenesis.bom pin-repository.properties
 */
module build.jenesis.repository.importer.nexus {
    requires build.jenesis.repository.importer;
    requires build.jenesis.repository.format;
    requires tools.jackson.databind;
    exports build.jenesis.repository.importer.nexus to build.jenesis.repository.test,
            build.jenesis.repository.server.e2e, build.jenesis.repository.importer.nexus.test;
    provides build.jenesis.repository.importer.ImportSourceProvider
            with build.jenesis.repository.importer.nexus.NexusSourceProvider;
}
