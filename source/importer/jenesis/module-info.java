/**
 * The Jenesis-to-Jenesis import connector: an {@link build.jenesis.repository.importer.ImportSourceProvider} that
 * builds a {@code JenesisSource} over another instance's {@code GET /api/assets} enumeration, so a repository migrates
 * between two deployments of this product as it does from Nexus or Artifactory.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.importer.jenesis {
    requires build.jenesis.repository.importer;
    requires build.jenesis.repository.format;
    requires tools.jackson.databind;
    exports build.jenesis.repository.importer.jenesis;
    provides build.jenesis.repository.importer.ImportSourceProvider
            with build.jenesis.repository.importer.jenesis.JenesisSourceProvider;
}
