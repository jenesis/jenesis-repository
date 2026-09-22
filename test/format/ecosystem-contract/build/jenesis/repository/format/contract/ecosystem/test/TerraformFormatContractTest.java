package build.jenesis.repository.format.contract.ecosystem.test;

/** Terraform's leg of the shared {@code RepositoryFormat} contract; everything Terraform-specific is in
 *  TerraformFormatFixture. */
class TerraformFormatContractTest extends EcosystemFormatContractSuite {

    @Override
    EcosystemFormatFixture fixture() {
        return new TerraformFormatFixture();
    }
}
