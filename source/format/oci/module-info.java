/**
 * The OCI / Docker registry format (the {@code /v2/} Distribution API) as a plugin module: {@code docker push}
 * and {@code docker pull} over the same content-addressed store. It requires the format SPI, the storage SPI and
 * Jackson (to read a manifest's media type and the token-auth response, and to write the tag list), no core - because
 * an OCI blob is addressed by its {@code sha256} digest, exactly the {@code blobs/<hex>} the store already uses.
 * Discovered through {@code provides}.
 *
 * @jenesis.release 25
 *
 * @jenesis.bom pin-repository.properties
 */
module build.jenesis.repository.format.oci {
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.walk;
    requires org.slf4j;
    requires com.github.benmanes.caffeine;
    requires tools.jackson.databind;
    exports build.jenesis.repository.format.oci to build.jenesis.repository.format.oci.test;
    provides build.jenesis.repository.format.RepositoryFormat
            with build.jenesis.repository.format.oci.OciFormat;
    provides build.jenesis.repository.store.PublicationObserver
            with build.jenesis.repository.format.oci.OciListingObserver;
}
