package build.jenesis.repository.cache.protocol.gradle.test;

import module java.base;

import build.jenesis.repository.cache.protocol.CacheProtocol;
import build.jenesis.repository.cache.protocol.gradle.GradleCacheProtocol;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GradleCacheProtocolTest {

    private final CacheProtocol protocol = new GradleCacheProtocol();

    @Test
    void claims_one_segment_under_its_own_prefix() {
        assertThat(protocol.handles("/gradle/abc123")).isTrue();
        assertThat(protocol.handles("/gradle/")).isFalse();
        assertThat(protocol.handles("/gradle/a/b")).isFalse();
        assertThat(protocol.handles("/compile/abc123")).isFalse();
    }

    @Test
    void its_name_is_the_segment_it_roots_at() {
        // What RESERVED rests on: the native protocol declines this segment by name, so the two claims are
        // disjoint only while the name and the prefix agree.
        assertThat(protocol.name()).isEqualTo("gradle");
        assertThat(CacheProtocol.RESERVED).contains(protocol.name());
        assertThat(protocol.handles("/" + protocol.name() + "/abc123")).isTrue();
    }

    @Test
    void a_client_pointed_at_the_endpoint_lands_on_a_path_this_claims() {
        // Gradle's url ends at the endpoint and the client appends the key.
        assertThat(protocol.endpoint()).isEqualTo("/gradle/");
        assertThat(protocol.handles(protocol.endpoint() + "abc123")).isTrue();
    }

    @Test
    void the_key_becomes_a_shard_and_a_whole_digest() {
        CacheProtocol.Address address = protocol.address(request("/gradle/abc123")).orElseThrow();
        // Not "the step appears once in the digest" - a four-character prefix may recur in sixty-four hex
        // characters, so that would pass or fail on the key rather than on the mapping.
        assertThat(address.inputs()).matches("[0-9a-f]{64}");
        assertThat(address.step()).isEqualTo(address.inputs().substring(0, 4));
        assertThat(address.existing()).isEqualTo(CacheProtocol.Existing.DEDUPE);
    }

    @Test
    void a_non_hex_key_still_maps_because_the_key_is_hashed() {
        // Gradle's keys are hex today, but that is Gradle's choice to change; hashing is what makes the mapping
        // total rather than refusing whatever a client puts on that path.
        assertThat(protocol.address(request("/gradle/not-hex-at-all!"))).isPresent();
    }

    @Test
    void distinct_keys_do_not_share_an_address() {
        CacheProtocol.Address one = protocol.address(request("/gradle/aaa")).orElseThrow();
        CacheProtocol.Address two = protocol.address(request("/gradle/bbb")).orElseThrow();
        assertThat(one.inputs()).isNotEqualTo(two.inputs());
    }

    @Test
    void the_identity_is_the_basic_pair_the_caller_derived() {
        CacheProtocol.Address address = protocol.address(new CacheProtocol.Request() {

            @Override
            public String path() {
                return "/gradle/abc123";
            }

            @Override
            public String method() {
                return "PUT";
            }

            @Override
            public String header(String name) {
                return null;   // Gradle names no header of its own; the pair rides in Basic
            }

            @Override
            public String project() {
                return "checkout";
            }

            @Override
            public String presentedKey() {
                return "jenk_x";
            }
        }).orElseThrow();

        assertThat(address.project()).isEqualTo("checkout");
        assertThat(address.key()).isEqualTo("jenk_x");
    }

    @Test
    void a_build_with_no_credentials_block_is_a_complete_address_with_nulls() {
        CacheProtocol.Address address = protocol.address(request("/gradle/abc123")).orElseThrow();
        assertThat(address.project()).isNull();
        assertThat(address.key()).isNull();
    }

    private static CacheProtocol.Request request(String path) {
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
                return null;
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
