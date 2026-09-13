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
 * @jenesis.pin com.fasterxml.jackson.core/jackson-annotations 2.22 SHA-256/21ddb598807d3a51a876704eb979d9296e1c6a6f47ab1826ff88c6d6a127a2d0
 * @jenesis.pin com.github.ben-manes.caffeine/caffeine 3.2.4 SHA-256/9d9d2cfd681fd9272ded3d27c9930db12f89f732345975aa113ebc223bbf1224
 * @jenesis.pin com.google.errorprone/error_prone_annotations 2.50.0 SHA-256/4667724877f1d37a689202da191e23efa7657c62eef93ccdac406eccfe5cdd0a
 * @jenesis.pin net.bytebuddy/byte-buddy 1.18.13 SHA-256/aa0013fbcb7e8086f4775f3dcb69a6c6833f7fd9023fe027d2d0241834cc51d5
 * @jenesis.pin org.apiguardian/apiguardian-api 1.1.2 SHA-256/b509448ac506d607319f182537f0b35d71007582ec741832a1f111e5b5b70b38
 * @jenesis.pin org.assertj.core 3.27.7 SHA-256/c4a445426c3c2861666863b842cc4ec7bbb1c4226fefd370b6d2fe83d6c4ff0f
 * @jenesis.pin org.assertj/assertj-core 3.27.7 SHA-256/c4a445426c3c2861666863b842cc4ec7bbb1c4226fefd370b6d2fe83d6c4ff0f
 * @jenesis.pin org.jspecify/jspecify 1.0.1 SHA-256/070d75f261fe4c5b8202508366715f7f2d4660f88c8ef7e6d3575e48c9683b66
 * @jenesis.pin org.junit.jupiter 6.1.3 SHA-256/6b0a14075c74221de53047e363860b7d19fce802bebc152df2c35c4089c6c379
 * @jenesis.pin org.junit.jupiter/junit-jupiter 6.1.3 SHA-256/6b0a14075c74221de53047e363860b7d19fce802bebc152df2c35c4089c6c379
 * @jenesis.pin org.junit.jupiter/junit-jupiter-api 6.1.3 SHA-256/555d6cf20fa1710884dd01b86cc5785397ba73e21ada2d4b784f5f1a14dcafc4
 * @jenesis.pin org.junit.jupiter/junit-jupiter-engine 6.1.3 SHA-256/414559e78bbfa3bdb2eab009ac3ab1e22369ac31117c270a0bd666da115c5c6c
 * @jenesis.pin org.junit.jupiter/junit-jupiter-params 6.1.3 SHA-256/05c51cedba2c06a9770707391e006cee825876937dea580b94a265ce145d4939
 * @jenesis.pin org.junit.platform.console 6.1.3 SHA-256/913554ad65b9420936822889a7268a6793a942e5f77ad8b653b8ec05068fa3be
 * @jenesis.pin org.junit.platform/junit-platform-commons 6.1.3 SHA-256/a4774ae923c109544034aeae7c762cf017fbcdd1342403ba90e0921984b608c3
 * @jenesis.pin org.junit.platform/junit-platform-console 6.1.3 SHA-256/913554ad65b9420936822889a7268a6793a942e5f77ad8b653b8ec05068fa3be
 * @jenesis.pin org.junit.platform/junit-platform-engine 6.1.3 SHA-256/21ad7ad3a35beda16387979317be1833a72a71d812f9a66ee4b9a7bafb56334c
 * @jenesis.pin org.junit.platform/junit-platform-launcher 6.1.3 SHA-256/1dec64b1fc0b7c47a11302aa5b4d37d8160c041b396e27067709ce775601a7b6
 * @jenesis.pin org.junit.platform/junit-platform-reporting 6.1.3 SHA-256/d584a16cd87da5609af5e35428ed4f40b192681a2487dc59d02ecb62f6c417c2
 * @jenesis.pin org.opentest4j.reporting/open-test-reporting-tooling-spi 0.2.7 SHA-256/ff1cf9d62786d067fa19c56f13cd6ed1077d0f8782ff269824b7a2c586101bf0
 * @jenesis.pin org.opentest4j/opentest4j 1.3.0 SHA-256/48e2df636cab6563ced64dcdff8abb2355627cb236ef0bf37598682ddf742f1b
 * @jenesis.pin org.slf4j/slf4j-api 2.0.19 SHA-256/e91ff6d720609e7a194ffe758c3ed5c84e798617ae07b0a0f6a4fe229741b4bb
 * @jenesis.pin tools.jackson.core/jackson-core 3.2.2 SHA-256/93d9090b8c505b2f942ad85dd5821e8c401bfbafafff11708a3fbe2ef9da18ca
 * @jenesis.pin tools.jackson.core/jackson-databind 3.2.2 SHA-256/d27509f990d990fc779a01f3f612005ff6c67dc3bb0a720f5f4d9b2f5c713401
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
