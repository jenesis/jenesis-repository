package build.jenesis.repository.web.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.management.web.AuditedCredentialContext;
import build.jenesis.repository.server.CredentialsController;
import build.jenesis.repository.server.FixedTenantRouting;
import build.jenesis.repository.server.PresentedKey;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.server.kernel.RepositoriesRoutingContext;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.servlet.testkit.Servlets;
import build.jenesis.repository.web.testkit.Web;
import jakarta.servlet.http.HttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The credential surface acts on the tenant the routing answers for the request, as every {@code /api} call does.
 * Under the fixed routing an operator key - one minted for the operator tenant, which is not the tenant served -
 * administers the tenant the deployment serves rather than its own, and the audit records that tenant against the
 * operator key's hash.
 */
class CredentialsControllerTest {

    private static final String SERVED = "acme", OPERATOR = "ops";

    @TempDir
    Path root;

    private Authorization authorization;
    private Web.Recording audit;
    private CredentialsController controller;

    @BeforeEach
    void wire() throws IOException {
        ArtifactStore store = Web.store(root);
        authorization = Authorization.enforcing(store);
        Repositories repositories = Web.repositories(store, authorization);
        audit = Web.audit();
        controller = new CredentialsController(authorization,
                new FixedTenantRouting(new RepositoriesRoutingContext(store, repositories, SERVED, _ -> null),
                        SERVED, OPERATOR),
                new AuditedCredentialContext(SERVED, audit));
    }

    @Test
    void an_operator_key_administers_the_served_tenant_and_the_audit_records_it() throws IOException {
        String operator = Authorization.mint(OPERATOR);
        Servlets.Response response = Servlets.response();

        CredentialsController.Minted minted = controller.mint(request(operator),
                new CredentialsController.MintRequest("ci", null, null), response.servlet());

        assertThat(response.status()).isEqualTo(201);
        assertThat(Authorization.tenantOf(minted.key())).as("the key is minted for the served tenant")
                .isEqualTo(SERVED);
        assertThat(authorization.credential(SERVED, minted.id())).as("and held in its credential space").isPresent();
        assertThat(authorization.credential(OPERATOR, minted.id())).as("never in the operator's own").isEmpty();
        assertThat(controller.credentials(request(operator), Servlets.response().servlet()))
                .extracting(CredentialsController.CredentialView::id).containsExactly(minted.id());
        assertThat(audit.rows()).singleElement().satisfies(row -> {
            assertThat(row.tenant()).as("the routed tenant, not the key's").isEqualTo(SERVED);
            assertThat(row.actor()).isEqualTo(Authorization.hash(operator));
            assertThat(row.action()).isEqualTo("credential.mint");
        });
    }

    private static HttpServletRequest request(String key) {
        return Servlets.request("POST", "/api/credentials", null, new byte[0], Map.of(PresentedKey.HEADER, key));
    }
}
