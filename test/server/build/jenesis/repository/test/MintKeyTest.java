package build.jenesis.repository.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.server.spi.MintKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The command-line mint: one well-formed key for the tenant named, {@code default} when none is, refused for a
 * tenant that is not a scope name. Exercised through {@code main} once, because the program is what a deployment
 * runs, and the rest through the method behind it.
 */
class MintKeyTest {

    @Test
    void main_prints_one_well_formed_key_for_the_tenant_named() {
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            MintKey.main(new String[] {"acme"});
        } finally {
            System.setOut(original);
        }
        String printed = captured.toString(StandardCharsets.UTF_8);
        assertThat(printed.strip()).doesNotContain("\n");
        assertThat(Authorization.wellFormed(printed.strip())).isTrue();
        assertThat(Authorization.tenantOf(printed.strip())).isEqualTo("acme");
    }

    @Test
    void the_tenant_defaults_to_default_and_every_key_is_fresh() {
        String first = MintKey.key();
        String second = MintKey.key();
        assertThat(Authorization.tenantOf(first)).isEqualTo("default");
        assertThat(Authorization.wellFormed(second)).isTrue();
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void a_tenant_that_is_not_a_scope_name_is_refused_before_a_key_is_minted() {
        assertThatThrownBy(() -> MintKey.key("no spaces")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MintKey.key(".system")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MintKey.key("a", "b")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("usage");
    }
}
