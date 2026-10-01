package build.jenesis.repository.gateway.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.audit.AuditActions;
import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.lifecycle.Lifecycle;
import build.jenesis.repository.gateway.testkit.FormatDrive;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A client's own lifecycle command - {@code gem yank}, {@code cargo yank} and its undo, {@code dotnet nuget delete},
 * {@code npm deprecate} - is the product's lifecycle mark, set through the one path the console and the API use: the
 * mark is stored where the format's listings read it, the change is recorded on the audit trail under the lifecycle's
 * action names, and the client is answered as its own registry answers it. A version the repository does not hold is
 * refused and marks nothing.
 */
class ClientLifecycleCommandsTest {

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeEach
    void store() {
        store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null)
                .scope("default").scope("releases");
    }

    /** A stored version: its serving pointer, which is all a lifecycle command asks of it. */
    private void held(String key) throws IOException {
        new Blobs(store).write(key, key.getBytes(StandardCharsets.UTF_8));
    }

    private FormatDrive.Call call(String format, FormatDrive.Call call) throws IOException {
        RepositoryFormat served = FormatDrive.format(format);
        served.serve(call, store);
        return call;
    }

    @Test
    void gem_yank_marks_the_version_yanked_and_says_so_as_rubygems_does() throws IOException {
        held("rubygemfiles/rake-13.0.0.gem");

        FormatDrive.Call yank = call("rubygems", new FormatDrive.Call("DELETE", "/rubygems/api/v1/gems/yank",
                "gem_name=rake&version=13.0.0".getBytes(StandardCharsets.UTF_8)));

        assertThat(yank.status).isEqualTo(200);
        assertThat(new String(yank.body(), StandardCharsets.UTF_8)).isEqualTo("Successfully deleted gem: rake (13.0.0)");
        assertThat(Lifecycle.read(store, "rake", "13.0.0")).get().extracting(Lifecycle.Flag::state)
                .isEqualTo(Lifecycle.State.YANKED);
        assertThat(yank.audited).containsExactly(AuditActions.LIFECYCLE_YANKED + " rake@13.0.0");

        FormatDrive.Call again = call("rubygems", new FormatDrive.Call("DELETE", "/rubygems/api/v1/gems/yank",
                "gem_name=rake&version=13.0.0".getBytes(StandardCharsets.UTF_8)));
        assertThat(again.status).as("already yanked, as rubygems.org answers it").isEqualTo(422);
        assertThat(again.audited).isEmpty();
    }

    @Test
    void a_platform_gem_is_yanked_under_its_platform() throws IOException {
        held("rubygemfiles/nokogiri-1.16.0-java.gem");

        FormatDrive.Call yank = call("rubygems", new FormatDrive.Call("DELETE", "/rubygems/api/v1/gems/yank")
                .query("gem_name", "nokogiri").query("version", "1.16.0").query("platform", "java"));

        assertThat(yank.status).isEqualTo(200);
        assertThat(Lifecycle.read(store, "nokogiri", "1.16.0-java")).isPresent();
    }

    @Test
    void a_gem_version_the_repository_does_not_hold_is_not_yanked() throws IOException {
        FormatDrive.Call yank = call("rubygems", new FormatDrive.Call("DELETE", "/rubygems/api/v1/gems/yank",
                "gem_name=rake&version=9.9.9".getBytes(StandardCharsets.UTF_8)));

        assertThat(yank.status).isEqualTo(404);
        assertThat(Lifecycle.versions(store, "rake")).isEmpty();
        assertThat(yank.audited).isEmpty();
    }

    @Test
    void cargo_yank_and_its_undo_set_and_clear_the_mark() throws IOException {
        held("cargo/main/crates/serde/serde-1.0.0.crate");

        FormatDrive.Call yank = call("cargo",
                new FormatDrive.Call("DELETE", "/cargo/main/api/v1/crates/serde/1.0.0/yank"));
        assertThat(yank.status).isEqualTo(200);
        assertThat(new String(yank.body(), StandardCharsets.UTF_8)).isEqualTo("{\"ok\":true}");
        assertThat(Lifecycle.read(store, "main/serde", "1.0.0")).isPresent();
        assertThat(yank.audited).containsExactly(AuditActions.LIFECYCLE_YANKED + " main/serde@1.0.0");

        FormatDrive.Call undo = call("cargo",
                new FormatDrive.Call("PUT", "/cargo/main/api/v1/crates/serde/1.0.0/unyank"));
        assertThat(undo.status).isEqualTo(200);
        assertThat(Lifecycle.read(store, "main/serde", "1.0.0")).isEmpty();
        assertThat(undo.audited).containsExactly(AuditActions.LIFECYCLE_CLEAR + " main/serde@1.0.0");

        FormatDrive.Call idle = call("cargo",
                new FormatDrive.Call("PUT", "/cargo/main/api/v1/crates/serde/1.0.0/unyank"));
        assertThat(idle.status).as("idempotent, as crates.io is").isEqualTo(200);
        assertThat(idle.audited).as("nothing changed, so nothing is recorded").isEmpty();
    }

    @Test
    void cargo_yank_of_a_version_the_registry_does_not_hold_is_cargos_error() throws IOException {
        FormatDrive.Call yank = call("cargo",
                new FormatDrive.Call("DELETE", "/cargo/main/api/v1/crates/serde/9.9.9/yank"));

        assertThat(yank.status).isEqualTo(404);
        assertThat(new String(yank.body(), StandardCharsets.UTF_8)).contains("\"errors\"").contains("detail");
        assertThat(Lifecycle.versions(store, "main/serde")).isEmpty();
    }

    @Test
    void dotnet_nuget_delete_unlists_the_version() throws IOException {
        held("nuget/newtonsoft.json/13.0.1/newtonsoft.json.13.0.1.nupkg");

        FormatDrive.Call delete = call("nuget",
                new FormatDrive.Call("DELETE", "/nuget/v3/package/Newtonsoft.Json/13.0.1"));

        assertThat(delete.status).isEqualTo(204);
        assertThat(Lifecycle.read(store, "newtonsoft.json", "13.0.1")).get().extracting(Lifecycle.Flag::state)
                .as("the mark NuGet renders as listed: false").isEqualTo(Lifecycle.State.YANKED);
        assertThat(delete.audited).containsExactly(AuditActions.LIFECYCLE_YANKED + " newtonsoft.json@13.0.1");
        assertThat(call("nuget", new FormatDrive.Call("DELETE", "/nuget/v3/package/Newtonsoft.Json/2.0.0")).status)
                .as("a version the feed does not hold").isEqualTo(404);
    }

    @Test
    void npm_deprecate_sets_the_message_as_the_mark_and_an_empty_one_clears_it() throws IOException {
        held("npm/left-pad/tarballs/left-pad-1.3.0.tgz");

        FormatDrive.Call deprecate = call("npm", new FormatDrive.Call("PUT", "/npm/left-pad", packument(
                "use something else")));
        assertThat(deprecate.status).isEqualTo(201);
        assertThat(Lifecycle.read(store, "left-pad", "1.3.0")).contains(
                new Lifecycle.Flag(Lifecycle.State.DEPRECATED, "use something else"));
        assertThat(deprecate.audited).containsExactly(AuditActions.LIFECYCLE_DEPRECATED + " left-pad@1.3.0");

        FormatDrive.Call echo = call("npm", new FormatDrive.Call("PUT", "/npm/left-pad", packument(
                "use something else")));
        assertThat(echo.audited).as("the client echoing what it read is no change").isEmpty();

        FormatDrive.Call undo = call("npm", new FormatDrive.Call("PUT", "/npm/left-pad", packument("")));
        assertThat(undo.status).isEqualTo(201);
        assertThat(Lifecycle.read(store, "left-pad", "1.3.0")).isEmpty();
        assertThat(undo.audited).containsExactly(AuditActions.LIFECYCLE_CLEAR + " left-pad@1.3.0");
        FormatDrive.Call packument = call("npm", new FormatDrive.Call("GET", "/npm/left-pad"));
        assertThat(new String(packument.body(), StandardCharsets.UTF_8))
                .as("the served packument says nothing of a deprecation once the mark is gone").contains("1.3.0")
                .doesNotContain("deprecated");
    }

    @Test
    void npm_deprecate_never_turns_a_yank_into_a_deprecation() throws IOException {
        held("npm/left-pad/tarballs/left-pad-1.3.0.tgz");
        Lifecycle.mark(store, "left-pad", "1.3.0", new Lifecycle.Flag(Lifecycle.State.YANKED, ""));

        FormatDrive.Call deprecate = call("npm", new FormatDrive.Call("PUT", "/npm/left-pad", packument(
                "This version has been yanked.")));

        assertThat(deprecate.status).isEqualTo(201);
        assertThat(Lifecycle.read(store, "left-pad", "1.3.0")).get().extracting(Lifecycle.Flag::state)
                .isEqualTo(Lifecycle.State.YANKED);
        assertThat(deprecate.audited).isEmpty();
    }

    @Test
    void a_version_s_mark_is_named_by_the_coordinate_its_own_client_names() {
        assertThat(FormatDrive.format("cargo").lifecycleCoordinate("serde",
                "/cargo/main/api/v1/crates/serde/1.0.0/download")).as("a crate's registry and the crate")
                .isEqualTo("main/serde");
        assertThat(FormatDrive.format("nuget").lifecycleCoordinate("Newtonsoft.Json",
                "/nuget/v3/flatcontainer/newtonsoft.json/13.0.1/newtonsoft.json.13.0.1.nupkg"))
                .as("a package id in lower case").isEqualTo("newtonsoft.json");
        assertThat(FormatDrive.format("npm").lifecycleCoordinate("left-pad", "/npm/left-pad/-/left-pad-1.3.0.tgz"))
                .as("the inventory's coordinate, where the format keys its marks by it").isEqualTo("left-pad");
    }

    /** The document {@code npm deprecate} PUTs: the package with one stored version carrying {@code message}, and no
     *  tarball attached. */
    private static byte[] packument(String message) {
        return ("{\"_id\":\"left-pad\",\"name\":\"left-pad\",\"versions\":{\"1.3.0\":{\"name\":\"left-pad\","
                + "\"version\":\"1.3.0\",\"deprecated\":\"" + message + "\",\"dist\":{\"tarball\":"
                + "\"http://localhost/npm/left-pad/-/left-pad-1.3.0.tgz\"}}},\"dist-tags\":{\"latest\":\"1.3.0\"}}")
                .getBytes(StandardCharsets.UTF_8);
    }
}
