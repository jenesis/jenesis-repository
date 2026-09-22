package build.jenesis.repository.gateway.contract.test;

import build.jenesis.repository.compliance.testkit.SignatureSchemeFixture;

/** The shared signature scheme contract on the PKCS#7 structure carrying its signer's chain. */
class Pkcs7SchemeContractTest extends SignatureSchemeContractSuite {

    @Override
    SignatureSchemeFixture fixture() {
        return new Pkcs7SchemeFixture();
    }
}
