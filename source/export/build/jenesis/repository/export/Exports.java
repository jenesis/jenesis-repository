package build.jenesis.repository.export;

import module java.base;
import build.jenesis.repository.format.ExportTarget;
import build.jenesis.repository.importer.ImportScreen;
import build.jenesis.repository.scope.Scopes;
import build.jenesis.repository.server.RepositoryRouting;
import build.jenesis.repository.store.ArtifactStore;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Starting an export and reading its state, for every surface that offers one: the API answers from it, and the
 * console's export screen calls it in process, so the two cannot disagree about which URL is refused, which job a
 * resume names or what a job has done.
 *
 * <p>The URL is screened as a migration URL is, under the same dial: https only, and no address that resolves inside
 * the deployment's own network, unless {@code jenreg.block-private-import-hosts=false} allows migrating to one. The
 * credential is kept for the job's life and never written; a resume therefore names it again.
 */
public final class Exports {

    /** How many jobs one page of {@link #jobs} holds. */
    public static final int PAGE = 20;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RepositoryRouting routing;
    private final UnaryOperator<String> settings;

    public Exports(RepositoryRouting routing, UnaryOperator<String> settings) {
        this.routing = routing;
        this.settings = settings;
    }

    /** What starting an export answered: {@code 202} and the job, or the status and the reason it was refused. */
    public record Started(int status, String job, String reason) {

        public boolean accepted() {
            return status == 202;
        }
    }

    /** One job as a screen lists it. */
    public record Job(String id, String state, String target, int published, int present, int withheld,
                      String reached, String error) {

        public boolean running() {
            return "running".equals(state);
        }
    }

    /** A page of jobs, and where the next one starts when there is more. */
    public record JobPage(List<Job> jobs, Optional<String> next) {
    }

    /** Start exporting {@code repo} of {@code tenant} to {@code url}, or resume the job {@code resume} names. */
    public Started start(String tenant, String repo, String url, Optional<ExportTarget.Credential> credential,
                         String resume) throws IOException {
        if (Boolean.parseBoolean(settings.apply("read-only"))) {
            return new Started(403, null,
                    "this instance is in read-only mode, and an export records its progress: refused");
        }
        Optional<ArtifactStore> store = store(tenant, repo);
        if (store.isEmpty()) {
            return new Started(404, null, "no such repository");
        }
        if (url == null || url.isBlank()) {
            return new Started(400, null, "url is required: the URL the format's client would be pointed at");
        }
        String refusal = ImportScreen.refusalReason(url, blockPrivateHosts());
        if (refusal != null) {
            return new Started(400, null, "export url is refused: " + refusal + "; an export sends the repository's "
                    + "contents and a credential, so it must be an https URL to a public host (set "
                    + "jenreg.block-private-import-hosts=false to migrate to an internal or plaintext one)");
        }
        URI target;
        try {
            target = URI.create(url);
        } catch (IllegalArgumentException _) {
            return new Started(400, null, "export url is refused: the URL is malformed");
        }
        ExportJobs jobs = new ExportJobs();
        ExportJobs.Snapshot prior = resume == null ? null : jobs.snapshot(store.get(), resume).orElse(null);
        if (resume != null && prior == null) {
            return new Started(404, null, "no export job " + resume + " to resume");
        }
        String jobId = prior == null ? ExportJobs.newId() : resume;
        try {
            jobs.submit(store.get(), new HttpExportTarget(target, credential), url, jobId, prior);
        } catch (IllegalArgumentException refused) {
            return new Started(400, null, refused.getMessage());
        }
        return new Started(202, jobId, null);
    }

    /** A job's state as the document it is stored as, or empty when the repository or the job does not exist. */
    public Optional<byte[]> status(String tenant, String repo, String id) throws IOException {
        Optional<ArtifactStore> store = store(tenant, repo);
        return store.isEmpty() ? Optional.empty() : new ExportJobs().status(store.get(), id);
    }

    /** One page of the repository's jobs after {@code after}, read one point at a time - never the whole list. */
    public JobPage jobs(String tenant, String repo, String after) throws IOException {
        Optional<ArtifactStore> store = store(tenant, repo);
        if (store.isEmpty()) {
            return new JobPage(List.of(), Optional.empty());
        }
        List<String> ids = new ArrayList<>();
        store.get().page("exports", after == null ? "" : after, PAGE + 1, ids::add);
        List<Job> jobs = new ArrayList<>();
        for (String id : ids.subList(0, Math.min(PAGE, ids.size()))) {
            Optional<byte[]> state = new ExportJobs().status(store.get(), id);
            if (state.isPresent()) {
                JsonNode node = JSON.readTree(state.get());
                jobs.add(new Job(id, node.path("state").asString(""), node.path("target").asString(""),
                        node.path("published").asInt(), node.path("present").asInt(), node.path("withheld").asInt(),
                        node.path("reached").asString(null), node.path("error").asString(null)));
            }
        }
        return new JobPage(jobs, ids.size() > PAGE ? Optional.of(ids.get(PAGE - 1)) : Optional.empty());
    }

    /** A credential from what a form or a request names: a password with its user name, else a token. */
    public static Optional<ExportTarget.Credential> credential(String username, String password, String token) {
        if (password != null && !password.isBlank()) {
            return Optional.of(new ExportTarget.Credential(
                    Optional.ofNullable(username).filter(name -> !name.isBlank()), password));
        }
        if (token != null && !token.isBlank()) {
            return Optional.of(new ExportTarget.Credential(Optional.empty(), token));
        }
        return Optional.empty();
    }

    private Optional<ArtifactStore> store(String tenant, String repo) {
        if (!Scopes.valid(repo)) {
            return Optional.empty();
        }
        return routing.route(tenant, repo, "/").map(RepositoryRouting.Route::store);
    }

    /** The migration screen's dial, shared with the import: unset or {@code true} blocks. */
    private boolean blockPrivateHosts() {
        String value = settings.apply("block-private-import-hosts");
        return value == null || value.isBlank() || Boolean.parseBoolean(value);
    }
}
