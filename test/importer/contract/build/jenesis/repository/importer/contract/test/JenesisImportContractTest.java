package build.jenesis.repository.importer.contract.test;

import build.jenesis.repository.importer.testkit.ImportFixture;

/** The jenesis leg of the shared import contract; everything jenesis-specific lives in {@link JenesisImportFixture}. */
class JenesisImportContractTest extends ImportContractSuite {

    @Override
    ImportFixture fixture() {
        return new JenesisImportFixture();
    }
}
