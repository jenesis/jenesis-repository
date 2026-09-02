package build.jenesis.repository.server;
import module java.base;

import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.server.spi.ImportEdgeProvider;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.importer.ImportRequest;
import build.jenesis.repository.importer.ImportScreen;
import build.jenesis.repository.importer.ImportSource;
import build.jenesis.repository.importer.ImportSourceProvider;
import build.jenesis.repository.store.ArtifactStore;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * The free single-tenant import edge: the repo-less {@code /repository/admin/import} migration trigger and its status
 * read, peeled out of {@link RepositoryController} into its own controller bean so a richer distribution can OWN the
 * import edge without a cross-layer mapping override. It triggers an asynchronous migration through the first
 * {@link ImportSourceProvider} that handles the requested source - discovered with {@code ServiceLoader} like the
 * formats, so the server knows no incumbent by name - run as a background {@link ImportJobs} writing into the request's
 * routed artifact space (so an import lands exactly where serving reads), and {@code GET /repository/admin/import/<id>}
 * returns its state.
 *
 * <p>This edge is registered <em>only when no {@link ImportEdgeProvider} is installed</em> (see
 * {@link RepositoryAutoConfiguration}). When a distribution ships an {@code ImportEdgeProvider} - the downstream
 * edition's tenant-scoped {@code /repository/<repo>/admin/import} with its audited, SSRF-screened choreography - this
 * free controller is simply not created, so its mapping never joins the handler mapping and the distribution's
 * controller is the only import edge: the downstream edition no longer needs a {@code WebMvcRegistrations} bean to
 * suppress the free mapping. With no provider installed (the free product) the edge is served exactly as before,
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
     * The migration trigger only accepts {@code POST}; any other method on {@code /admin/import} (a {@code GET}
     * without a job id, say) is a {@code 405}, matching the headless dispatch. The more specific route wins over the
     * format catch-all, so a stray method is rejected here rather than falling through to a {@code 404}.
     */
    @RequestMapping(value = "/repository/admin/import", method = {RequestMethod.GET, RequestMethod.HEAD,
            RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE})
    public void importMethodNotAllowed(HttpServletResponse response) {
        response.setStatus(405);
    }

    /**
     * The admin trigger for a migration, asynchronous so the call returns at once: a small JSON body
     * ({@code {"source":"nexus|artifactory|maven|jenesis","url":...,"repository":...,"format":...,"username":...,
     * "password":...,"resume":...}}) starts a background job (see {@link ImportJobs}) and answers {@code 202} with
     * its id. The format ({@code maven}, {@code docker}, {@code raw}) is required for an Artifactory source and
     * optional for the others. A {@code resume} naming a prior job continues its walk from the recorded continuation
     * token and counts.
     */
    @PostMapping("/repository/admin/import")
    public void submitImport(@RequestBody(required = false) String body,
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
        ArtifactStore store = routing.route(request).store();
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
        jobs.submit(store, source, jobId, prior == null ? 0 : prior.imported(), prior == null ? 0 : prior.skipped());
        response.setHeader("Content-Type", "application/json");
        respond(response, 202, JSON.writeValueAsString(Map.of("job", jobId, "state", "running")));
    }

    /** Return a job's persisted state as raw JSON ({@code 404} if there is no such job). */
    @GetMapping("/repository/admin/import/{id}")
    public void importStatus(@PathVariable("id") String id,
                             HttpServletRequest request,
                             HttpServletResponse response) throws IOException {
        Optional<byte[]> state = new ImportJobs().status(routing.route(request).store(), id);
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
