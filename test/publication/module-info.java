/**
 * The publication-hook contract suite: the JUnit driver for the testkit's {@code PublicationHookContract}, one
 * synthetic fixture per role and per delivery class the seam supports, and the completeness census that keeps the
 * static inventory, the runtime graph and the fixtures in step.
 *
 * <p>The suite exists because <b>nothing had ever driven the interceptor chain against its stated contract</b>. The
 * {@code PublishInterceptor} javadoc carries thirteen numbered clauses written by /from reading the
 * code, and the only executable evidence was a handful of happy-path cases in {@code PublishInterceptorTest}: no
 * fail-closed leg, no crash window, no ordering asymmetry, and - because {@code FaultInjectingStore} had never been
 * armed on the screen path at all - no test of the window between {@code committed} firing and the declared
 * visibility write.
 *
 * <p><b>Why the fixtures are synthetic, and why that is the point.</b> The core ships no
 * {@code PublicationObserver} and no {@code PublishInterceptor} - the shipped chain is empty and every upload is
 * accepted - which the SPI's own contract states. So the fixtures here are archetypes of the roles the SPI documents:
 * two after-commit observers (one per supported delivery class), three screens (a recording one, a withholding one
 * with a read side, and one that also overrides the inherited observer leg), and one pre-commit hold-release hook.
 * Each is a minimal but real implementation over a real
 * {@code FilesystemArtifactStore} rooted at a JUnit {@code @TempDir},
 * because a fail-closed check against a screen that never reads anything proves nothing.
 *
 * <p>It is a separate module on purpose: it {@code provides} its observers and screens and reaches them only through
 * {@code ServiceLoader}, so it is simultaneously the runtime-discovery graph the census needs and the graph in which
 * {@code Publication}'s own {@code instanceof PublishInterceptor} split can be driven end to end. A hook module
 * omitted here disappears from discovery while the source {@code provides} scan still declares it - the blind spot a
 * discovery-only census has.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.store.testkit
 * @jenesis.bom pin-repository.properties
 */
open module build.jenesis.repository.publication.contract.test {
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.format.maven;
    requires build.jenesis.repository.format.oci;
    requires build.jenesis.repository.format.raw;
    requires build.jenesis.repository.store.testkit;
    requires build.jenesis.repository.store.filesystem;
    requires build.jenesis.repository.contract.testkit;
    requires org.junit.jupiter;
    requires org.assertj.core;

    // Discovery is part of what is under test: the census enumerates what ServiceLoader really sees in this graph,
    // splits it by `instanceof PublishInterceptor` exactly as Publication does, and compares both halves against the
    // source `provides` scan - and each fixture reaches its hook through discovery rather than by construction, the
    // way a deployment reaches it. The same `uses`-in-a-test-module shape test/store/contract and test/walkconsumer
    // already carry.
    uses build.jenesis.repository.store.PublicationObserver;

    // The role archetypes the contract is run over. They are declared here rather than in the testkit because a
    // source testkit must stay inert on a runtime graph - and because this one clause is what makes the census's
    // role split real: two observers and three screens arrive through it, and only `instanceof` tells them apart.
    provides build.jenesis.repository.store.PublicationObserver with
            build.jenesis.repository.publication.contract.test.FeedSplittingObserver,
            build.jenesis.repository.publication.contract.test.IndexObserver,
            build.jenesis.repository.publication.contract.test.OutboxObserver,
            build.jenesis.repository.publication.contract.test.RecordingScreen,
            build.jenesis.repository.publication.contract.test.WithholdingScreen,
            build.jenesis.repository.publication.contract.test.AuditingScreen;
}
