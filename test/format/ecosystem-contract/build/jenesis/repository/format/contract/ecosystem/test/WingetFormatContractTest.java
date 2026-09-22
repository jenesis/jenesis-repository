package build.jenesis.repository.format.contract.ecosystem.test;

/** winget's leg of the shared {@code RepositoryFormat} contract; everything winget-specific is in
 *  WingetFormatFixture. */
class WingetFormatContractTest extends EcosystemFormatContractSuite {

    @Override
    EcosystemFormatFixture fixture() {
        return new WingetFormatFixture();
    }
}
