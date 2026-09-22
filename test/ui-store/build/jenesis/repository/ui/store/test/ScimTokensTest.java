package build.jenesis.repository.ui.store.test;

import build.jenesis.repository.store.Documents;
import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.cache.storage.testkit.CacheStorages;
import build.jenesis.repository.ui.store.ScimTokens;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A tenant's SCIM token is stored only as a hash and matched constant-time; a blank value clears it.
 */
public class ScimTokensTest {

    @TempDir
    private Path root;

    @Test
    public void it_stores_a_hash_and_matches_only_the_right_token() throws IOException {
        ScimTokens tokens = new ScimTokens(CacheStorages.documents(root));
        assertThat(tokens.configured()).as("no token by default").isFalse();
        assertThat(tokens.matches("anything")).isFalse();

        tokens.set("scim_secret");
        assertThat(tokens.configured()).isTrue();
        assertThat(tokens.matches("scim_secret")).as("the right token matches").isTrue();
        assertThat(tokens.matches("wrong")).as("another token does not").isFalse();

        tokens.set(null);
        assertThat(tokens.configured()).as("a blank value clears it").isFalse();
    }
}
