package build.jenesis.repository.gateway.contract.test;

import build.jenesis.repository.compliance.testkit.SignatureSchemeFixture;

/** The shared signature scheme contract on the clearsigned OpenPGP document naming its artifact. */
class OpenPgpClearsignedSchemeContractTest extends SignatureSchemeContractSuite {

    @Override
    SignatureSchemeFixture fixture() {
        return new OpenPgpClearsignedSchemeFixture();
    }
}
