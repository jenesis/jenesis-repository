/**
 * The cache protocol this build tool speaks, as its own module: {@code /<step>/<inputs>} within a tenant's cache,
 * with the project and the credential in headers. It is the protocol a node must carry to serve its own builds, and
 * the one a composition carrying no foreign layout still has.
 *
 * <p>Nothing beyond the SPI and {@code java.base}: a protocol translates a path into a record, so it is tested by
 * calling it.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.cache.protocol.jenesis {
    requires build.jenesis.repository.cache.protocol;
    exports build.jenesis.repository.cache.protocol.jenesis;
    provides build.jenesis.repository.cache.protocol.CacheProtocol
            with build.jenesis.repository.cache.protocol.jenesis.JenesisCacheProtocol;
}
