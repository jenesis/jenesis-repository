package build.jenesis.repository.format.contract.ecosystem.test;

/** Homebrew's leg of the shared {@code RepositoryFormat} contract; everything Homebrew-specific is in
 *  HomebrewFormatFixture. */
class HomebrewFormatContractTest extends EcosystemFormatContractSuite {

    @Override
    EcosystemFormatFixture fixture() {
        return new HomebrewFormatFixture();
    }
}
