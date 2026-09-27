package build.jenesis.repository.web.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.deploy.web.DeployController;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryType;
import build.jenesis.repository.server.FormatDispatcher;
import build.jenesis.repository.server.RepositoryController;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.server.kernel.Settings;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.servlet.testkit.Servlets;
import build.jenesis.repository.web.testkit.Web;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The console's deploy screen publishing through the repository's own edge: an upload read off a multipart body is
 * laid out by the format the repository holds, and every other outcome - no file, no such repository, a repository
 * that takes no direct write - is reported as itself rather than as one "upload failed". The edge is the real
 * {@link RepositoryController} over the raw format, so "published" means the store now holds the path.
 */
class DeployControllerTest {

    private static final String FILES = "files";

    @TempDir
    Path root;

    private ArtifactStore store;
    private Repositories repositories;
    private DeployController controller;

    @BeforeEach
    void wire() throws IOException {
        store = Web.store(root);
        wireOverStoredSettings();
        RepositoryType.create(repositories.store("default", FILES), "raw");
    }

    /** The kernel reads the stored definitions as it is built, so a suite that stores one builds it again. */
    private void wireOverStoredSettings() throws IOException {
        repositories = Web.repositories(store);
        RepositoryController edge = new RepositoryController(Web.routing(store, repositories),
                new FormatDispatcher(RepositoryType.installed("raw").orElseThrow().formats(), Map.of(),
                        ProxyFormat.Fetcher.NONE), List.of(), ProxyFormat.Fetcher.NONE);
        controller = new DeployController(edge, () -> "default");
    }

    private RedirectAttributesModelMap deploy(String repository, String path, byte[] content) {
        RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();
        String view = controller.deploy(repository, path,
                Servlets.multipart("/ui/repositories/" + repository + "/deploy", "artifact", "readme.txt", content),
                redirect);
        assertThat(view).isEqualTo("redirect:/ui/repositories/" + repository + "/deploy");
        return redirect;
    }

    @Test
    void the_form_names_the_tenant_and_the_repository() {
        ExtendedModelMap model = new ExtendedModelMap();

        assertThat(controller.form(FILES, model)).isEqualTo("deploy/form");
        assertThat(model).containsEntry("tenant", "default").containsEntry("repo", FILES);
    }

    @Test
    void an_upload_is_published_by_the_format_the_repository_holds() throws IOException {
        byte[] content = "published from the console".getBytes(StandardCharsets.UTF_8);

        RedirectAttributesModelMap redirect = deploy(FILES, "docs/readme.txt", content);

        assertThat(redirect.getFlashAttributes()).doesNotContainKey("error");
        assertThat(redirect.getFlashAttributes().get("message")).isEqualTo("Published /docs/readme.txt to files.");
        List<String> published = new ArrayList<>();
        repositories.store("default", FILES).scan("publish", null, 100, listed -> published.add(listed.key()));
        assertThat(published).as("the path the upload named is laid out").anyMatch(key -> key.endsWith("docs/readme.txt"));
    }

    @Test
    void a_repository_that_does_not_exist_publishes_nothing_and_says_why() {
        RedirectAttributesModelMap redirect = deploy("nowhere", "/a.txt", new byte[] {1});

        assertThat(redirect.getFlashAttributes().get("error")).asString()
                .startsWith("Nothing published: no repository 'nowhere'");
    }

    @Test
    void a_proxy_takes_no_direct_write_and_is_named_as_such() throws IOException {
        new Settings(store).set("repositories.mirror", "fallback https://mirror.example/files");
        wireOverStoredSettings();
        RepositoryType.create(repositories.store("default", "mirror"), "raw");

        RedirectAttributesModelMap redirect = deploy("mirror", "/a.txt", new byte[] {1});

        assertThat(redirect.getFlashAttributes().get("error")).asString()
                .isEqualTo("Nothing published: 'mirror' does not accept direct writes. A proxy or a group view is "
                        + "served from its backings, so an artifact is published into a hosted repository.");
    }

    @Test
    void a_request_with_no_file_part_is_asked_for_a_file() {
        RedirectAttributesModelMap plain = new RedirectAttributesModelMap();
        controller.deploy(FILES, "/a.txt", Servlets.request("POST", "/ui/repositories/files/deploy"), plain);
        RedirectAttributesModelMap otherField = new RedirectAttributesModelMap();
        controller.deploy(FILES, "/a.txt",
                Servlets.multipart("/ui/repositories/files/deploy", "attachment", "a.txt", new byte[] {1}),
                otherField);

        assertThat(plain.getFlashAttributes().get("error"))
                .isEqualTo("Could not publish /a.txt: Choose a file to publish.");
        assertThat(otherField.getFlashAttributes().get("error"))
                .isEqualTo("Could not publish /a.txt: Choose a file to publish.");
    }
}
