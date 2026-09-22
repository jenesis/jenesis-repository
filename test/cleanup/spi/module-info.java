/**
 * The retention contracts driven over a list of releases: what a policy evicts and what it refuses to, with no
 * store, no sweep engine and no scheduler behind them. The engine that applies a plan is discovered through
 * {@code RetentionProvider} and is asserted where it is installed.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.cleanup
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.cleanup.contract.test {
    requires build.jenesis.repository.cleanup;
    requires org.junit.jupiter;
    requires org.assertj.core;
}
