package build.jenesis.repository.cache.protocol.test;

import module java.base;

import build.jenesis.repository.cache.protocol.CacheProtocol;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The cache-protocol contract, driven by calling it - which is the point of a protocol that reaches no servlet
 * and no cache: the whole of what an implementation promises is decidable from a path and a few headers.
 */
class CacheProtocolContractTest {

    /** A protocol of the native shape: {@code /cache/<step>/<inputs>}, project and credential in headers. */
    private static final CacheProtocol NATIVE = new CacheProtocol() {

        @Override
        public String name() {
            return "jenesis";
        }

        @Override
        public boolean handles(String path) {
            return path.startsWith("/cache/") && path.chars().filter(c -> c == '/').count() == 3;
        }

        @Override
        public Optional<Address> address(Request request) {
            String[] segments = request.path().split("/");
            if (segments.length != 4 || segments[2].isEmpty() || segments[3].isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new Address(segments[2], segments[3],
                    request.header("Jenesis-Cache-Project"), request.header("Jenesis-Repository-Key"),
                    Existing.DEDUPE));
        }
    };

    @Test
    void reads_an_address_off_a_path_it_owns() {
        Optional<CacheProtocol.Address> address = NATIVE.address(request("/cache/compile/abc123", Map.of(
                "Jenesis-Cache-Project", "checkout", "Jenesis-Repository-Key", "jenk_x")));

        assertThat(address).hasValueSatisfying(value -> {
            assertThat(value.step()).isEqualTo("compile");
            assertThat(value.inputs()).isEqualTo("abc123");
            assertThat(value.project()).isEqualTo("checkout");
            assertThat(value.key()).isEqualTo("jenk_x");
            assertThat(value.existing()).isEqualTo(CacheProtocol.Existing.DEDUPE);
        });
    }

    @Test
    void a_path_it_cannot_read_is_an_empty_answer_rather_than_a_throw() {
        // Clause 4: a client's bad request is an answer. A protocol that threw here would turn a malformed path
        // into a 500 on a path it had already claimed.
        assertThat(NATIVE.address(request("/cache//abc123", Map.of()))).isEmpty();
    }

    @Test
    void an_unpresented_project_or_credential_is_a_complete_address_with_nulls() {
        // Clause 5: "I could not read this request" and "this request presented no credential" are different
        // answers, and the caller refuses them differently - a 400 against a 401.
        assertThat(NATIVE.address(request("/cache/compile/abc123", Map.of())))
                .hasValueSatisfying(value -> {
                    assertThat(value.project()).isNull();
                    assertThat(value.key()).isNull();
                    assertThat(value.step()).isEqualTo("compile");
                });
    }

    @Test
    void handles_decides_on_the_path_alone() {
        assertThat(NATIVE.handles("/cache/compile/abc123")).isTrue();
        assertThat(NATIVE.handles("/cache/gradle/abc123/extra")).isFalse();
        assertThat(NATIVE.handles("/repository/maven/x")).isFalse();
    }

    @Test
    void an_address_without_a_step_or_a_write_policy_is_unrepresentable() {
        assertThatThrownBy(() -> new CacheProtocol.Address(null, "abc", null, null,
                CacheProtocol.Existing.DEDUPE)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CacheProtocol.Address("compile", "abc", null, null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void discovery_answers_empty_when_nothing_provides_one() {
        // Nothing provides the service in this module's graph, so a node carrying no protocol serves no cache
        // rather than failing to start.
        //
        // That the answer is HELD is deliberately not asserted here: with an empty result the identity check
        // passes whether or not anything caches it, so it would be an assertion that cannot fail. It is proven
        // by the first protocol module, whose fixture can count the discoveries.
        assertThat(CacheProtocol.installed()).isEmpty();
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
