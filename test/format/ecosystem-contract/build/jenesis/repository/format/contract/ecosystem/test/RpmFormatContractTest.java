package build.jenesis.repository.format.contract.ecosystem.test;

/** The RPM/yum repository's leg of the shared {@code RepositoryFormat} contract - the checks live in the shared format
 *  testkit and everything rpm-specific in RpmFormatFixture. */
class RpmFormatContractTest extends EcosystemFormatContractSuite {

    @Override
    EcosystemFormatFixture fixture() {
        return new RpmFormatFixture();
    }
}
