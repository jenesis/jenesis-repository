package build.jenesis.repository.closure.contract.test;

import build.jenesis.repository.closure.testkit.ClosureFixture;

/** One leg of the shared closure-source contract; what is particular to its source lives in {@link CarriedBillFixture}. */
class CarriedBillContractTest extends ClosureContractSuite {

    @Override
    ClosureFixture fixture() {
        return new CarriedBillFixture();
    }
}
