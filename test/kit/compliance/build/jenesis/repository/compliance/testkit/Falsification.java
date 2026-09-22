package build.jenesis.repository.compliance.testkit;

import module java.base;

/**
 * Runs one contract check over its own deployment - once as itself, and once against a mutant it must not survive
 * (for the signal kit, for the inspector one).
 *
 * <p>Both legs live here rather than in the JUnit driver for the same reason the checks live in
 * {@link SignalContract} and {@link InspectorContract}: a runner inside the suite could only ever be exercised by
 * running the whole kit, which would make the falsification leg the one part of the kit nothing falsifies. The two
 * census tests call {@link #requireBroken} with synthetic checks instead, and hold all three of its outcomes.
 *
 * <p>The two kits' overloads are deliberately not folded into one generic pair. The kits' fixtures share no
 * supertype, their mutants remove different things, and the failure message is the whole value of the leg - a
 * generic version would have to say "the mutant" where these say what was removed and what it means for a feed or
 * for an inspector.
 */
public final class Falsification {

    private Falsification() {
    }

    /** Run {@code check} against {@code mutant}'s substitution for one of {@code fixture}'s deployment objects. */
    public static void run(SignalFixture fixture, SignalContract.Check check, Mutant mutant) throws Exception {
        check.body().run(Mutant.decorate(fixture, mutant));
    }

    /**
     * The falsification leg: run {@code check} against the deliberately broken deployment object {@code mutation}
     * names, and require it to say otherwise.
     *
     * <p>Only an {@link AssertionError} counts. Anything else means the mutant broke the check's <em>machinery</em>
     * rather than its claim - a recording that could not be served, a tripwire that stopped recording - which says
     * nothing about whether the check measures the property, and is reported as its own failure rather than quietly
     * banked as a red. That distinction is load-bearing here: {@link Mutant#A_QUERY_THAT_REACHES_A_HOST_ALREADY_REACHED}
     * raises exactly this way when the no-egress record has become a sampling instrument.
     */
    public static void requireBroken(SignalFixture fixture, SignalContract.Check check,
                                     SignalContract.Mutation mutation) throws Exception {
        try {
            run(fixture, check, mutation.mutant());
        } catch (AssertionError expected) {
            return;
        } catch (Exception | Error broken) {
            throw new AssertionError(fixture.signal() + ": '" + check.name() + "' did not fail against "
                    + mutation.mutant() + " - it broke. The mutation removes " + mutation.mutant().removes()
                    + ", and a check may only answer that with an AssertionError; anything else means the mutant "
                    + "took the harness out from under the check rather than falsifying it, and says nothing about "
                    + "whether the check measures its property.", broken);
        }
        throw new AssertionError(fixture.signal() + ": '" + check.name() + "' PASSED against " + mutation.mutant()
                + ", which removes " + mutation.mutant().removes() + ". A check that survives the mutation its "
                + "property declares does not measure that property for this feed - it would stay green over exactly "
                + "the defect the property exists to name. " + mutation.why() + ". Either the check has stopped "
                + "biting for this fixture (a recording it no longer reads, an instrument that answers the same "
                + "either way), or the mutation is the wrong one for this property and the declaration in "
                + "SignalContract.mutations() is what needs the argument.");
    }

    // --- the inspector kit ---------------------------------------------------------------------------------

    /** Run {@code check} against {@code mutant}'s substitution for {@code fixture}'s inspector. */
    public static void run(InspectorFixture fixture, InspectorContract.Check check, InspectorMutant mutant)
            throws Exception {
        check.body().run(mutant.decorate(fixture));
    }

    /**
     * The falsification leg: run {@code check} against the deliberately broken inspector {@code mutation} names, and
     * require it to say otherwise.
     *
     * <p>Only an {@link AssertionError} counts. Anything else means the mutant broke the check's <em>machinery</em>
     * rather than its claim - an artifact that could not be built, a handle that could not be opened - which says
     * nothing about whether the check measures the property, and is reported as its own failure rather than quietly
     * banked as a red.
     */
    public static void requireBroken(InspectorFixture fixture, InspectorContract.Check check,
                                     InspectorContract.Mutation mutation) throws Exception {
        try {
            run(fixture, check, mutation.mutant());
        } catch (AssertionError expected) {
            return;
        } catch (Exception | Error broken) {
            throw new AssertionError(fixture.inspectorClass() + ": '" + check.name() + "' did not fail against "
                    + mutation.mutant() + " - it broke. The mutation removes " + mutation.mutant().removes()
                    + ", and a check may only answer that with an AssertionError; anything else means the mutant "
                    + "took the harness out from under the check rather than falsifying it, and says nothing about "
                    + "whether the check measures its property.", broken);
        }
        throw new AssertionError(fixture.inspectorClass() + ": '" + check.name() + "' PASSED against "
                + mutation.mutant() + ", which removes " + mutation.mutant().removes() + ". A check that survives the "
                + "mutation its property declares does not measure that property for this inspector - it would stay "
                + "green over exactly the defect the property exists to name. " + mutation.why() + ". Either the check "
                + "has stopped biting for this fixture (an artifact it no longer reads, an expectation that is empty "
                + "either way), or the mutation is the wrong one for this property and the declaration in "
                + "InspectorContract.mutations() is what needs the argument.");
    }

    // --- the scheme kit ------------------------------------------------------------------------------------

    /** Run {@code check} against {@code mutant}'s substitution for {@code fixture}'s scheme. */
    public static void run(SignatureSchemeFixture fixture, SignatureSchemeContract.Check check,
                           SignatureSchemeMutant mutant) throws Exception {
        check.body().run(mutant.decorate(fixture));
    }

    /**
     * The falsification leg: run {@code check} against the deliberately broken verifier {@code mutation} names, and
     * require it to say otherwise. Only an {@link AssertionError} counts, for the reason the two legs above give.
     */
    public static void requireBroken(SignatureSchemeFixture fixture, SignatureSchemeContract.Check check,
                                     SignatureSchemeContract.Mutation mutation) throws Exception {
        try {
            run(fixture, check, mutation.mutant());
        } catch (AssertionError expected) {
            return;
        } catch (Exception | Error broken) {
            throw new AssertionError(fixture.schemeClass() + ": '" + check.name() + "' did not fail against "
                    + mutation.mutant() + " - it broke. The mutation removes " + mutation.mutant().removes()
                    + ", and a check may only answer that with an AssertionError; anything else means the mutant "
                    + "took the harness out from under the check rather than falsifying it, and says nothing about "
                    + "whether the check measures its property.", broken);
        }
        throw new AssertionError(fixture.schemeClass() + ": '" + check.name() + "' PASSED against "
                + mutation.mutant() + ", which removes " + mutation.mutant().removes() + ". A check that survives the "
                + "mutation its property declares does not measure that property for this scheme - it would stay "
                + "green over exactly the defect the property exists to name. " + mutation.why() + ". Either the check "
                + "has stopped biting for this fixture, or the mutation is the wrong one for this property and the "
                + "declaration in SignatureSchemeContract.mutations() is what needs the argument.");
    }
}
