package build.jenesis.repository.web.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.management.web.AuditedCredentialContext;
import build.jenesis.repository.server.FixedTenantRouting;
import build.jenesis.repository.server.PresentedKey;
import build.jenesis.repository.server.ResourceTenants;
import build.jenesis.repository.server.RoutedTenantResource;
import build.jenesis.repository.server.TenantResource;
import build.jenesis.repository.server.TokenController;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.server.kernel.RepositoriesRoutingContext;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.server.spi.TokenExchange;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.servlet.testkit.Servlets;
import build.jenesis.repository.web.testkit.Web;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The token endpoint speaks OAuth 2.0 Token Exchange (RFC 8693): what a request must carry, what each parameter the
 * RFC defines earns, and the answer in both directions. The exchange behind it is a stand-in that admits one token
 * and mints a real key for the tenant it is asked about, so the suite also holds that the issued key is a
 * credential the deployment accepts as a bearer.
 */
class TokenControllerTest {

    private static final String SERVED = "acme", HOST = "https://repository.example.com", TOKEN = "header.payload.signature",
            GRANT = "urn:ietf:params:oauth:grant-type:token-exchange",
            ID_TOKEN = "urn:ietf:params:oauth:token-type:id_token",
            JWT = "urn:ietf:params:oauth:token-type:jwt",
            ACCESS_TOKEN = "urn:ietf:params:oauth:token-type:access_token";

    @TempDir
    Path root;

    private Authorization authorization;
    private Web.Recording audit;
    private List<String> asked;
    private ResourceTenants resources;
    private TokenExchange exchange;
    private TokenController controller;

    @BeforeEach
    void wire() throws IOException {
        ArtifactStore store = Web.store(root);
        authorization = Authorization.enforcing(store);
        Repositories repositories = Web.repositories(store, authorization);
        // The repositories' URLs as the fixed routing reads them, and a stand-in for the cache's, which names the
        // tenant in the segment after its root.
        TenantResource cache = (_, path) -> path.startsWith("/build/")
                ? Optional.of(path.split("/")[2])
                : Optional.empty();
        resources = new ResourceTenants(List.of(new RoutedTenantResource(new FixedTenantRouting(
                new RepositoriesRoutingContext(store, repositories, SERVED, _ -> null), SERVED, "ops")), cache),
                key -> key.equals("public-url") ? HOST : null);
        audit = Web.audit();
        asked = new ArrayList<>();
        exchange = (tenant, token) -> {
            asked.add(tenant);
            if (!TOKEN.equals(token)) {
                return null;
            }
            String minted = Authorization.mint(tenant);
            Instant expires = Instant.now().plus(Duration.ofMinutes(30));
            authorization.provision(tenant, Authorization.hash(minted), "oidc:ci", expires);
            authorization.setGrant(tenant, Authorization.hash(minted), "*", "repository:read");
            return new TokenExchange.Exchanged(minted, expires, "ci");
        };
        controller = new TokenController(exchange, new AuditedCredentialContext(SERVED, audit), resources);
    }

    @Test
    void an_id_token_is_exchanged_for_a_key_the_deployment_accepts_as_a_bearer() throws IOException {
        ResponseEntity<Map<String, Object>> response = post(form());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsOnlyKeys("access_token", "issued_token_type", "token_type",
                        "expires_in")
                .containsEntry("issued_token_type", ACCESS_TOKEN)
                .containsEntry("token_type", "Bearer");
        assertThat((Long) response.getBody().get("expires_in")).as("the key's lifetime in seconds")
                .isBetween(1700L, 1800L);
        String key = (String) response.getBody().get("access_token");
        assertThat(Authorization.tenantOf(key)).as("a request naming no tenant gets the deployment's own")
                .isEqualTo(SERVED);
        String presented = PresentedKey.from(Servlets.request("GET", "/api/credentials", null, new byte[0],
                Map.of("Authorization", "Bearer " + key)));
        assertThat(presented).as("the issued key is read from a bearer header").isEqualTo(key);
        assertThat(authorization.authenticated(presented)).isEqualTo(Authorization.Decision.ALLOWED);
        assertThat(authorization.authorize(presented, "releases", "repository:read"))
                .isEqualTo(Authorization.Decision.ALLOWED);
        assertThat(audit.rows()).singleElement().satisfies(row -> {
            assertThat(row.tenant()).isEqualTo(SERVED);
            assertThat(row.actor()).isEqualTo(Authorization.hash(key));
            assertThat(row.action()).isEqualTo("credential.exchange");
            assertThat(row.target()).isEqualTo("oidc:ci");
        });
    }

    @Test
    void every_answer_forbids_caching() throws IOException {
        MultiValueMap<String, String> refused = form();
        refused.remove("subject_token");
        TokenController absent = new TokenController(TokenExchange.NONE, new AuditedCredentialContext(SERVED, audit),
                resources);

        assertThat(List.of(post(form()), post(refused), absent.token(form(), request("/api/token"))))
                .allSatisfy(response -> {
                    assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
                    assertThat(response.getHeaders().getPragma()).isEqualTo("no-cache");
                    assertThat(response.getHeaders().getContentType().toString()).isEqualTo("application/json");
                });
    }

    @Test
    void a_jwt_subject_token_type_and_an_access_token_request_are_accepted() throws IOException {
        MultiValueMap<String, String> form = form();
        form.set("subject_token_type", JWT);
        form.set("requested_token_type", ACCESS_TOKEN);

        assertThat(post(form).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void the_audience_names_the_tenant_as_the_tenant_parameter_does() throws IOException {
        MultiValueMap<String, String> byAudience = form();
        byAudience.set("audience", "other");
        MultiValueMap<String, String> byParameter = form();
        byParameter.set("tenant", "third");
        MultiValueMap<String, String> agreeing = form();
        agreeing.set("audience", "other");
        agreeing.set("tenant", "other");

        assertThat(Authorization.tenantOf((String) post(byAudience).getBody().get("access_token")))
                .isEqualTo("other");
        assertThat(Authorization.tenantOf((String) post(byParameter).getBody().get("access_token")))
                .isEqualTo("third");
        // The container hands a handler the query string's parameters and the body's as one map.
        MultiValueMap<String, String> byQuery = form();
        byQuery.set("tenant", "fourth");
        assertThat(Authorization.tenantOf((String) controller.token(byQuery, request("/api/token?tenant=fourth"))
                .getBody().get("access_token"))).as("the tenant may travel in the query string").isEqualTo("fourth");
        assertThat(post(agreeing).getStatusCode().value()).isEqualTo(200);
        assertThat(asked).containsExactly("other", "third", "fourth", "other");
    }

    @Test
    void a_tenant_and_an_audience_that_disagree_are_an_invalid_target() throws IOException {
        MultiValueMap<String, String> form = form();
        form.set("audience", "other");
        form.set("tenant", "third");

        assertRefused(post(form), 400, "invalid_target");
        assertThat(asked).isEmpty();
    }

    @Test
    void a_repository_url_of_this_server_as_the_resource_names_the_tenant_the_routing_serves_it_for()
            throws IOException {
        for (String resource : List.of(HOST + "/repository/acme/releases/", HOST + "/repository/acme/releases",
                HOST + "/repository/acme/releases/org/example/lib/1.0/lib-1.0.jar?x=y",
                "HTTPS://Repository.Example.com:443/repository/acme/releases/", HOST + "/v2/acme/images/app/tags/list",
                HOST + "/v2/")) {
            MultiValueMap<String, String> form = form();
            form.set("resource", resource);

            ResponseEntity<Map<String, Object>> response = post(form);

            assertThat(response.getStatusCode().value()).as(resource).isEqualTo(200);
            assertThat(Authorization.tenantOf((String) response.getBody().get("access_token"))).isEqualTo(SERVED);
        }
    }

    @Test
    void a_cache_url_of_this_server_as_the_resource_names_its_tenant() throws IOException {
        MultiValueMap<String, String> form = form();
        form.set("resource", HOST + "/build/other/");

        assertThat(Authorization.tenantOf((String) post(form).getBody().get("access_token"))).isEqualTo("other");
        assertThat(asked).containsExactly("other");
    }

    @Test
    void a_resource_of_another_host_scheme_or_port_is_an_invalid_target() throws IOException {
        for (String resource : List.of("https://elsewhere.example.com/repository/acme/releases/",
                "http://repository.example.com/repository/acme/releases/",
                "https://repository.example.com:8443/repository/acme/releases/",
                "https://repository.example.com.evil.example/repository/acme/releases/",
                "https://user@elsewhere.example.com/repository/acme/releases/",
                "ftp://repository.example.com/repository/acme/releases/")) {
            MultiValueMap<String, String> form = form();
            form.set("resource", resource);

            assertRefused(post(form), 400, "invalid_target");
        }
        assertThat(asked).isEmpty();
    }

    @Test
    void a_resource_whose_path_names_no_tenant_the_deployment_serves_is_an_invalid_target() throws IOException {
        for (String resource : List.of(HOST, HOST + "/", HOST + "/api/token", HOST + "/ui/", HOST + "/repository",
                HOST + "/repository/other/releases/", HOST + "/build/../repository/other/releases/",
                HOST + "/repository/..%2Fother/releases/", HOST + "/staging/other/releases/1/")) {
            MultiValueMap<String, String> form = form();
            form.set("resource", resource);

            assertRefused(post(form), 400, "invalid_target");
        }
        assertThat(asked).isEmpty();
    }

    @Test
    void a_resource_that_is_not_an_absolute_uri_or_carries_a_fragment_is_an_invalid_target() throws IOException {
        for (String resource : List.of("/repository/acme/releases/", "//repository.example.com/repository/acme/x/",
                "acme", HOST + "/repository/acme/releases/#top", "https://repository.example.com/repository/a b/",
                "mailto:acme@example.com")) {
            MultiValueMap<String, String> form = form();
            form.set("resource", resource);

            assertRefused(post(form), 400, "invalid_target");
        }
        assertThat(asked).isEmpty();
    }

    @Test
    void targets_may_be_combined_while_they_name_one_tenant() throws IOException {
        MultiValueMap<String, String> agreeing = form();
        agreeing.add("resource", HOST + "/repository/acme/releases/");
        agreeing.add("resource", HOST + "/build/acme/");
        agreeing.set("audience", SERVED);
        agreeing.set("tenant", SERVED);
        MultiValueMap<String, String> differing = form();
        differing.add("resource", HOST + "/repository/acme/releases/");
        differing.add("resource", HOST + "/build/other/");
        MultiValueMap<String, String> against = form();
        against.set("resource", HOST + "/build/other/");
        against.set("audience", SERVED);

        assertThat(post(agreeing).getStatusCode().value()).isEqualTo(200);
        assertRefused(post(differing), 400, "invalid_target");
        assertRefused(post(against), 400, "invalid_target");
        assertThat(asked).containsExactly(SERVED);
    }

    @Test
    void without_a_public_url_a_resource_is_held_to_the_host_the_client_asked_for() throws IOException {
        TokenController direct = new TokenController(exchange, new AuditedCredentialContext(SERVED, audit),
                new ResourceTenants(List.of((_, path) -> path.startsWith("/build/")
                        ? Optional.of(path.split("/")[2]) : Optional.empty()), _ -> null));
        MultiValueMap<String, String> own = form();
        own.set("resource", "http://localhost:8080/build/other/");
        MultiValueMap<String, String> foreign = form();
        foreign.set("resource", "http://localhost:9090/build/other/");

        assertThat(direct.token(own, hosted()).getStatusCode().value()).isEqualTo(200);
        assertRefused(direct.token(foreign, hosted()), 400, "invalid_target");
    }

    @Test
    void an_unsafe_tenant_name_is_an_invalid_target_and_reaches_no_exchange() throws IOException {
        for (String unsafe : List.of("../other", "a/b", ".system", "a b", "..")) {
            MultiValueMap<String, String> byAudience = form();
            byAudience.set("audience", unsafe);
            MultiValueMap<String, String> byParameter = form();
            byParameter.set("tenant", unsafe);

            assertRefused(post(byAudience), 400, "invalid_target");
            assertRefused(post(byParameter), 400, "invalid_target");
        }
        assertThat(asked).as("no exchange was asked about an unsafe tenant").isEmpty();
        assertThat(audit.rows()).isEmpty();
    }

    @Test
    void audiences_naming_different_tenants_are_an_invalid_target() throws IOException {
        MultiValueMap<String, String> audiences = form();
        audiences.add("audience", "other");
        audiences.add("audience", "third");

        assertRefused(post(audiences), 400, "invalid_target");
        assertThat(asked).isEmpty();
    }

    @Test
    void a_missing_grant_type_is_an_invalid_request_and_another_one_an_unsupported_grant_type() throws IOException {
        MultiValueMap<String, String> missing = form();
        missing.remove("grant_type");
        MultiValueMap<String, String> other = form();
        other.set("grant_type", "client_credentials");

        assertRefused(post(missing), 400, "invalid_request");
        assertRefused(post(other), 400, "unsupported_grant_type");
    }

    @Test
    void a_missing_subject_token_or_type_is_an_invalid_request() throws IOException {
        MultiValueMap<String, String> noToken = form();
        noToken.remove("subject_token");
        MultiValueMap<String, String> blankToken = form();
        blankToken.set("subject_token", "");
        MultiValueMap<String, String> noType = form();
        noType.remove("subject_token_type");

        assertRefused(post(noToken), 400, "invalid_request");
        assertRefused(post(blankToken), 400, "invalid_request");
        assertRefused(post(noType), 400, "invalid_request");
        assertThat(asked).isEmpty();
    }

    @Test
    void a_subject_token_type_other_than_an_id_token_or_a_jwt_is_an_invalid_request() throws IOException {
        for (String type : List.of(ACCESS_TOKEN, "urn:ietf:params:oauth:token-type:refresh_token",
                "urn:ietf:params:oauth:token-type:saml2", "id_token")) {
            MultiValueMap<String, String> form = form();
            form.set("subject_token_type", type);

            assertRefused(post(form), 400, "invalid_request");
        }
        assertThat(asked).isEmpty();
    }

    @Test
    void a_requested_token_type_other_than_an_access_token_is_an_invalid_request() throws IOException {
        MultiValueMap<String, String> form = form();
        form.set("requested_token_type", JWT);

        assertRefused(post(form), 400, "invalid_request");
    }

    @Test
    void an_actor_token_is_an_invalid_request_and_a_scope_an_invalid_scope() throws IOException {
        MultiValueMap<String, String> actor = form();
        actor.set("actor_token", TOKEN);
        actor.set("actor_token_type", JWT);
        MultiValueMap<String, String> actorType = form();
        actorType.set("actor_token_type", JWT);
        MultiValueMap<String, String> scope = form();
        scope.set("scope", "repository:write");

        assertRefused(post(actor), 400, "invalid_request");
        assertRefused(post(actorType), 400, "invalid_request");
        assertRefused(post(scope), 400, "invalid_scope");
        assertThat(asked).isEmpty();
    }

    @Test
    void a_repeated_parameter_is_an_invalid_request() throws IOException {
        MultiValueMap<String, String> form = form();
        form.add("subject_token", TOKEN);

        assertRefused(post(form), 400, "invalid_request");
        assertThat(asked).isEmpty();
    }

    @Test
    void a_parameter_without_a_value_counts_as_omitted_and_an_unknown_one_is_ignored() throws IOException {
        MultiValueMap<String, String> form = form();
        form.set("scope", "");
        form.set("resource", "");
        form.set("actor_token", "");
        form.set("client_id", "ci");
        form.add("client_id", "ci");

        assertThat(post(form).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void a_token_no_trust_admits_is_an_invalid_request_that_does_not_repeat_it() throws IOException {
        MultiValueMap<String, String> form = form();
        form.set("subject_token", "forged.token.value");

        ResponseEntity<Map<String, Object>> response = post(form);

        assertRefused(response, 400, "invalid_request");
        assertThat(response.getBody().toString()).doesNotContain("forged");
        assertThat(audit.rows()).isEmpty();
    }

    @Test
    void a_token_in_the_query_string_is_refused_whatever_its_encoding() throws IOException {
        for (String uri : List.of("/api/token?subject_token=" + TOKEN, "/api/token?subject%5Ftoken=" + TOKEN,
                "/api/token?tenant=other&grant_type=x")) {
            assertRefused(controller.token(form(), request(uri)), 400, "invalid_request");
        }
        assertThat(asked).isEmpty();
    }

    @Test
    void a_request_that_is_not_a_form_is_an_invalid_request() throws IOException {
        for (String contentType : Arrays.asList(null, "application/json", "text/plain", "not a media type")) {
            assertRefused(controller.token(form(), Servlets.request("POST", "/api/token", contentType, new byte[0],
                    Map.of())), 400, "invalid_request");
        }
        assertThat(controller.token(form(), Servlets.request("POST", "/api/token",
                "application/x-www-form-urlencoded;charset=UTF-8", new byte[0], Map.of())).getStatusCode().value())
                .isEqualTo(200);
    }

    @Test
    void a_deployment_without_an_exchange_answers_not_implemented() throws IOException {
        TokenController absent = new TokenController(TokenExchange.NONE, new AuditedCredentialContext(SERVED, audit),
                resources);

        assertRefused(absent.token(form(), request("/api/token")), 501, "unsupported_grant_type");
        assertThat(audit.rows()).isEmpty();
    }

    @Test
    void no_refusal_carries_a_character_an_error_description_may_not_hold() throws IOException {
        MultiValueMap<String, String> scope = form();
        scope.set("scope", "x");
        MultiValueMap<String, String> resource = form();
        resource.set("resource", "https://example.com/\"quoted\\");
        MultiValueMap<String, String> type = form();
        type.set("subject_token_type", "x");
        MultiValueMap<String, String> grant = form();
        grant.set("grant_type", "x");

        assertThat(List.of(post(scope), post(resource), post(type), post(grant),
                post(new LinkedMultiValueMap<>()), controller.token(form(), request("/api/token?x=y"))))
                .allSatisfy(response -> assertThat((String) response.getBody().get("error_description"))
                        .as("RFC 6749 section 5.2: %x20-21 / %x23-5B / %x5D-7E").matches("[\\x20-\\x21\\x23-\\x5B\\x5D-\\x7E]+"));
    }

    private ResponseEntity<Map<String, Object>> post(MultiValueMap<String, String> form) throws IOException {
        return controller.token(form, request("/api/token"));
    }

    private static jakarta.servlet.http.HttpServletRequest request(String uri) {
        return Servlets.request("POST", uri, "application/x-www-form-urlencoded", new byte[0], Map.of());
    }

    /** A request to {@code http://localhost:8080}, as a client reaching the server directly sends it. */
    private static jakarta.servlet.http.HttpServletRequest hosted() {
        jakarta.servlet.http.HttpServletRequest request = Servlets.request("POST", "/api/token",
                "application/x-www-form-urlencoded", new byte[0], Map.of("Host", "localhost:8080"));
        return new jakarta.servlet.http.HttpServletRequestWrapper(request) {
            @Override
            public String getScheme() {
                return "http";
            }

            @Override
            public String getRemoteAddr() {
                return "127.0.0.1";
            }
        };
    }

    private static MultiValueMap<String, String> form() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.set("grant_type", GRANT);
        form.set("subject_token", TOKEN);
        form.set("subject_token_type", ID_TOKEN);
        return form;
    }

    private static void assertRefused(ResponseEntity<Map<String, Object>> response, int status, String error) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getBody()).containsOnlyKeys("error", "error_description").containsEntry("error", error);
        assertThat(response.getBody().toString()).as("a refusal never repeats the token").doesNotContain(TOKEN);
    }
}
