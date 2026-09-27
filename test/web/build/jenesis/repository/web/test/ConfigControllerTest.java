package build.jenesis.repository.web.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.audit.AuditActions;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.config.web.ConfigController;
import build.jenesis.repository.server.RepositoryProperties;
import build.jenesis.repository.server.kernel.LiveConfig;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.server.kernel.Settings;
import build.jenesis.repository.settings.SecretCipher;
import build.jenesis.repository.settings.SettingsDocuments;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.RepositoryDocument;
import build.jenesis.repository.store.RepositoryRemoval;
import build.jenesis.repository.upstream.UpstreamCredentialSource;
import build.jenesis.repository.upstream.store.StoreUpstreamCredentials;
import build.jenesis.repository.servlet.testkit.Servlets;
import build.jenesis.repository.web.testkit.Web;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The deployment-config surface over a real store, driven through its handlers: the settings catalogue with each
 * row's effective value and whether a pin fixes it, the refusals a write meets (an unknown key, a value the setting
 * cannot parse, a pinned key, a bundle that would not resolve), the per-tenant slice, the routing entries and format
 * upstreams with the plaintext screen, the upstream credentials with and without a module to hold them, and the one
 * creation and removal of a repository every surface makes.
 */
class ConfigControllerTest {

    private static final String PINNED = "deny-list-action";

    @TempDir
    Path root;

    private ArtifactStore store;
    private Repositories repositories;
    private Settings settings;
    private Web.Recording audit;
    private ConfigController controller;

    @BeforeEach
    void wire() throws IOException {
        store = Web.store(root);
        repositories = Web.repositories(store);
        audit = Web.audit();
        controller = controller(UpstreamCredentialSource.NONE);
    }

    private ConfigController controller(UpstreamCredentialSource credentials) throws IOException {
        settings = new Settings(store);
        LiveConfig live = new LiveConfig(settings, new RepositoryProperties(), AdvisorySource.none(), _ -> null);
        return new ConfigController(repositories, settings, live,
                Web.pins(Web.environment(Map.of("jenreg." + PINNED, "REJECT"))), credentials, audit,
                Web.routing(store, repositories));
    }

    private ConfigController.SettingView row(List<ConfigController.SettingView> rows, String key) {
        return rows.stream().filter(row -> row.key().equals(key)).findFirst().orElseThrow();
    }

    @Test
    void every_catalogued_setting_is_listed_with_its_default_until_it_is_overridden() throws IOException {
        ConfigController.SettingView before = row(controller.settings(null, null), "vulnerability-threshold");
        assertThat(before.value()).isEqualTo(before.defaultValue());
        assertThat(before.overridden()).isFalse();

        Servlets.Response response = Servlets.response();
        controller.setSetting("vulnerability-threshold", "operator-key", null,
                new ConfigController.SettingRequest("HIGH"), response.servlet());

        assertThat(response.status()).isEqualTo(200);
        ConfigController.SettingView after = row(controller.settings(null, null), "vulnerability-threshold");
        assertThat(after.value()).isEqualTo("HIGH");
        assertThat(after.overridden()).isTrue();
        assertThat(audit.rows()).singleElement().satisfies(event -> {
            assertThat(event.action()).isEqualTo("setting.set");
            assertThat(event.target()).isEqualTo("vulnerability-threshold");
            assertThat(event.actor()).as("the key is hashed, never recorded").doesNotContain("operator-key");
        });
    }

    @Test
    void a_pinned_key_reports_the_pin_and_refuses_both_writes() throws IOException {
        ConfigController.SettingView pinned = row(controller.settings(null, null), PINNED);
        assertThat(pinned.pinned()).isTrue();
        assertThat(pinned.pinnedBy()).isNotBlank();
        assertThat(pinned.value()).as("the pin's value is the effective one").isEqualTo("REJECT");

        Servlets.Response put = Servlets.response();
        controller.setSetting(PINNED, null, null, new ConfigController.SettingRequest("ALLOW"), put.servlet());
        Servlets.Response delete = Servlets.response();
        controller.clearSetting(PINNED, null, null, delete.servlet());

        assertThat(put.status()).isEqualTo(409);
        assertThat(put.body()).contains("is pinned by").contains("would be inert");
        assertThat(delete.status()).isEqualTo(409);
        assertThat(settings.overrides()).doesNotContainKey(PINNED);
        assertThat(audit.rows()).isEmpty();
    }

    @Test
    void an_unknown_key_and_an_unparsable_value_are_refused_with_nothing_stored() throws IOException {
        Servlets.Response unknown = Servlets.response();
        controller.setSetting("no-such-setting", null, null, new ConfigController.SettingRequest("x"),
                unknown.servlet());
        Servlets.Response unparsable = Servlets.response();
        controller.setSetting("vulnerability-threshold", null, null, new ConfigController.SettingRequest("SEVERE"),
                unparsable.servlet());
        Servlets.Response clearUnknown = Servlets.response();
        controller.clearSetting("no-such-setting", null, null, clearUnknown.servlet());

        assertThat(unknown.status()).isEqualTo(400);
        assertThat(unparsable.status()).isEqualTo(400);
        assertThat(clearUnknown.status()).isEqualTo(400);
        assertThat(settings.overrides()).isEmpty();
        assertThat(audit.rows()).isEmpty();
    }

    @Test
    void a_cleared_setting_reverts_to_its_default() throws IOException {
        controller.setSetting("vulnerability-threshold", null, null, new ConfigController.SettingRequest("LOW"),
                Servlets.response().servlet());
        Servlets.Response response = Servlets.response();

        controller.clearSetting("vulnerability-threshold", null, null, response.servlet());

        assertThat(response.status()).isEqualTo(200);
        assertThat(row(controller.settings(null, null), "vulnerability-threshold").overridden()).isFalse();
        assertThat(audit.actions()).containsExactly("setting.set", "setting.clear");
    }

    @Test
    void a_tenant_override_lands_in_the_tenant_slice_alone() throws IOException {
        Servlets.Response response = Servlets.response();

        controller.setSetting("vulnerability-threshold", null, "acme", new ConfigController.SettingRequest("LOW"),
                response.servlet());

        assertThat(response.status()).isEqualTo(200);
        List<ConfigController.SettingView> tenant = controller.settings(null, "acme");
        assertThat(tenant).as("only what a tenant may retune is listed for it").extracting(
                ConfigController.SettingView::key).contains("vulnerability-threshold").doesNotContain("public-url");
        assertThat(row(tenant, "vulnerability-threshold").value()).isEqualTo("LOW");
        assertThat(row(tenant, "vulnerability-threshold").overridden()).isTrue();
        assertThat(row(controller.settings(null, null), "vulnerability-threshold").overridden())
                .as("the deployment-wide value is untouched").isFalse();

        controller.clearSetting("vulnerability-threshold", null, "acme", Servlets.response().servlet());
        assertThat(row(controller.settings(null, "acme"), "vulnerability-threshold").overridden()).isFalse();
        assertThat(audit.rows()).extracting(Web.Recorded::target)
                .containsExactly("acme/vulnerability-threshold", "acme/vulnerability-threshold");
    }

    @Test
    void the_setup_guide_carries_only_rows_the_catalogue_has() {
        ConfigController.SetupView setup = controller.setup(null);

        assertThat(setup.steps()).isNotEmpty();
        Set<String> catalogued = new HashSet<>();
        controller.settings(null, null).forEach(row -> catalogued.add(row.key()));
        setup.steps().forEach(step -> step.settings().forEach(row -> assertThat(catalogued).contains(row.key())));
    }

    @Test
    void an_exported_bundle_restores_and_a_bad_or_pinned_one_is_refused_before_a_write() throws IOException {
        controller.setSetting("vulnerability-threshold", null, null, new ConfigController.SettingRequest("HIGH"),
                Servlets.response().servlet());
        Servlets.Response exported = Servlets.response();
        controller.exportSettings(null, exported.servlet());
        assertThat(exported.contentType()).isEqualTo("application/json");
        assertThat(exported.body()).contains("\"vulnerability-threshold\"").contains("\"HIGH\"");

        Servlets.Response missing = Servlets.response();
        controller.importSettings(null, null, null, missing.servlet());
        assertThat(missing.status()).isEqualTo(400);

        Servlets.Response bad = Servlets.response();
        controller.importSettings(null, null, Map.of(SettingsDocuments.moduleOf("vulnerability-threshold"),
                Map.of("vulnerability-threshold", "SEVERE")), bad.servlet());
        assertThat(bad.status()).isEqualTo(400);
        assertThat(bad.body()).contains("does not resolve");

        Servlets.Response pinned = Servlets.response();
        controller.importSettings(null, null, Map.of(SettingsDocuments.moduleOf(PINNED), Map.of(PINNED, "ALLOW")), pinned.servlet());
        assertThat(pinned.status()).isEqualTo(409);
        assertThat(pinned.body()).contains(PINNED);
        assertThat(settings.overrides()).containsEntry("vulnerability-threshold", "HIGH");

        Servlets.Response restored = Servlets.response();
        controller.importSettings(null, null, Map.of(SettingsDocuments.moduleOf("vulnerability-threshold"), Map.of("vulnerability-threshold", "MEDIUM")),
                restored.servlet());
        assertThat(restored.status()).isEqualTo(200);
        assertThat(settings.overrides()).containsEntry("vulnerability-threshold", "MEDIUM");
        assertThat(audit.actions()).endsWith("settings.import");
    }

    @Test
    void a_tenant_slice_is_exported_and_restored_on_its_own() throws IOException {
        Servlets.Response restored = Servlets.response();
        controller.importSettings(null, "acme", Map.of(SettingsDocuments.moduleOf("deny-list"), Map.of("deny-list", "pkg:npm/evil")),
                restored.servlet());
        assertThat(restored.status()).isEqualTo(200);

        Servlets.Response exported = Servlets.response();
        controller.exportSettings("acme", exported.servlet());

        assertThat(exported.body()).contains("pkg:npm/evil");
        assertThat(settings.overrides()).as("the deployment-wide documents are untouched").isEmpty();
    }

    @Test
    void a_routing_entry_is_parsed_and_screened_before_it_is_stored() throws IOException {
        Servlets.Response writable = Servlets.response();
        controller.setRepositoryDefinition("releases", "key", null,
                new ConfigController.NamedValueRequest("writable"), writable.servlet());
        Servlets.Response nonsense = Servlets.response();
        controller.setRepositoryDefinition("broken", null, null,
                new ConfigController.NamedValueRequest("sometimes maybe"), nonsense.servlet());
        Servlets.Response plaintext = Servlets.response();
        controller.setRepositoryDefinition("mirror", null, null,
                new ConfigController.NamedValueRequest("fallback http://mirror.example/maven2"), plaintext.servlet());
        Servlets.Response unnamed = Servlets.response();
        controller.setRepositoryDefinition("..", null, null, new ConfigController.NamedValueRequest("writable"),
                unnamed.servlet());

        assertThat(writable.status()).isEqualTo(200);
        assertThat(nonsense.status()).isEqualTo(400);
        assertThat(nonsense.body()).contains("Repository 'broken' has an invalid definition");
        assertThat(plaintext.status()).isEqualTo(400);
        assertThat(plaintext.body()).contains("Repository 'mirror' has a refused definition");
        assertThat(unnamed.status()).isEqualTo(400);
        assertThat(controller.repositoryDefinitions(null))
                .containsExactly(new ConfigController.NamedValue("releases", "writable"));
        assertThat(audit.actions()).containsExactly(AuditActions.REPOSITORY_SET);

        controller.removeRepositoryDefinition("releases", null, null, Servlets.response().servlet());
        assertThat(controller.repositoryDefinitions(null)).isEmpty();
        assertThat(audit.actions()).endsWith(AuditActions.REPOSITORY_REMOVE);
    }

    @Test
    void a_tenant_routes_its_own_repository_over_the_deployment() throws IOException {
        controller.setRepositoryDefinition("releases", null, "acme",
                new ConfigController.NamedValueRequest("writable"), Servlets.response().servlet());
        Servlets.Response badTenant = Servlets.response();
        controller.setRepositoryDefinition("releases", null, "not a tenant",
                new ConfigController.NamedValueRequest("writable"), badTenant.servlet());

        assertThat(controller.repositoryDefinitions("acme"))
                .containsExactly(new ConfigController.NamedValue("releases", "writable"));
        assertThat(controller.repositoryDefinitions(null)).isEmpty();
        assertThat(controller.repositoryDefinitions("not a tenant")).isEmpty();
        assertThat(badTenant.status()).isEqualTo(400);
        assertThat(audit.rows()).singleElement()
                .satisfies(event -> assertThat(event.target()).isEqualTo("acme/releases"));
    }

    @Test
    void a_format_upstream_must_be_an_https_url() throws IOException {
        Servlets.Response https = Servlets.response();
        controller.setUpstream("npm", null, null, new ConfigController.NamedValueRequest("https://registry.npmjs.org"),
                https.servlet());
        Servlets.Response plaintext = Servlets.response();
        controller.setUpstream("pypi", null, null, new ConfigController.NamedValueRequest("http://pypi.example/"),
                plaintext.servlet());
        Servlets.Response blank = Servlets.response();
        controller.setUpstream("go", null, null, new ConfigController.NamedValueRequest(" "), blank.servlet());
        Servlets.Response malformed = Servlets.response();
        controller.setUpstream("go", null, null, new ConfigController.NamedValueRequest("https://bad host/"),
                malformed.servlet());

        assertThat(https.status()).isEqualTo(200);
        assertThat(plaintext.status()).isEqualTo(400);
        assertThat(plaintext.body()).contains("The 'pypi' upstream 'http://pypi.example/' is refused");
        assertThat(blank.status()).isEqualTo(400);
        assertThat(malformed.status()).isEqualTo(400);
        assertThat(controller.upstreams(null))
                .containsExactly(new ConfigController.NamedValue("npm", "https://registry.npmjs.org"));

        controller.removeUpstream("npm", null, null, Servlets.response().servlet());
        assertThat(controller.upstreams(null)).isEmpty();
        assertThat(audit.actions()).containsExactly(AuditActions.UPSTREAM_SET, AuditActions.UPSTREAM_REMOVE);
    }

    @Test
    void upstream_credentials_answer_501_where_no_module_holds_them() throws IOException {
        Servlets.Response list = Servlets.response();
        assertThat(controller.upstreamCredentialHosts(list.servlet())).isNull();
        Servlets.Response set = Servlets.response();
        controller.setUpstreamCredential("nexus.internal", null,
                new ConfigController.UpstreamAuthRequest("bearer", null, null, "t0ken", null), set.servlet());
        Servlets.Response remove = Servlets.response();
        controller.removeUpstreamCredential("nexus.internal", null, remove.servlet());

        assertThat(List.of(list.status(), set.status(), remove.status())).containsOnly(501);
        assertThat(list.body()).isEqualTo("upstream credentials are not installed on this deployment");
    }

    @Test
    void an_upstream_credential_is_stored_listed_by_host_and_removed() throws IOException {
        ConfigController keyed = controller(new StoreUpstreamCredentials(store, Duration.ofSeconds(30),
                SecretCipher.of("k1:" + Base64.getEncoder().encodeToString(new byte[32]))));
        Servlets.Response set = Servlets.response();
        keyed.setUpstreamCredential("nexus.internal", "operator",
                new ConfigController.UpstreamAuthRequest("bearer", null, null, "t0ken", null), set.servlet());
        Servlets.Response incomplete = Servlets.response();
        keyed.setUpstreamCredential("other.internal", null,
                new ConfigController.UpstreamAuthRequest("basic", "user", null, null, null), incomplete.servlet());

        assertThat(set.status()).isEqualTo(200);
        assertThat(incomplete.status()).isEqualTo(400);
        assertThat(keyed.upstreamCredentialHosts(Servlets.response().servlet())).containsExactly("nexus.internal");

        keyed.removeUpstreamCredential("nexus.internal", null, Servlets.response().servlet());
        assertThat(keyed.upstreamCredentialHosts(Servlets.response().servlet())).isEmpty();
        assertThat(audit.actions()).containsExactly(AuditActions.UPSTREAM_AUTH_SET, AuditActions.UPSTREAM_AUTH_REMOVE);
    }

    @Test
    void an_upstream_credential_is_refused_where_nothing_can_encrypt_it() throws IOException {
        ConfigController unkeyed = controller(new StoreUpstreamCredentials(store, Duration.ofSeconds(30),
                SecretCipher.of(null)));
        Servlets.Response response = Servlets.response();

        unkeyed.setUpstreamCredential("nexus.internal", null,
                new ConfigController.UpstreamAuthRequest("bearer", null, null, "t0ken", null), response.servlet());

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body()).contains("JENREG_SECRETS_KEY");
        assertThat(audit.rows()).isEmpty();
    }

    @Test
    void a_repository_is_created_once_described_and_deleted() throws IOException {
        Servlets.Response created = Servlets.response();
        controller.createRepository("files", "key", new ConfigController.RepositoryRequest("raw", "Build outputs"),
                Servlets.request("PUT", "/repository/default/files"), created.servlet());
        Servlets.Response again = Servlets.response();
        controller.createRepository("files", "key", new ConfigController.RepositoryRequest("raw", null),
                Servlets.request("PUT", "/repository/default/files"), again.servlet());

        assertThat(created.status()).isEqualTo(201);
        assertThat(again.status()).as("it already holds that type").isEqualTo(200);
        ArtifactStore repository = repositories.store("default", "files");
        assertThat(RepositoryDocument.read(repository)).hasValueSatisfying(document -> {
            assertThat(document.format()).isEqualTo("raw");
            assertThat(document.description()).isEqualTo("Build outputs");
        });

        Servlets.Response described = Servlets.response();
        controller.createRepository("files", "key", new ConfigController.RepositoryRequest(null, "Nightly outputs"),
                Servlets.request("PUT", "/repository/default/files"), described.servlet());
        assertThat(described.status()).isEqualTo(200);
        assertThat(RepositoryDocument.read(repository).orElseThrow().description()).isEqualTo("Nightly outputs");

        Servlets.Response deleted = Servlets.response();
        controller.deleteRepository("files", "key", Servlets.request("DELETE", "/repository/default/files"),
                deleted.servlet());
        assertThat(deleted.status()).isEqualTo(202);
        assertThat(deleted.body()).startsWith("Deleting repository 'files'");
        assertThat(RepositoryDocument.read(repository)).as("it stops answering before the answer").isEmpty();
        assertThat(audit.actions()).containsExactly(AuditActions.REPOSITORY_DESCRIBE, AuditActions.REPOSITORY_CREATE,
                AuditActions.REPOSITORY_DESCRIBE, AuditActions.REPOSITORY_DELETE);
    }

    @Test
    void creation_refuses_a_type_nothing_serves_and_a_repository_still_being_deleted() throws IOException {
        Servlets.Response unknown = Servlets.response();
        controller.createRepository("files", null, new ConfigController.RepositoryRequest("cobol", null),
                Servlets.request("PUT", "/repository/default/files"), unknown.servlet());
        assertThat(unknown.status()).isEqualTo(400);
        assertThat(unknown.body()).contains("'cobol' is not a format a repository can hold here");

        ArtifactStore repository = repositories.store("default", "gone");
        controller.createRepository("gone", null, new ConfigController.RepositoryRequest("raw", null),
                Servlets.request("PUT", "/repository/default/gone"), Servlets.response().servlet());
        RepositoryRemoval.begin(repository);
        Servlets.Response removing = Servlets.response();
        controller.createRepository("gone", null, new ConfigController.RepositoryRequest("raw", null),
                Servlets.request("PUT", "/repository/default/gone"), removing.servlet());
        assertThat(removing.status()).isEqualTo(409);
        assertThat(removing.body()).contains("is still being deleted");
    }

    @Test
    void describing_or_deleting_a_repository_that_does_not_exist_is_a_404() throws IOException {
        Servlets.Response described = Servlets.response();
        controller.createRepository("nowhere", null, new ConfigController.RepositoryRequest(null, "text"),
                Servlets.request("PUT", "/repository/default/nowhere"), described.servlet());
        Servlets.Response deleted = Servlets.response();
        controller.deleteRepository("nowhere", null, Servlets.request("DELETE", "/repository/default/nowhere"),
                deleted.servlet());

        assertThat(described.status()).isEqualTo(404);
        assertThat(deleted.status()).isEqualTo(404);
        assertThat(audit.rows()).isEmpty();
    }
}
