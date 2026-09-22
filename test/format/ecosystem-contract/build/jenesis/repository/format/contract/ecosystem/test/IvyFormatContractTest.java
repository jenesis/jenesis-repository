package build.jenesis.repository.format.contract.ecosystem.test;

/** Ivy's leg of the shared {@code RepositoryFormat} contract; everything Ivy-specific is in IvyFormatFixture. */
class IvyFormatContractTest extends EcosystemFormatContractSuite {

    @Override
    EcosystemFormatFixture fixture() {
        return new IvyFormatFixture();
    }
}
