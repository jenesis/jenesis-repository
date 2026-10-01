package build.jenesis.repository.cache.protocol.bazel.test;

import module java.base;

import build.jenesis.repository.cache.protocol.CacheProtocol;
import build.jenesis.repository.cache.protocol.bazel.BazelCacheProtocol;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BazelCacheProtocolTest {

    private final CacheProtocol protocol = new BazelCacheProtocol();

    @Test
    void claims_both_namespaces_and_nothing_else() {
        assertThat(protocol.handles("/bazel/ac/abc")).isTrue();
        assertThat(protocol.handles("/bazel/cas/abc")).isTrue();
        assertThat(protocol.handles("/bazel/other/abc")).isFalse();
        assertThat(protocol.handles("/bazel/ac/")).isFalse();
        assertThat(protocol.handles("/bazel/ac/a/b")).isFalse();
        assertThat(protocol.handles("/bazel/abc")).isFalse();
    }

    @Test
    void its_name_is_the_segment_it_roots_at() {
        assertThat(protocol.name()).isEqualTo("bazel");
        assertThat(CacheProtocol.RESERVED).contains(protocol.name());
    }

    @Test
    void a_client_pointed_at_the_endpoint_lands_on_a_path_this_claims() {
        // --remote_cache ends at the endpoint and the client appends the namespace and the hash.
        assertThat(protocol.endpoint()).isEqualTo("/bazel");
        assertThat(protocol.handles(protocol.endpoint() + "/ac/abc123")).isTrue();
        assertThat(protocol.handles(protocol.endpoint() + "/cas/abc123")).isTrue();
    }

    @Test
    void an_action_result_replaces_and_content_deduplicates() {
        // The whole of the split: an action result is addressed by the action, so a re-run producing different
        // output must replace it; a CAS entry cannot differ from itself, so a second upload is waste.
        assertThat(protocol.address(request("/bazel/ac/abc")).orElseThrow().existing())
                .isEqualTo(CacheProtocol.Existing.REWRITE);
        assertThat(protocol.address(request("/bazel/cas/abc")).orElseThrow().existing())
                .isEqualTo(CacheProtocol.Existing.DEDUPE);
    }

    @Test
    void one_hash_in_both_namespaces_is_two_entries() {
        // The namespace is part of the key, not just of the route: the two spaces are digests of different
        // things, so the same hash value can legitimately appear in both meaning unrelated entries.
        CacheProtocol.Address action = protocol.address(request("/bazel/ac/same")).orElseThrow();
        CacheProtocol.Address content = protocol.address(request("/bazel/cas/same")).orElseThrow();
        assertThat(action.inputs()).isNotEqualTo(content.inputs());
    }

    @Test
    void the_address_is_a_shard_of_its_own_digest() {
        CacheProtocol.Address address = protocol.address(request("/bazel/cas/abc")).orElseThrow();
        assertThat(address.inputs()).matches("[0-9a-f]{64}");
        assertThat(address.step()).isEqualTo(address.inputs().substring(0, 4));
    }

    @Test
    void a_path_it_does_not_own_reads_as_empty() {
        assertThat(protocol.address(request("/bazel/other/abc"))).isEmpty();
        assertThat(protocol.address(request("/gradle/abc"))).isEmpty();
    }

    @Test
    void the_identity_is_whatever_the_caller_derived() {
        CacheProtocol.Address address = protocol.address(new CacheProtocol.Request() {

            @Override
            public String path() {
                return "/bazel/cas/abc";
            }

            @Override
            public String method() {
                return "PUT";
            }

            @Override
            public String header(String name) {
                return null;
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
