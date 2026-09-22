package build.jenesis.repository.format.contract.ecosystem.test;

/** The RubyGems compact index's leg of the shared {@code RepositoryFormat} contract - the checks live in the shared format
 *  testkit and everything rubygems-specific in RubyGemsFormatFixture. */
class RubyGemsFormatContractTest extends EcosystemFormatContractSuite {

    @Override
    EcosystemFormatFixture fixture() {
        return new RubyGemsFormatFixture();
    }
}
