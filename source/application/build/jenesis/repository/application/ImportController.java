package build.jenesis.repository.application;

import module java.base;

import build.jenesis.repository.server.kernel.PublishTenant;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.server.RepositoryProperties;
import build.jenesis.repository.server.kernel.RepositoryRequests;
import build.jenesis.repository.server.kernel.Settings;
import build.jenesis.repository.audit.AuditActions;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.gate.QuarantineDispatch;
import build.jenesis.repository.gateway.RepositoryRouter;
import build.jenesis.repository.importer.ImportRequest;
import build.jenesis.repository.importer.ImportSource;
import build.jenesis.repository.importer.ImportSourceProvider;
import build.jenesis.repository.server.ImportJobs;
import build.jenesis.repository.server.Observations;
import build.jenesis.repository.server.RepositoryImport;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.settings.ImportHostGuard;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Features;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import build.jenesis.repository.server.RepositoryRouting;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The trigger for a migration off an incumbent manager, asynchronous so the call returns at once. One of the
 * focused controllers the {@code RepositoryController} monolith split into: the import surface is
 * core to the app (it routes writes into the repository's hosted store), not a removable feature.
 */
@RestController
public class ImportController {

    private final Repositories repositories;
    private final RepositoryRouting routing;
    private final RepositoryRouter router;
    private final RepositoryProperties properties;
    private final Settings settings;
    private final ProxyFormat.Fetcher upstreamFetcher;
    private final ObservationRegistry observations;
    private final Environment environment;
    private final AuditTrail audit;

    public ImportController(Repositories repositories, RepositoryRouting routing, RepositoryRouter router,
                            RepositoryProperties properties,
                            Settings settings, ProxyFormat.Fetcher upstreamFetcher, ObservationRegistry observations,
                            Environment environment, AuditTrail audit) {
        this.repositories = repositories;
        this.routing = routing;
        this.router = router;
        this.properties = properties;
        this.settings = settings;
        this.upstreamFetcher = upstreamFetcher;
        this.observations = observations;
        this.environment = environment;
        this.audit = audit;
    }

    /**
     * {@code POST /api/repository/import?repo=<repo>} with a JSON body ({@link ImportRequestBody}) starts a background job (see
     * {@link ImportJobs}) walking the named source repository (whichever incumbents the installed import-source
     * modules connect to) into this repository's store, and answers {@code 202} with the job id;
     * {@code GET /api/repository/import/<id>?repo=<repo>} returns its state and counts. It needs {@code repository:write} (a status
     * read needs {@code repository:read}) and routes the write into the repository's hosted target (a proxy
     * repository is read-only - {@code 405}). The importers on this edition's module path decide coverage: every
     * installed format carrying the importer capability; an asset whose format has no importer is reported
     * skipped. A {@code resume} naming a prior job continues its walk from the recorded continuation token and
     * counts. Each asset is screened INLINE at the import edge (EPIC 26): the walk screens every asset against its
     * target coordinate before the layout-only importer lays it out, so a migration lands the same compliance gate a
     * deploy or batch upload passes - an accepted asset is laid out from the screened blob, a quarantined one is
     * counted {@code held} (its replay context recorded beside the hold so a review release materialises it), and a
     * rejected one is counted {@code rejected} and skipped.
     */
    @PostMapping("/api/repository/import")
    @ResponseBody
    public ImportJob importRepository(@RequestParam("repo") String repo,
                                      @RequestHeader(value = Repositories.KEY, required = false) String key,
                                      @RequestBody(required = false) ImportRequestBody request,
                                      HttpServletRequest servlet, HttpServletResponse response) throws IOException {
        // Whether a migration can run at all is answered before what was asked for is read, so a deployment with no
        // fetcher says so to any request rather than to a well-formed one only.
        if (upstreamFetcher == ProxyFormat.Fetcher.NONE) {
            response.setStatus(501);
            return null;
        }
        if (request == null) {
            response.setStatus(400);
            return null;
        }
        String tenant = tenant(repo, servlet);
        ArtifactStore store = importStore(repo, tenant, response);
        if (store == null) {
            return null;
        }
        // The resume id becomes the imports/<id> store key on both the snapshot read and the job write; a JSON body
        // value is not normalised by the servlet container, so guard it against a parent-directory segment (a 400 via
        // the handler below) rather than let it aim a store key outside the imports/ subtree - the peer id guards.
        if (request.resume() != null) {
            RepositoryRequests.rejectTraversal(request.resume());
        }
        return Observations.observe(observations, "jenreg.import", repo, tenant, observation -> {
            observation.lowCardinalityKeyValue("source",
                    request.source() == null || request.source().isBlank() ? "none" : request.source());
            ImportJobs jobs = new ImportJobs();
            ImportJobs.Snapshot prior = request.resume() == null
                    ? null : jobs.snapshot(store, request.resume()).orElse(null);
            // Resolve the SSRF-guard enable decision through the one resolver both import legs share: the stored
            // block-private-import-hosts setting layered over the deployment env-field, fail-closed (block) when
            // neither is set - so a fixed-edition API import no longer defaults SSRF-open.
            Boolean storedGuard = ImportHostGuard.stored(settings.getOrDefault("block-private-import-hosts", null));
            ImportSource source = source(request, prior == null ? null : prior.cursor(),
                    properties.importHostsGuarded(storedGuard));
            if (source == null) {
                response.setStatus(400);
                return null;
            }
            String jobId = prior == null ? ImportJobs.newId() : request.resume();
            // The import job runs on a fresh unbound virtual thread (ImportJobs.submit -> Thread.ofVirtual), where
            // PublishTenant.current() is null and the discovered gate would resolve the DEPLOYMENT-wide policy instead
            // of this tenant's. Bind the tenant around the whole job body through the job-scope seam, so the screen
            // on the job thread resolves the tenant's own policy (Q3 subtlety, /).
            UnaryOperator<Runnable> jobScope = body -> () -> {
                try (PublishTenant.Scope scope = PublishTenant.open(tenant)) {
                    body.run();
                }
            };
            // Record the held replay context beside every quarantined asset: the QuarantineDispatch shape keyed by the
            // target path (the /quarantine<path> hold pointer), method IMPORT, the format's ecosystem (which importer
            // owns the layout) and the stored blob hash. An import replay reproduces its body from the blob and captures
            // no HTTP framing header; the one piece of replay context an import needs beyond a deploy is the SOURCE path
            // the walk reached - the importer's describe/importArtifact are keyed on it (e.g. Maven's importArtifact
            // prepends /maven/ to its path), so re-driving importArtifact from the target path would mis-lay it out.
            // It rides the dispatch's context map. HoldLifecycle.release re-drives importArtifact from this context so a
            // released imported asset materialises.
            RepositoryImport.Listener listener = new RepositoryImport.Listener() {
                @Override
                public void held(String path, ArtifactDescriptor descriptor, String hash) {
                    try {
                        QuarantineDispatch.record(store, descriptor.path(), descriptor.ecosystem(),
                                QuarantineDispatch.IMPORT, hash, Map.of(QuarantineDispatch.IMPORT_SOURCE_PATH, path));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
            };
            jobs.submit(store, source, jobId, prior == null ? 0 : prior.imported(),
                    prior == null ? 0 : prior.skipped(), listener, jobScope);
            // A bulk migration is a privileged mutation that routes writes into the hosted store; audit the
            // trigger as its /api peers audit theirs (best-effort, so it never fails the started job). Recorded only
            // once the job is actually submitted, and naming the source and target the way the console leg does.
            audit.record(tenant, key == null ? "anonymous" : Authorization.hash(key), AuditActions.REPOSITORY_IMPORT,
                    repo + " from " + (request.source() == null || request.source().isBlank() ? "none" : request.source()));
            response.setStatus(202);
            return new ImportJob(jobId, "running");
        });
    }

    @GetMapping("/api/repository/import/{job}")
    @ResponseBody
    public ImportJobs.Snapshot importStatus(@RequestParam("repo") String repo, @PathVariable("job") String job,
                                            @RequestHeader(value = Repositories.KEY, required = false) String key,
                                            HttpServletRequest servlet, HttpServletResponse response)
            throws IOException {
        String tenant = tenant(repo, servlet);
        ArtifactStore store = importStore(repo, tenant, response);
        if (store == null) {
            return null;
        }
        ImportJobs.Snapshot snapshot = new ImportJobs().snapshot(store, job).orElse(null);
        if (snapshot == null) {
            response.setStatus(404);
        }
        return snapshot;
    }

    /** The tenant an import answers for - the routing's, for a request that names none in its URL - once the
     *  repository it names has been checked as a routable name, since it is about to scope the store. */
    private String tenant(String repo, HttpServletRequest request) {
        if (!Repositories.valid(repo)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a routable repository name");
        }
        return routing.tenant(request);
    }

    /** The store a migration into {@code repo} writes to and reads its jobs from: the repository's OWN store when it is
     *  {@code writable} (EPIC 25 §2.3 - {@code writeTarget} is writable-only, no push-delegation), or {@code 405} when
     *  it is a read-only proxy/group. An unconfigured name is a plain writable repository. */
    private ArtifactStore importStore(String repo, String tenant, HttpServletResponse response) {
        String target = router.writeTarget(repo);
        if (target == null) {
            response.setStatus(405);
            return null;
        }
        return repositories.store(tenant, target);
    }

    private ImportSource source(ImportRequestBody request, String cursor, boolean blockPrivateHosts) {
        if (request.url() == null || request.repository() == null) {
            return null;
        }
        // One screen, shared with the console leg: the transport must be https AND the host must not resolve
        // internally, both under the single block-private-import-hosts dial. The reason is carried through rather
        // than flattened into one sentence, because a caller whose URL is plaintext on a perfectly public host must
        // not be told to go and look at its host - the 400 below is the only place this refusal is ever stated.
        String refusal = ImportHostGuard.refusalReason(request.url(), blockPrivateHosts);
        if (refusal != null) {
            throw new IllegalArgumentException("The import URL is refused: " + refusal + ". A migration is fetched "
                    + "server-side with the upstream credentials attached, so it must be an https URL to a public "
                    + "host; set block-private-import-hosts=false to migrate from an internal or plaintext mirror.");
        }
        // Parsed after the screen, so a URL the screen would reject never depends on URI.create's own message.
        ImportRequest sourceRequest = new ImportRequest(URI.create(request.url()), request.repository());
        if (request.format() != null) {
            sourceRequest = sourceRequest.withFormat(request.format());
        }
        // Either half alone is a real credential: the jenesis connector takes the key as the password (or the
        // username) with no other half, and a token-authenticated incumbent does the same - requiring both silently
        // dropped the credential and walked the source anonymously, which a private source answers with 401s.
        if (request.username() != null || request.password() != null) {
            sourceRequest = sourceRequest.withCredentials(request.username(), request.password());
        }
        if (cursor != null) {
            sourceRequest = sourceRequest.withCursor(cursor);
        }
        String source = request.source();
        if (source == null || source.isBlank()) {
            return null;
        }
        // Discover the import source, but honour the same Features toggle the format/feed discovery applies
        // (ServingConfig.enabledFormats/importSources): a source disabled with jenreg.<name>=false
        // must degrade exactly like an absent module - it drops out of /api/capabilities and must not be usable
        // here either, or a config-disabled connector stays reachable to any repository:write caller.
        ImportSourceProvider provider = ImportSourceProvider
                .installed(source, Features.namespaced(environment::getProperty))
                .orElse(null);
        return provider == null ? null : provider.create(sourceRequest, upstreamFetcher);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public void badRequest(HttpServletResponse response) {
        response.setStatus(400);
    }

    /** The body of an import request: the source kind (an installed import-source module's name), its base URL and
     *  the source repository to walk, the format (required when the source needs one), optional credentials,
     *  and an optional {@code resume} naming a prior job to continue. */
    public record ImportRequestBody(String source, String url, String repository, String format,
                                    String username, String password, String resume) {
    }

    /** The acknowledgement of a submitted import: the job id to poll and its initial state. */
    public record ImportJob(String job, String state) {
    }
}
