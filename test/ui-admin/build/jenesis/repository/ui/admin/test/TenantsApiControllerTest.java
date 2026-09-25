package build.jenesis.repository.ui.admin.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.cache.storage.testkit.CacheStorages;
import build.jenesis.repository.server.PresentedKey;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Documents;
import build.jenesis.repository.console.api.TenantsApiController;
import build.jenesis.repository.ui.store.TenantService;
import jakarta.servlet.http.HttpServletRequest;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A tenant is created, listed and deleted over the API through the same directory and purge the console's instances
 * screen uses: a deletion takes the tenant's artifacts with it, the audit names the key's hash and never the key, and
 * a key outside the operator tenant is refused however it is granted.
 */
class TenantsApiControllerTest {

    @TempDir
    Path root;

    private final List<String> recorded = new CopyOnWriteArrayList<>();

    private Documents rootStorage;
    private ArtifactStore repositoryStore;
    private TenantsApiController controller;

    @BeforeEach
    void setUp() {
        rootStorage = CacheStorages.documents(root);
        repositoryStore = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.resolve("repo").toString() : null);
        controller = new TenantsApiController(rootStorage, repositoryStore, Authorization.enforcing(repositoryStore),
                audit(), "operator");
    }

    @Test
    void an_operator_key_creates_lists_and_deletes_a_tenant_through_the_screens_own_implementation()
            throws IOException {
        String key = Authorization.mint("operator");
        HttpServletRequest request = request(key);

        assertThat(controller.create("acme", request).getStatusCode().value()).isEqualTo(201);
        assertThat(new TenantService(rootStorage).exists("acme")).as("the directory the console lists").isTrue();
        assertThat(controller.list(request).getBody())
                .isEqualTo(new TenantsApiController.Tenants(List.of("acme")));
        assertThat(controller.create("acme", request).getStatusCode().value()).as("a second create").isEqualTo(409);
        assertThat(controller.create("not/a/name", request).getStatusCode().value()).isEqualTo(400);

        repositoryStore.scope("acme").scope("releases")
                .write("raw/a.txt", new ByteArrayInputStream("a".getBytes(UTF_8)));
        assertThat(controller.delete("acme", request).getStatusCode().value()).isEqualTo(200);
        assertThat(new TenantService(rootStorage).exists("acme")).isFalse();
        assertThat(repositoryStore.list("acme")).as("the purge took what the tenant held").isEmpty();
        assertThat(controller.delete("acme", request).getStatusCode().value()).as("nothing left to delete")
                .isEqualTo(404);

        assertThat(recorded).as("attributed to the key's hash, never to the key")
                .contains("acme " + Authorization.hash(key) + " tenant.create acme",
                        "operator " + Authorization.hash(key) + " tenant.purge acme")
                .noneMatch(line -> line.contains(key));
    }

    @Test
    void a_key_outside_the_operator_tenant_and_a_request_without_one_are_refused() throws IOException {
        HttpServletRequest tenantAdmin = request(Authorization.mint("acme"));
        assertThat(controller.create("globex", tenantAdmin).getStatusCode().value()).isEqualTo(403);
        assertThat(controller.list(tenantAdmin).getStatusCode().value()).isEqualTo(403);
        assertThat(controller.create("globex", request(null)).getStatusCode().value()).isEqualTo(403);
        assertThat(new TenantService(rootStorage).exists("globex")).as("nothing was created").isFalse();
    }

    /** A request presenting {@code key} in the header a script sends it in, and nothing else. */
    private static HttpServletRequest request(String key) {
        return (HttpServletRequest) java.lang.reflect.Proxy.newProxyInstance(
                TenantsApiControllerTest.class.getClassLoader(), new Class<?>[]{HttpServletRequest.class},
                (_, method, arguments) -> {
                    if (method.getName().equals("getHeader")) {
                        return PresentedKey.HEADER.equals(arguments[0]) ? key : null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private AuditTrail audit() {
        return new AuditTrail() {
            @Override
            public boolean enabled() {
                return true;
            }

            @Override
            public void record(String tenant, String actor, String action, String target) {
                recorded.add(tenant + " " + actor + " " + action + " " + target);
            }

            @Override
            public List<Event> query(String tenant, Instant from, Instant to, String action) {
                return List.of();
            }
        };
    }
}
