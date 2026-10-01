/**
 * The npm registry format as a plugin module: a {@link build.jenesis.repository.format.RepositoryFormat} for
 * {@code /npm/...}, storing each published version and tarball and maintaining the packument on publish, with Jackson
 * for the documents and Commons Compress for a tarball's {@code package/package.json}.
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
    exports build.jenesis.repository.format.npm;
    provides build.jenesis.repository.format.RepositoryFormat
            with build.jenesis.repository.format.npm.NpmFormat;
    provides build.jenesis.repository.store.PublicationObserver
            with build.jenesis.repository.format.npm.NpmListingObserver;
}
