package build.jenesis.repository.format.contract.ecosystem.test;

/** The Conda channel's leg of the shared {@code RepositoryFormat} contract - the checks live in the shared format
 *  testkit and everything conda-specific in CondaFormatFixture. */
class CondaFormatContractTest extends EcosystemFormatContractSuite {

    @Override
    EcosystemFormatFixture fixture() {
        return new CondaFormatFixture();
    }
}
