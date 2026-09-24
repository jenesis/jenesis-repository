package build.jenesis.repository.cache.protocol.jenesis.test;

import module java.base;

import build.jenesis.repository.cache.protocol.CacheProtocol;
import build.jenesis.repository.cache.protocol.jenesis.JenesisCacheProtocol;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JenesisCacheProtocolTest {

    private final CacheProtocol protocol = new JenesisCacheProtocol();

    @Test
    void claims_exactly_two_segments_under_the_prefix() {
        assertThat(protocol.handles("/compile/abc123")).isTrue();
        assertThat(protocol.handles("/compile/")).isFalse();
        assertThat(protocol.handles("//abc123")).isFalse();
        assertThat(protocol.handles("/compile")).isFalse();
        assertThat(protocol.handles("/repository/maven/x")).isFalse();
    }

    @Test
    void declines_every_reserved_segment_so_ownership_does_not_overlap() {
        // Clause 3, and the case that makes it a real rule rather than a hope: /cache/gradle/<key> has exactly
        // this protocol's shape, so without RESERVED both would claim it and dispatch would need a precedence
        // the contract does not have. Bazel and Maven are longer and would not collide anyway; they are declined
        // on the same rule rather than on their length, so adding a two-segment foreign layout cannot reopen it.
        assertThat(protocol.handles("/gradle/abc123")).isFalse();
        assertThat(protocol.handles("/maven/p/1/g/a/s/f")).isFalse();
        assertThat(protocol.handles("/bazel/ac/abc123")).isFalse();
        assertThat(protocol.handles("/bazel/cas/abc123")).isFalse();
        // A step that merely starts with a reserved word is still a step.
        assertThat(protocol.handles("/gradle-ish/abc123")).isTrue();
    }

    @Test
    void a_reserved_segment_is_not_an_address_either() {
        assertThat(protocol.address(request("/gradle/abc123", Map.of()))).isEmpty();
    }

    @Test
    void reads_the_address_and_both_headers() {
        assertThat(protocol.address(request("/compile/abc123",
                Map.of(CacheProtocol.PROJECT_HEADER, "checkout", CacheProtocol.KEY_HEADER, "jenk_x"))))
                .hasValueSatisfying(address -> {
                    assertThat(address.step()).isEqualTo("compile");
                    assertThat(address.inputs()).isEqualTo("abc123");
                    assertThat(address.project()).isEqualTo("checkout");
                    assertThat(address.key()).isEqualTo("jenk_x");
                    assertThat(address.existing()).isEqualTo(CacheProtocol.Existing.DEDUPE);
                });
    }

    @Test
    void an_unpresented_credential_is_a_complete_address_rather_than_an_empty_answer() {
        assertThat(protocol.address(request("/compile/abc123", Map.of())))
                .hasValueSatisfying(address -> {
                    assertThat(address.project()).isNull();
                    assertThat(address.key()).isNull();
                });
    }

    @Test
    void a_path_it_does_not_own_reads_as_empty() {
        assertThat(protocol.address(request("/bazel/ac/abc", Map.of()))).isEmpty();
    }

    @Test
    void the_address_deduplicates_because_it_is_a_digest_of_the_bytes() {
        assertThat(protocol.address(request("/compile/abc", Map.of())).orElseThrow().existing())
                .isEqualTo(CacheProtocol.Existing.DEDUPE);
    }

    @Test
    void discovery_finds_it_and_holds_the_answer() {
        // The assertion the SPI's own suite could not make: with a protocol on the path the list is non-empty,
        // so an identity check across two calls is a statement about caching rather than about List.of().
        assertThat(CacheProtocol.installed()).hasSize(1);
        assertThat(CacheProtocol.installed().getFirst().name()).isEqualTo("jenesis");
        assertThat(CacheProtocol.installed()).isSameAs(CacheProtocol.installed());
    }

    private static CacheProtocol.Request request(String path, Map<String, String> headers) {
        return new CacheProtocol.Request() {

            @Override
            public String path() {
                return path;
            }

            @Override
            public String method() {
                return "GET";
            }

            @Override
            public String header(String name) {
                return headers.get(name);
            }

            @Override
            public String project() {
                return null;
            }

            @Override
            public String presentedKey() {
                return null;
            }
        };
    }
}
