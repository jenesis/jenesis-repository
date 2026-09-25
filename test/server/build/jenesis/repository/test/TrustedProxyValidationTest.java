package build.jenesis.repository.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.server.RepositoryAuthorizationManager;
import build.jenesis.repository.server.RepositoryProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A malformed {@code jenreg.trusted-proxies} entry must fail fast at construction (§9), naming the bad value,
 * rather than being silently dropped - a swallowed CIDR would leave a real reverse proxy treated as untrusted, quietly
 * ignoring {@code X-Forwarded-For} and defeating the source-IP allowlist. Well-formed values (IPv4/IPv6 addresses and
 * CIDRs, and the empty secure default) construct cleanly.
 */
public class TrustedProxyValidationTest {

    @Test
    public void a_malformed_cidr_fails_fast_naming_the_value() {
        RepositoryProperties properties = new RepositoryProperties();
        properties.setTrustedProxies("10.0.0.0/8, not-a-cidr");
        assertThatThrownBy(() -> new RepositoryAuthorizationManager(null, null, properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trusted-proxies")
                .hasMessageContaining("not-a-cidr");
    }

    @Test
    public void an_out_of_range_prefix_length_fails_fast() {
        RepositoryProperties properties = new RepositoryProperties();
        properties.setTrustedProxies("10.0.0.0/40");
        assertThatThrownBy(() -> new RepositoryAuthorizationManager(null, null, properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("10.0.0.0/40");
    }

    @Test
    public void well_formed_ipv4_ipv6_and_plain_addresses_construct() {
        RepositoryProperties properties = new RepositoryProperties();
        properties.setTrustedProxies("127.0.0.1/32, 10.0.0.0/8 , ::1/128, 2001:db8::/32, 192.168.1.1");
        assertThatCode(() -> new RepositoryAuthorizationManager(null, null, properties))
                .doesNotThrowAnyException();
    }

    @Test
    public void the_empty_default_trusts_no_proxy_and_constructs() {
        RepositoryProperties properties = new RepositoryProperties();
        assertThat(properties.getTrustedProxies()).isEmpty();
        assertThatCode(() -> new RepositoryAuthorizationManager(null, null, properties))
                .doesNotThrowAnyException();
    }
}
