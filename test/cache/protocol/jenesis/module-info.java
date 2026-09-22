/**
 * Unit test for the native cache protocol: the paths it claims and declines, the address it reads off one, and
 * the discovery that finds it. The one module where the SPI's held answer can be shown to be held, since it is
 * the first composition with a protocol on the path.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.cache.protocol.jenesis
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.cache.protocol.jenesis.test {
    requires build.jenesis.repository.cache.protocol;
    requires build.jenesis.repository.cache.protocol.jenesis;
    requires org.junit.jupiter;
    requires org.assertj.core;
}
