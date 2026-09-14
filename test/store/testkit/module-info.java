/**
 * Unit tests for the shared crash-recovery fixtures: the {@code FaultInjectingStore} decorator fires each armed fault
 * once and then heals (a crash before a write, a crash after a write lands, and a compare-and-set conflict), counts its
 * calls, and passes every other call through to a real filesystem store; and the {@code StoreInvariants} checker
 * accepts a consistent store, catches a dangling {@code publish/} pointer, and catches an unreferenced {@code blobs/}
 * object. Proves the free repository can require the fixture module directly, exactly as the downstream distribution's
 * crash-recovery suite does.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.store.testkit
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.store.testkit.test {
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.store.filesystem;
    requires build.jenesis.repository.store.testkit;
    requires org.junit.jupiter;
    requires org.assertj.core;
}
