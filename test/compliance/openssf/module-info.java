/**
 * Tests of the OpenSSF malicious-packages source in isolation: the OSV query it sends for a package, that only the
 * dataset's {@code MAL-} records are taken and each is flagged malicious, and the fail-closed answer to a query that
 * did not succeed - against a fixed endpoint, with no network and no framework.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.compliance.openssf
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.compliance.openssf.test {
    requires build.jenesis.repository.compliance.openssf;
    requires build.jenesis.repository.compliance;
    requires org.junit.jupiter;
    requires org.assertj.core;
}
