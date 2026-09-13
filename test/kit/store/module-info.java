/**
 * Shared test support over the artifact-store SPI, in three parts.
 *
 * <p><b>Fault fixtures:</b> a {@code FaultInjectingStore} decorator that injects a store fault at a chosen point (a
 * write that never lands, a read that fails, a compare-and-set that loses its race) and a {@code StoreInvariants}
 * checker for the two store-primitive consistency invariants the {@code Publication} / {@code ArtifactStore} layer owns
 * (no dangling {@code publish/} pointer, no unreferenced {@code blobs/} object after a GC).
 *
 * <p><b>The store contract kit:</b> {@code StoreContract} is the executable {@code ArtifactStore} contract - one
 * parameterized body of checks covering content-addressed writes, compare-and-set conflict semantics, opaque version
 * tokens, ordered paging, traversal rejection and the explicitly non-transactional per-entry batch outcomes - and
 * {@code StoreFixture} is how one backend registers with it. A backend is covered by writing a fixture, never by
 * copying assertions, and the kit drives the two fault fixtures above rather than duplicating them. The JUnit driver
 * and the filesystem fixture live in {@code test/store/contract}, which also carries the completeness census.
 *
 * <p><b>The publication-hook contract kit:</b> {@code PublicationHookContract} is the executable contract of the
 * {@code PublicationObserver} family, and {@code PublicationHookFixture} is how one hook registers with it. The kit's
 * distinguishing property is that it <em>derives</em> which contract a hook is held to rather than letting the fixture
 * declare it: the whole family rides one {@code uses PublicationObserver} clause and {@code Publication} splits it by
 * {@code instanceof PublishInterceptor}, so a contained after-commit observer and a fail-closed pre-commit screen
 * arrive through the same seam with opposite failure semantics. {@code Role.of} asks an instance exactly what
 * {@code Publication} asks it, and a fixture whose declaration disagrees is refused - a screen can never be run
 * through the contained legs and reported green. A third role covers downstream's pre-commit
 * {@code HoldReleaseObserver} hooks, which are not {@code PublicationObserver}s at all and are reached through an
 * adapter. The crash windows are armed on the <em>screen</em> path with the {@code FaultInjectingStore} above, and
 * each is re-verified from durable state so a point that stopped biting fails. The JUnit driver, the synthetic
 * fixtures and the census live in {@code test/publication}.
 *
 * <p>The module depends only on the store SPI - no junit, no assertion library, no format, no server - so both this
 * repository's and the downstream distribution's test modules can require it rather than each hand-rolling a bespoke
 * throwing decorator. The classes are test doubles; nothing here provides a service, so the module is inert on a
 * runtime graph.
 *
 * @jenesis.release 25
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
 * @jenesis.pin org.junit.platform/junit-platform-commons 6.1.3 SHA-256/a4774ae923c109544034aeae7c762cf017fbcdd1342403ba90e0921984b608c3
 * @jenesis.pin org.junit.platform/junit-platform-engine 6.1.3 SHA-256/21ad7ad3a35beda16387979317be1833a72a71d812f9a66ee4b9a7bafb56334c
 * @jenesis.pin org.opentest4j/opentest4j 1.3.0 SHA-256/48e2df636cab6563ced64dcdff8abb2355627cb236ef0bf37598682ddf742f1b
 * @jenesis.pin org.slf4j/slf4j-api 2.0.19 SHA-256/e91ff6d720609e7a194ffe758c3ed5c84e798617ae07b0a0f6a4fe229741b4bb
 */
module build.jenesis.repository.store.testkit {
    requires transitive build.jenesis.repository.store;
    // Permitted since the kit moved under test/: the JUnit rule is a direction now, not a ban on the kits.
    requires org.assertj.core;
    requires java.net.http;
    requires org.junit.jupiter;
    exports build.jenesis.repository.store.testkit;
}
