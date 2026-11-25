package build.jenesis.repository.cache.protocol.jenesis.test;

import module java.base;

import build.jenesis.repository.cache.protocol.CacheProtocol;
import build.jenesis.repository.cache.protocol.jenesis.JenesisCacheProtocol;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JenesisCacheProtocolTest {

    private final CacheProtocol protocol = new JenesisCacheProtocol();

    @Test
    void claims_a_step_of_exactly_two_segments_and_the_batch_of_files() {
        assertThat(protocol.handles("/step/compile/abc123")).isTrue();
        assertThat(protocol.handles("/blobs")).isTrue();
        assertThat(protocol.handles("/step/compile/")).isFalse();
        assertThat(protocol.handles("/step//abc123")).isFalse();
        assertThat(protocol.handles("/step/compile")).isFalse();
        assertThat(protocol.handles("/step/compile/abc123/more")).isFalse();
        assertThat(protocol.handles("/blobs/abc123")).isFalse();
        assertThat(protocol.handles("/compile/abc123")).as("a step's result is not one entry at /<step>/<inputs>").isFalse();
        assertThat(protocol.handles("/repository/maven/x")).isFalse();
    }

    @Test
    void claims_no_path_under_a_reserved_segment_so_ownership_does_not_overlap() {
        // Clause 3: neither of this protocol's roots is a foreign tool's, and neither shape has the two segments of
        // Gradle's /gradle/<key>, so no path has two claimants and dispatch needs no precedence.
        assertThat(CacheProtocol.RESERVED).doesNotContain("step", "blobs");
        assertThat(protocol.handles("/gradle/abc123")).isFalse();
        assertThat(protocol.handles("/maven/p/1/g/a/s/f")).isFalse();
        assertThat(protocol.handles("/bazel/ac/abc123")).isFalse();
        assertThat(protocol.handles("/bazel/cas/abc123")).isFalse();
    }

    @Test
    void a_path_it_does_not_own_is_not_an_address_either() {
        assertThat(protocol.address(request("/gradle/abc123", Map.of()))).isEmpty();
        assertThat(protocol.address(request("/bazel/ac/abc", Map.of()))).isEmpty();
    }

    @Test
    void a_client_pointed_at_the_endpoint_lands_on_a_path_this_claims() {
        // A build tool's cache.uri is the tenant's cache itself, and the tool appends the address.
        assertThat(protocol.endpoint()).isEmpty();
        assertThat(protocol.handles(protocol.endpoint() + "/step/compile/abc123")).isTrue();
        assertThat(protocol.handles(protocol.endpoint() + "/blobs")).isTrue();
    }

    @Test
    void a_step_is_a_manifest_and_the_batch_is_content() {
        assertThat(protocol.shape("/step/compile/abc123")).isEqualTo(CacheProtocol.Shape.MANIFEST);
        assertThat(protocol.shape("/blobs")).isEqualTo(CacheProtocol.Shape.CONTENT);
    }

    @Test
    void reads_the_address_of_a_step_and_both_headers() {
        assertThat(protocol.address(request("/step/compile/abc123",
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
    void the_batch_carries_the_project_and_the_credential_under_a_placeholder_address() {
        assertThat(protocol.address(request("/blobs",
                Map.of(CacheProtocol.PROJECT_HEADER, "checkout", CacheProtocol.KEY_HEADER, "jenk_x"))))
                .hasValueSatisfying(address -> {
                    assertThat(address.project()).isEqualTo("checkout");
                    assertThat(address.key()).isEqualTo("jenk_x");
                    assertThat(address.step()).as("a valid segment, so that the request is authorised as any other").isEqualTo("00");
                    assertThat(address.inputs()).isEqualTo("00");
                });
    }

    @Test
    void an_unpresented_credential_is_a_complete_address_rather_than_an_empty_answer() {
        assertThat(protocol.address(request("/step/compile/abc123", Map.of())))
                .hasValueSatisfying(address -> {
                    assertThat(address.project()).isNull();
                    assertThat(address.key()).isNull();
                });
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
