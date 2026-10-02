/**
 * The JFrog Artifactory import connector: an {@link build.jenesis.repository.importer.ImportSourceProvider} that builds
 * an {@code ArtifactorySource} over the storage API, fetching through the format SPI's shared fetcher.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.importer.artifactory {
    requires build.jenesis.repository.importer;
    requires build.jenesis.repository.format;
    requires tools.jackson.databind;
    exports build.jenesis.repository.importer.artifactory;
    provides build.jenesis.repository.importer.ImportSourceProvider
            with build.jenesis.repository.importer.artifactory.ArtifactorySourceProvider;
}
