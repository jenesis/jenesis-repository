package build.jenesis.repository.format.contract.test;

import build.jenesis.repository.format.testkit.FormatFixture;

/** The Oci leg of the shared format contract; everything Oci-specific lives in {@link OciFormatFixture}. */
class OciFormatContractTest extends FormatContractSuite {

    @Override
    FormatFixture fixture() {
        return new OciFormatFixture();
    }
}
