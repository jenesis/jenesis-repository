package build.jenesis.repository.format.contract.ecosystem.test;

/** The Hugging Face Hub's leg of the shared {@code RepositoryFormat} contract - the checks live in the shared format
 *  testkit and everything huggingface-specific in HuggingFaceFormatFixture. */
class HuggingFaceFormatContractTest extends EcosystemFormatContractSuite {

    @Override
    EcosystemFormatFixture fixture() {
        return new HuggingFaceFormatFixture();
    }
}
