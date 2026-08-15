package build.jenesis.repository.store.testkit;

import module java.base;

/**
 * Runs one publication-hook contract check over its own deployment - once as itself, and once against a
 * {@link Mutant} it must not survive (D-135).
 *
 * <p>Both legs live here rather than in the JUnit driver for the same reason the checks live in
 * {@link PublicationHookContract}: a runner inside the suite could only ever be exercised by running the whole kit,
 * which would make the falsification leg the one part of the kit nothing falsifies. {@code PublicationHookCensusTest}
 * calls {@link #requireBroken} with synthetic checks instead, and holds all three of its outcomes.
 */
public final class Falsification {

    private Falsification() {
    }

    /**
     * How one check gets its store. Every check gets its own, freshly created and empty, because absence,
     * convergence and crash windows are all what these checks assert and a store carrying another check's rows would
     * weaken them. The driver owns the policy (a JUnit {@code @TempDir}); this module owns the sequence.
     */
    @FunctionalInterface
    public interface Deployment {

        /** A fresh, empty, fault-armable store named for the check about to run. */
        FaultInjectingStore store(String name) throws Exception;
    }

    /** Run {@code check} against {@code mutant}'s substitution for {@code fixture}'s hook, over a fresh store. */
    public static void run(PublicationHookFixture fixture, PublicationHookContract.Check check, Mutant mutant,
                           Deployment deployment) throws Exception {
        FaultInjectingStore store = deployment.store(fixture.hook() + "-" + check.property() + "-" + mutant);
        check.body().run(Mutant.decorate(fixture, mutant, store), store);
    }

    /**
     * The falsification leg: run {@code check} against the deliberately broken deployment object {@code mutation}
     * names, and require it to say otherwise.
     *
     * <p>Only an {@link AssertionError} counts. Anything else means the mutant broke the check's <em>machinery</em>
     * rather than its claim - a store that could not be read, a hook that could not be built - which says nothing
     * about whether the check measures the property, and is reported as its own failure rather than quietly banked as
     * a red.
     */
    public static void requireBroken(PublicationHookFixture fixture, PublicationHookContract.Check check,
                                     PublicationHookContract.Mutation mutation, Deployment deployment)
            throws Exception {
        try {
            run(fixture, check, mutation.mutant(), deployment);
        } catch (AssertionError expected) {
            return;
        } catch (Exception | StackOverflowError broken) {
            throw new AssertionError(fixture.hook() + ": '" + check.name() + "' did not fail against "
                    + mutation.mutant() + " - it broke. The mutation removes " + mutation.mutant().removes()
                    + ", and a check may only answer that with an AssertionError; anything else means the mutant "
                    + "took the harness out from under the check rather than falsifying it, and says nothing about "
                    + "whether the check measures its property.", broken);
        }
        throw new AssertionError(fixture.hook() + ": '" + check.name() + "' PASSED against " + mutation.mutant()
                + ", which removes " + mutation.mutant().removes() + ". A check that survives the mutation its "
                + "property declares does not measure that property for this hook - it would stay green over exactly "
                + "the defect the property exists to name. " + mutation.why() + ". Either the check has stopped "
                + "biting for this fixture (a hook the choreography no longer reaches, a projection that reads the "
                + "same either way), or the mutation is the wrong one for this property and the declaration in "
                + "PublicationHookContract.mutations() is what needs the argument.");
    }
}
