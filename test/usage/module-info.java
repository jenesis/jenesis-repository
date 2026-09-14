/**
 * Focused unit tests for the batching key-usage tracker's observability adoption - that an <em>enabled</em> tracker
 * is an {@link build.jenesis.repository.observation.ObservabilitySource} reporting its bounded queue depth ({@code
 * jenreg.usage.queue}, used vs the fixed capacity), the per-credential accumulators it holds ({@code
 * jenreg.usage.tracked}), the hits it has dropped under back-pressure ({@code jenreg.usage.dropped}), a {@code
 * jenreg.usage.worker} health check that is DOWN when the worker died with tracking on, and a {@code
 * jenreg.usage.flush} task status stamped with the last drain - while a <em>disabled</em> tracker reports nothing at
 * all, all collected into the single {@link build.jenesis.repository.observation.ObservabilityReport} view without the
 * server, Micrometer or a network. The tracker's flush/accumulation behaviour itself is covered by the server test
 * module; a real filesystem store backs the authorization the drain flushes through.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.usage
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.usage.test {
    requires build.jenesis.repository.usage;
    requires build.jenesis.repository.server.spi;
    requires build.jenesis.repository.observation;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.store.filesystem;
    requires org.junit.jupiter;
    requires org.assertj.core;
}
