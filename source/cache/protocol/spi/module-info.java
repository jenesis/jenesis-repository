/**
 * The cache-protocol SPI: one build tool's cache wire protocol as a {@code CacheProtocol} that owns request paths and
 * reads a request as an address in the shared cache. Each protocol is a module that {@code provides} one, so the tools
 * a node serves are the modules on its path.
 *
 * <p>Lighter than the cache-storage SPI: a protocol translates a path into a record and reaches no servlet, framework
 * or cache, so it can be driven by calling it, and the serving - one read and one store, the metering, the refusals -
 * stays in the one place every protocol funnels into. Beyond {@code java.base} it uses only the shared
 * {@code Providers}/{@code Features} resolution.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.cache.protocol {
    requires build.jenesis.repository.store;
    exports build.jenesis.repository.cache.protocol;
    uses build.jenesis.repository.cache.protocol.CacheProtocol;
}
