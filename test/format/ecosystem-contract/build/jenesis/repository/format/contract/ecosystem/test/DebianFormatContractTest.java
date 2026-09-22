package build.jenesis.repository.format.contract.ecosystem.test;

/** The Debian/apt archive's leg of the shared {@code RepositoryFormat} contract - the checks live in the shared format
 *  testkit and everything debian-specific in DebianFormatFixture. */
class DebianFormatContractTest extends EcosystemFormatContractSuite {

    @Override
    EcosystemFormatFixture fixture() {
        return new DebianFormatFixture();
    }
}
