package build.jenesis.repository.format.contract.ecosystem.test;

/** The CocoaPods CDN's leg of the shared {@code RepositoryFormat} contract - the checks live in the shared format
 *  testkit and everything cocoapods-specific in CocoaPodsFormatFixture. */
class CocoaPodsFormatContractTest extends EcosystemFormatContractSuite {

    @Override
    EcosystemFormatFixture fixture() {
        return new CocoaPodsFormatFixture();
    }
}
