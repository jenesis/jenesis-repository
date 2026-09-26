package build.jenesis.repository.export;

import module java.base;
import build.jenesis.repository.server.RepositoryRouting;
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
 * for npm - of another deployment of this product or of any repository manager. What is refused and why is
 * {@link Exports}', which the console's export screen calls too.
 *
 * <p>Authorization is the security chain's: an export sends a repository's contents wherever it is told to, so it
 * takes the manage rights on that repository.
 */
@RestController
public class ExportController {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Exports exports;
    private final RepositoryRouting routing;

    public ExportController(Exports exports, RepositoryRouting routing) {
        this.exports = exports;
        this.routing = routing;
    }

    @PostMapping("/api/repository/export")
    public void submit(@RequestParam("repo") String repo, @RequestBody(required = false) String body,
                       HttpServletRequest request, HttpServletResponse response) throws IOException {
        JsonNode spec = JSON.readTree(body == null || body.isBlank() ? "{}" : body);
        Exports.Started started = exports.start(routing.tenant(request), repo, spec.path("url").asString(null),
                Exports.credential(spec.path("username").asString(null), spec.path("password").asString(null),
                        spec.path("token").asString(null)),
                spec.path("resume").asString(null));
        if (!started.accepted()) {
            respond(response, started.status(), started.reason());
            return;
        }
        response.setHeader("Content-Type", "application/json");
        respond(response, 202, JSON.writeValueAsString(Map.of("job", started.job(), "state", "running")));
    }

    @GetMapping("/api/repository/export/{id}")
    public void status(@RequestParam("repo") String repo, @PathVariable("id") String id,
                       HttpServletRequest request, HttpServletResponse response) throws IOException {
        Optional<byte[]> state = exports.status(routing.tenant(request), repo, id);
        if (state.isEmpty()) {
            respond(response, 404, "no such repository, or no export job " + id + " in it");
            return;
        }
        response.setHeader("Content-Type", "application/json");
        response.setStatus(200);
        try (OutputStream out = response.getOutputStream()) {
            out.write(state.get());
        }
    }

    private static void respond(HttpServletResponse response, int status, String body) throws IOException {
        response.setStatus(status);
        try (OutputStream out = response.getOutputStream()) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
    }
}
