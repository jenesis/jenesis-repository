package build.jenesis.repository.export;

import module java.base;
import build.jenesis.repository.format.ExportTarget;
import build.jenesis.repository.importer.ImportScreen;
import build.jenesis.repository.scope.Scopes;
import build.jenesis.repository.server.RepositoryRouting;
import build.jenesis.repository.store.ArtifactStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Starts an export of a repository and reads its state: {@code POST /api/repository/export?repo=<name>} with
 * {@code {"url": ..., "username": ..., "password": ...}} or {@code {"url": ..., "token": ...}}, and optionally
 * {@code "resume": <job>} to continue a stopped job, answers {@code 202} with the job's id; {@code GET
 * /api/repository/export/<id>?repo=<name>} answers its state.
 *
 * <p>The URL is the one the repository's format's client is pointed at - {@code .../maven/} for Maven, the registry
 * for npm - of another deployment of this product or of any repository manager. It is screened as a migration URL is,
 * under the same dial: https only, and no address that resolves inside the deployment's own network, unless
 * {@code jenreg.block-private-import-hosts=false} allows migrating to one. The credential is kept for the job's life
 * and never written; a resume therefore names it again.
 *
 * <p>Authorization is the security chain's: an export sends a repository's contents wherever it is told to, so it
 * takes the manage rights on that repository.
 */
@RestController
public class ExportController {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RepositoryRouting routing;
    private final UnaryOperator<String> settings;

    public ExportController(RepositoryRouting routing, UnaryOperator<String> settings) {
        this.routing = routing;
        this.settings = settings;
    }

    @PostMapping("/api/repository/export")
    public void submit(@RequestParam("repo") String repo, @RequestBody(required = false) String body,
                       HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (Boolean.parseBoolean(settings.apply("read-only"))) {
            respond(response, 403, "this instance is in read-only mode, and an export records its progress: refused");
            return;
        }
        Optional<ArtifactStore> store = store(repo, request);
        if (store.isEmpty()) {
            respond(response, 404, "no such repository");
            return;
        }
        JsonNode spec = JSON.readTree(body == null || body.isBlank() ? "{}" : body);
        String url = spec.path("url").asString(null);
        if (url == null || url.isBlank()) {
            respond(response, 400, "url is required: the URL the format's client would be pointed at");
            return;
        }
        String refusal = ImportScreen.refusalReason(url, blockPrivateHosts());
        if (refusal != null) {
            respond(response, 400, "export url is refused: " + refusal + "; an export sends the repository's "
                    + "contents and a credential, so it must be an https URL to a public host (set "
                    + "jenreg.block-private-import-hosts=false to migrate to an internal or plaintext one)");
            return;
        }
        URI target;
        try {
            target = URI.create(url);
        } catch (IllegalArgumentException _) {
            respond(response, 400, "export url is refused: the URL is malformed");
            return;
        }
        ExportJobs jobs = new ExportJobs();
        String resume = spec.path("resume").asString(null);
        ExportJobs.Snapshot prior = resume == null ? null : jobs.snapshot(store.get(), resume).orElse(null);
        if (resume != null && prior == null) {
            respond(response, 404, "no export job " + resume + " to resume");
            return;
        }
        String jobId = prior == null ? ExportJobs.newId() : resume;
        try {
            jobs.submit(store.get(), new HttpExportTarget(target, credential(spec)), url, jobId, prior);
        } catch (IllegalArgumentException refused) {
            respond(response, 400, refused.getMessage());
            return;
        }
        response.setHeader("Content-Type", "application/json");
        respond(response, 202, JSON.writeValueAsString(Map.of("job", jobId, "state", "running")));
    }

    @GetMapping("/api/repository/export/{id}")
    public void status(@RequestParam("repo") String repo, @PathVariable("id") String id,
                       HttpServletRequest request, HttpServletResponse response) throws IOException {
        Optional<ArtifactStore> store = store(repo, request);
        if (store.isEmpty()) {
            respond(response, 404, "no such repository");
            return;
        }
        Optional<byte[]> state = new ExportJobs().status(store.get(), id);
        if (state.isEmpty()) {
            respond(response, 404, "no export job " + id);
            return;
        }
        response.setHeader("Content-Type", "application/json");
        response.setStatus(200);
        try (OutputStream out = response.getOutputStream()) {
            out.write(state.get());
        }
    }

    private static Optional<ExportTarget.Credential> credential(JsonNode spec) {
        String username = spec.path("username").asString(null);
        String password = spec.path("password").asString(null);
        String token = spec.path("token").asString(null);
        if (password != null && !password.isBlank()) {
            return Optional.of(new ExportTarget.Credential(Optional.ofNullable(username), password));
        }
        if (token != null && !token.isBlank()) {
            return Optional.of(new ExportTarget.Credential(Optional.empty(), token));
        }
        return Optional.empty();
    }

    private Optional<ArtifactStore> store(String repo, HttpServletRequest request) {
        if (!Scopes.valid(repo)) {
            return Optional.empty();
        }
        return routing.route(routing.tenant(request), repo, "/").map(RepositoryRouting.Route::store);
    }

    /** The migration screen's dial, shared with the import: unset or {@code true} blocks. */
    private boolean blockPrivateHosts() {
        String value = settings.apply("block-private-import-hosts");
        return value == null || value.isBlank() || Boolean.parseBoolean(value);
    }

    private static void respond(HttpServletResponse response, int status, String body) throws IOException {
        response.setStatus(status);
        try (OutputStream out = response.getOutputStream()) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
    }
}
