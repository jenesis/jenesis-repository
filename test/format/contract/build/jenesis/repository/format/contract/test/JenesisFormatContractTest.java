package build.jenesis.repository.format.contract.test;

import build.jenesis.repository.format.testkit.FormatFixture;

/** The Jenesis leg of the shared format contract; everything Jenesis-specific lives in {@link JenesisFormatFixture}. */
class JenesisFormatContractTest extends FormatContractSuite {

    @Override
    FormatFixture fixture() {
        return new JenesisFormatFixture();
    }
}
