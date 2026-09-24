/**
 * Tests of the OSV advisory source in isolation: the query it sends, the severity band each record maps to, the
 * fail-closed answer to a query that did not succeed, and the verdict a recorded answer decides under the shipped
 * thresholds, and that a deployment which set nothing asks nothing - against a fixed endpoint, with no network and no framework.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.compliance.osv
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.compliance.osv.test {
    requires build.jenesis.repository.compliance.osv;
    requires build.jenesis.repository.compliance;
    requires build.jenesis.repository.settings;
    requires org.junit.jupiter;
    requires org.assertj.core;
}
