package build.jenesis.repository.importer.contract.test;

import build.jenesis.repository.importer.testkit.ImportFixture;

/** The maven leg of the shared import contract; everything maven-specific lives in {@link MavenImportFixture}. */
class MavenImportContractTest extends ImportContractSuite {

    @Override
    ImportFixture fixture() {
        return new MavenImportFixture();
    }
}
