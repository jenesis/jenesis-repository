/**
 * The JFrog Artifactory import connector as a plugin module: it {@code provides} an
 * {@link build.jenesis.repository.importer.ImportSourceProvider} that builds an {@code ArtifactorySource} over the
 * storage API, reading the listing with Jackson. Depends on the import SPI, the format SPI (for the shared fetcher) and
 * Jackson; the server discovers it with {@code ServiceLoader}, so Artifactory support is present exactly when this
 * module is on the path.
 *
 * @jenesis.release 25
 *
 * @jenesis.bom pin-repository.properties
 */
module build.jenesis.repository.importer.artifactory {
    requires build.jenesis.repository.importer;
    requires build.jenesis.repository.format;
    requires tools.jackson.databind;
    exports build.jenesis.repository.importer.artifactory to build.jenesis.repository.test,
            build.jenesis.repository.server.e2e, build.jenesis.repository.importer.artifactory.test;
    provides build.jenesis.repository.importer.ImportSourceProvider
            with build.jenesis.repository.importer.artifactory.ArtifactorySourceProvider;
}
