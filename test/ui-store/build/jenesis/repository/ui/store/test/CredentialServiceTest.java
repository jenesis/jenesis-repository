package build.jenesis.repository.ui.store.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.ui.store.CredentialService;
import build.jenesis.repository.ui.CurrentTenant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The console credential service's input validators - the sanitisation between the console form and the stored
 * authorization model. A credential id must be a 64-hex string, a grant's project and path-prefix are charset-checked,
 * and a custom role's tokens must be known rights, so the console cannot persist a malformed grant or an unknown right.
 */
public class CredentialServiceTest {

    private static final String ID = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @TempDir
    private Path root;

    private CredentialService credentials;

    @BeforeEach
    public void setUp() {
        ArtifactStore store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        credentials = new CredentialService(Authorization.anonymous(), AuditTrail.none(),
                new CurrentTenant() {
                    @Override
                    public String name() {
                        return "acme";
                    }
                }, () -> "console", List.of());
    }

    @Test
    public void a_non_hex_credential_id_is_rejected() {
        assertThatThrownBy(() -> credentials.get("not-a-hex-id"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("credential id");
    }

    @Test
    public void a_grant_path_prefix_outside_the_charset_is_rejected() {
        assertThatThrownBy(() -> credentials.setGrant(ID, "*", "bad prefix!", "viewer"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("path prefix");
    }

    @Test
    public void a_grant_project_outside_the_charset_is_rejected() {
        assertThatThrownBy(() -> credentials.setGrant(ID, "bad project", "", "viewer"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("project");
    }

    @Test
    public void an_unknown_role_token_is_rejected() {
        assertThatThrownBy(() -> credentials.setRole("custom", "not-a-right"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unknown role token");
    }
}
