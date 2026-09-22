/**
 * Unit test for the cache-protocol SPI in isolation - no cache, no server, no container. Drives the contract a
 * protocol is held to: an address is complete or absent, a path it cannot read is an empty answer rather than a
 * throw, and the write policy is the protocol's statement about its own address space. A pure java.base + JUnit +
 * AssertJ module, so it always runs.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.cache.protocol
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.cache.protocol.spi.test {
    requires build.jenesis.repository.cache.protocol;
    requires org.junit.jupiter;
    requires org.assertj.core;
}
