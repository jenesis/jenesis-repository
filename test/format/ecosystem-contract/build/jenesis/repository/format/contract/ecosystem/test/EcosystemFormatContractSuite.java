package build.jenesis.repository.format.contract.ecosystem.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.format.testkit.FormatContract;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.StoredListing;

/**
 * The JUnit driver for one ecosystem format's leg of the shared {@code RepositoryFormat} contract. Everything
 * format-specific lives in the {@link EcosystemFormatFixture} a subclass supplies; the checks come from the free
 * the format testkit, so a new ecosystem format is covered by a fixture and a four-line subclass rather than by
 * another hand-written per-format suite - which is how the fourteen ecosystem layouts drifted into fourteen ideas of
 * what a traversal-shaped path, a held version or a proxied digest means.
 *
 * <p>Each contract property becomes one dynamic test named for its format and its expectation, so a divergence reports
 * as "debian: a HEAD answers from the store's metadata ..." rather than as one opaque failure. Every check gets its own
 * freshly created, empty store - absence is half of what these checks assert, so a store carrying another check's
 * leftovers would make "the traversal landed nothing" and "the held version is gone" weaker than they read.
 *
 * <p>The second factory is the packaged-artifact addition: the four ecosystems whose publish protocol <em>parses</em> the
 * artifact ({@code .nupkg}, {@code .gem}, {@code .deb}, {@code .rpm}) cannot take the kit's arbitrary byte body, so
 * they exclude its two publish legs with that protocol reason and run the same two properties over a real package
 * here. It emits nothing for a format that runs the kit's own legs, so the two factories never both cover one format.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class EcosystemFormatContractSuite {

    @TempDir
    Path root;

    /**
     * Finish any derivation a check queued before the {@code @TempDir} above is deleted.
     *
     * <p>A format may hand a derived twin to {@link StoredListing#later} to keep it off the request path - conda's
     * {@code repodata.json.bz2} is the one that does today. That write lands on a background thread, and a
     * {@code @TempDir} is removed the moment the test method returns, so the two race: the derivation writes into a
     * directory JUnit is deleting and the failure surfaces as {@code Failed to close extension context}, nowhere
     * near the format that caused it. Reproduced exactly that way before this was added.
     *
     * <p>It is here rather than in the conda subclass on purpose. Any format may grow a deferred twin, and the
     * hazard belongs to <em>owning a temp store</em>, not to conda - putting it in the one subclass that needs it
     * today is what leaves the next one to rediscover this from a flake. Costs microseconds when nothing is
     * pending: it submits a marker to an idle executor and returns.
     */
    @AfterEach
    void settleDeferredDerivations() {
        StoredListing.settle();
    }

    /** The format under test. */
    abstract EcosystemFormatFixture fixture();

    @TestFactory
    Stream<DynamicTest> the_repository_format_contract() {
        EcosystemFormatFixture fixture = fixture();
        return FormatContract.checks(fixture).stream().map(check -> DynamicTest.dynamicTest(
                fixture.format() + ": " + check.name(),
                () -> check.body().run(fixture, store(fixture.format() + "-" + check.property()))));
    }

    @TestFactory
    Stream<DynamicTest> the_packaged_artifact_contract() {
        EcosystemFormatFixture fixture = fixture();
        return PackagedArtifactContract.checks(fixture).stream().map(check -> DynamicTest.dynamicTest(
                fixture.format() + ": " + check.name(),
                () -> check.body().run(fixture, store(fixture.format() + "-" + check.property()))));
    }

    private ArtifactStore store(String name) throws IOException {
        Path directory = Files.createDirectories(root.resolve(name.replaceAll("[^A-Za-z0-9]", "_")));
        return ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? directory.toString() : null);
    }
}
