/**
 * Tests for the shared XML reader: that a document parses in both namespace modes, that a DOCTYPE is refused, and
 * that a body which is not XML raises without writing anything to stderr.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.xml
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.xml.test {
    requires build.jenesis.repository.xml;
    requires org.junit.jupiter;
    requires org.assertj.core;
}
