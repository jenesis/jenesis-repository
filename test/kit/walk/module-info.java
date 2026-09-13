/**
 * The walk-consumer contract kit: the executable {@code WalkConsumer} contract and the fixture seam one consumer
 * registers with.
 *
 * <p>{@code WalkConsumerContract} states the two promises the walk SPI has always made in prose - idempotent delivery
 * and at-least-once crash-resume - as checks: a corpus spanning several checkpoint strides is enumerated, the pass is
 * killed at six named points (before the first delivery, mid-stride, a full stride with the cursor commit dying before
 * it lands, the same with it landing but the caller never learning, the terminal segment commit, and the
 * pass-completion hook), the consumer is rebuilt as a <em>fresh instance</em> - a crashed process keeps no memory -
 * and the pass is resumed. {@code WalkConsumerFixture} is how one consumer registers, and the reason the kit can span
 * consumers that hold entirely different bytes: the fixture declares {@code projection}, its own normalised view of
 * its durable state, and {@code Delivery}, the durability it actually rides. Convergence is then an equality between
 * what the fixture declared and what the store holds - never a comparison of layouts the kit has no business owning -
 * and no consumer is held to a guarantee stronger than its declared class, which for a pass-snapshot rebuilder means
 * "converged <em>or</em> visibly degraded, but never a fragment published as a whole view".
 *
 * <p>The crash itself is the store testkit's {@code FaultInjectingStore}, armed off the consumer's own delivery count
 * rather than off the walk's internal store-call sequence, so a crash point stays where it is named when the walk
 * implementation changes - and every check re-derives from the durable pass state that the crash really landed there.
 * {@code WalkHarness} is what the JUnit driver supplies: the walk, its checkpoint stride, and the ability to age a
 * dead worker's claim past its lease so a resume is possible at all.
 *
 * <p>The module depends only on the walk SPI and the store testkit - no junit, no assertion library, no walk
 * implementation, no server - so both this repository's and the downstream distribution's test modules can require it
 * for their own consumer fixtures exactly as they already require the store testkit. The classes are test doubles;
 * nothing here provides a service, so the module is inert on a runtime graph.
 *
 * @jenesis.release 25
 * @jenesis.pin com.github.ben-manes.caffeine/caffeine 3.2.4 SHA-256/9d9d2cfd681fd9272ded3d27c9930db12f89f732345975aa113ebc223bbf1224
 * @jenesis.pin com.google.errorprone/error_prone_annotations 2.50.0 SHA-256/4667724877f1d37a689202da191e23efa7657c62eef93ccdac406eccfe5cdd0a
 * @jenesis.pin net.bytebuddy/byte-buddy 1.18.13 SHA-256/aa0013fbcb7e8086f4775f3dcb69a6c6833f7fd9023fe027d2d0241834cc51d5
 * @jenesis.pin org.apiguardian/apiguardian-api 1.1.2 SHA-256/b509448ac506d607319f182537f0b35d71007582ec741832a1f111e5b5b70b38
 * @jenesis.pin org.assertj/assertj-core 3.27.7 SHA-256/c4a445426c3c2861666863b842cc4ec7bbb1c4226fefd370b6d2fe83d6c4ff0f
 * @jenesis.pin org.jspecify/jspecify 1.0.1 SHA-256/070d75f261fe4c5b8202508366715f7f2d4660f88c8ef7e6d3575e48c9683b66
 * @jenesis.pin org.junit.jupiter/junit-jupiter 6.1.3 SHA-256/6b0a14075c74221de53047e363860b7d19fce802bebc152df2c35c4089c6c379
 * @jenesis.pin org.junit.jupiter/junit-jupiter-api 6.1.3 SHA-256/555d6cf20fa1710884dd01b86cc5785397ba73e21ada2d4b784f5f1a14dcafc4
 * @jenesis.pin org.junit.jupiter/junit-jupiter-engine 6.1.3 SHA-256/414559e78bbfa3bdb2eab009ac3ab1e22369ac31117c270a0bd666da115c5c6c
 * @jenesis.pin org.junit.jupiter/junit-jupiter-params 6.1.3 SHA-256/05c51cedba2c06a9770707391e006cee825876937dea580b94a265ce145d4939
 * @jenesis.pin org.junit.platform/junit-platform-commons 6.1.3 SHA-256/a4774ae923c109544034aeae7c762cf017fbcdd1342403ba90e0921984b608c3
 * @jenesis.pin org.junit.platform/junit-platform-engine 6.1.3 SHA-256/21ad7ad3a35beda16387979317be1833a72a71d812f9a66ee4b9a7bafb56334c
 * @jenesis.pin org.opentest4j/opentest4j 1.3.0 SHA-256/48e2df636cab6563ced64dcdff8abb2355627cb236ef0bf37598682ddf742f1b
 * @jenesis.pin org.slf4j/slf4j-api 2.0.19 SHA-256/e91ff6d720609e7a194ffe758c3ed5c84e798617ae07b0a0f6a4fe229741b4bb
 */
module build.jenesis.repository.walk.testkit {
    requires transitive build.jenesis.repository.walk;
    requires transitive build.jenesis.repository.store.testkit;
    exports build.jenesis.repository.walk.testkit;
}
