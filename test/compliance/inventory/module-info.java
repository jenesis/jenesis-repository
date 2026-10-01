/**
 * The licence inventory in process, over a real filesystem store: how one version's declared licences resolve - the
 * gate's record first, its stored metadata through a discovered inspector otherwise, unknown when neither says - and
 * how the count folds every version into per-category and per-SPDX-id counts, each version once, and is read back as
 * a stored report in each of its states.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.compliance.inventory
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.compliance.inventory.test {
    requires build.jenesis.repository.compliance.inventory;
    requires build.jenesis.repository.cleanup;
    requires build.jenesis.repository.compliance;
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.inventory;
    requires build.jenesis.repository.metadata.store;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.store.filesystem;
    requires org.junit.jupiter;
    requires org.assertj.core;
    provides build.jenesis.repository.compliance.QualityInspector
            with build.jenesis.repository.compliance.inventory.test.FakeLicensedInspector;
    provides build.jenesis.repository.format.RepositoryFormat
            with build.jenesis.repository.compliance.inventory.test.FakeLicensedFormat;
}
