/**
 * Tests for the GCS artifact-store backend, in three legs that need no network and no Docker. The protocol leg drives
 * the real API client against a JSON-API stub on WireMock that stores objects with a monotonically increasing
 * generation: create-if-absent and update-if-unchanged through {@code ifGenerationMatch}, the token a delete and
 * re-create never re-issues, the generation read with the bytes, paging through {@code maxResults} and
 * {@code pageToken} (the one path Google's testbench cannot exercise, since it returns every match in one page), a
 * ranged read, a 429 the client's backoff retries, a missing bucket surfacing as an error rather than a lost
 * compare-and-set, and the plaintext-endpoint refusal. The presign leg builds a service-account key in the test and
 * verifies the V4 signature with its public half. The spool leg holds an upload in flight and reads the spool file's
 * permissions. The cross-backend {@code ArtifactStore} contract itself lives in the shared {@code StoreContract} kit
 * and runs against this backend from a downstream store-backends suite over Google's storage testbench.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.store.gcs
 * @jenesis.alias wiremock.core org.wiremock/wiremock-core
 * @jenesis.alias wiremock.jetty org.wiremock/wiremock-jetty
 * @jenesis.alias wiremock.httpclient org.wiremock/wiremock-httpclient-apache5
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.store.gcs.test {
    requires build.jenesis.repository.store.gcs;
    requires build.jenesis.repository.store;
    requires org.junit.jupiter;
    requires org.assertj.core;
    requires wiremock.core;
    requires wiremock.jetty;
    requires wiremock.httpclient;
}
