package build.jenesis.repository.format.contract.ecosystem.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.testkit.ContractExchange;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.StoredListing;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A suite's {@code Packages.gz} is the {@code Packages} index compressed, served by streaming its stored twin rather
 * than by handing over a document read whole - the index names every package of the suite, so a read that held it
 * would hold the whole suite on every {@code apt update}. It follows the index as packages are pushed: the twin a
 * client reads after a push decompresses to the index that push wrote.
 */
class DebianPackagesGzTest {

    private static final int PACKAGES = 200;

    private static final String INDEX = "/debian/dists/trixie/main/binary-amd64/Packages";

    @TempDir
    Path root;

    @Test
    void packages_gz_is_streamed_and_decompresses_to_the_index_after_every_push() throws IOException {
        ArtifactStore store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null);
        for (int i = 0; i < PACKAGES; i++) {
            push(store, "tool-" + i);
        }
        assertServedAsTheIndexCompressed(store);

        push(store, "tool-late");
        assertServedAsTheIndexCompressed(store);
        assertThat(get(store, INDEX).responseText()).as("the late push reached the index")
                .contains("Package: tool-late");
    }

    private static void assertServedAsTheIndexCompressed(ArtifactStore store) throws IOException {
        ContractExchange index = get(store, INDEX);
        ContractExchange compressed = get(store, INDEX + ".gz");
        assertThat(compressed.status()).isEqualTo(200);
        assertThat(compressed.buffered()).as("Packages.gz is streamed from its stored twin, never handed over whole")
                .isFalse();
        try (InputStream gunzipped = new GZIPInputStream(new ByteArrayInputStream(compressed.responseBytes()))) {
            assertThat(gunzipped.readAllBytes()).as("and decompresses to the index the same suite serves")
                    .isEqualTo(index.responseBytes());
        }
    }

    private static void push(ArtifactStore store, String name) throws IOException {
        byte[] deb = Packages.deb(name, "1.0", "amd64", "", "control.tar.gz");
        ContractExchange put = ContractExchange.of("PUT", "/debian/trixie/pool/main/" + name + "_1.0_amd64.deb", deb);
        debian().handle(put, store);
        assertThat(put.status()).as("the push of %s", name).isEqualTo(201);
        StoredListing.settle();
    }

    private static ContractExchange get(ArtifactStore store, String path) throws IOException {
        ContractExchange exchange = ContractExchange.of("GET", path);
        debian().handle(exchange, store);
        assertThat(exchange.status()).as("GET %s", path).isEqualTo(200);
        return exchange;
    }

    private static RepositoryFormat debian() {
        return ServiceLoader.load(RepositoryFormat.class).stream().map(ServiceLoader.Provider::get)
                .filter(format -> format.name().equals("debian")).findFirst().orElseThrow();
    }
}
