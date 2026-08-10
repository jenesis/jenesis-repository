package build.jenesis.repository.publication.contract.test;

import build.jenesis.repository.store.testkit.PublicationHookFixture;

/** the screen that also overrides the inherited observer leg's leg of the shared publication-hook contract. */
class AuditingScreenContractTest extends PublicationHookContractSuite {

    @Override
    PublicationHookFixture fixture() {
        return new AuditingScreenFixture();
    }
}
