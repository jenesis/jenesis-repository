package build.jenesis.repository.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.server.FixedTenantRouting;
import build.jenesis.repository.server.PresentedKey;
import build.jenesis.repository.server.RoutingContext;
import build.jenesis.repository.server.spi.AccessDenial;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.store.Features;
import jakarta.servlet.http.HttpServletRequest;
import org.mockito.Mockito;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The fixed routing serves one tenant and refuses a key minted for any tenant but that one and the operator's, on an
 * addressed request and on one naming its repository in a parameter alike, and a URL naming any other tenant - each
 * refusal answered as the deployment's {@link AccessDenial} says, under both of its values. Authorization matches a
 * key's grants to a repository by name in the key's own tenant, so a key of another tenant holding a grant on a
 * repository of the same name would otherwise reach the served tenant's.
 */
class FixedTenantKeyTest {

    private static final String SERVED = "acme";

    private static final String OPERATOR = "ops";

    private final FixedTenantRouting routing = new FixedTenantRouting(mock(RoutingContext.class), SERVED, OPERATOR);

    @AfterEach
    void restore() {
        Features.reset();
    }

    @Test
    void a_key_of_another_tenant_is_refused_on_both_kinds_of_request() {
        HttpServletRequest request = request(Authorization.mint("globex"));

        for (AccessDenial denial : AccessDenial.values()) {
            Features.configure(Map.of("jenrepo." + AccessDenial.KEY, denial.value())::get);
            assertThatThrownBy(() -> routing.route(request)).as(denial.value())
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            refused -> assertThat(refused.getStatusCode().value()).isEqualTo(denial.status()));
            assertThatThrownBy(() -> routing.tenant(request)).as(denial.value())
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            refused -> assertThat(refused.getStatusCode().value()).isEqualTo(denial.status()));
        }
    }

    @Test
    void a_url_naming_another_tenant_is_refused_alike_whoever_asks() {
        // The served tenant's own key, and no key: the URL names a tenant this deployment does not answer, which is
        // refused as a key of another tenant is, since nothing about the answer may depend on that tenant existing.
        for (AccessDenial denial : AccessDenial.values()) {
            Features.configure(Map.of("jenrepo." + AccessDenial.KEY, denial.value())::get);
            for (String key : Arrays.asList(Authorization.mint(SERVED), null)) {
                HttpServletRequest request = request(key);
                when(request.getRequestURI()).thenReturn("/repository/globex/releases/maven/a/b/1.0/b-1.0.pom");
                // The context's own confinement, over nothing: a refused request must not reach the store.
                FixedTenantRouting confined = new FixedTenantRouting(
                        mock(RoutingContext.class, Mockito.CALLS_REAL_METHODS), SERVED, OPERATOR);
                assertThatThrownBy(() -> confined.route(request)).as("%s, key %s", denial.value(), key)
                        .isInstanceOfSatisfying(ResponseStatusException.class,
                                refused -> assertThat(refused.getStatusCode().value()).isEqualTo(denial.status()));
            }
        }
    }

    /** The operator administers the deployment, which under this routing is the one tenant it serves. */
    @Test
    void a_key_of_the_tenant_served_of_the_operator_and_a_keyless_request_answer_for_it() {
        assertThat(routing.tenant(request(Authorization.mint(SERVED)))).isEqualTo(SERVED);
        assertThat(routing.tenant(request(Authorization.mint(OPERATOR)))).isEqualTo(SERVED);
        assertThat(routing.tenant(request(null))).isEqualTo(SERVED);
    }

    private static HttpServletRequest request(String key) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader(PresentedKey.HEADER)).thenReturn(key);
        when(request.getRequestURI()).thenReturn("/repository/" + SERVED + "/releases/maven/a/b/1.0/b-1.0.pom");
        return request;
    }
}
