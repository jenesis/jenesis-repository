package build.jenesis.repository.format.contract.ecosystem.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.format.testkit.ContractExchange;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.StoredListing;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A Hugging Face file is addressed through a revision, and the two kinds of revision promise opposite things: a
 * commit id names content, so a download pinned to one must get the same bytes for ever, while a branch names
 * whatever was pushed to it last. A push of other bytes is therefore refused at a commit and replaces the file on a
 * branch.
 */
class HuggingFaceRevisionTest {

    private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";

    @TempDir
    Path root;

    @AfterEach
    void settle() {
        StoredListing.settle();
    }

    @Test
    void other_bytes_at_a_commit_are_refused_and_the_first_still_serve() throws IOException {
        ArtifactStore store = store();
        String file = path(COMMIT);
        assertThat(put(store, file, "the weights").status()).isEqualTo(201);

        ContractExchange again = put(store, file, "other weights");

        assertThat(again.status()).isEqualTo(409);
        assertThat(again.responseText()).contains("already published");
        assertThat(get(store, file)).isEqualTo("the weights");
        assertThat(put(store, file, "the weights").status()).as("the same bytes again converge").isEqualTo(201);
    }

    @Test
    void other_bytes_on_a_branch_replace_the_file() throws IOException {
        ArtifactStore store = store();
        String file = path("main");
        assertThat(put(store, file, "the weights").status()).isEqualTo(201);

        assertThat(put(store, file, "other weights").status()).isEqualTo(201);

        assertThat(get(store, file)).isEqualTo("other weights");
    }

    private static String path(String revision) {
        return "/huggingface/hub/acme/model/resolve/" + revision + "/model.safetensors";
    }

    private ArtifactStore store() {
        return ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
    }

    private static ContractExchange put(ArtifactStore store, String path, String body) throws IOException {
        ContractExchange exchange = ContractExchange.of("PUT", path, body.getBytes(StandardCharsets.UTF_8));
        new HuggingFaceFormatFixture().serving().handle(exchange, store);
        return exchange;
    }

    private static String get(ArtifactStore store, String path) throws IOException {
        ContractExchange exchange = ContractExchange.of("GET", path);
        new HuggingFaceFormatFixture().serving().handle(exchange, store);
        assertThat(exchange.status()).as("%s serves", path).isEqualTo(200);
        return exchange.responseText();
    }
}
