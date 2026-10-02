package build.jenesis.repository.format.contract.ecosystem.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.format.testkit.FormatContract;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.StoredCounter;
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
     * Finish every write a check left to a background thread before the {@code @TempDir} above is deleted.
     *
     * <p>Two kinds land after the check returns: a derived twin a format hands to {@link StoredListing#later} to keep
     * it off the request path (conda's {@code repodata.json.bz2}), and a counter delta a publication observer defers
     * to the node's flusher (the subtree sizes every publish rolls up), which is written at the next flush tick. Either
     * may then write into a directory JUnit is deleting, and the failure surfaces as {@code Failed to close extension
     * context}, nowhere near the format that caused it.
     *
     * <p>It is here rather than in one subclass because the hazard belongs to owning a temp store, not to a format.
     * Costs microseconds when nothing is pending.
     */
    @AfterEach
    void settleDeferredWrites() {
        StoredListing.settle();
        StoredCounter.settle();
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
                key -> "jenrepo.filesystem.root".equals(key) ? directory.toString() : null);
    }
}
