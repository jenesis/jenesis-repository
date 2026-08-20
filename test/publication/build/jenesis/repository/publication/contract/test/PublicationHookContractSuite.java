package build.jenesis.repository.publication.contract.test;

import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.testkit.FaultInjectingStore;
import build.jenesis.repository.store.testkit.Falsification;
import build.jenesis.repository.store.testkit.Mutant;
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
 *
 * <p><b>And every check is run a second time against each mutation its property declares</b> (D-135), by
 * {@link #every_contract_check_is_falsifiable()}. The two factories are deliberately separate: the first says what
 * the hook does, the second says the first could have said otherwise, and a green in one is not evidence for the
 * other. The mutated leg runs in the same lane as the ordinary one - it costs roughly what the ordinary leg costs,
 * because a mutated check usually fails on its first assertion rather than running to the end - so a kit that has
 * stopped measuring anything cannot stay green anywhere the ordinary kit is green.
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
                () -> Falsification.run(fixture, check, Mutant.NONE, this::store)));
    }

    /**
     * The falsification leg: each check, re-run against every mutation its property declares for this hook, requiring
     * the check to say otherwise. A check that survives is not measuring its property for this hook - it would stay
     * green over the very defect the property exists to name - and that is what fails here.
     */
    @TestFactory
    Stream<DynamicTest> every_contract_check_is_falsifiable() {
        PublicationHookFixture fixture = fixture();
        return PublicationHookContract.checks(fixture).stream()
                .flatMap(check -> PublicationHookContract.mutations(fixture, check.property()).stream()
                        .map(mutation -> DynamicTest.dynamicTest(
                                fixture.hook() + ": " + mutation.mutant() + " must break - " + check.name(),
                                () -> Falsification.requireBroken(fixture, check, mutation, this::store))));
    }

    private FaultInjectingStore store(String name) throws IOException {
        Path directory = Files.createDirectories(root.resolve(name.replaceAll("[^A-Za-z0-9]", "_")));
        return FaultInjectingStore.wrap(ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? directory.toString() : null));
    }
}
