/**
 * The npm registry format as a plugin module: it provides
 * {@link build.jenesis.repository.format.RepositoryFormat} for the {@code /npm/...} layout, storing each published
 * version and tarball and maintaining the stored packument on publish. It parses the publish document and emits the packument
 * with the Jackson databind already on the server's module path. The importer reads a tarball's
 * {@code package/package.json} with Commons Compress ({@code TarArchiveInputStream}), so PAX/GNU long-name entries are
 * decoded rather than dropped by a hand-walk. Discovered through {@code provides}.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.format.npm {
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.format.lifecycle;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.walk;
    requires build.jenesis.repository.blobs;
    requires org.slf4j;
    requires org.apache.commons.compress;
    requires tools.jackson.databind;
    exports build.jenesis.repository.format.npm to build.jenesis.repository.gateway.test;
    provides build.jenesis.repository.format.RepositoryFormat
            with build.jenesis.repository.format.npm.NpmFormat;
    provides build.jenesis.repository.store.PublicationObserver
            with build.jenesis.repository.format.npm.NpmListingObserver;
}
