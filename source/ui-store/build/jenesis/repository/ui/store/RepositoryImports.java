package build.jenesis.repository.ui.store;

import module java.base;

import build.jenesis.repository.store.Documents;
import build.jenesis.repository.store.Features;
import build.jenesis.repository.ui.CurrentTenant;
import build.jenesis.repository.server.kernel.Settings;
import build.jenesis.repository.server.kernel.SettingsEditor;
import build.jenesis.repository.audit.AuditActions;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.format.FetcherProvider;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.importer.ImportRequest;
import build.jenesis.repository.importer.ImportSource;
import build.jenesis.repository.importer.ImportSourceProvider;
import build.jenesis.repository.server.ImportJobs;
import build.jenesis.repository.settings.ImportHostGuard;
import build.jenesis.repository.store.ArtifactStore;
import io.micrometer.observation.ObservationRegistry;

/**
 * The console's migration jobs, scoped to the signed-in tenant: start (or resume) a background migration of another
 * manager's repository into one of the tenant's, watch and dismiss those jobs, and guard the operator-supplied URL
 * against being turned into a server-side request against a private/loopback host.
 */
public class RepositoryImports extends TenantScope {

    /** The deployment's settings, read as the API's import leg reads them. */
    private final Settings settings;

    public RepositoryImports(ArtifactStore repositoryStore, CurrentTenant current, ObservationRegistry observations,
                             AuditTrail audit, ConsoleActor actor, SettingsEditor editor) {
        super(repositoryStore, current, observations, audit, actor);
        this.settings = editor.settings();
    }

    /** Starts a background migration of another manager's repository into this one, over the store the server serves,
     *  with the installed importers; returns the job id. */
    public String startImport(String repository, String source, String url, String sourceRepository, String format,
                              String username, String password, String resume) throws IOException {
        return observe("import", repository, observation -> {
            ArtifactStore store = scope(repository);
            ImportJobs jobs = new ImportJobs();
            ImportJobs.Snapshot prior = resume == null || resume.isBlank()
                    ? null : jobs.snapshot(store, resume).orElse(null);
            // The guard both import legs share; the console has no deployment property to pass.
            boolean blockPrivateHosts = ImportHostGuard.blockPrivateHosts(
                    ImportHostGuard.stored(settings.getOrDefault("block-private-import-hosts", null)), null);
            ImportSource importSource = importSource(source, url, sourceRepository, format, username, password,
                    prior == null ? null : prior.cursor(), blockPrivateHosts);
            if (importSource == null) {
                throw new IllegalArgumentException("A source this deployment carries, its URL and its repository "
                        + "are required (and a format when the source needs one).");
            }
            String jobId = prior == null ? ImportJobs.newId() : resume;
            writeSource(store, jobId, source, url, sourceRepository, format);
            jobs.submit(store, importSource, jobId, prior);
            // Audited once submitted, as the API's import is.
            audit(AuditActions.REPOSITORY_IMPORT, repository + " from " + (source == null || source.isBlank() ? "none" : source));
            observation.lowCardinalityKeyValue("source", source == null || source.isBlank() ? "none" : source)
                    .highCardinalityKeyValue("job", jobId);
            return jobId;
        });
    }

    /** Forgets a finished or failed migration job and its remembered source; a running one is left alone. Returns
     *  whether it was dismissed. */
    public boolean dismiss(String repository, String jobId) throws IOException {
        ArtifactStore store = scope(repository);
        Optional<ImportJobs.Snapshot> snapshot = new ImportJobs().snapshot(store, jobId);
        if (snapshot.isPresent() && "running".equals(snapshot.get().state())) {
            return false;
        }
        store.delete("imports/" + jobId);
        store.delete("import-source/" + jobId);
        store.delete("import-expiry/" + jobId);      // the cleanup pass's first-seen-terminal marker, if one was set
        return true;
    }

    /** The migrations one page of the screen lists. */
    public static final int PAGE = 50;

    /** The first page of a repository's migration jobs, each with the source it walked (no credentials), so a resume
     *  can be pre-filled. */
    public List<JobView> imports(String repository) throws IOException {
        return imports(repository, null, PAGE).jobs();
    }

    /** One page of a repository's migrations, in job-id order; {@code next} resumes after it, null on the last. */
    public record JobPage(List<JobView> jobs, String next) {
    }

    public JobPage imports(String repository, String after, int limit) throws IOException {
        ArtifactStore store = scope(repository);
        ImportJobs jobs = new ImportJobs();
        List<JobView> views = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        int page = Math.clamp(limit, 1, PAGE);
        store.page("imports", after == null ? "" : after, ArtifactStore.oneMoreThan(page), ids::add);
        boolean more = ids.size() > page;
        List<String> window = more ? ids.subList(0, page) : ids;
        for (String id : window) {
            Optional<ImportJobs.Snapshot> snapshot = jobs.snapshot(store, id);
            if (snapshot.isPresent()) {
                ImportJobs.Snapshot state = snapshot.get();
                Properties remembered = readSource(store, id);
                views.add(new JobView(id, state.state(), state.imported(), state.skipped(),
                        state.held(), state.rejected(), state.droppedTotal(), state.cursor(), state.asset(),
                        state.error(),
                        remembered.getProperty("source", ""), remembered.getProperty("url", ""),
                        remembered.getProperty("repository", ""), remembered.getProperty("format", "")));
            }
        }
        return new JobPage(views, more ? window.getLast() : null);
    }

    private static ImportSource importSource(String source, String url, String sourceRepository, String format,
                                             String username, String password, String cursor,
                                             boolean blockPrivateHosts) {
        if (url == null || url.isBlank() || sourceRepository == null || sourceRepository.isBlank()) {
            return null;
        }
        // The panel names the half of the guard that refused.
        String refusal = ImportHostGuard.refusalReason(url, blockPrivateHosts);
        if (refusal != null) {
            throw new IllegalArgumentException("The migration URL is refused: " + refusal + ". A migration is fetched "
                    + "server-side with the upstream credentials attached, so it must be an https URL to a public "
                    + "host; set block-private-import-hosts=false to migrate from an internal or plaintext mirror.");
        }
        ImportRequest request = new ImportRequest(URI.create(url), sourceRepository);
        if (format != null && !format.isBlank()) {
            request = request.withFormat(format);
        }
        if (username != null && !username.isBlank() && password != null && !password.isBlank()) {
            request = request.withCredentials(username, password);
        }
        if (cursor != null) {
            request = request.withCursor(cursor);
        }
        if (source == null || source.isBlank()) {
            return null;
        }
        // installed(): a connector configured off is as unreachable from a console job as from the API's edge.
        ImportSourceProvider provider = ImportSourceProvider.installed(source, Features.settings()).orElse(null);
        ProxyFormat.Fetcher fetcher = FetcherProvider.resolve(_ -> null);
        if (fetcher == ProxyFormat.Fetcher.NONE) {
            throw new IllegalStateException("No upstream fetcher module is installed on this deployment.");
        }
        return provider == null ? null : provider.create(request, fetcher);
    }

    /** Remember a job's source (the kind, URL, source repository and format - never the credentials), so a resume can
     *  pre-fill the form and the operator need only re-enter the credentials. */
    private static void writeSource(ArtifactStore store, String jobId, String source, String url,
                                    String sourceRepository, String format) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("source", source);
        properties.setProperty("url", url);
        properties.setProperty("repository", sourceRepository);
        if (format != null && !format.isBlank()) {
            properties.setProperty("format", format);
        }
        store.write("import-source/" + jobId, new ByteArrayInputStream(Documents.bytes(properties)));
    }

    private static Properties readSource(ArtifactStore store, String jobId) throws IOException {
        Properties properties = new Properties();
        Optional<ArtifactStore.Versioned> object = store.readVersioned("import-source/" + jobId);
        if (object.isPresent()) {
            properties.load(new ByteArrayInputStream(object.get().content()));
        }
        return properties;
    }

    /** A migration job as the console lists it: state, counts ({@code held} and {@code rejected} by the import edge),
     *  resume cursor, error, and the source it walked, without credentials. */
    public record JobView(String id, String state, int imported, int skipped, int held, int rejected, int dropped,
                          String cursor, String asset, String error, String source, String url,
                          String sourceRepository, String format) {
    }
}
