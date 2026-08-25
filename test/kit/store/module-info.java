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
 * @jenesis.pin net.bytebuddy/byte-buddy 1.18.3 SHA-256/d78396e3c5bce3f2865c9186647481e5589d34cacc632484715b686108d17c66
 * @jenesis.pin net.bytebuddy/byte-buddy-agent 1.12.10 SHA-256/5e8606d14a844c1ec70d2eb8f50c4009fb16138905dee8ca50a328116c041257
 * @jenesis.pin org.assertj.core 3.27.7
 * @jenesis.pin org.assertj/assertj-core 3.27.7 SHA-256/c4a445426c3c2861666863b842cc4ec7bbb1c4226fefd370b6d2fe83d6c4ff0f
 * @jenesis.pin org.slf4j/slf4j-api 2.0.18 SHA-256/44508fd1576500688c790b190acdd16fec4f8c79a3e0b900afd70503cf055f55
 */
module build.jenesis.repository.store.testkit {
    requires transitive build.jenesis.repository.store;
    // Permitted since the kit moved under test/: the JUnit rule is a direction now, not a ban on the kits.
    requires org.assertj.core;
    exports build.jenesis.repository.store.testkit;
}
