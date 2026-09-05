package build.jenesis.repository.store.testkit;

import module java.base;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;

/**
 * The JUnit driver for one leg of the shared {@code ArtifactStore} contract. Everything leg-specific lives in the
 * {@link StoreFixture} a subclass supplies - a backend over its emulator, a decorator over the filesystem store - and
 * the checks themselves are {@link StoreContract}'s, so a new leg is a fixture and a four-line subclass rather than
 * another hand-written suite. It lives in the kit because two modules used to carry it as identical copies, and a
 * third was about to.
 *
 * <p>Each contract property becomes one dynamic test, named for its leg and its expectation, so a divergence reports
 * as "azure-blob: page streams ordered children ..." rather than as one opaque failure covering the whole contract. A
 * fixture that cannot start self-skips the class on a developer machine and fails it under the strict lane - the
 * decision lives in {@link StoreFixture#skipReason}, once, for every leg.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class StoreContractSuite {

    private StoreFixture fixture;

    /** The leg under test. Called once; the fixture must start nothing in its constructor, because a census
     *  instantiates every fixture without a Docker daemon. */
    protected abstract StoreFixture fixture();

    @BeforeAll
    public void start() throws Exception {
        StoreFixture candidate = fixture();
        Optional<String> skip = StoreFixture.skipReason(candidate);
        if (skip.isPresent()) {
            Assumptions.abort(skip.get());
        }
        // Past this point a failure is a failure: a fixture that began starting and could not finish is exactly the
        // broken lane a self-skip would hide, so nothing below is guarded by an assumption.
        candidate.start();
        fixture = candidate;
    }

    @AfterAll
    public void stop() throws Exception {
        if (fixture != null) {
            fixture.close();
        }
    }

    @TestFactory
    public Stream<DynamicTest> the_artifact_store_contract() {
        return StoreContract.checks(fixture).stream().map(check -> DynamicTest.dynamicTest(
                fixture.backend() + ": " + check.name(),
                () -> check.body().run(fixture.store())));
    }
}
