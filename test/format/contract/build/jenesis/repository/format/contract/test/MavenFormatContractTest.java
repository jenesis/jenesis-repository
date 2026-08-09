package build.jenesis.repository.format.contract.test;

import build.jenesis.repository.format.testkit.FormatFixture;

/** The Maven leg of the shared format contract; everything Maven-specific lives in {@link MavenFormatFixture}. */
class MavenFormatContractTest extends FormatContractSuite {

    @Override
    FormatFixture fixture() {
        return new MavenFormatFixture();
    }
}
