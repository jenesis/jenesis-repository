package build.jenesis.repository.publication.contract.test;

import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.testkit.FaultInjectingStore;
import build.jenesis.repository.store.testkit.PublicationHookContract;
import build.jenesis.repository.store.testkit.PublicationHookFixture;
import module org.junit.jupiter.api;

import module java.base;

/**
 * The JUnit driver for one hook's leg of the shared publication-hook contract. Everything hook-specific lives in the
 * {@link PublicationHookFixture} a subclass supplies; the checks come from the testkit, so a new hook is covered by a
 * fixture and a four-line subclass rather than by another hand-written suite.
 *
 * <p>Which checks a subclass gets is not its choice: {@link PublicationHookContract#checks(PublicationHookFixture)}
 * derives the role from the fixture's own instance and hands out only that role's contract. A screen therefore cannot
 * be driven through the contained observer legs by mistake, and an observer cannot be held to the fail-closed ones.
 *
 * <p>Each check gets its own freshly created, empty store wrapped in a {@link FaultInjectingStore}: absence,
 * convergence and crash windows are all what these checks assert, so a store carrying another check's rows would
 * weaken them.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class PublicationHookContractSuite {

    @TempDir
    Path root;

    /** The hook under test. */
    abstract PublicationHookFixture fixture();

    @TestFactory
    Stream<DynamicTest> the_publication_hook_contract() {
        PublicationHookFixture fixture = fixture();
        List<PublicationHookContract.Check> checks = PublicationHookContract.checks(fixture);
        if (checks.isEmpty()) {
            throw new AssertionError("the '" + fixture.hook() + "' fixture runs no checks at all, so its role "
                    + fixture.role() + " has no contract in the kit");
        }
        return checks.stream().map(check -> DynamicTest.dynamicTest(
                fixture.hook() + ": " + check.name(),
                () -> check.body().run(fixture, store(fixture.hook() + "-" + check.property()))));
    }

    private FaultInjectingStore store(String name) throws IOException {
        Path directory = Files.createDirectories(root.resolve(name.replaceAll("[^A-Za-z0-9]", "_")));
        return FaultInjectingStore.wrap(ArtifactStoreProvider.resolve("filesystem",
                key -> "JENESIS_STORE_ROOT".equals(key) ? directory.toString() : null));
    }
}
