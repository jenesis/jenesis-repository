package build.jenesis.repository.format.contract.test;

import build.jenesis.repository.format.testkit.FormatFixture;

/** The Raw leg of the shared format contract; everything Raw-specific lives in {@link RawFormatFixture}. */
class RawFormatContractTest extends FormatContractSuite {

    @Override
    FormatFixture fixture() {
        return new RawFormatFixture();
    }
}
