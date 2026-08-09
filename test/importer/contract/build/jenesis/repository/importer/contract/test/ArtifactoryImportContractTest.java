package build.jenesis.repository.importer.contract.test;

import build.jenesis.repository.importer.testkit.ImportFixture;

/** The artifactory leg of the shared import contract; everything artifactory-specific lives in {@link ArtifactoryImportFixture}. */
class ArtifactoryImportContractTest extends ImportContractSuite {

    @Override
    ImportFixture fixture() {
        return new ArtifactoryImportFixture();
    }
}
