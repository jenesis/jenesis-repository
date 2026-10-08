package build.jenesis.repository.store.azure.test;

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
 * The Azure Blob backend over {@link RestAzure}, the stateful stub of the Blob service slice the backend speaks: the
 * real client, through the provider an operator selects - its boot-time conditional-write probe included - against
 * blobs with entity tags, conditional commits, staged blocks, ranged downloads and paged listings, in process. The
 * contract holds this backend in every lane rather than only where a container runs Azurite.
 */
final class AzureStubStoreFixture implements StoreFixture {

    private WireMockServer server;
    private ArtifactStore store;

    @Override
    public String backend() {
        return "azure-blob";
    }

    @Override
    public String providerClass() {
        return "build.jenesis.repository.store.azure.AzureArtifactStoreProvider";
    }

    @Override
    public void start() {
        server = new WireMockServer(WireMockConfiguration.options().bindAddress("localhost").dynamicPort()
                .extensions(new RestAzure()));
        server.start();
        server.stubFor(any(anyUrl()).willReturn(aResponse()));
        store = ArtifactStoreProvider.resolve("azure-blob", RestAzure.settings(server.port(), "contract")::get)
                .scope("kit" + Long.toHexString(ThreadLocalRandom.current().nextLong() >>> 1));
    }

    @Override
    public ArtifactStore store() {
        return store;
    }

    @Override
    public Optional<Plaintext> plaintext() {
        return Optional.of(new Plaintext(RestAzure.settings(server.port(), "contract"),
                "jenrepo.azure-blob.allow-insecure-endpoint"));
    }

    @Override
    public Map<StoreContract.Property, String> unsupported() {
        return Map.of(StoreContract.Property.PRESIGNED_GET_FETCHES_THE_BYTES,
                "the stub verifies no shared-access signature, so a presigned GET reaching it would prove only that a "
                        + "URL was minted; the property is proven against Azurite by the container fixture");
    }

    @Override
    public void close() {
        if (server != null) {
            server.stop();
        }
    }
}
