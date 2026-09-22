package build.jenesis.repository.format.contract.ecosystem.test;

/** Swift's leg of the shared {@code RepositoryFormat} contract; everything Swift-specific is in
 *  SwiftFormatFixture. */
class SwiftFormatContractTest extends EcosystemFormatContractSuite {

    @Override
    EcosystemFormatFixture fixture() {
        return new SwiftFormatFixture();
    }
}
