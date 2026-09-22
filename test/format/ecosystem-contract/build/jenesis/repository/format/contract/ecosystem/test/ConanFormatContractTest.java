package build.jenesis.repository.format.contract.ecosystem.test;

/** The Conan (C/C++) v2 registry's leg of the shared {@code RepositoryFormat} contract - the checks live in the shared format
 *  testkit and everything conan-specific in ConanFormatFixture. */
class ConanFormatContractTest extends EcosystemFormatContractSuite {

    @Override
    EcosystemFormatFixture fixture() {
        return new ConanFormatFixture();
    }
}
