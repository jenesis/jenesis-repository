package build.jenesis.repository.ui.admin.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.server.kernel.SettingsEditor;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.cleanup.StoredReport;
import build.jenesis.repository.format.FormatMarks;
import build.jenesis.repository.inventory.DownloadTracker;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.RepositoryDocument;
import build.jenesis.repository.ui.BrowseRow;
import build.jenesis.repository.ui.admin.web.RepositoryAdminController;
import build.jenesis.repository.ui.store.RepositoryAdmin;
import build.jenesis.repository.ui.store.RepositoryBrowse;
import build.jenesis.repository.ui.store.RepositoryImports;
import build.jenesis.repository.ui.store.RepositoryLifecycle;
import build.jenesis.repository.ui.store.SettingsAdmin;
import build.jenesis.repository.ui.store.TenantLimits;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The repository screens of the console, driven over the console's own services on a real store: a repository is
 * created with its type, listed, described, routed for its tenant and deleted only through the typed confirmation;
 * its overview, browse tree, artifact and coordinate pages render from what was published; its pins, retention,
 * staging and migration pages read what they name; and the tenant's limits page sets the quota and the
 * rate ceiling. Each handler answers a view name and a model, so a suite asserts both - the screen it chose and what
 * it put there - and the flash line an operator reads after a write.
 */
class RepositoryAdminControllerTest {

    private static final String TENANT = "acme";

    @TempDir
    Path root;

    private ArtifactStore store;
    private SettingsAdmin settings;
    private TenantLimits limits;
    private RepositoryAdminController controller;

    @BeforeEach
    void wire() throws IOException {
        store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenreg.filesystem.root".equals(key) ? root.toString() : null);
        settings = new SettingsAdmin(store);
        limits = new TenantLimits(store, settings, () -> TENANT, ObservationRegistry.NOOP, AuditTrail.none(),
                () -> "operator");
        controller = new RepositoryAdminController(
                new RepositoryAdmin(store, () -> TENANT, ObservationRegistry.NOOP),
                new RepositoryBrowse(store, () -> TENANT, ObservationRegistry.NOOP), limits,
                new RepositoryImports(store, () -> TENANT, ObservationRegistry.NOOP, AuditTrail.none(),
                        () -> "operator", SettingsEditor.over(store, _ -> Optional.empty(), AuditTrail.none(),
                                _ -> null)),
                new RepositoryLifecycle(store, () -> TENANT, ObservationRegistry.NOOP, AuditTrail.none(),
                        () -> "operator", settings),
                settings, FormatMarks.installed(),
                new StaticListableBeanFactory().getBeanProvider(DownloadTracker.class), () -> TENANT);
    }

    /** A super-admin's session - the operator, who alone routes a repository. */
    private static final Authentication OPERATOR = new UsernamePasswordAuthenticationToken("root", null,
            List.of(new SimpleGrantedAuthority("ROLE_SUPERADMIN")));

    /** A tenant editor's session. */
    private static final Authentication EDITOR = new UsernamePasswordAuthenticationToken("ada", null,
            List.of(new SimpleGrantedAuthority("ROLE_USER")));

    private String route(String repository, String definition, RedirectAttributesModelMap redirect)
            throws IOException {
        return controller.saveSetting(repository, "routing", definition, "/ui/repositories/" + repository, OPERATOR,
                redirect);
    }

    private ArtifactStore repository(String name) {
        return store.scope(TENANT).scope(name);
    }

    private void create(String name, String format) throws IOException {
        controller.create(name, format, "", new RedirectAttributesModelMap());
    }

    private void publish(String repository, String path, String content) throws IOException {
        Publication publication = new Publication(repository(repository));
        publication.link(path, publication.storeBlob(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))));
    }

    private static Object flash(RedirectAttributesModelMap redirect, String name) {
        return redirect.getFlashAttributes().get(name);
    }

    @Test
    void a_repository_is_created_with_its_type_and_creating_it_again_changes_nothing() throws IOException {
        RedirectAttributesModelMap created = new RedirectAttributesModelMap();
        RedirectAttributesModelMap again = new RedirectAttributesModelMap();

        assertThat(controller.create(" files ", "raw", "Build outputs", created))
                .isEqualTo("redirect:/ui/repositories/files");
        controller.create("files", "raw", "", again);

        assertThat(flash(created, "message")).isEqualTo("Created raw repository 'files'.");
        assertThat(flash(again, "message")).isEqualTo("Repository 'files' already holds raw.");
        assertThat(RepositoryDocument.read(repository("files")).orElseThrow().description())
                .isEqualTo("Build outputs");
    }

    @Test
    void a_type_that_does_not_hold_what_is_there_is_refused() throws IOException {
        create("files", "raw");
        RedirectAttributesModelMap conflict = new RedirectAttributesModelMap();
        RedirectAttributesModelMap unknown = new RedirectAttributesModelMap();

        assertThat(controller.create("files", "maven", "", conflict)).isEqualTo("redirect:/ui/repositories");
        controller.create("other", "cobol", "", unknown);

        assertThat(flash(conflict, "error")).asString().startsWith("Repository 'files' holds a type maven does not");
        assertThat(flash(unknown, "error")).isNotNull();
        assertThat(RepositoryDocument.read(repository("files")).orElseThrow().format()).isEqualTo("raw");
    }

    @Test
    void the_list_names_each_repository_with_its_type_and_the_warnings_its_routing_raises() throws IOException {
        controller.create("files", "raw", "Build outputs", new RedirectAttributesModelMap());
        create("libs", "maven");
        route("mirror", "fallback https://mirror.example/maven2 unscreened", new RedirectAttributesModelMap());
        create("mirror", "maven");
        ExtendedModelMap model = new ExtendedModelMap();

        assertThat(controller.list(model)).isEqualTo("repositories");

        @SuppressWarnings("unchecked")
        List<RepositoryAdminController.RepositoryRow> rows =
                (List<RepositoryAdminController.RepositoryRow>) model.get("repositories");
        assertThat(rows).extracting(RepositoryAdminController.RepositoryRow::name)
                .containsExactlyInAnyOrder("files", "libs", "mirror");
        assertThat(rows).filteredOn(row -> row.name().equals("files")).singleElement().satisfies(row -> {
            assertThat(row.format()).isEqualTo("raw");
            assertThat(row.description()).isEqualTo("Build outputs");
            assertThat(row.created()).isNotBlank();
            assertThat(row.removing()).isFalse();
        });
        assertThat(model.get("formats")).asInstanceOf(InstanceOfAssertFactories.LIST).contains("raw", "maven");
        @SuppressWarnings("unchecked")
        List<RepositoryAdminController.RepositoryWarning> warnings =
                (List<RepositoryAdminController.RepositoryWarning>) model.get("definitionWarnings");
        assertThat(warnings).as("an unscreened upstream is a warning, not a refusal").singleElement()
                .satisfies(warning -> assertThat(warning.repository()).isEqualTo("mirror"));
    }

    @Test
    void a_description_is_changed_and_a_missing_repository_says_so() throws IOException {
        create("files", "raw");
        RedirectAttributesModelMap described = new RedirectAttributesModelMap();
        RedirectAttributesModelMap missing = new RedirectAttributesModelMap();
        RedirectAttributesModelMap tooLong = new RedirectAttributesModelMap();

        controller.describe("files", "Nightly outputs", described);
        assertThat(controller.describe("nowhere", "text", missing)).isEqualTo("redirect:/ui/repositories");
        controller.describe("files", "x".repeat(RepositoryDocument.DESCRIPTION_LIMIT + 1), tooLong);

        assertThat(flash(described, "message")).isEqualTo("Updated the description of 'files'.");
        assertThat(flash(missing, "error")).isEqualTo("There is no repository 'nowhere'.");
        assertThat(flash(tooLong, "error")).isNotNull();
        assertThat(RepositoryDocument.read(repository("files")).orElseThrow().description())
                .isEqualTo("Nightly outputs");
    }

    @Test
    void a_repository_routing_is_its_own_setting_stored_validated_removed_and_the_operators() throws IOException {
        RedirectAttributesModelMap routed = new RedirectAttributesModelMap();
        RedirectAttributesModelMap refused = new RedirectAttributesModelMap();
        RedirectAttributesModelMap notTheOperator = new RedirectAttributesModelMap();
        RedirectAttributesModelMap removed = new RedirectAttributesModelMap();
        create("central", "maven");

        assertThat(route("central", "fallback https://repo1.maven.org/maven2", routed))
                .isEqualTo("redirect:/ui/repositories/central");
        route("broken", "sometimes maybe", refused);
        controller.saveSetting("central", "routing", "writable", "", EDITOR, notTheOperator);

        assertThat(flash(routed, "message")).isEqualTo("Saved 'routing' for 'central'.");
        assertThat(flash(refused, "error")).isNotNull();
        assertThat(flash(notTheOperator, "error")).asString().contains("operator");
        assertThat(settings.routing(TENANT, "central").layer()).isEqualTo(SettingsAdmin.Layer.REPOSITORY);
        assertThat(settings.routing(TENANT, "central").specification())
                .as("the editor's routing was refused").isEqualTo("fallback https://repo1.maven.org/maven2");
        assertThat(settings.routing(TENANT, "broken").layer()).isEqualTo(SettingsAdmin.Layer.NONE);

        route("central", "", removed);
        assertThat(flash(removed, "message")).asString().contains("inherited again");
        assertThat(settings.routing(TENANT, "central").layer()).isEqualTo(SettingsAdmin.Layer.NONE);
    }

    @Test
    void a_repository_is_deleted_only_with_its_typed_confirmation() throws IOException {
        create("files", "raw");
        route("files", "writable", new RedirectAttributesModelMap());
        RedirectAttributesModelMap unconfirmed = new RedirectAttributesModelMap();
        RedirectAttributesModelMap confirmed = new RedirectAttributesModelMap();
        RedirectAttributesModelMap absent = new RedirectAttributesModelMap();

        assertThat(controller.delete("files", "delete other", unconfirmed))
                .isEqualTo("redirect:/ui/repositories/files");
        assertThat(RepositoryDocument.read(repository("files"))).as("nothing was deleted").isPresent();
        controller.delete("files", " delete files ", confirmed);
        controller.delete("nowhere", "delete nowhere", absent);

        assertThat(flash(unconfirmed, "error")).isEqualTo("Nothing was deleted: type \"delete files\" to confirm.");
        assertThat(flash(confirmed, "message")).asString().startsWith("Deleting repository 'files'.");
        assertThat(RepositoryDocument.read(repository("files"))).isEmpty();
        assertThat(flash(absent, "error")).isEqualTo("There is no repository 'nowhere'.");
    }

    @Test
    void the_limits_page_shows_and_sets_the_tenants_limits_through_the_catalogue() throws IOException {
        RedirectAttributesModelMap quota = new RedirectAttributesModelMap();
        RedirectAttributesModelMap cleared = new RedirectAttributesModelMap();

        controller.saveLimit("tenant-quota", "4096", quota);
        ExtendedModelMap model = new ExtendedModelMap();

        assertThat(controller.limits(model)).isEqualTo("limits");
        assertThat(flash(quota, "message")).isEqualTo("Saved 'tenant-quota' for this tenant.");
        assertThat(model.get("quota")).isEqualTo(new TenantLimits.QuotaView(4096, 0));
        assertThat(model.get("groups")).asInstanceOf(InstanceOfAssertFactories.LIST).singleElement()
                .satisfies(group -> assertThat(((SettingsAdmin.Group) group).settings())
                        .extracting(SettingsAdmin.SettingView::key).contains("tenant-quota"));
        assertThatThrownBy(() -> controller.saveLimit("vulnerability-threshold", "LOW",
                new RedirectAttributesModelMap()))
                .as("a tenant's other settings are the operator's, not the limits page's")
                .isInstanceOf(IllegalArgumentException.class);

        controller.saveLimit("tenant-quota", "", cleared);
        assertThat(flash(cleared, "message")).isEqualTo("'tenant-quota' follows the deployment again.");
        assertThat(limits.quota().maxBytes()).isZero();
    }

    @Test
    void the_overview_carries_the_description_the_routing_and_the_format_upstream() throws IOException {
        controller.create("libs", "maven", "Platform releases", new RedirectAttributesModelMap());
        settings.setUpstream(null, "maven", "https://repo1.maven.org/maven2");
        ExtendedModelMap model = new ExtendedModelMap();

        assertThat(controller.detail("libs", model)).isEqualTo("repository");

        assertThat(model).containsEntry("repo", "libs").containsEntry("description", "Platform releases")
                .containsEntry("hardened", false).containsEntry("holdingsMore", false)
                .containsEntry("formatUpstream", "https://repo1.maven.org/maven2");
        assertThat((Collection<?>) model.get("unplaceable")).isEmpty();
        assertThat(((SettingsAdmin.Routing) model.get("routing")).shape()).isNotNull();

        settings.setUpstream(TENANT, "maven", "https://mirror.example/maven2");
        ExtendedModelMap tenant = new ExtendedModelMap();
        controller.detail("libs", tenant);
        assertThat(tenant).as("the tenant's own upstream is the one it fetches through")
                .containsEntry("formatUpstream", "https://mirror.example/maven2");
    }

    @Test
    void the_browse_tree_lists_a_level_its_trail_and_its_children_on_demand() throws IOException {
        create("files", "raw");
        publish("files", "/docs/guide/readme.txt", "read me");
        publish("files", "/notes.txt", "notes");
        ExtendedModelMap rootLevel = new ExtendedModelMap();

        assertThat(controller.browse("files", "", "", "name", "asc", rootLevel)).isEqualTo("browse");

        assertThat(rootLevel).containsEntry("searching", false).containsEntry("hasParent", false)
                .containsEntry("dir", "asc");
        assertThat((List<?>) rootLevel.get("entries")).hasSize(2);
        assertThat((List<?>) rootLevel.get("crumbs")).isEmpty();

        ExtendedModelMap nested = new ExtendedModelMap();
        controller.browse("files", "/docs/guide", "", "size", "desc", nested);
        assertThat(nested).containsEntry("hasParent", true).containsEntry("parent", "/docs")
                .containsEntry("dir", "desc");
        assertThat(nested.get("crumbs")).asInstanceOf(InstanceOfAssertFactories.LIST).containsExactly(
                new RepositoryAdminController.Crumb("docs", "/docs", false),
                new RepositoryAdminController.Crumb("guide", "/docs/guide", true));

        ExtendedModelMap children = new ExtendedModelMap();
        assertThat(controller.browseChildren("files", "/docs", "", "name", "asc", children))
                .isEqualTo("browse :: rows");
        assertThat((List<?>) children.get("entries")).singleElement().isInstanceOfSatisfying(BrowseRow.class, row -> {
            assertThat(row.name()).isEqualTo("guide");
            assertThat(row.folder()).isTrue();
            assertThat(row.depth()).as("one level below the page it is expanded on").isEqualTo(1);
            assertThat(row.childrenHref()).startsWith("/ui/repositories/files/browse/children?prefix=/docs/guide");
        });
    }

    @Test
    void a_search_is_shown_as_one_with_its_bound() throws IOException {
        create("files", "raw");
        ExtendedModelMap model = new ExtendedModelMap();

        assertThat(controller.browse("files", "", "readme", "name", "asc", model)).isEqualTo("browse");

        assertThat(model).containsEntry("searching", true).containsEntry("query", "readme")
                .containsKey("truncated").containsKey("neutralMark");
        assertThat((List<?>) model.get("results")).isNotNull();
    }

    @Test
    void an_artifact_page_names_its_path_and_the_folder_it_returns_to() throws IOException {
        create("files", "raw");
        publish("files", "/docs/readme.txt", "read me");
        ExtendedModelMap model = new ExtendedModelMap();

        assertThat(controller.artifact("files", "/docs/readme.txt", model)).isEqualTo("artifact");

        RepositoryBrowse.ArtifactDetail detail = (RepositoryBrowse.ArtifactDetail) model.get("detail");
        assertThat(detail.present()).isTrue();
        assertThat(detail.sizeBytes()).isEqualTo("read me".length());
        assertThat(model).containsEntry("parent", "/docs").containsEntry("downloadsTracked", false)
                .containsEntry("downloadsLag", "");
        assertThat(controller.artifactOrigin("files", "/docs/readme.txt")).isEmpty();
    }

    @Test
    void a_coordinate_page_lists_what_the_inventory_records_for_it() throws IOException {
        create("libs", "maven");
        new StoreRepositoryInventory(repository("libs"))
                .record("Maven", "org.acme:lib", "1.0", Instant.parse("2026-01-01T00:00:00Z"));
        ExtendedModelMap model = new ExtendedModelMap();

        assertThat(controller.coordinate("libs", "Maven", "org.acme:lib", "", model)).isEqualTo("coordinate");

        RepositoryBrowse.CoordinateDetail detail = (RepositoryBrowse.CoordinateDetail) model.get("detail");
        assertThat(detail.coordinate()).isEqualTo("org.acme:lib");
        assertThat(model.get("mark")).as("an installed format marks its own ecosystem").isNotNull();
        ExtendedModelMap orphan = new ExtendedModelMap();
        controller.coordinate("libs", "cobol", "x", "", orphan);
        assertThat(orphan.get("mark")).as("an ecosystem nothing installed declares is drawn as an orphan")
                .isNotNull();
    }

    @Test
    void the_overview_and_a_coordinate_page_show_a_copy_cached_from_an_upstream_beside_the_releases()
            throws IOException {
        create("libs", "maven");
        publish("libs", "/maven/org/yaml/snakeyaml/1.33/snakeyaml-1.33.jar", "cached jar");
        publish("libs", "/maven/org/acme/lib/1.0/lib-1.0.jar", "released jar");
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(repository("libs"));
        inventory.record("Maven", "org.acme:lib", "1.0", Instant.parse("2026-01-01T00:00:00Z"));
        inventory.cache("Maven", "org.yaml:snakeyaml", "1.33", "https://repo1.maven.org/maven2/",
                Instant.parse("2026-01-02T00:00:00Z"));
        ExtendedModelMap overview = new ExtendedModelMap();

        controller.detail("libs", overview);

        assertThat(overview.get("holdings")).asInstanceOf(InstanceOfAssertFactories.LIST)
                .as("newest first, the copy beside the release rather than an empty list of releases")
                .extracting(holding -> ((StoreRepositoryInventory.Holding) holding).coordinate() + " "
                        + ((StoreRepositoryInventory.Holding) holding).cached())
                .containsExactly("org.yaml:snakeyaml true", "org.acme:lib false");

        ExtendedModelMap page = new ExtendedModelMap();
        controller.coordinate("libs", "Maven", "org.yaml:snakeyaml", "", page);
        RepositoryBrowse.CoordinateDetail detail = (RepositoryBrowse.CoordinateDetail) page.get("detail");
        assertThat(detail.versions()).singleElement().satisfies(version -> {
            assertThat(version.version()).isEqualTo("1.33");
            assertThat(version.cached()).isTrue();
            assertThat(version.upstream()).isEqualTo("https://repo1.maven.org/maven2/");
            assertThat(version.pinned()).isFalse();
            assertThat(version.paths()).containsExactly("/maven/org/yaml/snakeyaml/1.33/snakeyaml-1.33.jar");
        });
    }

    @Test
    void a_pin_returns_to_the_page_that_asked_only_within_the_repository() throws IOException {
        create("libs", "maven");
        RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

        assertThat(controller.pin("libs", "Maven", "org.acme:lib", "1.0", "/ui/repositories/libs/coordinate?x=1",
                redirect)).isEqualTo("redirect:/ui/repositories/libs/coordinate?x=1");
        assertThat(flash(redirect, "message")).isEqualTo("Pinned org.acme:lib:1.0.");
        ExtendedModelMap pins = new ExtendedModelMap();
        assertThat(controller.pins("libs", pins)).isEqualTo("repository-pins");
        assertThat((List<?>) pins.get("pins")).hasSize(1);

        assertThat(controller.unpin("libs", "Maven", "org.acme:lib", "1.0", "https://elsewhere.example/",
                new RedirectAttributesModelMap())).as("a return address elsewhere is not followed")
                .isEqualTo("redirect:/ui/repositories/libs/pins");
        assertThat(controller.unpin("libs", "Maven", "org.acme:lib", "1.0", "/ui/repositories/libs//x",
                new RedirectAttributesModelMap())).isEqualTo("redirect:/ui/repositories/libs/pins");
        ExtendedModelMap after = new ExtendedModelMap();
        controller.pins("libs", after);
        assertThat((List<?>) after.get("pins")).isEmpty();
    }

    @Test
    void the_staging_and_migration_pages_render_what_the_repository_holds() throws IOException {
        create("libs", "maven");
        ExtendedModelMap staging = new ExtendedModelMap();
        ExtendedModelMap imports = new ExtendedModelMap();
        RedirectAttributesModelMap dismissed = new RedirectAttributesModelMap();

        assertThat(controller.staging("libs", staging)).isEqualTo("repository-staging");
        assertThat(controller.imports("libs", "", imports)).isEqualTo("import");
        controller.dismissImport("libs", "no-such-job", dismissed);

        assertThat((List<?>) staging.get("staging")).isEmpty();
        assertThat(staging).containsEntry("stagingMore", false)
                .containsEntry("stagedCountCap", RepositoryLifecycle.STAGED_COUNT_CAP);
        assertThat((List<?>) imports.get("jobs")).isEmpty();
        assertThat(imports).containsEntry("running", false).containsEntry("paged", false);
        assertThat(flash(dismissed, "message")).isEqualTo("Dismissed migration no-such-job.");
    }

    @Test
    void retiring_an_ecosystem_an_installed_format_places_is_refused_on_the_flash_line() throws IOException {
        create("libs", "maven");
        new StoreRepositoryInventory(repository("libs"))
                .record("cobol", "x", "1", Instant.parse("2026-01-01T00:00:00Z"));
        RedirectAttributesModelMap placed = new RedirectAttributesModelMap();
        RedirectAttributesModelMap unplaced = new RedirectAttributesModelMap();

        assertThat(controller.forgetEcosystem("libs", "Maven", placed)).isEqualTo("redirect:/ui/repositories/libs");
        controller.forgetEcosystem("libs", "cobol", unplaced);

        assertThat(flash(placed, "message")).asString().doesNotStartWith("Retiring");
        assertThat(flash(unplaced, "message")).asString().startsWith("Retiring ecosystem cobol");
        // The retirement runs off the request; the suite outlives it before its store is removed.
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (StoredReport.inFlight(repository("libs"), RepositoryLifecycle.forgetReport("cobol"))) {
            assertThat(Instant.now()).as("the retirement finished").isBefore(deadline);
            Thread.onSpinWait();
        }
        assertThat(new StoreRepositoryInventory(repository("libs")).publishedAt("cobol", "x", "1")).isEmpty();
    }
}
