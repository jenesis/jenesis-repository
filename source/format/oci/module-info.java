/**
 * The OCI / Docker registry format (the {@code /v2/} Distribution API) as a plugin module: {@code docker push}
 * and {@code docker pull} over the same content-addressed store. It requires the format SPI, the storage SPI and
 * Jackson (to read a manifest's media type and the token-auth response, and to write the tag list), no core - because
 * an OCI blob is addressed by its {@code sha256} digest, exactly the {@code blobs/<hex>} the store already uses.
 * Discovered through {@code provides}.
 *
 * @jenesis.release 25
 *
 * @jenesis.pin com.fasterxml.jackson.core/jackson-annotations 2.22 SHA-256/21ddb598807d3a51a876704eb979d9296e1c6a6f47ab1826ff88c6d6a127a2d0
 * @jenesis.pin com.github.ben-manes.caffeine/caffeine 3.2.4 SHA-256/9d9d2cfd681fd9272ded3d27c9930db12f89f732345975aa113ebc223bbf1224
 * @jenesis.pin com.github.benmanes.caffeine 3.2.4 SHA-256/9d9d2cfd681fd9272ded3d27c9930db12f89f732345975aa113ebc223bbf1224
 * @jenesis.pin com.google.errorprone/error_prone_annotations 2.50.0 SHA-256/4667724877f1d37a689202da191e23efa7657c62eef93ccdac406eccfe5cdd0a
 * @jenesis.pin org.jspecify/jspecify 1.0.1 SHA-256/070d75f261fe4c5b8202508366715f7f2d4660f88c8ef7e6d3575e48c9683b66
 * @jenesis.pin org.slf4j 2.0.19 SHA-256/e91ff6d720609e7a194ffe758c3ed5c84e798617ae07b0a0f6a4fe229741b4bb
 * @jenesis.pin org.slf4j/slf4j-api 2.0.19 SHA-256/e91ff6d720609e7a194ffe758c3ed5c84e798617ae07b0a0f6a4fe229741b4bb
 * @jenesis.pin tools.jackson.core/jackson-core 3.2.2 SHA-256/93d9090b8c505b2f942ad85dd5821e8c401bfbafafff11708a3fbe2ef9da18ca
 * @jenesis.pin tools.jackson.core/jackson-databind 3.2.2 SHA-256/d27509f990d990fc779a01f3f612005ff6c67dc3bb0a720f5f4d9b2f5c713401
 * @jenesis.pin tools.jackson.databind 3.2.2 SHA-256/d27509f990d990fc779a01f3f612005ff6c67dc3bb0a720f5f4d9b2f5c713401
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
