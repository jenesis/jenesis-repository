/**
 * The OCI / Docker registry format (the {@code /v2/} Distribution API) as a plugin module: {@code docker push}
 * and {@code docker pull} over the content-addressed store, whose {@code blobs/<hex>} is exactly an OCI blob's
 * {@code sha256} digest. A {@code DELETE} removes a version through the cleanup SPI. Discovered through
 * {@code provides}.
 *
 * @jenesis.release 25
 *
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.format.oci {
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.walk;
    requires build.jenesis.repository.cleanup;
    requires build.jenesis.repository.audit;
    requires org.slf4j;
    requires com.github.benmanes.caffeine;
    requires tools.jackson.databind;
    exports build.jenesis.repository.format.oci to build.jenesis.repository.format.oci.test;
    provides build.jenesis.repository.format.RepositoryFormat
            with build.jenesis.repository.format.oci.OciFormat;
    provides build.jenesis.repository.store.PublicationObserver
            with build.jenesis.repository.format.oci.OciListingObserver;
}
