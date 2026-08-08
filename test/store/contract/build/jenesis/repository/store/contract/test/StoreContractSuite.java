package build.jenesis.repository.store.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.store.testkit.StoreContract;
import build.jenesis.repository.store.testkit.StoreFixture;

/**
 * The JUnit driver for one backend's leg of the shared {@code ArtifactStore} contract. Everything backend-specific
 * lives in the {@link StoreFixture} a subclass supplies; the checks themselves come from the testkit, so a new backend
 * is covered by a fixture and a four-line subclass rather than by another hand-written suite.
 *
 * <p>Each contract property becomes one dynamic test, named for its backend and its expectation, so a divergence
 * reports as "azure-blob: page streams ordered children ..." rather than as one opaque failure covering sixteen
 * properties. A fixture that cannot start self-skips the class on a developer machine and fails it under the strict
 * lane - the decision lives in {@link StoreFixture#skipReason}, once, for every backend.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class StoreContractSuite {

    private StoreFixture fixture;

    /** The backend under test. Called once; the fixture must start nothing in its constructor, because the census
     *  instantiates every fixture without a Docker daemon. */
    abstract StoreFixture fixture();

    @BeforeAll
    void start() throws Exception {
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
    void stop() throws Exception {
        if (fixture != null) {
            fixture.close();
        }
    }

    @TestFactory
    Stream<DynamicTest> the_artifact_store_contract() {
        return StoreContract.checks(fixture).stream().map(check -> DynamicTest.dynamicTest(
                fixture.backend() + ": " + check.name(),
                () -> check.body().run(fixture.store())));
    }
}
