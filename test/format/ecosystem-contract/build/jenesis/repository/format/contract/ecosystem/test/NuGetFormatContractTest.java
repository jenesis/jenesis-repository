package build.jenesis.repository.format.contract.ecosystem.test;

/** The NuGet v3 API's leg of the shared {@code RepositoryFormat} contract - the checks live in the shared format
 *  testkit and everything nuget-specific in NuGetFormatFixture. */
class NuGetFormatContractTest extends EcosystemFormatContractSuite {

    @Override
    EcosystemFormatFixture fixture() {
        return new NuGetFormatFixture();
    }
}
