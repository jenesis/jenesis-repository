package build.jenesis.repository.importer.contract.test;

import build.jenesis.repository.importer.testkit.ImportFixture;

/** The index leg of the shared import contract; everything index-specific lives in {@link IndexImportFixture}. */
class IndexImportContractTest extends ImportContractSuite {

    @Override
    ImportFixture fixture() {
        return new IndexImportFixture();
    }
}
