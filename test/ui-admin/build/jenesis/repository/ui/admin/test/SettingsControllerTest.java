package build.jenesis.repository.ui.admin.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.servlet.testkit.Servlets;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.ui.admin.web.SettingsController;
import build.jenesis.repository.ui.store.SettingsAdmin;
import org.springframework.core.env.StandardEnvironment;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The console's settings screens over a real store: a value saved on the screen is the stored override and a blank
 * one clears it, a save returns only to a screen that asked for it, the selected tenant's slice is edited on its own
 * and needs a tenant to be selected, the backup downloads what was saved and restores from an uploaded bundle read
 * with the shared multipart reader - refusing a bundle that is not a document of documents, or no file at all - and
 * the routing and upstream forms land in the deployment's documents or the tenant's.
 */
class SettingsControllerTest {

    private static final String TENANT = "acme";

    @TempDir
    Path root;

    private SettingsAdmin settings;
    private String selected;
    private SettingsController controller;

    @BeforeEach
    void wire() {
        ArtifactStore store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        settings = new SettingsAdmin(store);
        selected = TENANT;
        controller = new SettingsController(settings, () -> selected, new StandardEnvironment());
    }

    private static Object flash(RedirectAttributesModelMap redirect, String name) {
        return redirect.getFlashAttributes().get(name);
    }

    private static String effective(List<SettingsAdmin.Group> groups, String key) {
        return groups.stream().flatMap(group -> group.settings().stream())
                .filter(setting -> setting.key().equals(key)).findFirst().orElseThrow().value();
    }

    @Test
    void the_screens_render_what_the_catalogue_and_the_documents_hold() throws IOException {
        ExtendedModelMap list = new ExtendedModelMap();
        ExtendedModelMap modules = new ExtendedModelMap();

        assertThat(controller.list(list)).isEqualTo("settings");
        assertThat(controller.modules(modules)).isEqualTo("modules");
        assertThat(controller.backup()).isEqualTo("backup");

        assertThat((List<?>) list.get("groups")).isNotEmpty();
        assertThat((List<?>) modules.get("modules")).isNotEmpty();
        assertThat(modules).containsKey("orphanScannedAt");
    }

    @Test
    void a_saved_value_is_the_override_and_a_blank_one_reverts_it() throws IOException {
        RedirectAttributesModelMap saved = new RedirectAttributesModelMap();
        RedirectAttributesModelMap cleared = new RedirectAttributesModelMap();

        assertThat(controller.save("vulnerability-threshold", "HIGH", "/ui/setup", saved))
                .as("the setup guide saves through here and is returned to").isEqualTo("redirect:/ui/setup");
        assertThat(effective(settings.groups(), "vulnerability-threshold")).isEqualTo("HIGH");
        assertThat(controller.save("vulnerability-threshold", " ", "https://elsewhere.example/", cleared))
                .as("a return address no screen names is not followed").isEqualTo("redirect:/ui/settings");

        assertThat(flash(saved, "message")).isEqualTo("Updated vulnerability-threshold.");
        assertThat(flash(cleared, "message"))
                .isEqualTo("Cleared vulnerability-threshold; reverted to its default.");
        assertThat(effective(settings.groups(), "vulnerability-threshold")).isNotEqualTo("HIGH");
    }

    @Test
    void the_tenant_slice_is_edited_for_the_selected_tenant_alone() throws IOException {
        RedirectAttributesModelMap saved = new RedirectAttributesModelMap();
        ExtendedModelMap model = new ExtendedModelMap();

        assertThat(controller.saveTenant("vulnerability-threshold", "LOW", saved))
                .isEqualTo("redirect:/ui/settings/tenant");
        assertThat(controller.tenantSettings(model)).isEqualTo("tenant-settings");

        assertThat(flash(saved, "message")).isEqualTo("Updated vulnerability-threshold for tenant acme.");
        @SuppressWarnings("unchecked")
        List<SettingsAdmin.Group> groups = (List<SettingsAdmin.Group>) model.get("groups");
        assertThat(effective(groups, "vulnerability-threshold")).isEqualTo("LOW");
        assertThat(effective(settings.groups(), "vulnerability-threshold")).as("the deployment's is untouched")
                .isNotEqualTo("LOW");

        RedirectAttributesModelMap cleared = new RedirectAttributesModelMap();
        controller.saveTenant("vulnerability-threshold", "", cleared);
        assertThat(flash(cleared, "message")).asString().startsWith("Cleared vulnerability-threshold for tenant acme");
    }

    @Test
    void a_tenant_screen_with_no_tenant_selected_sends_the_reader_to_choose_one() {
        selected = null;

        assertThatThrownBy(() -> controller.tenantSettings(new ExtendedModelMap()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> controller.importTenant(Servlets.request("POST", "/ui/settings/tenant/import"),
                new RedirectAttributesModelMap())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void the_backup_downloads_what_was_saved_and_restores_from_an_upload() throws IOException {
        controller.save("vulnerability-threshold", "HIGH", "/ui/settings", new RedirectAttributesModelMap());
        Servlets.Response download = Servlets.response();

        controller.export(download.servlet());

        assertThat(download.contentType()).isEqualTo("application/json");
        assertThat(download.header("Content-Disposition")).contains("jenesis-settings.json");
        String bundle = download.body().replace("HIGH", "MEDIUM");
        assertThat(bundle).contains("MEDIUM");

        RedirectAttributesModelMap restored = new RedirectAttributesModelMap();
        assertThat(controller.importBundle(Servlets.multipart("/ui/settings/import", "bundle", "settings.json",
                bundle.getBytes(StandardCharsets.UTF_8)), restored)).isEqualTo("redirect:/ui/settings/backup");

        assertThat(flash(restored, "error")).isNull();
        assertThat(flash(restored, "message")).isEqualTo("Imported the deployment settings.");
        assertThat(effective(settings.groups(), "vulnerability-threshold")).isEqualTo("MEDIUM");
    }

    @Test
    void an_upload_that_is_not_a_bundle_is_refused_with_the_reason() throws IOException {
        RedirectAttributesModelMap array = new RedirectAttributesModelMap();
        RedirectAttributesModelMap flat = new RedirectAttributesModelMap();
        RedirectAttributesModelMap empty = new RedirectAttributesModelMap();
        RedirectAttributesModelMap none = new RedirectAttributesModelMap();

        controller.importBundle(upload("[1, 2]"), array);
        controller.importBundle(upload("{\"core\": \"HIGH\"}"), flat);
        controller.importBundle(upload(""), empty);
        controller.importBundle(Servlets.request("POST", "/ui/settings/import"), none);

        assertThat(flash(array, "error")).asString().contains("must be a JSON object of module documents");
        assertThat(flash(flat, "error")).asString().contains("module 'core' is not a settings document");
        assertThat(flash(empty, "error")).asString().contains("Choose a settings bundle file to import.");
        assertThat(flash(none, "error")).asString().contains("Choose a settings bundle file to import.");
    }

    @Test
    void a_tenant_bundle_restores_the_selected_tenants_slice() throws IOException {
        controller.saveTenant("vulnerability-threshold", "LOW", new RedirectAttributesModelMap());
        String document = new String(settings.exportBundle(), StandardCharsets.UTF_8);
        RedirectAttributesModelMap restored = new RedirectAttributesModelMap();
        RedirectAttributesModelMap refused = new RedirectAttributesModelMap();

        controller.importTenant(upload("{}"), restored);
        controller.importTenant(upload("not json"), refused);

        assertThat(flash(restored, "message")).isEqualTo("Imported the settings for tenant acme.");
        assertThat(flash(refused, "error")).asString().startsWith("Could not import the tenant settings bundle: ");
        assertThat(document).as("the deployment bundle carries the tenant's slice").contains("tenant:acme:");
    }

    @Test
    void the_routing_and_upstream_forms_land_in_the_deployment_or_the_tenant() throws IOException {
        ExtendedModelMap model = new ExtendedModelMap();
        RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

        controller.setRepository("central", "fallback https://repo1.maven.org/maven2", redirect);
        controller.setUpstream("npm", "https://registry.npmjs.org", false, new RedirectAttributesModelMap());
        RedirectAttributesModelMap tenantUpstream = new RedirectAttributesModelMap();
        controller.setUpstream("npm", "https://npm.acme.example", true, tenantUpstream);
        assertThat(controller.upstreams(model)).isEqualTo("upstreams");

        assertThat(flash(redirect, "message")).isEqualTo("Saved repository 'central'.");
        assertThat(flash(tenantUpstream, "message")).isEqualTo("Saved upstream for 'npm' for tenant 'acme'.");
        assertThat(model).containsEntry("routedTenant", TENANT);
        assertThat(model.get("repositories")).asInstanceOf(InstanceOfAssertFactories.MAP).containsOnlyKeys("central");
        assertThat(model.get("upstreams")).asInstanceOf(InstanceOfAssertFactories.MAP)
                .containsEntry("npm", "https://registry.npmjs.org");
        assertThat(model.get("tenantUpstreams")).asInstanceOf(InstanceOfAssertFactories.MAP)
                .containsEntry("npm", "https://npm.acme.example");

        controller.removeRepository("central", new RedirectAttributesModelMap());
        controller.removeUpstream("npm", true, new RedirectAttributesModelMap());
        assertThat(settings.repositories()).isEmpty();
        assertThat(settings.upstreams(TENANT)).isEmpty();
        assertThat(settings.upstreams()).as("the deployment's upstream stays").containsKey("npm");
    }

    @Test
    void purging_a_module_no_manifest_names_purges_nothing_and_says_so() throws IOException {
        RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

        assertThat(controller.purgeOrphanedData("no.such.module", redirect)).isEqualTo("redirect:/ui/settings/modules");

        assertThat(flash(redirect, "message"))
                .isEqualTo("No storage-manifest entry names no.such.module; nothing was purged.");
    }

    private static jakarta.servlet.http.HttpServletRequest upload(String json) {
        return Servlets.multipart("/ui/settings/import", "bundle", "settings.json",
                json.getBytes(StandardCharsets.UTF_8));
    }
}
