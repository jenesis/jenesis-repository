package build.jenesis.repository.gateway.contract.test;

import build.jenesis.repository.compliance.testkit.SignatureSchemeFixture;

/** The shared signature scheme contract on the bare signature beside the chain the artifact carries. */
class X509DetachedSchemeContractTest extends SignatureSchemeContractSuite {

    @Override
    SignatureSchemeFixture fixture() {
        return new X509DetachedSchemeFixture();
    }
}
