package build.jenesis.repository.gateway.contract.test;

import build.jenesis.repository.compliance.testkit.SignatureSchemeFixture;

/** The shared signature scheme contract on the detached OpenPGP signature over an artifact's bytes. */
class OpenPgpDetachedSchemeContractTest extends SignatureSchemeContractSuite {

    @Override
    SignatureSchemeFixture fixture() {
        return new OpenPgpDetachedSchemeFixture();
    }
}
