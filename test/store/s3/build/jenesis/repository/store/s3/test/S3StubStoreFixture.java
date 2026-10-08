package build.jenesis.repository.store.s3.test;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.testkit.StoreContract;
import build.jenesis.repository.store.testkit.StoreFixture;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;

/**
 * The S3 backend over {@link RestS3}, the stateful stub of the REST slice the backend speaks: the real SDK client,
 * through the provider an operator selects - its boot-time conditional-write probe included - against objects with
 * entity tags, conditional puts, ranged gets and paged listings, in process. The contract holds this backend in every
 * lane rather than only where a container runs MinIO.
 */
final class S3StubStoreFixture implements StoreFixture {

    private WireMockServer server;
    private ArtifactStore store;

    @Override
    public String backend() {
        return "s3";
    }

    @Override
    public String providerClass() {
        return "build.jenesis.repository.store.s3.S3ArtifactStoreProvider";
    }

    @Override
    public void start() {
        server = new WireMockServer(WireMockConfiguration.options().bindAddress("localhost").dynamicPort()
                .extensions(new RestS3()));
        server.start();
        server.stubFor(any(anyUrl()).willReturn(aResponse()));
        store = ArtifactStoreProvider.resolve("s3", RestS3.settings(server.port(), "contract")::get)
                .scope("kit" + Long.toHexString(ThreadLocalRandom.current().nextLong() >>> 1));
    }

    @Override
    public ArtifactStore store() {
        return store;
    }

    @Override
    public Optional<Plaintext> plaintext() {
        return Optional.of(new Plaintext(RestS3.settings(server.port(), "contract"),
                "jenrepo.s3.allow-insecure-endpoint"));
    }

    @Override
    public Map<StoreContract.Property, String> unsupported() {
        return Map.of(StoreContract.Property.PRESIGNED_GET_FETCHES_THE_BYTES,
                "the stub verifies no query-string signature, so a presigned GET reaching it would prove only that a "
                        + "URL was minted; the property is proven against MinIO by the container fixture");
    }

    @Override
    public void close() {
        if (server != null) {
            server.stop();
        }
    }
}
