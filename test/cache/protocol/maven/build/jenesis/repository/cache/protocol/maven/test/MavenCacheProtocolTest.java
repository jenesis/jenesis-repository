package build.jenesis.repository.cache.protocol.maven.test;

import module java.base;

import build.jenesis.repository.cache.protocol.CacheProtocol;
import build.jenesis.repository.cache.protocol.maven.MavenCacheProtocol;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MavenCacheProtocolTest {

    private static final String REAL = "/maven/checkout/v1.1/com.acme/widget/abc123/widget.jar";

    private final CacheProtocol protocol = new MavenCacheProtocol();

    @Test
    void claims_exactly_the_six_segments_the_extension_sends() {
        assertThat(protocol.handles(REAL)).isTrue();
        assertThat(protocol.handles("/maven/checkout/v1.1/com.acme/widget/abc123")).isFalse();
        assertThat(protocol.handles(REAL + "/extra")).isFalse();
        assertThat(protocol.handles("/maven//v1.1/com.acme/widget/abc123/widget.jar")).isFalse();
        assertThat(protocol.handles("/gradle/abc")).isFalse();
    }

    @Test
    void its_name_is_the_segment_it_roots_at() {
        assertThat(protocol.name()).isEqualTo("maven");
        assertThat(CacheProtocol.RESERVED).contains(protocol.name());
    }

    @Test
    void a_client_pointed_at_the_endpoint_lands_on_a_path_this_claims() {
        // The extension's <url> ends at the endpoint, its project filled in, and the extension appends the rest.
        assertThat(protocol.endpoint()).isEqualTo("/maven/<project>");
        assertThat(protocol.handles(protocol.endpoint().replace("<project>", "checkout")
                + "/v1.1/com.acme/app/0a1b2c/app.jar")).isTrue();
    }

    @Test
    void the_project_comes_from_the_path_and_the_credential_from_the_presentation() {
        // Maven's extension sends only its configured URL, so the project has nowhere but the path; the key is
        // the Basic password Maven Resolver already sends. The presented project is deliberately ignored.
        CacheProtocol.Address address = protocol.address(new CacheProtocol.Request() {

            @Override
            public String path() {
                return REAL;
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
                return "ignored";
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
    void the_module_is_the_step_so_one_artifact_groups_together() {
        CacheProtocol.Address jar = protocol.address(request(REAL)).orElseThrow();
        CacheProtocol.Address info = protocol.address(
                request("/maven/checkout/v1.1/com.acme/widget/abc123/build-info.xml")).orElseThrow();

        assertThat(jar.step()).isEqualTo(info.step());
        assertThat(jar.inputs()).isNotEqualTo(info.inputs());
    }

    @Test
    void a_later_layout_version_cannot_collide_with_entries_written_under_an_earlier_one() {
        CacheProtocol.Address one = protocol.address(request(REAL)).orElseThrow();
        CacheProtocol.Address two = protocol.address(
                request("/maven/checkout/v1.2/com.acme/widget/abc123/widget.jar")).orElseThrow();

        assertThat(one.step()).isEqualTo(two.step());
        assertThat(one.inputs()).isNotEqualTo(two.inputs());
    }

    @Test
    void a_coordinate_carrying_dots_still_maps_because_both_components_are_hashed() {
        CacheProtocol.Address address = protocol.address(request(REAL)).orElseThrow();
        assertThat(address.step()).matches("[0-9a-f]{64}");
        assertThat(address.inputs()).matches("[0-9a-f]{64}");
        assertThat(address.existing()).isEqualTo(CacheProtocol.Existing.DEDUPE);
    }

    @Test
    void a_path_it_does_not_own_reads_as_empty() {
        assertThat(protocol.address(request("/maven/too/few/segments"))).isEmpty();
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
