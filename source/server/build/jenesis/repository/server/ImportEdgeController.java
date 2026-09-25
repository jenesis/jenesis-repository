package build.jenesis.repository.server;
import module java.base;

import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.server.spi.ImportEdgeProvider;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.importer.ImportRequest;
import build.jenesis.repository.importer.ImportScreen;
import build.jenesis.repository.importer.ImportSource;
import build.jenesis.repository.importer.ImportSourceProvider;
import build.jenesis.repository.scope.Scopes;
import build.jenesis.repository.store.ArtifactStore;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The free import edge: the {@code /api/repository/import?repo=<repository>} migration trigger and its status read, peeled out of {@link RepositoryController} into its own controller bean so a richer distribution can OWN the
 * import edge without a cross-layer mapping override. It triggers an asynchronous migration through the first
 * {@link ImportSourceProvider} that handles the requested source - discovered with {@code ServiceLoader} like the
 * formats, so the server knows no incumbent by name - run as a background {@link ImportJobs} writing into the request's
 * routed artifact space (so an import lands exactly where serving reads, and lays out only the formats that repository
 * holds), and {@code GET /api/repository/import/<id>?repo=<repository>} returns its state.
 *
 * <p>This edge is registered <em>only when no {@link ImportEdgeProvider} is installed</em> (see
 * {@link RepositoryAutoConfiguration}). When a distribution ships an {@code ImportEdgeProvider} - the downstream
 * edition's tenant-scoped {@code /api/repository/import} with its audited, SSRF-screened choreography - this
 * free controller is simply not created, so its mapping never joins the handler mapping and the distribution's
 * controller is the only import edge: the downstream edition no longer needs a {@code WebMvcRegistrations} bean to
 * suppress the mapping. With no provider installed (the product) the edge is served exactly as before,
 * byte-for-byte unchanged.
 *
 * <p>Authorization is not done here: {@link RepositorySecurityAutoConfiguration} gates the wire through the
 * {@link Authorization} credential model, exactly as it does for the rest of {@link RepositoryController}'s surface.
 */
@RestController
public class ImportEdgeController {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RepositoryRouting routing;
    private final List<ImportSourceProvider> importSources;
    private final ProxyFormat.Fetcher fetcher;
    private final UnaryOperator<String> settings;

    /** As {@link RepositoryController}, this reads its deployment toggles (read-only, the import SSRF screen) off the
     *  shared {@code jenreg.*} settings through {@code settings}, so the import edge needs no extra
     *  dependency; {@code key -> null} keeps every toggle on its shipped default. */
    public ImportEdgeController(RepositoryRouting routing,
                                List<ImportSourceProvider> importSources,
                                ProxyFormat.Fetcher fetcher,
                                UnaryOperator<String> settings) {
        this.routing = routing;
        this.importSources = importSources;
        this.fetcher = fetcher;
        this.settings = settings;
    }

    /**
     * The admin trigger for a migration, asynchronous so the call returns at once: a small JSON body
     * ({@code {"source":"nexus|artifactory|maven|jenesis","url":...,"repository":...,"format":...,"username":...,
     * "password":...,"resume":...}}) starts a background job (see {@link ImportJobs}) and answers {@code 202} with
     * its id. The format ({@code maven}, {@code docker}, {@code raw}) is required for an Artifactory source and
     * optional for the others. A {@code resume} naming a prior job continues its walk from the recorded continuation
     * token and counts.
     */
    @PostMapping("/api/repository/import")
    public void submitImport(@RequestParam("repo") String repo,
                             @RequestBody(required = false) String body,
                             HttpServletRequest request,
                             HttpServletResponse response)
            throws IOException {
        if (readOnly()) {
            respond(response, 403, "this instance is in read-only mode: import is refused");
            return;
        }
        if (fetcher == ProxyFormat.Fetcher.NONE) {
            respond(response, 501, "no upstream fetcher module is installed on this deployment");
            return;
        }
        // The import writes into the same routed artifact space serving reads from, so a migrated artifact is
        // found where a later request looks for it; the job state rides along under that space's imports/ keys.
        Optional<ArtifactStore> routed = store(repo, request);
        if (routed.isEmpty()) {
            respond(response, 404, "no such repository");
            return;
        }
        ArtifactStore store = routed.get();
        ImportJobs jobs = new ImportJobs();
        JsonNode spec = JSON.readTree(body == null || body.isBlank() ? "{}" : body);
        String url = spec.path("url").asString(null);
        String repository = spec.path("repository").asString(null);
        if (url == null || repository == null) {
            respond(response, 400, "url and repository are required");
            return;
        }
        // The import screen, both halves under the one block-private-import-hosts dial (ImportScreen.refusalReason):
        // the transport must be https, because the request below attaches the operator's upstream username and
        // password and a plaintext migration hands them to every observer on the path; and the host must not resolve
        // internally, because with the anonymous-possible default an unguarded import URL otherwise turns this
        // endpoint into a proxy for the deployment's own network (169.254.169.254, a loopback control plane, an
        // internal host). The reason is carried through rather than flattened, so an operator whose source is
        // plaintext on a perfectly public host is not told to go and look at its host. On by default; an
        // internal or plaintext on-prem migration opts out with jenreg.block-private-import-hosts=false.
        String refusal = ImportScreen.refusalReason(url, blockPrivateImportHosts());
        if (refusal != null) {
            respond(response, 400, "import url is refused: " + refusal + "; a migration is walked server-side with "
                    + "the upstream credentials attached, so it must be an https URL to a public host (set "
                    + "jenreg.block-private-import-hosts=false to migrate from an internal or plaintext "
                    + "mirror)");
            return;
        }
        URI target;
        try {
            target = URI.create(url);
        } catch (IllegalArgumentException _) {
            // Reachable only with the dial off, where the screen above returns without parsing: a malformed URL is a
            // bad request, not the unmapped 500 an escaping IllegalArgumentException would have been.
            respond(response, 400, "import url is refused: the URL is malformed");
            return;
        }
        String resume = spec.path("resume").asString(null);
        ImportJobs.Snapshot prior = resume == null ? null : jobs.snapshot(store, resume).orElse(null);
        String cursor = prior == null ? null : prior.cursor();
        String sourceName = spec.path("source").asString(null);
        ImportRequest importRequest = new ImportRequest(target, repository)
                .withFormat(spec.path("format").asString(null))
                .withCredentials(spec.path("username").asString(null), spec.path("password").asString(null))
                .withCursor(cursor);
        ImportSource source = importSources.stream()
                .filter(provider -> provider.handles(sourceName))
                .findFirst()
                // open(), not create(): the fetcher a connector walks with is screened against the URL the operator
                // submitted, so every per-asset URL a listing hands back is judged before it is fetched. A connector
                // carries no screen of its own, and one added tomorrow arrives screened.
                .map(provider -> ImportSourceProvider.open(provider, importRequest, fetcher))
                .orElse(null);
        if (source == null) {
            respond(response, 400, "unknown import source, or its configuration is incomplete");
            return;
        }
        String jobId = prior == null ? ImportJobs.newId() : resume;
        try {
            jobs.submit(store, source, jobId, prior == null ? 0 : prior.imported(), prior == null ? 0 : prior.skipped());
        } catch (IllegalArgumentException refused) {
            respond(response, 400, refused.getMessage());
            return;
        }
        response.setHeader("Content-Type", "application/json");
        respond(response, 202, JSON.writeValueAsString(Map.of("job", jobId, "state", "running")));
    }

    /** The routed artifact space of {@code repo} in the tenant the request answers for, or empty when the routing
     *  cannot resolve one; a name that is not routable is a {@code 400}. */
    private Optional<ArtifactStore> store(String repo, HttpServletRequest request) {
        if (!Scopes.valid(repo)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a routable repository name");
        }
        return routing.route(routing.tenant(request), repo, "/").map(RepositoryRouting.Route::store);
    }

    /** Return a job's persisted state as raw JSON ({@code 404} if there is no such job). */
    @GetMapping("/api/repository/import/{id}")
    public void importStatus(@RequestParam("repo") String repo,
                             @PathVariable("id") String id,
                             HttpServletRequest request,
                             HttpServletResponse response) throws IOException {
        Optional<ArtifactStore> store = store(repo, request);
        if (store.isEmpty()) {
            respond(response, 404, "no such repository");
            return;
        }
        Optional<byte[]> state = new ImportJobs().status(store.get(), id);
        if (state.isEmpty()) {
            response.setStatus(404);
            return;
        }
        response.setHeader("Content-Type", "application/json");
        response.setStatus(200);
        try (OutputStream out = response.getOutputStream()) {
            out.write(state.get());
        }
    }

    /** The deployment-wide read-only flag, read off the same {@code jenreg.*} settings the formats read a
     *  toggle from, so no extra dependency is threaded in; unset means read-write. */
    private boolean readOnly() {
        return Boolean.parseBoolean(settings.apply("read-only"));
    }

    /** The import screen is on by default (the secure default) and this one dial governs <em>both</em> its halves -
     *  the transport and the host - so an internal <em>or</em> plaintext on-premises migration opts out with
     *  {@code jenreg.block-private-import-hosts=false} and nothing can be opted out of alone. Read off the
     *  same settings the read-only flag reads, so no extra dependency is threaded in - unset or blank blocks, and so
     *  does an explicit {@code true}. */
    private boolean blockPrivateImportHosts() {
        String value = settings.apply("block-private-import-hosts");
        return value == null || value.isBlank() || Boolean.parseBoolean(value);
    }

    private static void respond(HttpServletResponse response, int status, String body) throws IOException {
        response.setStatus(status);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = response.getOutputStream()) {
            out.write(bytes);
        }
    }
}
