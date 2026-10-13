package build.jenesis.repository.publication.contract.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.store.testkit.FaultInjectingStore;
import build.jenesis.repository.store.testkit.Falsification;
import build.jenesis.repository.store.testkit.Mutant;
import build.jenesis.repository.store.testkit.PublicationHookContract;
import build.jenesis.repository.store.testkit.PublicationHookFixture;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;
import build.jenesis.repository.hooks.testkit.HookStores;
import build.jenesis.repository.webhook.Webhooks;

/**
 * The JUnit driver of the shared publication-hook contract, run once over every fixture in
 * {@link PublicationHookFixtures} - the list the census reads, so a hook it counts is a hook this runs. Everything
 * hook-specific lives in the {@link PublicationHookFixture}; the checks come from the testkit, so a new hook is covered
 * by a fixture and a line in that list rather than by another hand-written suite.
 *
 * <p>Which checks a fixture gets is not its choice: {@link PublicationHookContract#checks(PublicationHookFixture)}
 * derives the role from the fixture's own instance and hands out only that role's contract. A screen therefore cannot
 * be driven through the contained observer legs by mistake, and an observer cannot be held to the fail-closed ones.
 *
 * <p>Each check gets its own freshly created, empty store wrapped in a {@link FaultInjectingStore}: absence,
 * convergence and crash windows are all what these checks assert, so a store carrying another check's rows would
 * weaken them. A shipped hook gated on the surface it feeds gets that surface's deployment seeded first, by
 * {@link HookStores#deployed}, and the webhook latch is put back before every check.
 *
 * <p><b>And every check is run a second time against each mutation its property declares</b>, by
 * {@link #every_contract_check_is_falsifiable()}. The two factories are deliberately separate: the first says what
 * the hook does, the second says the first could have said otherwise, and a green in one is not evidence for the
 * other. The mutated leg runs in the same lane as the ordinary one - it costs roughly what the ordinary leg costs,
 * because a mutated check usually fails on its first assertion rather than running to the end - so a kit that has
 * stopped measuring anything cannot stay green anywhere the ordinary kit is green.
 */
@ParameterizedClass(name = "{0}")
@MethodSource("fixtures")
class PublicationHookContractTest {

    /** Every fixture, named for the hook it drives. */
    static List<Named<PublicationHookFixture>> fixtures() {
        return PublicationHookFixtures.all().stream().map(fixture -> Named.of(fixture.hook(), fixture)).toList();
    }

    @Parameter
    PublicationHookFixture fixture;

    @TempDir
    Path root;

    @TestFactory
    Stream<DynamicTest> the_publication_hook_contract() {
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
        return PublicationHookContract.checks(fixture).stream()
                .flatMap(check -> PublicationHookContract.mutations(fixture, check.property()).stream()
                        .filter(mutation -> HookStores.injectable(fixture, mutation))
                        .map(mutation -> DynamicTest.dynamicTest(
                                fixture.hook() + ": " + mutation.mutant() + " must break - " + check.name(),
                                () -> Falsification.requireBroken(fixture, check, mutation, this::store))));
    }

    private FaultInjectingStore store(String name) throws IOException {
        return HookStores.deployed(root, fixture, name, PublicationHookContractTest::reset);
    }

    /** Put the one process-global latch this graph carries back where a fresh process finds it. */
    static void reset() {
        Webhooks.configure(false);
    }
}
