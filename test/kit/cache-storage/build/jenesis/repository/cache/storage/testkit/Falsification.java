package build.jenesis.repository.cache.storage.testkit;

import module java.base;
import build.jenesis.repository.cache.storage.CacheStorage;

/**
 * Runs one contract check against the mutant its property declares, and insists the check notices.
 *
 * <p>The distinction this draws is the whole point of the leg. A check that answers the mutation with an
 * {@link AssertionError} has <em>measured</em> the property: it looked, and what it looked at was wrong. A check
 * that answers with anything else - an exception, an error - has been knocked over rather than falsified, and says
 * nothing about whether it measures anything. A check that <em>passes</em> is the finding: it would stay green over
 * exactly the defect its property exists to name.
 */
public final class Falsification {

    private Falsification() {
    }

    /** Run {@code check} against {@code mutant}'s substitution for {@code storage}. */
    public static void run(CacheStorage storage, CacheStorageContract.Check check, CacheStorageMutant mutant)
            throws Exception {
        check.body().run(mutant.decorate(storage));
    }

    /**
     * Assert that {@code check} fails against the mutant {@code mutation} names - and fails as an assertion rather
     * than by falling over.
     */
    public static void requireBroken(String backend, CacheStorage storage, CacheStorageContract.Check check,
                                     CacheStorageContract.Mutation mutation) throws Exception {
        try {
            run(storage, check, mutation.mutant());
        } catch (AssertionError expected) {
            return;
        } catch (Exception | Error broken) {
            throw new AssertionError(backend + ": '" + check.name() + "' did not fail against " + mutation.mutant()
                    + " - it broke. The mutation removes " + mutation.mutant().removes() + ", and a check may only "
                    + "answer that with an AssertionError; anything else means the mutant took the harness out from "
                    + "under the check rather than falsifying it, and says nothing about whether the check measures "
                    + "its property.", broken);
        }
        throw new AssertionError(backend + ": '" + check.name() + "' PASSED against " + mutation.mutant()
                + ", which removes " + mutation.mutant().removes() + ". A check that survives the mutation its "
                + "property declares does not measure that property for this backend - it would stay green over "
                + "exactly the defect the property exists to name. " + mutation.why() + ". Either the check has "
                + "stopped biting for this backend, or the mutation is the wrong one for this property and the "
                + "declaration in CacheStorageContract.mutations() is what needs the argument.");
    }
}
