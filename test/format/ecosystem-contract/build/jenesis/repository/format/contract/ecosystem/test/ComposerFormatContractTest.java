package build.jenesis.repository.format.contract.ecosystem.test;

/** The Composer (PHP) registry's leg of the shared {@code RepositoryFormat} contract - the checks live in the shared format
 *  testkit and everything composer-specific in ComposerFormatFixture. */
class ComposerFormatContractTest extends EcosystemFormatContractSuite {

    @Override
    EcosystemFormatFixture fixture() {
        return new ComposerFormatFixture();
    }
}
