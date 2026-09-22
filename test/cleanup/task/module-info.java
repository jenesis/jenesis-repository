/**
 * Tests of the cleanup task: the cadence its provider parses out of a malformed dial without dropping every
 * pass, the retention engine it resolves - including the fail-fast when an operator names one no installed
 * module answers to - and the sweep, which groups an interleaved inventory before judging it and then applies
 * exactly the evictions it planned. No store and no network, against a fixed clock. What a policy decides is
 * asserted against the contract alone, where it needs no engine at all.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.cleanup
 * @jenesis.test build.jenesis.repository.cleanup.task
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.cleanup.test {
    requires build.jenesis.repository.cleanup;
    requires build.jenesis.repository.cleanup.task;
    requires build.jenesis.repository.maintenance;
    requires org.junit.jupiter;
    requires org.assertj.core;
}
