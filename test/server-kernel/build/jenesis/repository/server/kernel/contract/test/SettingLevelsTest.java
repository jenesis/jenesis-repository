package build.jenesis.repository.server.kernel.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.server.RepositoryProperties;
import build.jenesis.repository.server.kernel.LiveConfig;
import build.jenesis.repository.server.kernel.Settings;
import build.jenesis.repository.server.kernel.SettingsEditor;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;
import build.jenesis.repository.settings.SettingsScopes;
import build.jenesis.repository.settings.StoredSettings;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The levels a setting's value may be stored at, and how a value resolves across them: a repository setting is its
 * repository's own value over its tenant's over the deployment's over its default, each stored in that level's own
 * settings documents; a local one has no wider value; and every write asks the catalogue first. Over a real
 * filesystem store and the catalogue this module's graph carries, whose retention rules are repository settings.
 */
class SettingLevelsTest {

    @TempDir
    Path root;

    private ArtifactStore store;
    private Settings settings;
    private LiveConfig live;
    private SettingsEditor editor;

    /** Who these writes are recorded against. */
    private static final SettingsEditor.Actor ACTOR = new SettingsEditor.Actor("acme", "test");

    @BeforeEach
    void setUp() throws IOException {
        store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null);
        settings = new Settings(store);
        live = new LiveConfig(settings, new RepositoryProperties(), AdvisorySource.none(), _ -> null);
        editor = new SettingsEditor(settings, _ -> Optional.empty(), live, AuditTrail.none());
    }

    private static Setting of(Setting.Scope scope) {
        return new Setting("example", "Example", "Example", "An example.", Setting.Kind.STRING, "", true, scope);
    }

    @Test
    void a_setting_is_stored_at_its_own_level_and_every_wider_one_unless_it_is_local() {
        assertThat(Stream.of(Setting.Scope.values()).filter(of(Setting.Scope.GLOBAL)::settableAt))
                .containsExactly(Setting.Scope.GLOBAL);
        assertThat(Stream.of(Setting.Scope.values()).filter(of(Setting.Scope.TENANT)::settableAt))
                .containsExactly(Setting.Scope.GLOBAL, Setting.Scope.TENANT);
        assertThat(Stream.of(Setting.Scope.values()).filter(of(Setting.Scope.REPOSITORY)::settableAt))
                .as("a repository setting's tenant and deployment values are the defaults a repository inherits")
                .containsExactly(Setting.Scope.GLOBAL, Setting.Scope.TENANT, Setting.Scope.REPOSITORY);
        assertThat(Stream.of(Setting.Scope.values()).filter(of(Setting.Scope.PROJECT).local()::settableAt))
                .as("a local one is its own level's alone").containsExactly(Setting.Scope.PROJECT);
        assertThatThrownBy(() -> of(Setting.Scope.TENANT).local())
                .as("only a repository or project setting has a wider default to go without")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_duration_rule_that_may_be_inherited_takes_none_to_switch_it_off() {
        Setting rule = new Setting("rule", "Example", "Rule", "A rule.", Setting.Kind.DURATION_OR_NONE, "", true,
                Setting.Scope.REPOSITORY);

        assertThat(rule.parses("none")).isTrue();
        assertThat(rule.parses("P30D")).isTrue();
        assertThat(rule.parses("30d")).isTrue();
        assertThat(rule.parses("never")).isFalse();
        assertThat(new Setting("plain", "Example", "Plain", "A plain duration.", Setting.Kind.DURATION, "", true)
                .parses("none")).as("a plain duration dial does not honour it, so it does not take it").isFalse();
    }

    @Test
    void every_write_asks_the_catalogue_which_refuses_by_level_by_kind_and_by_what_its_reader_honours() {
        UnaryOperator<String> deployment = _ -> null;

        assertThat(SettingsContributor.refusal("keep-last", "5", Setting.Scope.REPOSITORY, deployment)).isEmpty();
        assertThat(SettingsContributor.refusal("keep-last", "5", Setting.Scope.TENANT, deployment))
                .as("a tenant default for every repository").isEmpty();
        assertThat(SettingsContributor.refusal("keep-last", "many", Setting.Scope.REPOSITORY, deployment))
                .as("the kind refuses it").isPresent();
        assertThat(SettingsContributor.refusal("keep-last", "-1", Setting.Scope.REPOSITORY, deployment))
                .as("the kind takes it, the policy does not").get().asString().contains("keep-last");
        assertThat(SettingsContributor.refusal("max-age", "PT0S", Setting.Scope.REPOSITORY, deployment))
                .as("a zero age would evict everything but each coordinate's newest").isPresent();
        assertThat(SettingsContributor.refusal("max-age", "none", Setting.Scope.REPOSITORY, deployment)).isEmpty();
        assertThat(SettingsContributor.refusal("default-tenant", "acme", Setting.Scope.REPOSITORY, deployment))
                .as("a deployment knob is not a repository's").get().asString().contains("for a repository");
        assertThat(SettingsContributor.refusal("no-such-setting", "x", Setting.Scope.GLOBAL, deployment))
                .get().asString().contains("Unknown setting");
        assertThat(SettingsContributor.refusal("keep-last", "", Setting.Scope.REPOSITORY, deployment))
                .as("a blank value clears").isEmpty();
    }

    @Test
    void a_repository_value_resolves_over_its_tenants_over_the_deployments_over_the_default() throws IOException {
        assertThat(live.effective("acme", "libs", "keep-last", "0")).isEqualTo("0");
        settings.set("keep-last", "1");
        assertThat(live.effective("acme", "libs", "keep-last", "0")).isEqualTo("1");
        settings.set("acme", "keep-last", "3");
        assertThat(live.effective("acme", "libs", "keep-last", "0")).isEqualTo("3");
        editor.repository("acme", "libs", Map.of("keep-last", "5"), false, ACTOR);
        assertThat(live.effective("acme", "libs", "keep-last", "0")).isEqualTo("5");
        assertThat(live.effective("acme", "other", "keep-last", "0")).as("another repository inherits")
                .isEqualTo("3");
        assertThat(live.retention("acme", "libs").keepLast()).as("the policy a sweep runs under").isEqualTo(5);

        editor.repository("acme", "libs", Map.of("keep-last", ""), false, ACTOR);
        assertThat(live.effective("acme", "libs", "keep-last", "0")).as("cleared, it inherits again")
                .isEqualTo("3");
    }

    @Test
    void a_repositorys_values_live_in_its_own_documents() throws IOException {
        editor.repository("acme", "libs", Map.of("max-age", "P30D"), false, ACTOR);

        assertThat(StoredSettings.read(store.scope("acme").scope("libs"))).containsEntry("max-age", "P30D");
        assertThat(StoredSettings.read(store.scope("acme"))).as("not the tenant's").doesNotContainKey("max-age");
        assertThat(StoredSettings.read(store)).as("nor the deployment's").doesNotContainKey("max-age");
        assertThat(store.list("acme/libs/.system/config/settings")).allMatch(name -> name.endsWith(".json"));
    }

    @Test
    void a_repository_write_is_refused_whole_when_any_of_its_values_is_refused() throws IOException {
        assertThatThrownBy(() -> editor.repository("acme", "libs",
                Map.of("keep-last", "5", "max-age", "PT0S", "default-tenant", "acme"), false, ACTOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-age").hasMessageContaining("default-tenant");

        assertThat(StoredSettings.read(store.scope("acme").scope("libs"))).as("nothing is written").isEmpty();
    }

    @Test
    void a_one_time_move_never_overwrites_a_value_already_set() throws IOException {
        ArtifactStore libs = store.scope("acme").scope("libs");
        StoredSettings.write(libs, Map.of("keep-last", "7"));

        assertThat(StoredSettings.writeAbsent(libs, Map.of("keep-last", "2", "max-age", "P1D")))
                .containsExactly(Map.entry("max-age", "P1D"));
        assertThat(StoredSettings.read(libs)).containsEntry("keep-last", "7").containsEntry("max-age", "P1D");
    }

    @Test
    void a_project_resolves_over_its_tenant_over_the_deployment_straight_from_the_store() throws IOException {
        settings.set("project-size", "1");
        StoredSettings.write(store.scope("acme"), Map.of("project-size", "3"));
        ArtifactStore project = StoredSettings.project(store, "acme", "agents");

        assertThat(StoredSettings.projectChain(store, "acme", "agents").apply("project-size")).isEqualTo("3");
        StoredSettings.write(project, Map.of("project-size", "9"));
        assertThat(StoredSettings.projectChain(store, "acme", "agents").apply("project-size")).isEqualTo("9");
        assertThat(StoredSettings.projectChain(store, "globex", "agents").apply("project-size"))
                .as("another tenant's project inherits the deployment's").isEqualTo("1");
        assertThat(project.list(".system/config/settings")).isNotEmpty();
    }

    @Test
    void a_deployment_or_tenant_document_carries_only_what_that_level_may_hold() throws IOException {
        assertThat(SettingsScopes.settableAt("default-tenant", Setting.Scope.TENANT)).isFalse();
        assertThatThrownBy(() -> settings.set("acme", "default-tenant", "acme"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(SettingsScopes.settableOnly(Map.of("core", Map.of("keep-last", "2", "default-tenant", "x")),
                Setting.Scope.TENANT))
                .as("an export of a tenant's documents leaves out what a tenant may not hold")
                .containsExactly(Map.entry("core", new TreeMap<>(Map.of("keep-last", "2"))));
    }
}
