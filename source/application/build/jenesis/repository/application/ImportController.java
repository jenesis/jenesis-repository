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
import build.jenesis.repository.store.JobState;
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
 * The trigger for a migration off an incumbent manager, asynchronous so the call returns at once.
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
     * {@code POST /api/repository/import?repo=<repo>} with an {@link ImportRequestBody} starts a background job
     * ({@link ImportJobs}) walking the named source repository into this repository's hosted store and answers
     * {@code 202} with the job id; {@code GET /api/repository/import/<id>?repo=<repo>} returns its state and counts.
     * Starting needs {@code repository:write}, reading {@code repository:read}; a proxy or group answers {@code 405}.
     * A {@code resume} naming a prior job continues from its recorded continuation token and counts.
     *
     * <p>Every asset is screened against its target coordinate before it is laid out, so a migration passes the same
     * gate a deploy does: an accepted asset is laid out from the screened blob, a quarantined one is counted
     * {@code held} with its replay context recorded so a release materialises it, a rejected one is counted
     * {@code rejected}, and an asset whose format has no importer is counted skipped.
     */
    @PostMapping("/api/repository/import")
    @ResponseBody
    public ImportJob importRepository(@RequestParam("repo") String repo,
                                      @RequestHeader(value = Repositories.KEY, required = false) String key,
                                      @RequestBody(required = false) ImportRequestBody request,
                                      HttpServletRequest servlet, HttpServletResponse response) throws IOException {
        // Answered before the request is read, so a deployment with no fetcher says so to any request.
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
        // The resume id becomes a store key and a JSON value is not normalised by the container, so a parent segment
        // is refused here.
        if (request.resume() != null) {
            RepositoryRequests.rejectTraversal(request.resume());
        }
        return Observations.observe(observations, "jenrepo.import", repo, tenant, observation -> {
            observation.lowCardinalityKeyValue("source",
                    request.source() == null || request.source().isBlank() ? "none" : request.source());
            ImportJobs jobs = new ImportJobs();
            ImportJobs.Snapshot prior = request.resume() == null
                    ? null : jobs.snapshot(store, request.resume()).orElse(null);
            // The stored block-private-import-hosts over the deployment's value, blocking when neither is set.
            Boolean storedGuard = ImportHostGuard.stored(settings.getOrDefault("block-private-import-hosts", null));
            ImportSource source = source(request, prior == null ? null : prior.cursor(),
                    properties.importHostsGuarded(storedGuard));
            if (source == null) {
                response.setStatus(400);
                return null;
            }
            String jobId = prior == null ? ImportJobs.newId() : request.resume();
            // The job runs on a fresh virtual thread with no tenant bound, where the gate would resolve the
            // deployment-wide policy; binding the tenant around the body screens with this tenant's.
            UnaryOperator<Runnable> jobScope = body -> () -> {
                try (PublishTenant.Scope scope = PublishTenant.open(tenant)) {
                    body.run();
                }
            };
            // Each quarantined asset records its replay context keyed by the target path, method IMPORT, the format's
            // ecosystem and the blob hash - plus the source path, which the importer's layout is keyed on - so a
            // release re-drives the importer and materialises it.
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
            jobs.submit(store, source, jobId, prior, listener, jobScope);
            // Audited best-effort once the job is submitted, naming source and target as the console leg does.
            audit.record(tenant, key == null ? "anonymous" : Authorization.hash(key), AuditActions.REPOSITORY_IMPORT,
                    repo + " from " + (request.source() == null || request.source().isBlank() ? "none" : request.source()));
            response.setStatus(202);
            return new ImportJob(jobId, JobState.RUNNING);
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

    /** The store a migration into {@code repo} writes to and reads its jobs from: the repository's own when it is
     *  writable, else {@code 405} for a proxy or group. An unconfigured name is a plain writable repository. */
    private ArtifactStore importStore(String repo, String tenant, HttpServletResponse response) {
        String target = router.writeTarget(tenant, repo);
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
        // The screen the console leg shares: https, and a host that does not resolve internally. Its own reason is
        // returned, so a plaintext URL on a public host is not told to look at its host.
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
        // Either half alone is a real credential: a key or token is sent as one half with no other.
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
        // A source switched off with jenrepo.<name>=false is unusable here, exactly like an absent module.
        ImportSourceProvider provider = ImportSourceProvider
                .installed(source, Features.namespaced(environment::getProperty))
                .orElse(null);
        return provider == null ? null : provider.create(sourceRequest, upstreamFetcher);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public void badRequest(HttpServletResponse response) {
        response.setStatus(400);
    }

    /** A resume that lost to a reap - the job it named was dismissed meanwhile, and starting again is the answer -
     *  or that named a job still running. */
    @ExceptionHandler({JobState.Dismissed.class, JobState.Running.class})
    public void taken(IOException taken, HttpServletResponse response) throws IOException {
        response.setStatus(409);
        response.setContentType("text/plain;charset=UTF-8");
        response.getWriter().write(taken.getMessage());
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
