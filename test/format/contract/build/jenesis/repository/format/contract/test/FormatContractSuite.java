package build.jenesis.repository.format.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.format.testkit.FormatContract;
import build.jenesis.repository.format.testkit.FormatFixture;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

/**
 * The JUnit driver for one format's leg of the shared {@code RepositoryFormat} contract. Everything format-specific
 * lives in the {@link FormatFixture} a subclass supplies; the checks themselves come from the testkit, so a new format
 * is covered by a fixture and a four-line subclass rather than by another hand-written suite.
 *
 * <p>Each contract property becomes one dynamic test, named for its format and its expectation, so a divergence reports
 * as "raw: a HEAD answers from the store's metadata ..." rather than as one opaque failure covering eight properties.
 * Every check gets its own freshly created, empty store - absence is half of what these checks assert, so a store
 * carrying another check's leftovers would make "the traversal landed nothing" and "the held version is gone" weaker
 * than they read.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class FormatContractSuite {

    @TempDir
    Path root;

    /** The format under test. */
    abstract FormatFixture fixture();

    @TestFactory
    Stream<DynamicTest> the_repository_format_contract() {
        FormatFixture fixture = fixture();
        return FormatContract.checks(fixture).stream().map(check -> DynamicTest.dynamicTest(
                fixture.format() + ": " + check.name(),
                () -> check.body().run(fixture, store(fixture.format() + "-" + check.property()))));
    }

    private ArtifactStore store(String name) throws IOException {
        Path directory = Files.createDirectories(root.resolve(name.replaceAll("[^A-Za-z0-9]", "_")));
        return ArtifactStoreProvider.resolve("filesystem",
                key -> "JENESIS_STORE_ROOT".equals(key) ? directory.toString() : null);
    }
}
