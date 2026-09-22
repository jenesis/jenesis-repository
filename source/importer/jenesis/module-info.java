/**
 * The jenesis-to-jenesis import connector as a plugin module: it {@code provides} an
 * {@link build.jenesis.repository.importer.ImportSourceProvider} that builds a {@code JenesisSource} over another
 * jenesis instance's {@code GET /api/assets} enumeration, reading the listing pages with Jackson. It is the read
 * half of the exit story - the outbound mirror of the export endpoint the server now serves - so a jenesis
 * repository migrates into another jenesis (or is drained by a forwarder) symmetrically with the Nexus and
 * Artifactory connectors. Depends on the import SPI, the format SPI (for the shared fetcher) and Jackson; the
 * server discovers it with {@code ServiceLoader}, so jenesis-source support is present exactly when this module is
 * on the path.
 *
 * @jenesis.release 25
 *
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.importer.jenesis {
    requires build.jenesis.repository.importer;
    requires build.jenesis.repository.format;
    requires tools.jackson.databind;
    exports build.jenesis.repository.importer.jenesis to build.jenesis.repository.test,
            build.jenesis.repository.server.e2e, build.jenesis.repository.importer.jenesis.test;
    provides build.jenesis.repository.importer.ImportSourceProvider
            with build.jenesis.repository.importer.jenesis.JenesisSourceProvider;
}
