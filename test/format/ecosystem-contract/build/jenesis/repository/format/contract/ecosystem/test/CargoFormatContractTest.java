package build.jenesis.repository.format.contract.ecosystem.test;

/** The Cargo sparse-index registry's leg of the shared {@code RepositoryFormat} contract - the checks live in the shared format
 *  testkit and everything cargo-specific in CargoFormatFixture. */
class CargoFormatContractTest extends EcosystemFormatContractSuite {

    @Override
    EcosystemFormatFixture fixture() {
        return new CargoFormatFixture();
    }
}
