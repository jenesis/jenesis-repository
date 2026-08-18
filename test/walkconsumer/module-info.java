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
 * @jenesis.pin net.bytebuddy/byte-buddy 1.18.3 SHA-256/d78396e3c5bce3f2865c9186647481e5589d34cacc632484715b686108d17c66
 * @jenesis.pin org.apiguardian/apiguardian-api 1.1.2 SHA-256/b509448ac506d607319f182537f0b35d71007582ec741832a1f111e5b5b70b38
 * @jenesis.pin org.assertj.core 3.27.7
 * @jenesis.pin org.assertj/assertj-core 3.27.7 SHA-256/c4a445426c3c2861666863b842cc4ec7bbb1c4226fefd370b6d2fe83d6c4ff0f
 * @jenesis.pin org.jspecify/jspecify 1.0.0 SHA-256/1fad6e6be7557781e4d33729d49ae1cdc8fdda6fe477bb0cc68ce351eafdfbab
 * @jenesis.pin org.junit.jupiter 6.0.3
 * @jenesis.pin org.junit.jupiter.api 6.0.3
 * @jenesis.pin org.junit.jupiter/junit-jupiter 6.0.3 SHA-256/784b65815f479a0c99a9d3a573b142e2a525efb6025d97f751b19e72f90aeda3
 * @jenesis.pin org.junit.jupiter/junit-jupiter-api 6.0.3 SHA-256/d655d7e6f0c7ae07f10a2f3bbaaebb6d30e9b26204a068ad9e9b3950aa28792c
 * @jenesis.pin org.junit.jupiter/junit-jupiter-engine 6.0.3 SHA-256/1e2fab61ad27ea08fc7c70dd9677cf8c6d1ae5434d42dcfdd633b12c7e7c04d0
 * @jenesis.pin org.junit.jupiter/junit-jupiter-params 6.0.3 SHA-256/cf2947e2302b9f8c8a059259a277881c1cadae8fbc2514c16a925cfeb7beb2e5
 * @jenesis.pin org.junit.platform.commons 6.0.3
 * @jenesis.pin org.junit.platform.console 6.0.3
 * @jenesis.pin org.junit.platform/junit-platform-commons 6.0.3 SHA-256/39f262d09c3d52719fe0b77f080e90a3695e285d779a41b232e17963ae5da200
 * @jenesis.pin org.junit.platform/junit-platform-console 6.0.3
 * @jenesis.pin org.junit.platform/junit-platform-engine 6.0.3 SHA-256/491e9e4f745f161b8a8e4186a1a7c6a450ea12c70930c9aedae427215301d947
 * @jenesis.pin org.junit.platform/junit-platform-launcher 6.0.3
 * @jenesis.pin org.junit.platform/junit-platform-reporting 6.0.3
 * @jenesis.pin org.opentest4j 1.3.0
 * @jenesis.pin org.opentest4j.reporting/open-test-reporting-tooling-spi 0.2.4
 * @jenesis.pin org.opentest4j/opentest4j 1.3.0 SHA-256/48e2df636cab6563ced64dcdff8abb2355627cb236ef0bf37598682ddf742f1b
 * @jenesis.pin org.slf4j/slf4j-api 2.0.18 SHA-256/44508fd1576500688c790b190acdd16fec4f8c79a3e0b900afd70503cf055f55
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
    // because the census demands that every declared consumer really resolve here (D-059).
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
