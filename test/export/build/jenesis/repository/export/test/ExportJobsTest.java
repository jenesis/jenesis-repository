package build.jenesis.repository.export.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.export.ExportController;
import build.jenesis.repository.export.Exports;
import build.jenesis.repository.export.web.ExportScreenController;
import build.jenesis.repository.format.ExportTarget;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.format.RepositoryType;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.server.FormatDispatcher;
import build.jenesis.repository.server.RepositoryController;
import build.jenesis.repository.server.RepositoryRouting;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.servlet.testkit.Servlets;
import build.jenesis.repository.web.testkit.Web;
import com.sun.net.httpserver.HttpServer;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An export run end to end in process: a repository's published content is sent by a background job to another
 * repository, stood in for by the JDK's HTTP server on a loopback port that keeps what it is sent and serves it back.
 *
 * <p>Two walks are driven - a format that exports every published path (raw) and one that exports coordinate by
 * coordinate from the inventory (Maven) - and each job is watched the way every surface watches it: through the
 * stored state document, polled until it is no longer running. A resumed job asks the target for each file first and
 * sends nothing the target already holds. The refusals come before any job exists: a read-only instance, a repository
 * that does not exist or holds nothing that exports, a URL the migration screen refuses, a job that cannot be resumed.
 * The API and the console screen answer from the same {@link Exports}, so both are driven over it here.
 */
class ExportJobsTest {

    @TempDir
    Path root;

    private final Map<String, byte[]> received = new ConcurrentHashMap<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private Repositories repositories;
    private RepositoryRouting routing;
    private RepositoryController edge;
    private Exports exports;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            requests.add(exchange.getRequestMethod() + " " + path);
            byte[] body = exchange.getRequestBody().readAllBytes();
            if (exchange.getRequestMethod().equals("PUT")) {
                received.put(path, body);
                exchange.sendResponseHeaders(201, -1);
            } else if (received.containsKey(path)) {
                byte[] held = received.get(path);
                exchange.sendResponseHeaders(200, held.length);
                exchange.getResponseBody().write(held);
            } else {
                exchange.sendResponseHeaders(404, -1);
            }
            exchange.close();
        });
        server.start();
        ArtifactStore store = Web.store(root);
        repositories = Web.repositories(store);
        routing = Web.routing(store, repositories);
        List<RepositoryFormat> formats = new ArrayList<>(RepositoryType.installed("raw").orElseThrow().formats());
        formats.addAll(RepositoryType.installed("maven").orElseThrow().formats());
        edge = new RepositoryController(routing, new FormatDispatcher(formats, Map.of(), ProxyFormat.Fetcher.NONE),
                List.of(), ProxyFormat.Fetcher.NONE);
        // A loopback target is an internal host, which a deployment reaches only by switching the screen off.
        exports = new Exports(routing, Map.of("block-private-import-hosts", "false")::get);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String target() {
        return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort() + "/target/";
    }

    private void repository(String name, String format) throws IOException {
        RepositoryType.create(repositories.store("default", name), format);
    }

    private void publish(String repository, String path, String content) throws IOException {
        assertThat(edge.publish("default", repository, path,
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))))
                .as("publishing %s", path).isLessThan(300);
    }

    /** The job's stored state once it has stopped running. */
    private Exports.Job finished(String repository, String job) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (Instant.now().isBefore(deadline)) {
            Optional<Exports.Job> state = exports.jobs("default", repository, null).jobs().stream()
                    .filter(candidate -> candidate.id().equals(job)).findFirst();
            if (state.isPresent() && !state.get().running()) {
                return state.get();
            }
            Thread.sleep(20);
        }
        throw new AssertionError("export " + job + " was still running after 30 seconds");
    }

    @Test
    void every_published_path_is_sent_to_the_target_and_the_job_says_so() throws Exception {
        repository("files", "raw");
        publish("files", "/docs/readme.txt", "read me");
        publish("files", "/docs/notes.txt", "notes");

        Exports.Started started = exports.start("default", "files", target(), Optional.empty(), null);

        assertThat(started.accepted()).isTrue();
        Exports.Job job = finished("files", started.job());
        assertThat(job.state()).isEqualTo("completed");
        assertThat(job.published()).isEqualTo(2);
        assertThat(job.target()).isEqualTo(target());
        assertThat(received).containsOnlyKeys("/target/docs/readme.txt", "/target/docs/notes.txt");
        assertThat(new String(received.get("/target/docs/readme.txt"), StandardCharsets.UTF_8)).isEqualTo("read me");
        assertThat(new String(exports.status("default", "files", started.job()).orElseThrow(),
                StandardCharsets.UTF_8)).contains("\"state\":\"completed\"");
    }

    @Test
    void a_resumed_job_sends_nothing_the_target_already_holds() throws Exception {
        repository("files", "raw");
        publish("files", "/docs/readme.txt", "read me");
        String first = exports.start("default", "files", target(), Optional.empty(), null).job();
        finished("files", first);
        requests.clear();

        Exports.Started resumed = exports.start("default", "files", target(), Optional.empty(), first);

        assertThat(resumed.job()).as("a resume continues the job it names").isEqualTo(first);
        Exports.Job job = finished("files", first);
        assertThat(job.state()).isEqualTo("completed");
        assertThat(job.present()).isEqualTo(1);
        assertThat(requests).as("the target is asked, and nothing is put twice").noneMatch(r -> r.startsWith("PUT"));
    }

    @Test
    void a_coordinate_format_exports_the_versions_its_inventory_records() throws Exception {
        repository("libs", "maven");
        publish("libs", "/maven/org/acme/lib/1.0/lib-1.0.jar", "jar bytes");
        publish("libs", "/maven/org/acme/lib/1.0/lib-1.0.pom", "<project><modelVersion>4.0.0</modelVersion><groupId>org.acme"
                + "</groupId><artifactId>lib</artifactId><version>1.0</version></project>");
        // What the screening pipeline records for a publish in a full composition.
        new StoreRepositoryInventory(repositories.store("default", "libs"))
                .record("Maven", "org.acme:lib", "1.0", Instant.parse("2026-01-01T00:00:00Z"));

        Exports.Started started = exports.start("default", "libs", target(), Optional.empty(), null);

        Exports.Job job = finished("libs", started.job());
        assertThat(job.state()).as("error: %s", job.error()).isEqualTo("completed");
        assertThat(job.reached()).isEqualTo("org.acme:lib 1.0");
        assertThat(received).containsKeys("/target/org/acme/lib/1.0/lib-1.0.jar", "/target/org/acme/lib/1.0/lib-1.0.pom");
    }

    @Test
    void a_target_that_refuses_a_file_fails_the_job_naming_the_file() throws Exception {
        server.removeContext("/");
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(exchange.getRequestMethod().equals("PUT") ? 403 : 404, -1);
            exchange.close();
        });
        repository("files", "raw");
        publish("files", "/docs/readme.txt", "read me");

        Exports.Job job = finished("files",
                exports.start("default", "files", target(), Optional.empty(), null).job());

        assertThat(job.state()).isEqualTo("failed");
        assertThat(job.error()).contains("/docs/readme.txt");
    }

    @Test
    void what_cannot_be_exported_is_refused_before_a_job_exists() throws IOException {
        repository("files", "raw");
        repositories.store("default", "empty").write("unrelated", new ByteArrayInputStream(new byte[] {1}));

        assertThat(new Exports(routing, Map.of("read-only", "true")::get)
                .start("default", "files", target(), Optional.empty(), null).status()).isEqualTo(403);
        assertThat(exports.start("default", "nowhere/..", target(), Optional.empty(), null).status())
                .isEqualTo(404);
        assertThat(exports.start("default", "files", " ", Optional.empty(), null).status()).isEqualTo(400);
        assertThat(exports.start("default", "empty", target(), Optional.empty(), null).reason())
                .contains("holds no format");
        assertThat(exports.start("default", "files", target(), Optional.empty(), "no-such-job").status())
                .isEqualTo(404);
        Exports.Started screened = new Exports(routing, _ -> null)
                .start("default", "files", target(), Optional.empty(), null);
        assertThat(screened.status()).isEqualTo(400);
        assertThat(screened.reason()).startsWith("export url is refused: ");
        assertThat(requests).isEmpty();
    }

    @Test
    void a_credential_is_a_password_with_its_user_or_else_a_token() {
        assertThat(Exports.credential("ci", "secret", "ignored")).hasValueSatisfying(credential -> {
            assertThat(credential.username()).contains("ci");
            assertThat(credential.secret()).isEqualTo("secret");
        });
        assertThat(Exports.credential(" ", "secret", null)).hasValueSatisfying(
                credential -> assertThat(credential.username()).isEmpty());
        assertThat(Exports.credential(null, "", "t0ken")).contains(new ExportTarget.Credential(Optional.empty(),
                "t0ken"));
        assertThat(Exports.credential(null, null, " ")).isEmpty();
    }

    @Test
    void the_api_answers_202_with_the_job_and_then_its_state() throws Exception {
        repository("files", "raw");
        publish("files", "/a.txt", "a");
        ExportController controller = new ExportController(exports, routing);
        Servlets.Response submitted = Servlets.response();

        controller.submit("files", "{\"url\":\"" + target() + "\",\"token\":\"t0ken\"}",
                Servlets.request("POST", "/api/repository/export"), submitted.servlet());

        assertThat(submitted.status()).isEqualTo(202);
        assertThat(submitted.body()).contains("\"state\":\"running\"");
        String job = submitted.body().replaceAll(".*\"job\":\"([^\"]+)\".*", "$1");
        finished("files", job);
        Servlets.Response state = Servlets.response();
        controller.status("files", job, Servlets.request("GET", "/api/repository/export/" + job), state.servlet());
        assertThat(state.status()).isEqualTo(200);
        assertThat(state.body()).contains("\"state\":\"completed\"");

        Servlets.Response refused = Servlets.response();
        controller.submit("files", null, Servlets.request("POST", "/api/repository/export"), refused.servlet());
        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.body()).startsWith("url is required");
        Servlets.Response missing = Servlets.response();
        controller.status("files", "no-such-job", Servlets.request("GET", "/api/repository/export/x"),
                missing.servlet());
        assertThat(missing.status()).isEqualTo(404);
    }

    @Test
    void the_screen_lists_the_jobs_and_reports_a_start_or_a_refusal() throws Exception {
        repository("files", "raw");
        publish("files", "/a.txt", "a");
        ExportScreenController screen = new ExportScreenController(exports, () -> "default");
        RedirectAttributesModelMap started = new RedirectAttributesModelMap();

        assertThat(screen.start("files", target(), "", "", "", "", started))
                .isEqualTo("redirect:/ui/repositories/files/export");
        String message = (String) started.getFlashAttributes().get("message");
        assertThat(message).startsWith("Started export ").endsWith(" of files to " + target() + ".");
        finished("files", message.substring("Started export ".length(), message.indexOf(" of files")));

        ExtendedModelMap model = new ExtendedModelMap();
        assertThat(screen.screen("files", "", "", model)).isEqualTo("export/form");
        assertThat((List<?>) model.get("jobs")).hasSize(1);
        assertThat(model).containsEntry("running", false).containsEntry("tenant", "default");
        assertThat(model.get("next")).isNull();

        RedirectAttributesModelMap refused = new RedirectAttributesModelMap();
        screen.start("files", " ", "", "", "", "", refused);
        assertThat(refused.getFlashAttributes().get("error")).asString().startsWith("Nothing exported: url is required");
    }
}
