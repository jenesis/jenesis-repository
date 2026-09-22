/**
 * Unit test for the cache-storage SPI's shared naming rule ({@code Names}) in isolation - no backend, no
 * container, no network. Pins the {@code isHex} entry-segment predicate every storage backend applies before a
 * {@code step}/{@code inputs} pair becomes an object key: it is deliberately ASCII-only and length-capped, where a
 * {@code Character.digit(c, 16)} shortcut would silently admit a Unicode/fullwidth digit into the key-space. A pure
 * java.base + JUnit + AssertJ module, so it always runs.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.cache.storage
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.cache.storage.spi.test {
    requires build.jenesis.repository.cache.storage;
    requires org.junit.jupiter;
    requires org.assertj.core;
}
