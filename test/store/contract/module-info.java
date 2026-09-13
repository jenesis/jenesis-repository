/**
 * The store-backend contract suite: the JUnit driver for the testkit's {@code StoreContract}, one fixture per
 * {@code ArtifactStoreProvider} backend, and the completeness census that keeps the two in step.
 *
 * <p>The suite exists because {@code StoreInvariants} / {@code FaultInjectingStore} were shared but the backend
 * <em>contract</em> was not: {@code test/store/{filesystem,s3,gcs,azure}} each hand-wrote their own idea of
 * what an {@code ArtifactStore} promises and drifted apart. Here the contract is stated once in the testkit and every
 * backend runs all of it through a {@link build.jenesis.repository.store.testkit.StoreFixture}: the filesystem inline
 * on a temporary directory, {@code s3} and {@code gcs} against one MinIO container (the GCS backend speaks the
 * S3-compatible XML surface), {@code azure-blob} against Azurite. The containerised fixtures self-skip without a
 * Docker daemon and <em>fail</em> under the strict lane's {@code -Djenreg.test.required}, where the environment is
 * declared complete and a skip would be a broken lane reported as green.
 *
 * <p>This module deliberately requires all four backend implementations and reaches them only through
 * {@code ArtifactStoreProvider.resolve} - the way a deployment does - so it is simultaneously the runtime-discovery
 * graph the census needs: a backend module omitted here disappears from {@code ServiceLoader}, and the census fails
 * because the source {@code provides} scan still declares it.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.store.testkit
 * @jenesis.bom pin-repository.properties
 */
open module build.jenesis.repository.store.contract.test {
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.store.testkit;
    requires build.jenesis.repository.contract.testkit;
    requires build.jenesis.repository.store.filesystem;
    requires build.jenesis.repository.store.s3;
    requires build.jenesis.repository.store.gcs;
    requires build.jenesis.repository.store.azure;
    requires org.junit.jupiter;
    requires org.assertj.core;

    // Discovery is the thing under test here, so this module loads the SPI itself rather than through a resolver
    // static: the census has to enumerate what ServiceLoader really sees in this graph and compare it against the
    // source `provides` scan. The same `uses`-in-a-test-module shape the importer census already uses in test/server.
    uses build.jenesis.repository.store.ArtifactStoreProvider;
}
