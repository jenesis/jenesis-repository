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
import build.jenesis.repository.server.kernel.SettingsEditor;
import build.jenesis.repository.settings.SecretCipher;
import build.jenesis.repository.settings.SettingsDocuments;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.RepositoryDocument;
import build.jenesis.repository.store.RepositoryRemoval;
import build.jenesis.repository.format.RepositoryType;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.StoredSettings;
import build.jenesis.repository.settings.Wizard;
import build.jenesis.repository.upstream.UpstreamCredentialSource;
import build.jenesis.repository.upstream.store.StoreUpstreamCredentials;
import build.jenesis.repository.servlet.testkit.Servlets;
import build.jenesis.repository.web.testkit.Web;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        return controller(credentials, true);
    }

    /** A controller whose callers are the deployment's operator, or are not. */
    private ConfigController controller(UpstreamCredentialSource credentials, boolean operator) throws IOException {
        settings = new Settings(store);
        LiveConfig live = new LiveConfig(settings, new RepositoryProperties(), AdvisorySource.none(), _ -> null);
        SettingsEditor editor = new SettingsEditor(settings,
                Web.pins(Web.environment(Map.of("jenrepo." + PINNED, "REJECT")))::pinned, live, audit);
        return new ConfigController(repositories, editor, credentials, audit, Web.routing(store, repositories),
                _ -> operator);
    }

    /** An {@code /api} call, which names no tenant: the routing answers the one this deployment serves. */
    private static HttpServletRequest request() {
        return Servlets.request("PUT", "/api/settings");
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
                new ConfigController.SettingRequest("HIGH"), request(), response.servlet());

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
        controller.setSetting(PINNED, null, null, new ConfigController.SettingRequest("ALLOW"),
                request(), put.servlet());
        Servlets.Response delete = Servlets.response();
        controller.clearSetting(PINNED, null, null, request(), delete.servlet());

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
                request(), unknown.servlet());
        Servlets.Response unparsable = Servlets.response();
        controller.setSetting("vulnerability-threshold", null, null, new ConfigController.SettingRequest("SEVERE"),
                request(), unparsable.servlet());
        Servlets.Response clearUnknown = Servlets.response();
        controller.clearSetting("no-such-setting", null, null, request(), clearUnknown.servlet());

        assertThat(unknown.status()).isEqualTo(400);
        assertThat(unparsable.status()).isEqualTo(400);
        assertThat(clearUnknown.status()).isEqualTo(400);
        assertThat(settings.overrides()).isEmpty();
        assertThat(audit.rows()).isEmpty();
    }

    @Test
    void a_cleared_setting_reverts_to_its_default() throws IOException {
        controller.setSetting("vulnerability-threshold", null, null, new ConfigController.SettingRequest("LOW"),
                request(), Servlets.response().servlet());
        Servlets.Response response = Servlets.response();

        controller.clearSetting("vulnerability-threshold", null, null, request(), response.servlet());

        assertThat(response.status()).isEqualTo(200);
        assertThat(row(controller.settings(null, null), "vulnerability-threshold").overridden()).isFalse();
        assertThat(audit.actions()).containsExactly("setting.set", "setting.clear");
    }

    @Test
    void a_tenant_override_lands_in_the_tenant_slice_alone() throws IOException {
        Servlets.Response response = Servlets.response();

        controller.setSetting("vulnerability-threshold", null, "acme", new ConfigController.SettingRequest("LOW"),
                request(), response.servlet());

        assertThat(response.status()).isEqualTo(200);
        List<ConfigController.SettingView> tenant = controller.settings(null, "acme");
        assertThat(tenant).as("only what a tenant may retune is listed for it").extracting(
                ConfigController.SettingView::key).contains("vulnerability-threshold").doesNotContain("public-url");
        assertThat(row(tenant, "vulnerability-threshold").value()).isEqualTo("LOW");
        assertThat(row(tenant, "vulnerability-threshold").overridden()).isTrue();
        assertThat(row(controller.settings(null, null), "vulnerability-threshold").overridden())
                .as("the deployment-wide value is untouched").isFalse();

        controller.clearSetting("vulnerability-threshold", null, "acme", request(), Servlets.response().servlet());
        assertThat(row(controller.settings(null, "acme"), "vulnerability-threshold").overridden()).isFalse();
        assertThat(audit.rows()).extracting(Web.Recorded::target)
                .containsExactly("acme/vulnerability-threshold", "acme/vulnerability-threshold");
    }

    @Test
    void the_setup_view_is_the_first_boot_wizard_the_console_runs() {
        ConfigController.SetupView setup = controller.setup(null);

        assertThat(setup.steps().getFirst().id()).as("the starter credential first, asking no setting")
                .isEqualTo(Wizard.STARTER_CREDENTIAL.id());
        assertThat(setup.steps().getFirst().settings()).isEmpty();
        List<String> asked = setup.steps().stream().flatMap(step -> step.settings().stream())
                .map(ConfigController.SettingView::key).toList();
        assertThat(asked).as("every essential deployment, tenant and repository setting, and nothing else")
                .containsExactlyElementsOf(Wizard.SETUP.steps().stream()
                        .flatMap(step -> step.settings().stream()).map(Setting::key).toList());
        assertThat(asked).as("a repository's essential setting is asked as the default every repository inherits")
                .contains("vulnerability-threshold", "keep-last", "full-text-search");
    }

    @Test
    void an_exported_bundle_restores_and_a_bad_or_pinned_one_is_refused_before_a_write() throws IOException {
        controller.setSetting("vulnerability-threshold", null, null, new ConfigController.SettingRequest("HIGH"),
                request(), Servlets.response().servlet());
        Servlets.Response exported = Servlets.response();
        controller.exportSettings(null, exported.servlet());
        assertThat(exported.contentType()).isEqualTo("application/json");
        assertThat(exported.body()).contains("\"vulnerability-threshold\"").contains("\"HIGH\"");

        Servlets.Response missing = Servlets.response();
        controller.importSettings(null, null, null, request(), missing.servlet());
        assertThat(missing.status()).isEqualTo(400);

        Servlets.Response bad = Servlets.response();
        controller.importSettings(null, null, Map.of(SettingsDocuments.moduleOf("vulnerability-threshold"),
                Map.of("vulnerability-threshold", "SEVERE")), request(), bad.servlet());
        assertThat(bad.status()).isEqualTo(400);
        assertThat(bad.body()).contains("does not resolve");

        Servlets.Response pinned = Servlets.response();
        controller.importSettings(null, null, Map.of(SettingsDocuments.moduleOf(PINNED), Map.of(PINNED, "ALLOW")),
                request(), pinned.servlet());
        assertThat(pinned.status()).isEqualTo(409);
        assertThat(pinned.body()).contains(PINNED);
        assertThat(settings.overrides()).containsEntry("vulnerability-threshold", "HIGH");

        Servlets.Response restored = Servlets.response();
        controller.importSettings(null, null, Map.of(SettingsDocuments.moduleOf("vulnerability-threshold"), Map.of("vulnerability-threshold", "MEDIUM")),
                request(), restored.servlet());
        assertThat(restored.status()).isEqualTo(200);
        assertThat(settings.overrides()).containsEntry("vulnerability-threshold", "MEDIUM");
        assertThat(audit.actions()).endsWith("settings.import");
    }

    @Test
    void a_tenant_slice_is_exported_and_restored_on_its_own() throws IOException {
        Servlets.Response restored = Servlets.response();
        controller.importSettings(null, "acme", Map.of(SettingsDocuments.moduleOf("deny-list"), Map.of("deny-list", "pkg:npm/evil")),
                request(), restored.servlet());
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
                new ConfigController.NamedValueRequest("writable"), request(), writable.servlet());
        Servlets.Response nonsense = Servlets.response();
        controller.setRepositoryDefinition("broken", null, null,
                new ConfigController.NamedValueRequest("sometimes maybe"), request(), nonsense.servlet());
        Servlets.Response plaintext = Servlets.response();
        controller.setRepositoryDefinition("mirror", null, null,
                new ConfigController.NamedValueRequest("fallback http://mirror.example/maven2"),
                request(), plaintext.servlet());
        Servlets.Response unnamed = Servlets.response();
        controller.setRepositoryDefinition("..", null, null, new ConfigController.NamedValueRequest("writable"),
                request(), unnamed.servlet());

        assertThat(writable.status()).isEqualTo(200);
        assertThat(nonsense.status()).isEqualTo(400);
        assertThat(nonsense.body()).contains("Repository 'broken' has an invalid definition");
        assertThat(plaintext.status()).isEqualTo(400);
        assertThat(plaintext.body()).contains("Repository 'mirror' has a refused definition");
        assertThat(unnamed.status()).isEqualTo(400);
        assertThat(controller.repositoryDefinitions(null))
                .containsExactly(new ConfigController.NamedValue("releases", "writable"));
        assertThat(audit.actions()).containsExactly(AuditActions.REPOSITORY_SET);

        controller.removeRepositoryDefinition("releases", null, null, request(), Servlets.response().servlet());
        assertThat(controller.repositoryDefinitions(null)).isEmpty();
        assertThat(audit.actions()).endsWith(AuditActions.REPOSITORY_REMOVE);
    }

    @Test
    void a_repository_routes_itself_through_its_own_setting_and_a_tenant_has_no_definitions() throws IOException {
        Servlets.Response routed = Servlets.response();
        controller.setRepositorySetting("routing", "releases", null, new ConfigController.SettingRequest("writable"),
                request(), routed.servlet());
        Servlets.Response tenantDefinition = Servlets.response();
        controller.setRepositoryDefinition("releases", null, "acme",
                new ConfigController.NamedValueRequest("writable"), request(), tenantDefinition.servlet());

        assertThat(routed.status()).isEqualTo(200);
        ConfigController.SettingView routing = row(controller.repositorySettings("releases", request()), "routing");
        assertThat(routing.value()).isEqualTo("writable");
        assertThat(routing.overridden()).as("the repository set its own").isTrue();
        assertThat(controller.repositoryDefinitions(null)).as("the deployment's definitions are untouched").isEmpty();
        assertThat(tenantDefinition.status())
                .as("a tenant definition is refused: a repository is routed by its own setting").isEqualTo(400);
        assertThat(audit.rows()).singleElement()
                .satisfies(event -> assertThat(event.target()).endsWith("/releases/routing"));
    }

    @Test
    void a_repository_routing_is_validated_through_the_catalogue_and_is_the_operators_to_set() throws IOException {
        Servlets.Response plaintext = Servlets.response();
        controller.setRepositorySetting("routing", "mirror", null,
                new ConfigController.SettingRequest("fallback http://mirror.example/maven2"), request(),
                plaintext.servlet());
        Servlets.Response nonsense = Servlets.response();
        controller.setRepositorySetting("routing", "mirror", null,
                new ConfigController.SettingRequest("sometimes maybe"), request(), nonsense.servlet());
        Servlets.Response tenantWide = Servlets.response();
        controller.setSetting("routing", null, "acme", new ConfigController.SettingRequest("writable"), request(),
                tenantWide.servlet());
        Servlets.Response notTheOperator = Servlets.response();
        controller(UpstreamCredentialSource.NONE, false).setRepositorySetting("routing", "mirror", null,
                new ConfigController.SettingRequest("writable"), request(), notTheOperator.servlet());

        assertThat(plaintext.status()).as("a plaintext upstream is refused, as every write surface refuses it")
                .isEqualTo(400);
        assertThat(nonsense.status()).as("a definition the parser refuses").isEqualTo(400);
        assertThat(tenantWide.status()).as("a routing has no tenant-wide value").isEqualTo(400);
        assertThat(notTheOperator.status()).as("a routing is the deployment operator's to set").isEqualTo(400);
        assertThat(row(controller.repositorySettings("mirror", request()), "routing").overridden())
                .as("nothing refused was stored").isFalse();
        assertThat(audit.rows()).isEmpty();
    }

    @Test
    void a_format_upstream_must_be_an_https_url() throws IOException {
        Servlets.Response https = Servlets.response();
        controller.setUpstream("npm", null, null, new ConfigController.NamedValueRequest("https://registry.npmjs.org"),
                request(), https.servlet());
        Servlets.Response plaintext = Servlets.response();
        controller.setUpstream("pypi", null, null, new ConfigController.NamedValueRequest("http://pypi.example/"),
                request(), plaintext.servlet());
        Servlets.Response blank = Servlets.response();
        controller.setUpstream("go", null, null, new ConfigController.NamedValueRequest(" "),
                request(), blank.servlet());
        Servlets.Response malformed = Servlets.response();
        controller.setUpstream("go", null, null, new ConfigController.NamedValueRequest("https://bad host/"),
                request(), malformed.servlet());

        assertThat(https.status()).isEqualTo(200);
        assertThat(plaintext.status()).isEqualTo(400);
        assertThat(plaintext.body()).contains("The 'pypi' upstream 'http://pypi.example/' is refused");
        assertThat(blank.status()).isEqualTo(400);
        assertThat(malformed.status()).isEqualTo(400);
        assertThat(controller.upstreams(null))
                .containsExactly(new ConfigController.NamedValue("npm", "https://registry.npmjs.org"));

        controller.removeUpstream("npm", null, null, request(), Servlets.response().servlet());
        assertThat(controller.upstreams(null)).isEmpty();
        assertThat(audit.actions()).containsExactly(AuditActions.UPSTREAM_SET, AuditActions.UPSTREAM_REMOVE);
    }

    @Test
    void upstream_credentials_answer_501_where_no_module_holds_them() throws IOException {
        Servlets.Response list = Servlets.response();
        assertThat(controller.upstreamCredentialHosts(list.servlet())).isNull();
        Servlets.Response set = Servlets.response();
        controller.setUpstreamCredential("nexus.internal", null,
                new ConfigController.UpstreamAuthRequest("bearer", null, null, "t0ken", null),
                request(), set.servlet());
        Servlets.Response remove = Servlets.response();
        controller.removeUpstreamCredential("nexus.internal", null, request(), remove.servlet());

        assertThat(List.of(list.status(), set.status(), remove.status())).containsOnly(501);
        assertThat(list.body()).isEqualTo("upstream credentials are not installed on this deployment");
    }

    @Test
    void an_upstream_credential_is_stored_listed_by_host_and_removed() throws IOException {
        ConfigController keyed = controller(new StoreUpstreamCredentials(store, Duration.ofSeconds(30),
                SecretCipher.of("k1:" + Base64.getEncoder().encodeToString(new byte[32]))));
        Servlets.Response set = Servlets.response();
        keyed.setUpstreamCredential("nexus.internal", "operator",
                new ConfigController.UpstreamAuthRequest("bearer", null, null, "t0ken", null), request(),
                        set.servlet());
        Servlets.Response incomplete = Servlets.response();
        keyed.setUpstreamCredential("other.internal", null,
                new ConfigController.UpstreamAuthRequest("basic", "user", null, null, null), request(),
                        incomplete.servlet());

        assertThat(set.status()).isEqualTo(200);
        assertThat(incomplete.status()).isEqualTo(400);
        assertThat(keyed.upstreamCredentialHosts(Servlets.response().servlet())).containsExactly("nexus.internal");

        keyed.removeUpstreamCredential("nexus.internal", null, request(), Servlets.response().servlet());
        assertThat(keyed.upstreamCredentialHosts(Servlets.response().servlet())).isEmpty();
        assertThat(audit.actions()).containsExactly(AuditActions.UPSTREAM_AUTH_SET, AuditActions.UPSTREAM_AUTH_REMOVE);
    }

    @Test
    void an_upstream_credential_is_refused_where_nothing_can_encrypt_it() throws IOException {
        ConfigController unkeyed = controller(new StoreUpstreamCredentials(store, Duration.ofSeconds(30),
                SecretCipher.of(null)));
        Servlets.Response response = Servlets.response();

        unkeyed.setUpstreamCredential("nexus.internal", null,
                new ConfigController.UpstreamAuthRequest("bearer", null, null, "t0ken", null), request(),
                        response.servlet());

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body()).contains("JENREPO_SECRETS_KEY");
        assertThat(audit.rows()).isEmpty();
    }

    @Test
    void a_repository_is_created_once_described_and_deleted() throws IOException {
        Servlets.Response created = Servlets.response();
        controller.createRepository("files", "key", new ConfigController.RepositoryRequest("raw", "Build outputs", null),
                Servlets.request("PUT", "/repository/default/files"), created.servlet());
        Servlets.Response again = Servlets.response();
        controller.createRepository("files", "key", new ConfigController.RepositoryRequest("raw", null, null),
                Servlets.request("PUT", "/repository/default/files"), again.servlet());

        assertThat(created.status()).isEqualTo(201);
        assertThat(again.status()).as("it already holds that type").isEqualTo(200);
        ArtifactStore repository = repositories.store("default", "files");
        assertThat(RepositoryDocument.read(repository)).hasValueSatisfying(document -> {
            assertThat(document.format()).isEqualTo("raw");
            assertThat(document.description()).isEqualTo("Build outputs");
        });

        Servlets.Response described = Servlets.response();
        controller.createRepository("files", "key", new ConfigController.RepositoryRequest(null, "Nightly outputs", null),
                Servlets.request("PUT", "/repository/default/files"), described.servlet());
        assertThat(described.status()).isEqualTo(200);
        assertThat(RepositoryDocument.read(repository).orElseThrow().description()).isEqualTo("Nightly outputs");

        Servlets.Response deleted = Servlets.response();
        controller.deleteRepository("files", "key", Servlets.request("DELETE", "/repository/default/files"),
                deleted.servlet());
        assertThat(deleted.status()).isEqualTo(202);
        assertThat(deleted.body()).startsWith("Deleting repository 'files'");
        assertThat(RepositoryDocument.read(repository)).as("it stops answering before the answer").isEmpty();
        assertThat(audit.actions()).as("a description given at creation is in the document the creation writes")
                .containsExactly(AuditActions.REPOSITORY_CREATE, AuditActions.REPOSITORY_DESCRIBE,
                        AuditActions.REPOSITORY_DELETE);
    }

    @Test
    void a_deletion_reads_present_then_running_then_gone() throws Exception {
        controller.createRepository("files", "key", new ConfigController.RepositoryRequest("raw", null, null),
                Servlets.request("PUT", "/repository/default/files"), Servlets.response().servlet());
        ArtifactStore repository = repositories.store("default", "files");
        repository.write("raw/a.txt", new ByteArrayInputStream("a".getBytes(StandardCharsets.UTF_8)));
        assertThat(controller.repositoryDeletion("files", request()).state()).isEqualTo("present");

        // A deletion begun and not yet purged - a node that stopped between the two halves - reads as running.
        RepositoryRemoval.begin(repository);
        ConfigController.Deletion running = controller.repositoryDeletion("files", request());
        assertThat(running.state()).isEqualTo("running");
        assertThat(running.startedAt()).isNotNull();
        assertThat(running.failure()).isNull();

        Servlets.Response resumed = Servlets.response();
        controller.deleteRepository("files", "key", Servlets.request("DELETE", "/repository/default/files"),
                resumed.servlet());
        assertThat(resumed.status()).isEqualTo(202);
        Instant deadline = Instant.now().plusSeconds(30);
        while (!controller.repositoryDeletion("files", request()).state().equals("gone")) {
            assertThat(Instant.now()).as("the purge finished within 30 seconds; the scope holds %s",
                    repository.list("")).isBefore(deadline);
            Thread.sleep(50);
        }
        assertThat(controller.repositoryDeletion("never", request()).state())
                .as("a name that never held a repository reads the same as one whose deletion finished")
                .isEqualTo("gone");
        assertThatThrownBy(() -> controller.repositoryDeletion("../up", request()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_repository_created_with_its_settings_holds_them_from_its_first_request() throws IOException {
        Servlets.Response created = Servlets.response();
        controller.createRepository("libs", "key", new ConfigController.RepositoryRequest("raw", "Build outputs",
                        Map.of("keep-last", "3", "routing", "writable")),
                Servlets.request("PUT", "/repository/default/libs"), created.servlet());

        assertThat(created.status()).isEqualTo(201);
        ArtifactStore repository = repositories.store("default", "libs");
        assertThat(RepositoryDocument.read(repository)).hasValueSatisfying(document -> {
            assertThat(document.format()).isEqualTo("raw");
            assertThat(document.description()).isEqualTo("Build outputs");
        });
        assertThat(StoredSettings.read(repository, Setting.Scope.REPOSITORY))
                .containsEntry("keep-last", "3").containsEntry("routing", "writable");
        assertThat(audit.actions()).as("the settings are written, and recorded, before what makes the repository exist")
                .containsExactly(AuditActions.SETTING_SET, AuditActions.SETTING_SET, AuditActions.REPOSITORY_CREATE);
    }

    @Test
    void a_creation_with_a_refused_setting_writes_nothing_at_all() throws IOException {
        Servlets.Response refused = Servlets.response();
        controller.createRepository("libs", "key", new ConfigController.RepositoryRequest("raw", "Build outputs",
                        Map.of("keep-last", "-1", "max-age", "PT0S", "routing", "writable")),
                Servlets.request("PUT", "/repository/default/libs"), refused.servlet());

        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.body()).as("every refused value is named, not the first")
                .contains("keep-last").contains("max-age");
        assertThat(written("default/libs")).as("no document, and no setting").isEmpty();
        assertThat(audit.rows()).isEmpty();

        Servlets.Response notTheOperator = Servlets.response();
        controller(UpstreamCredentialSource.NONE, false).createRepository("libs", "key",
                new ConfigController.RepositoryRequest("raw", null, Map.of("routing", "writable")),
                Servlets.request("PUT", "/repository/default/libs"), notTheOperator.servlet());
        assertThat(notTheOperator.status()).as("the routing is the deployment operator's to set").isEqualTo(400);
        assertThat(written("default/libs")).isEmpty();
    }

    @Test
    void a_creation_with_settings_only_creates_and_never_changes_a_repository() throws IOException {
        controller.createRepository("libs", null, new ConfigController.RepositoryRequest("raw", null, null),
                Servlets.request("PUT", "/repository/default/libs"), Servlets.response().servlet());
        Servlets.Response again = Servlets.response();
        controller.createRepository("libs", "key", new ConfigController.RepositoryRequest("raw", null,
                        Map.of("keep-last", "3")),
                Servlets.request("PUT", "/repository/default/libs"), again.servlet());
        Servlets.Response alone = Servlets.response();
        controller.createRepository("libs", "key", new ConfigController.RepositoryRequest(null, null,
                        Map.of("keep-last", "3")),
                Servlets.request("PUT", "/repository/default/libs"), alone.servlet());

        assertThat(again.status()).isEqualTo(409);
        assertThat(again.body()).contains("already exists").contains("/api/repository/settings/");
        assertThat(alone.status()).as("settings with no format are no creation").isEqualTo(400);
        assertThat(StoredSettings.read(repositories.store("default", "libs"), Setting.Scope.REPOSITORY)).isEmpty();
    }

    @Test
    void a_creation_whose_settings_cannot_be_written_leaves_no_repository() {
        ArtifactStore repository = repositories.store("default", "libs");

        assertThatThrownBy(() -> RepositoryType.create(repository, "raw", "", () -> {
            throw new IOException("the store went away");
        })).isInstanceOf(IOException.class);
        assertThat(RepositoryDocument.exists(repository))
                .as("the settings are written before the document that makes the repository exist").isFalse();
    }

    /** Every object stored under {@code prefix}. */
    private List<String> written(String prefix) throws IOException {
        Path under = root.resolve(prefix);
        if (!Files.exists(under)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(under)) {
            return files.filter(Files::isRegularFile).map(Path::toString).toList();
        }
    }

    @Test
    void creation_refuses_a_type_nothing_serves_and_a_repository_still_being_deleted() throws IOException {
        Servlets.Response unknown = Servlets.response();
        controller.createRepository("files", null, new ConfigController.RepositoryRequest("cobol", null, null),
                Servlets.request("PUT", "/repository/default/files"), unknown.servlet());
        assertThat(unknown.status()).isEqualTo(400);
        assertThat(unknown.body()).contains("'cobol' is not a format a repository can hold here");

        ArtifactStore repository = repositories.store("default", "gone");
        controller.createRepository("gone", null, new ConfigController.RepositoryRequest("raw", null, null),
                Servlets.request("PUT", "/repository/default/gone"), Servlets.response().servlet());
        RepositoryRemoval.begin(repository);
        Servlets.Response removing = Servlets.response();
        controller.createRepository("gone", null, new ConfigController.RepositoryRequest("raw", null, null),
                Servlets.request("PUT", "/repository/default/gone"), removing.servlet());
        assertThat(removing.status()).isEqualTo(409);
        assertThat(removing.body()).contains("is still being deleted");
    }

    @Test
    void a_creation_naming_a_tenant_the_routing_refuses_is_refused_before_its_body_is_judged() {
        // A description past the limit is a 400 once the request may be answered at all; naming another tenant, it
        // must meet the routing's refusal first, as an empty body does.
        String tooLong = "x".repeat(RepositoryDocument.DESCRIPTION_LIMIT + 1);
        for (ConfigController.RepositoryRequest body : Arrays.asList(
                new ConfigController.RepositoryRequest("raw", tooLong, null), null)) {
            Servlets.Response answered = Servlets.response();
            assertThatThrownBy(() -> controller.createRepository("files", null, body,
                    Servlets.request("PUT", "/repository/elsewhere/files"), answered.servlet()))
                    .isInstanceOf(ResponseStatusException.class);
        }
        assertThat(audit.rows()).isEmpty();
    }

    @Test
    void describing_or_deleting_a_repository_that_does_not_exist_is_a_404() throws IOException {
        Servlets.Response described = Servlets.response();
        controller.createRepository("nowhere", null, new ConfigController.RepositoryRequest(null, "text", null),
                Servlets.request("PUT", "/repository/default/nowhere"), described.servlet());
        Servlets.Response deleted = Servlets.response();
        controller.deleteRepository("nowhere", null, Servlets.request("DELETE", "/repository/default/nowhere"),
                deleted.servlet());

        assertThat(described.status()).isEqualTo(404);
        assertThat(deleted.status()).isEqualTo(404);
        assertThat(audit.rows()).isEmpty();
    }
}
