/**
 * Tests of the staging lifecycle in isolation: deploys are accepted only while open, closing seals the repository,
 * promotion re-points every staged item through the backend and is terminal, dropping discards, and every illegal
 * transition is rejected. No store, against a recording backend.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.staging
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.staging.test {
    requires build.jenesis.repository.staging;
    requires org.junit.jupiter;
    requires org.assertj.core;
}
