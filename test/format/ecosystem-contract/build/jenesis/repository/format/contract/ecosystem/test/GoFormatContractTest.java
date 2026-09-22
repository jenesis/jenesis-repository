package build.jenesis.repository.format.contract.ecosystem.test;

/** The Go module proxy's leg of the shared {@code RepositoryFormat} contract - the checks live in the shared format
 *  testkit and everything go-specific in GoFormatFixture. */
class GoFormatContractTest extends EcosystemFormatContractSuite {

    @Override
    EcosystemFormatFixture fixture() {
        return new GoFormatFixture();
    }
}
