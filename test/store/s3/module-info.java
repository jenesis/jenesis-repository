/**
 * Integration tests for what is <em>particular</em> to the S3 artifact-store backend, run against a MinIO
 * S3-compatible container: a bucket-level 404 that must surface as a transport error rather than a silent
 * compare-and-set conflict, a ranged read that must issue a real {@code Range} GET, an endpoint scheme screen, a
 * presigned direct-fetch URL, server-side encryption, owner-only upload spooling, and a &gt;1000-key prefix that must
 * cross {@code ListObjectsV2}'s own page boundary. The cross-backend {@code ArtifactStore} contract itself - blob
 * round-trips, content-addressed writes, enumeration, scoping, ordered paging, compare-and-set and per-entry batch
 * outcomes - lives in the shared {@code StoreContract} kit and runs against this backend from
 * {@code test/store/contract}. The suite skips itself (JUnit assumptions) when no Docker daemon is reachable, so a
 * checkout without Docker still builds green.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.store.s3
 * @jenesis.alias wiremock.core org.wiremock/wiremock-core
 * @jenesis.alias wiremock.jetty org.wiremock/wiremock-jetty
 * @jenesis.alias wiremock.httpclient org.wiremock/wiremock-httpclient-apache5
 * @jenesis.bom pin-repository.properties
 */
open module build.jenesis.repository.store.s3.test {
    requires build.jenesis.repository.store.s3;
    requires build.jenesis.repository.store;
    requires software.amazon.awssdk.services.s3;
    requires software.amazon.awssdk.regions;
    requires software.amazon.awssdk.auth;
    requires software.amazon.awssdk.http.urlconnection;
    requires org.junit.jupiter;
    requires org.assertj.core;

    requires wiremock.core;
    requires wiremock.jetty;
    requires wiremock.httpclient;
}
