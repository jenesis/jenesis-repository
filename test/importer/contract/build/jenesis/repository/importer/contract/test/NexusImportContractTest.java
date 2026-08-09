package build.jenesis.repository.importer.contract.test;

import build.jenesis.repository.importer.testkit.ImportFixture;

/** The nexus leg of the shared import contract; everything nexus-specific lives in {@link NexusImportFixture}. */
class NexusImportContractTest extends ImportContractSuite {

    @Override
    ImportFixture fixture() {
        return new NexusImportFixture();
    }
}
