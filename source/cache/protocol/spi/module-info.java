/**
 * The cache-protocol SPI: one build tool's cache wire protocol as a {@code CacheProtocol} that says which request
 * paths it owns and reads a request of its shape as an address in the shared cache. A protocol ships as its own
 * module that {@code provides} one, so which tools a node serves is which modules are on its path.
 *
 * <p>Deliberately lighter than the cache-storage SPI beside it: a protocol translates a path and answers a record,
 * so it reaches no servlet, no framework and not the cache itself. That is what lets one be driven by calling it,
 * and what keeps the serving - one read and one store over the same cache, the metering and the refusals - in the
 * one place every protocol funnels into rather than reimplemented per tool. The single dependency beyond
 * {@code java.base} is the {@code Providers}/{@code Features} resolution, so this family is discovered and
 * validated by the same primitives as every other (&sect;2 - shared mechanism has one home and is reused, never
 * copied).
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
