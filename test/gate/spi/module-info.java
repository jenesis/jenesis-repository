/**
 * The gate's contracts driven with nothing installed behind them: how the quarantine ledger records a hold and a
 * refusal, how a claiming inspector's subjects are ordered before the gate assesses them, and that merging the
 * signal sources cannot let one family's answer leak into another's.
 *
 * <p>What a screening implementation does with those contracts is asserted beside it.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.gate.spi
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.gate.contract.test {
    requires build.jenesis.repository.gate.spi;
    requires build.jenesis.repository.compliance;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.store.filesystem;
    requires build.jenesis.repository.inventory;
    requires org.junit.jupiter;
    requires org.assertj.core;
}
