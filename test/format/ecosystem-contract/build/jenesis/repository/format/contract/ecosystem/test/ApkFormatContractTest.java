package build.jenesis.repository.format.contract.ecosystem.test;

/** The Alpine apk format's leg of the shared {@code RepositoryFormat} contract; everything apk-specific is in
 *  ApkFormatFixture. */
class ApkFormatContractTest extends EcosystemFormatContractSuite {

    @Override
    EcosystemFormatFixture fixture() {
        return new ApkFormatFixture();
    }
}
