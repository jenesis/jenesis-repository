/**
 * The compliance contracts driven in isolation, with nothing installed behind them: the gate's decision logic and
 * its strongest-verdict aggregation, licence identification, the bounded inspection tiers and what they refuse, the
 * feed cache's aged answer and its per-key single flight, the refresh ledger, VEX suppression and waivers.
 *
 * <p>No network, no framework and <em>no discovered source</em>. That last one is the point of the module rather
 * than an omission: these suites assert what the contract does when a deployment has installed nothing, which is
 * the state every provider SPI here documents as its default - {@code none()}, {@code disabled()}, empty. A suite
 * that asserts which providers a module path yields belongs beside those providers, not here.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.compliance
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.compliance.spi.test {
    requires build.jenesis.repository.compliance;
    requires build.jenesis.repository.store;
    // SignalContextTest resolves a real filesystem store to assert the snapshot root a mirrored catalogue is
    // confined to; it is the store contract's own reference backend, so the assertion is about the layout.
    requires build.jenesis.repository.store.filesystem;
    requires org.junit.jupiter;
    requires org.assertj.core;
}
