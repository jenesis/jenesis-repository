/**
 * The build cache driven in isolation: what a key resolves to, which outcome a request gets when the entry is
 * absent, rejected or served, and what the storage seam promises about paging and about the names it will accept.
 *
 * <p>No server, no client, no container - the cache over a real storage backend under a {@code @TempDir}. What a
 * build tool makes of it is asserted where the tools run.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.cache.server
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.cache.server.contract.test {
    requires build.jenesis.repository.cache.server;
    requires build.jenesis.repository.cache.storage;
    requires build.jenesis.repository.cache.storage.testkit;
    requires build.jenesis.repository.cache.storage.delegating;
    requires build.jenesis.repository.scope;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.store.filesystem;
    requires build.jenesis.repository.server.spi;
    requires build.jenesis.repository.walk;
    requires micrometer.core;
    requires org.junit.jupiter;
    requires org.assertj.core;
}
