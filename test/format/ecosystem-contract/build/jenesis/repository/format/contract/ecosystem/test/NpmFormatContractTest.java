package build.jenesis.repository.format.contract.ecosystem.test;

/** The npm registry's leg of the shared {@code RepositoryFormat} contract - the checks live in the shared format
 *  testkit and everything npm-specific in NpmFormatFixture. */
class NpmFormatContractTest extends EcosystemFormatContractSuite {

    @Override
    EcosystemFormatFixture fixture() {
        return new NpmFormatFixture();
    }
}
