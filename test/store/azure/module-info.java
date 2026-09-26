/**
 * Integration tests for what is <em>particular</em> to the Azure Blob artifact-store backend, run against an Azurite
 * emulator: an aborted upload that must abandon its {@code BlobOutputStream} unclosed so the staged block list is
 * never committed as a truncated blob, a container-level 404 that must surface as a transport error rather than a
 * silent compare-and-set conflict, a ranged read over a real {@code BlobRange}, a presigned SAS URL, owner-only upload
 * spooling, and a &gt;1000-key prefix that must drain every page of the SDK's {@code PagedIterable}. The cross-backend
 * {@code ArtifactStore} contract itself lives in the shared {@code StoreContract} kit and runs against this backend
 * from {@code test/store/contract}. The suite skips itself (JUnit assumptions) when no Docker daemon is reachable, so
 * a checkout without Docker still builds green.
 *
 * <p>Its own blob clients speak through the store's {@code AzureTransport}, as the store does, so the SDK's Netty
 * client is excluded here as well.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.store.azure
 * @jenesis.alias wiremock.core org.wiremock/wiremock-core
 * @jenesis.alias wiremock.jetty org.wiremock/wiremock-jetty
 * @jenesis.alias wiremock.httpclient org.wiremock/wiremock-httpclient-apache5
 * @jenesis.exclude com.azure.storage.blob com.azure/azure-core-http-netty io.netty/netty-codec-marshalling io.netty/netty-codec-protobuf
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.store.azure.test {
    requires build.jenesis.repository.store.azure;
    requires build.jenesis.repository.store;
    requires com.azure.storage.blob;
    requires org.junit.jupiter;
    requires org.assertj.core;

    requires wiremock.core;
    requires wiremock.jetty;
    requires wiremock.httpclient;
}
