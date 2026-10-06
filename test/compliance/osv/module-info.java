/**
 * Tests of the OSV advisory source in isolation: the query it sends, the severity band each record maps to, the
 * fail-closed answer to a query that did not succeed, and the verdict a recorded answer decides under the shipped
 * thresholds, the change log drawn from the export's change lists, the mirror drawn from the export and read locally,
 * and that a deployment which set nothing asks nothing - against a fixed endpoint, with no network and no framework.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.compliance.osv
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.compliance.osv.test {
    requires build.jenesis.repository.compliance.osv;
    requires build.jenesis.repository.compliance;
    requires build.jenesis.repository.feed;
    // The ecosystems' version orders the mirror reads a record's ranges by.
    requires build.jenesis.repository.dependents.requirements;
    requires build.jenesis.repository.settings;
    // The change log is drawn into a real filesystem store, the store contract's reference backend.
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.store.filesystem;
    requires org.junit.jupiter;
    requires org.assertj.core;
}
