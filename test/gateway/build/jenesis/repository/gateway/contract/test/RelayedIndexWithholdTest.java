package build.jenesis.repository.gateway.contract.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.format.DetachedExchange;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.inventory.HeldSubjects;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.store.Checksums;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.UpstreamMemory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An index relayed from an upstream lists no version this repository holds for review: a copy the proxy fetched and
 * held answers {@code 404}, so a client that found it listed would select it and fail. Each format's relay - an npm
 * packument, a {@code maven-metadata.xml} and its checksum, a PyPI Simple page, a NuGet version index, a Cargo index
 * file, a Go version list, a Terraform version list, a Swift release list, a Helm repository index - is answered by
 * an in-memory upstream listing two versions, the newer one held as the proxy holds a copy at its fill, and is
 * required to leave the held one out and keep the other; with nothing held, the upstream's list is served whole.
 */
class RelayedIndexWithholdTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeEach
    void setUp() {
        UpstreamMemory.reset();
        store = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null);
    }

    @AfterEach
    void forget() {
        UpstreamMemory.reset();
    }

    @Test
    void an_npm_packument_leaves_out_a_held_version_and_moves_a_tag_naming_it() throws IOException {
        byte[] packument = """
                {"name":"leftpad","dist-tags":{"latest":"1.1.0","next":"1.1.0"},
                 "time":{"1.0.0":"2026-01-01T00:00:00Z","1.1.0":"2026-02-01T00:00:00Z"},
                 "versions":{
                   "1.0.0":{"version":"1.0.0","dist":{"tarball":"https://registry.example/leftpad/-/leftpad-1.0.0.tgz"}},
                   "1.1.0":{"version":"1.1.0","dist":{"tarball":"https://registry.example/leftpad/-/leftpad-1.1.0.tgz"}}}}
                """.getBytes(StandardCharsets.UTF_8);
        JsonNode whole = JSON.readTree(relay("npm", "/npm/leftpad", packument).body());
        assertThat(whole.path("versions").has("1.1.0")).as("nothing held: the upstream's list, whole").isTrue();

        UpstreamMemory.reset();
        hold("/npm/leftpad/-/leftpad-1.1.0.tgz", "npm", "leftpad", "1.1.0");
        JsonNode served = JSON.readTree(relay("npm", "/npm/leftpad", packument).body());

        assertThat(served.path("versions").has("1.1.0")).as("the held version is not listed").isFalse();
        assertThat(served.path("versions").has("1.0.0")).isTrue();
        assertThat(served.path("time").has("1.1.0")).isFalse();
        assertThat(served.path("dist-tags").path("latest").asString("")).as("a tag naming it moves to what is left")
                .isEqualTo("1.0.0");
        assertThat(served.path("dist-tags").path("next").asString("")).isEqualTo("1.0.0");
    }

    @Test
    void a_maven_metadata_document_and_its_checksum_leave_out_a_held_version() throws IOException {
        byte[] metadata = ("<metadata><groupId>org.example</groupId><artifactId>lib</artifactId><versioning>"
                + "<latest>1.1</latest><release>1.1</release><versions><version>1.0</version>"
                + "<version>1.1</version></versions></versioning></metadata>").getBytes(StandardCharsets.UTF_8);
        String document = "/maven/org/example/lib/maven-metadata.xml";
        assertThat(relay("maven", document, metadata).bytes()).as("nothing held: relayed as the upstream serves it")
                .isEqualTo(metadata);

        UpstreamMemory.reset();
        hold("/maven/org/example/lib/1.1/lib-1.1.jar", "Maven", "org.example:lib", "1.1");
        Exchange served = relay("maven", document, metadata);

        assertThat(served.body()).as("the held version is not listed").doesNotContain("<version>1.1</version>")
                .contains("<version>1.0</version>");
        UpstreamMemory.reset();
        assertThat(relay("maven", document + ".sha1", metadata).body())
                .as("its checksum is the served document's, so a client checking one against the other finds them "
                        + "equal")
                .isEqualTo(Checksums.hex("SHA-1", served.bytes()));
    }

    @Test
    void a_pypi_simple_page_leaves_out_the_files_of_a_held_version() throws IOException {
        byte[] page = """
                <!DOCTYPE html><html><body>
                <a href="https://files.example/packages/proj-1.0.0-py3-none-any.whl#sha256=aa">proj-1.0.0-py3-none-any.whl</a><br/>
                <a href="https://files.example/packages/proj-1.1.0-py3-none-any.whl#sha256=bb">proj-1.1.0-py3-none-any.whl</a><br/>
                <a href="https://files.example/packages/proj-1.1.0.tar.gz#sha256=cc">proj-1.1.0.tar.gz</a><br/>
                </body></html>
                """.getBytes(StandardCharsets.UTF_8);
        assertThat(relay("pypi", "/pypi/simple/proj/", page).body()).as("nothing held: every file listed")
                .contains("proj-1.1.0-py3-none-any.whl");

        UpstreamMemory.reset();
        hold("/pypi/simple/proj/proj-1.1.0-py3-none-any.whl", "PyPI", "proj", "1.1.0");
        String served = relay("pypi", "/pypi/simple/proj/", page).body();

        assertThat(served).as("no file of the held version is listed, its sdist included")
                .doesNotContain("proj-1.1.0").contains("href=\"proj-1.0.0-py3-none-any.whl#sha256=aa\"");
    }

    @Test
    void a_nuget_version_index_leaves_out_a_held_version() throws IOException {
        byte[] index = "{\"versions\":[\"1.0.0\",\"1.1.0\"]}".getBytes(StandardCharsets.UTF_8);
        String path = "/nuget/v3-flatcontainer/acme.lib/index.json";
        assertThat(JSON.readTree(relay("nuget", path, index).body()).path("versions")).hasSize(2);

        UpstreamMemory.reset();
        hold("/nuget/v3-flatcontainer/acme.lib/1.1.0/acme.lib.1.1.0.nupkg", "NuGet", "acme.lib", "1.1.0");

        assertThat(JSON.readTree(relay("nuget", path, index).body()).path("versions"))
                .extracting(JsonNode::asString).containsExactly("1.0.0");
    }

    @Test
    void a_cargo_index_file_leaves_out_a_held_version() throws IOException {
        byte[] index = ("{\"name\":\"leftpad\",\"vers\":\"1.0.0\",\"deps\":[],\"cksum\":\"aa\",\"features\":{}}\n"
                + "{\"name\":\"leftpad\",\"vers\":\"1.1.0\",\"deps\":[],\"cksum\":\"bb\",\"features\":{}}\n")
                .getBytes(StandardCharsets.UTF_8);
        String path = "/cargo/main/le/ft/leftpad";
        assertThat(relay("cargo", path, index).body()).as("nothing held: every version").contains("\"1.1.0\"");

        UpstreamMemory.reset();
        hold("/cargo/main/api/v1/crates/leftpad/1.1.0/download", "crates.io", "leftpad", "1.1.0");

        assertThat(relay("cargo", path, index).body()).doesNotContain("\"1.1.0\"").contains("\"vers\":\"1.0.0\"");
    }

    @Test
    void a_go_version_list_leaves_out_a_held_version_and_latest_naming_it_answers_none() throws IOException {
        byte[] list = "v1.0.0\nv1.1.0\n".getBytes(StandardCharsets.UTF_8);
        byte[] latest = "{\"Version\":\"v1.1.0\",\"Time\":\"2026-02-01T00:00:00Z\"}".getBytes(StandardCharsets.UTF_8);
        assertThat(relay("go", "/go/example.com/acme/mod/@v/list", list).body()).contains("v1.1.0");

        UpstreamMemory.reset();
        hold("/go/example.com/acme/mod/@v/v1.1.0.zip", "Go", "example.com/acme/mod", "v1.1.0");

        assertThat(relay("go", "/go/example.com/acme/mod/@v/list", list).body()).isEqualTo("v1.0.0\n");
        UpstreamMemory.reset();
        Exchange query = new Exchange("/go/example.com/acme/mod/@latest");
        ((ProxyFormat) discover("go")).proxy(query, store, URI.create("https://upstream.example/"),
                (ProxyFormat.Fetcher.Buffered) (url, headers) -> Optional.of(new ProxyFormat.Fetched(200, latest,
                        Map.of())));
        assertThat(query.status).as("go resolves from the list instead, which leaves the held version out")
                .isEqualTo(404);
    }

    @Test
    void a_go_version_held_at_its_info_is_left_out_of_the_list() throws IOException {
        // go reads a version's .info before its archive, and the screen holds the version there: the hold is recorded
        // under the version the .info sits beside, so the list the .info files are listed from leaves it out.
        byte[] list = "v1.0.0\nv1.1.0\n".getBytes(StandardCharsets.UTF_8);
        String info = "/go/example.com/acme/mod/@v/v1.1.0.info";
        Publication publication = new Publication(store);
        HeldSubjects.recordFetched(store, info, "https://upstream.example/");
        publication.link("/quarantine" + info, publication.storeBlob(new ByteArrayInputStream(new byte[]{1})));

        assertThat(relay("go", "/go/example.com/acme/mod/@v/list", list).body()).isEqualTo("v1.0.0\n");
    }

    @Test
    void a_minified_composer_list_leaves_out_a_held_version_the_rest_inherit_from() throws IOException {
        // Packagist minifies a p2 file: each entry carries only what differs from the one before. The held version is
        // the first, which every entry after it inherits its name, its requirements and its licence from.
        byte[] p2 = """
                {"minified":"composer/2.0","packages":{"acme/widget":[
                  {"name":"acme/widget","version":"1.0.0","require":{"php":">=7.4"},"license":["MIT"],
                   "dist":{"type":"zip","url":"https://upstream.example/acme/widget/1.0.0.zip"}},
                  {"version":"1.1.0","dist":{"type":"zip","url":"https://upstream.example/acme/widget/1.1.0.zip"}},
                  {"version":"2.0.0","require":"__unset","license":["Apache-2.0"],
                   "dist":{"type":"zip","url":"https://upstream.example/acme/widget/2.0.0.zip"}}]}}"""
                .getBytes(StandardCharsets.UTF_8);
        String path = "/composer/main/p2/acme/widget.json";
        assertThat(JSON.readTree(relay("composer", path, p2).body()).path("packages").path("acme/widget"))
                .as("nothing held: every version").hasSize(3);

        UpstreamMemory.reset();
        hold("/composer/main/dists/acme/widget/1.0.0.zip", "Packagist", "acme/widget", "1.0.0");

        JsonNode served = JSON.readTree(relay("composer", path, p2).body());
        assertThat(served.path("minified").asString("")).isEqualTo("composer/2.0");
        JsonNode versions = served.path("packages").path("acme/widget");
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).path("version").asString("")).isEqualTo("1.1.0");
        assertThat(versions.get(0).path("name").asString("")).as("what it inherited, now its own")
                .isEqualTo("acme/widget");
        assertThat(versions.get(0).path("require").path("php").asString("")).isEqualTo(">=7.4");
        assertThat(versions.get(0).path("license").get(0).asString("")).isEqualTo("MIT");
        assertThat(versions.get(1).path("version").asString("")).isEqualTo("2.0.0");
        assertThat(versions.get(1).path("require").asString("")).as("still removed from the one before")
                .isEqualTo("__unset");
        assertThat(versions.get(1).has("name")).as("unchanged, so inherited").isFalse();
    }

    @Test
    void a_terraform_version_list_leaves_out_a_held_version() throws IOException {
        byte[] versions = ("{\"versions\":[{\"version\":\"1.0.0\",\"protocols\":[\"5.0\"]},"
                + "{\"version\":\"1.1.0\",\"protocols\":[\"5.0\"]}]}").getBytes(StandardCharsets.UTF_8);
        String path = "/terraform/main/v1/providers/acme/widget/versions";
        assertThat(relay("terraform", path, Map.of("/versions", versions)).body()).contains("1.1.0");

        UpstreamMemory.reset();
        hold("/terraform/main/providers/acme/widget/1.1.0/terraform-provider-widget_1.1.0_linux_amd64.zip",
                "Terraform", "acme/widget", "1.1.0");

        assertThat(JSON.readTree(relay("terraform", path, Map.of("/versions", versions)).body()).path("versions"))
                .extracting(entry -> entry.path("version").asString("")).containsExactly("1.0.0");
    }

    @Test
    void a_swift_release_list_leaves_out_a_held_release() throws IOException {
        byte[] releases = ("{\"releases\":{\"1.0.0\":{\"url\":\"https://swift.example/acme/widget/1.0.0\"},"
                + "\"1.1.0\":{\"url\":\"https://swift.example/acme/widget/1.1.0\"}}}").getBytes(StandardCharsets.UTF_8);
        assertThat(JSON.readTree(relay("swift", "/swift/main/acme/widget", releases).body()).path("releases")
                .has("1.1.0")).isTrue();

        UpstreamMemory.reset();
        hold("/swift/main/acme/widget/1.1.0.zip", "Swift", "acme.widget", "1.1.0");

        JsonNode served = JSON.readTree(relay("swift", "/swift/main/acme/widget", releases).body()).path("releases");
        assertThat(served.has("1.1.0")).isFalse();
        assertThat(served.has("1.0.0")).isTrue();
    }

    @Test
    void a_helm_repository_index_leaves_out_a_held_chart_version() throws IOException {
        byte[] index = """
                apiVersion: v1
                entries:
                  widget:
                  - name: widget
                    version: 1.0.0
                    urls: [https://charts.example/widget-1.0.0.tgz]
                  - name: widget
                    version: 1.1.0
                    urls: [https://charts.example/widget-1.1.0.tgz]
                  gadget:
                  - name: gadget
                    version: 1.1.0
                    urls: [https://charts.example/gadget-1.1.0.tgz]
                """.getBytes(StandardCharsets.UTF_8);
        assertThat(relay("helm", "/helm/main/index.yaml", index).body()).contains("widget-1.1.0.tgz");

        UpstreamMemory.reset();
        hold("/helm/main/charts/widget-1.1.0.tgz", "Helm", "widget", "1.1.0");
        String served = relay("helm", "/helm/main/index.yaml", index).body();

        assertThat(served).as("the held version of one chart is left out, the same version of another kept")
                .doesNotContain("widget-1.1.0.tgz").contains("widget-1.0.0.tgz").contains("gadget-1.1.0.tgz");
    }

    /** {@code path} held for review as the proxy holds a copy at its fill: its subject recorded and its review pointer
     *  linked, and nothing else. */
    private void hold(String path, String ecosystem, String coordinate, String version) throws IOException {
        Publication publication = new Publication(store);
        HeldSubjects.hold(publication, store, path,
                publication.storeBlob(new ByteArrayInputStream(new byte[]{1})), ecosystem, coordinate, version);
    }

    /** {@code path} relayed through {@code format}'s proxy leg from an upstream answering {@code body} to anything. */
    private Exchange relay(String format, String path, byte[] body) throws IOException {
        return relay(format, path, Map.of("", body));
    }

    /** {@code path} relayed through {@code format}'s proxy leg from an upstream answering each URL ending in a key of
     *  {@code answers} with its value, and {@code 404} to anything else. */
    private Exchange relay(String format, String path, Map<String, byte[]> answers) throws IOException {
        ProxyFormat.Fetcher.Buffered upstream = (url, headers) -> answers.entrySet().stream()
                .filter(answer -> url.toString().endsWith(answer.getKey()))
                .findFirst()
                .map(answer -> new ProxyFormat.Fetched(200, answer.getValue(), Map.of()))
                .or(() -> Optional.of(new ProxyFormat.Fetched(404, new byte[0], Map.of())));
        Exchange exchange = new Exchange(path);
        assertThat(((ProxyFormat) discover(format)).proxy(exchange, store, URI.create("https://upstream.example/"),
                upstream)).as("%s relays %s", format, path).isTrue();
        assertThat(exchange.status).as("%s relays %s", format, path).isEqualTo(200);
        return exchange;
    }

    private static RepositoryFormat discover(String name) {
        for (RepositoryFormat format : ServiceLoader.load(RepositoryFormat.class)) {
            if (format.name().equals(name)) {
                return format;
            }
        }
        throw new AssertionError("no format named " + name + " on the module path");
    }

    /** A {@code GET} of one path, capturing what is answered. */
    private static final class Exchange implements DetachedExchange {

        private final String path;
        private int status = -1;
        private final ByteArrayOutputStream captured = new ByteArrayOutputStream();

        private Exchange(String path) {
            this.path = path;
        }

        byte[] bytes() {
            return captured.toByteArray();
        }

        String body() {
            return captured.toString(StandardCharsets.UTF_8);
        }

        @Override
        public String method() {
            return "GET";
        }

        @Override
        public String path() {
            return path;
        }

        @Override
        public String queryParameter(String name) {
            return null;
        }

        @Override
        public String requestHeader(String name) {
            return null;
        }

        @Override
        public InputStream requestStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public void setResponseHeader(String name, String value) {
        }

        @Override
        public OutputStream respond(int status, long contentLength) {
            this.status = status;
            return captured;
        }
    }
}
