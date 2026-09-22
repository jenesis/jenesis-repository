package build.jenesis.repository.format.contract.ecosystem.test;

/** The PyPI Simple API's leg of the shared {@code RepositoryFormat} contract - the checks live in the shared format
 *  testkit and everything pypi-specific in PyPiFormatFixture. */
class PyPiFormatContractTest extends EcosystemFormatContractSuite {

    @Override
    EcosystemFormatFixture fixture() {
        return new PyPiFormatFixture();
    }
}
