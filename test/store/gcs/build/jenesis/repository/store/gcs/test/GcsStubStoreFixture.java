package build.jenesis.repository.store.gcs.test;

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
 * The Cloud Storage backend over {@link JsonGcs}, the stateful stub of the JSON API slice the backend speaks: the real
 * API client, through the provider an operator selects, against objects with generations, conditional uploads, ranged
 * downloads and paged listings - in process, so the shared contract holds this backend in every lane rather than only
 * where a container runs Google's storage testbench.
 */
final class GcsStubStoreFixture implements StoreFixture {

    private WireMockServer server;
    private Map<String, String> settings;
    private ArtifactStore store;

    @Override
    public String backend() {
        return "gcs";
    }

    @Override
    public String providerClass() {
        return "build.jenesis.repository.store.gcs.GcsArtifactStoreProvider";
    }

    @Override
    public void start() {
        server = new WireMockServer(WireMockConfiguration.options().bindAddress("localhost").dynamicPort()
                .extensions(new JsonGcs()));
        server.start();
        server.stubFor(any(anyUrl()).willReturn(aResponse()));
        settings = JsonGcs.settings(server.port(), "contract");
        store = ArtifactStoreProvider.resolve("gcs", settings::get)
                .scope("kit" + Long.toHexString(ThreadLocalRandom.current().nextLong() >>> 1));
    }

    @Override
    public ArtifactStore store() {
        return store;
    }

    @Override
    public Optional<Plaintext> plaintext() {
        return Optional.of(new Plaintext(JsonGcs.settings(server.port(), "contract"),
                "jenrepo.gcs.allow-insecure-endpoint"));
    }

    @Override
    public Map<StoreContract.Property, String> unsupported() {
        return Map.of(StoreContract.Property.PRESIGNED_GET_FETCHES_THE_BYTES,
                "the stub answers the JSON API and no XML-API signed URL, so a presigned GET has nothing to reach; "
                        + "the property is proven against Google's storage testbench by the container fixture");
    }

    @Override
    public void close() {
        if (server != null) {
            server.stop();
        }
    }
}
