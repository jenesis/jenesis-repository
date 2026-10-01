package build.jenesis.repository.cache.server.contract.test;

import module java.base;

import build.jenesis.repository.cache.protocol.CacheProtocol;
import build.jenesis.repository.cache.server.CacheProtocolsCapabilityContributor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code /api/capabilities} says about the build cache: every installed protocol by name, with the endpoint a
 * client of that tool is pointed at - and nothing at all where the cache is switched off, since its endpoint is then
 * not served.
 */
class CacheProtocolsCapabilityTest {

    private final CacheProtocolsCapabilityContributor contributor = new CacheProtocolsCapabilityContributor();

    @Test
    void every_installed_protocol_is_listed_with_the_endpoint_its_client_is_pointed_at() {
        assertThat(protocols(_ -> null)).containsExactly(
                Map.of("name", "bazel", "endpoint", "/build/<tenant>/bazel"),
                Map.of("name", "gradle", "endpoint", "/build/<tenant>/gradle/"),
                Map.of("name", "jenesis", "endpoint", "/build/<tenant>"),
                Map.of("name", "maven", "endpoint", "/build/<tenant>/maven/<project>"));
    }

    @Test
    void the_list_is_the_installed_set_rather_than_a_list_of_its_own() {
        assertThat(protocols(_ -> null)).extracting(protocol -> protocol.get("name"))
                .containsExactlyElementsOf(CacheProtocol.installed().stream().map(CacheProtocol::name).toList());
    }

    @Test
    void a_cache_that_is_switched_off_lists_nothing() {
        assertThat(protocols(key -> "build-cache".equals(key) ? "false" : null)).isEmpty();
        assertThat(protocols(key -> "build-cache".equals(key) ? "true" : null)).hasSize(4);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> protocols(UnaryOperator<String> configuration) {
        return (List<Map<String, String>>) contributor.capabilities(configuration)
                .get(CacheProtocolsCapabilityContributor.KEY);
    }
}
