/**
 * The walk-consumer contract suite: the JUnit driver for the testkit's {@code WalkConsumerContract}, one fixture per
 * delivery class the walk's commit protocol supports, and the completeness census that keeps the two in step.
 *
 * <p>The suite exists because the {@code WalkConsumer} javadoc has always promised idempotent delivery and
 * at-least-once crash-resume in prose, while the only executable evidence was one mid-pass kill inside
 * {@code RebuildPassTest} against one ad-hoc consumer. Here the promise is stated once in the testkit and run against
 * every consumer, at six crash points, against a real
 * {@code FilesystemArtifactStore} rooted at a JUnit {@code @TempDir} - no
 * HTTP server, no network, and no sleeping through a real claim lease (the harness moves the walk's clock instead).
 *
 * <p>It lives beside {@code test/walk} rather than inside it because a module directory is compiled whole, and it is
 * a separate module on purpose: it {@code provides} the three archetype consumers and reaches them only through
 * {@code ServiceLoader}, so it is simultaneously the runtime-discovery graph the census needs. A consumer module
 * omitted here disappears from discovery, and the census fails because the source {@code provides} scan still declares
 * it - the blind spot a discovery-only census has.
 *
 * <p>The three consumers it provides are archetypes, not stubs: neither repository ships a production
 * {@code WalkConsumer} at these tips, so the kit covers the three delivery classes the SPI documents - a per-item
 * durable index, a stride-durable batching index, and a pass-end snapshot rebuilder - each a minimal but real
 * implementation, because a crash check against a consumer that never writes proves nothing.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.walk.testkit
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.walk.contract.test {
    requires build.jenesis.repository.walk;
    requires build.jenesis.repository.walk.store;
    requires build.jenesis.repository.walk.testkit;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.store.testkit;
    requires build.jenesis.repository.store.filesystem;
    requires build.jenesis.repository.contract.testkit;
    requires build.jenesis.repository.observation;
    // The first SHIPPED consumer and the module that provides what it repairs: the Maven format declares
    // ModuleViewRebuild, and the Jenesis format provides the ModuleView it writes through. Both are on this graph
    // because the census demands that every declared consumer really resolve here.
    requires build.jenesis.repository.format.maven;
    requires build.jenesis.repository.format.jenesis;
    requires org.junit.jupiter;
    requires org.assertj.core;

    // Discovery is part of what is under test: the census enumerates what ServiceLoader really sees in this graph and
    // compares it against the source `provides` scan, and each fixture reaches its consumer through
    // WalkConsumer.discovered() rather than by construction - the way the scheduled pass reaches it. The same
    // `uses`-in-a-test-module shape test/store/contract and test/format/contract already carry.
    uses build.jenesis.repository.walk.WalkConsumer;
    uses build.jenesis.repository.walk.WalkProvider;

    // The three delivery archetypes the contract is run over. They are declared here rather than in the testkit
    // because a source testkit must stay inert on a runtime graph.
    provides build.jenesis.repository.walk.WalkConsumer with
            build.jenesis.repository.walk.contract.test.StreamingIndexConsumer,
            build.jenesis.repository.walk.contract.test.StrideBufferedConsumer,
            build.jenesis.repository.walk.contract.test.PassSnapshotConsumer;
}
