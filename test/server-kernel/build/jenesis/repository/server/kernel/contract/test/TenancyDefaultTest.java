package build.jenesis.repository.server.kernel.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.server.FixedTenantRouting;
import build.jenesis.repository.server.RepositoryProperties;
import build.jenesis.repository.server.RepositoryRoutingProvider;
import build.jenesis.repository.server.RoutingContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A deployment that names no tenancy serves one tenant: the properties the server binds say {@code fixed}, and the
 * routing that name selects is the fixed one. The value is written out here as a literal rather than read back from
 * its definition, so moving the definition is a red test rather than a quiet change to which tenants answer.
 */
class TenancyDefaultTest {

    @Test
    void the_properties_the_server_binds_default_to_fixed() {
        assertThat(new RepositoryProperties().getTenancy())
                .as("the routing a shipped composition runs on when jenreg.tenancy is not set")
                .isEqualTo("fixed");
    }

    @Test
    void the_default_selects_the_fixed_routing() {
        RoutingContext context = mock(RoutingContext.class);
        when(context.defaultTenant()).thenReturn("releases");

        assertThat(RepositoryRoutingProvider.resolve(new RepositoryProperties().getTenancy(), context))
                .as("what the code does with nothing set").isInstanceOf(FixedTenantRouting.class);
    }
}
