package build.jenesis.repository.format.contract.ecosystem.test;

/** Helm's leg of the shared {@code RepositoryFormat} contract; everything Helm-specific is in HelmFormatFixture. */
class HelmFormatContractTest extends EcosystemFormatContractSuite {

    @Override
    EcosystemFormatFixture fixture() {
        return new HelmFormatFixture();
    }
}
