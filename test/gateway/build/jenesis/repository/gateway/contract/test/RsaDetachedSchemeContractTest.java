package build.jenesis.repository.gateway.contract.test;

import build.jenesis.repository.compliance.testkit.SignatureSchemeFixture;

/** The shared signature scheme contract on the bare RSA signature named after its key file. */
class RsaDetachedSchemeContractTest extends SignatureSchemeContractSuite {

    @Override
    SignatureSchemeFixture fixture() {
        return new RsaDetachedSchemeFixture();
    }
}
