package build.jenesis.repository.closure.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.closure.testkit.ClosureContract;
import build.jenesis.repository.closure.testkit.ClosureFixture;
import build.jenesis.repository.compliance.testkit.NoEgressResolver;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

/**
 * The JUnit driver for one source's leg of the shared {@code ClosureSource} contract: each check one dynamic test named
 * for its source and its expectation, over its own fresh store, and none reaching for the network.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class ClosureContractSuite {

    @TempDir
    Path root;

    /** The source under test. */
    abstract ClosureFixture fixture();

    @TestFactory
    Stream<DynamicTest> the_closure_source_contract() {
        ClosureFixture fixture = fixture();
        return ClosureContract.checks(fixture).stream().map(check -> DynamicTest.dynamicTest(
                fixture.source() + ": " + check.name(), () -> {
                    List<String> before = NoEgressResolver.attempted();
                    check.body().run(fixture, store(fixture.source() + "-" + check.property()));
                    Assertions.assertTrue(NoEgressResolver.since(before).isEmpty(),
                            () -> "reached for " + NoEgressResolver.since(before));
                }));
    }

    private ArtifactStore store(String name) throws IOException {
        Path directory = Files.createDirectories(root.resolve(name.replaceAll("[^A-Za-z0-9]", "_")));
        return ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? directory.toString() : null).scope("default")
                .scope("releases");
    }
}
