package build.jenesis.repository.ui.store;

import module java.base;
import build.jenesis.repository.store.Retries;
import build.jenesis.repository.store.RepositoryDocument;
import build.jenesis.repository.cache.protocol.CacheProtocol;

import build.jenesis.repository.ui.CurrentTenant;
import build.jenesis.repository.cache.storage.CacheStorage;
import build.jenesis.repository.cache.storage.ProjectPolicy;
import build.jenesis.repository.cache.storage.Names;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.walk.Traversal;

/**
 * The build-cache project operations over a {@link CacheStorage} view confined to the session's tenant: listing with
 * stats, creating, editing a project's settings and per-project eviction. Credentials grant projects by role;
 * cross-tenant reclaim is {@link VolumeReclaim}'s.
 */
public class CacheService {

    /** The deployment's cache root; every call scopes it to the selected tenant ({@link #storage()}). */
    private final CacheStorage root;
    private final AuditTrail audit;
    private final CurrentTenant current;
    private final ConsoleActor actor;
    private final Passes passes;
    private final SettingsAdmin settings;

    /**
     * How a background pass is started: {@link #BACKGROUND} as a running console does, or {@link #CALLING_THREAD} for a
     * caller that owns the directory the pass writes into and must not end before it does.
     */
    @FunctionalInterface
    public interface Passes {

        /** Run {@code pass}, which is named for the action and project it is about. */
        void start(String name, Runnable pass);

        /** Each pass on its own virtual thread - what a running console does. */
        Passes BACKGROUND = (name, pass) -> Thread.ofVirtual().name(name).start(pass);

        /** Each pass on the calling thread, so it is over before the caller returns. */
        Passes CALLING_THREAD = (name, pass) -> pass.run();
    }

    public CacheService(CacheStorage root, AuditTrail audit, CurrentTenant current, ConsoleActor actor,
                        SettingsAdmin settings) {
        this(root, audit, current, actor, settings, Passes.BACKGROUND);
    }

    /** {@code root} is the deployment's cache, scoped to the tenant {@code current} names on every call;
     *  {@code settings} reads and writes a project's settings, the policy a sweep started here applies. */
    public CacheService(CacheStorage root, AuditTrail audit, CurrentTenant current, ConsoleActor actor,
                        SettingsAdmin settings, Passes passes) {
        this.settings = settings;
        this.passes = passes;
        this.root = root;
        this.audit = audit;
        this.current = current;
        this.actor = actor;
    }

    /** The selected tenant's cache, resolved now: a pass takes it with it, since the tenant is the request's and the
     *  pass outlives the request. */
    private CacheStorage storage() {
        String tenant = current.name();
        if (tenant == null) {
            throw new IllegalStateException("No tenant selected.");
        }
        return root.scope(tenant);
    }

    /** What a background pass does to the project, over the tenant's cache the request resolved; {@code null} for a
     *  pass that only counts. */
    @FunctionalInterface
    private interface Work {

        Eviction.Result run(CacheStorage scoped) throws Exception;
    }

    /** Records a privileged cache mutation under the selected tenant, attributed to the member; best-effort. */
    private void audit(String action, String project) {
        audit.record(current.name(), actor.name(), action, project);
    }

    /** A project's stored count from the file the passes write: entries and bytes the last pass found and when
     *  ({@code null} before any), whether a pass runs, and what the last eviction did. */
    public record Stats(long entryCount, long totalBytes, Instant countedAt, boolean counting, String lastAction,
                        String lastOutcome) {

        static Stats unknown() {
            return new Stats(0, 0, null, false, "", "");
        }

        public boolean known() {
            return countedAt != null;
        }
    }

    /** A project in the list: its name, the build tool it caches for ({@code null} while untyped), its description,
     *  its stored figures and its own size cap and ttl. */
    public record ProjectSummary(String name, String type, String description, long entryCount, long totalBytes,
                                 String sizeCap, String ttl, Stats stats) {
    }

    /** One project's screen: as {@link ProjectSummary}, with its eviction order. */
    public record ProjectDetail(String name, String type, String description, String size, boolean lru, String ttl,
                                long entryCount, long totalBytes, Stats stats) {
    }

    /** The build tools a project can be a cache for: the cache protocols installed, by name, in order. */
    public static final List<String> TYPES = CacheProtocol.installed().stream().map(CacheProtocol::name).sorted()
            .toList();

    /** The per-project file the passes write their outcome into. */
    static final String STATS_FILE = "stats.properties";

    /** A pass older than this that still says running is taken as crashed, and a new one may start. */
    private static final Duration STALE_PASS = Duration.ofHours(1);

    /** The stored stats of a project - a point read, {@link Stats#unknown()} before any pass has run. */
    public Stats stats(String name) {
        Properties stored = storage().readConfig(name, STATS_FILE);
        String at = stored.getProperty("counted-at", "");
        boolean running = Boolean.parseBoolean(stored.getProperty("running", "false"));
        return new Stats(Long.parseLong(stored.getProperty("entries", "0")),
                Long.parseLong(stored.getProperty("bytes", "0")),
                at.isBlank() ? null : Instant.parse(at), running,
                stored.getProperty("last-action", ""), stored.getProperty("last-outcome", ""));
    }

    /** Count the project's entries in the background and store the figure; whether a pass was started. */
    public boolean recount(String name) throws IOException {
        requireProject(name);
        return pass(name, "count", _ -> null);
    }

    /** Run {@code action} in the background, then count what it left, and store both; refuses to start while a
     *  pass younger than {@link #STALE_PASS} is running. */
    private boolean pass(String name, String action, Work work) throws IOException {
        CacheStorage storage = storage();
        if (!begin(storage, name, action)) {
            return false;
        }
        passes.start("cache-" + action + "-" + name, () -> {
            String outcome;
            try {
                Eviction.Result result = work.run(storage);
                outcome = result == null ? "counted"
                        : "deleted " + result.entriesDeleted() + " entries, freed " + result.bytesFreed() + " bytes";
            } catch (Exception failure) {
                outcome = "failed: " + failure;
            }
            try {
                Eviction.Stats counted = Eviction.stats(storage, name);
                Properties done = storage.readConfig(name, STATS_FILE);
                done.setProperty("entries", Long.toString(counted.entryCount()));
                done.setProperty("bytes", Long.toString(counted.totalBytes()));
                done.setProperty("counted-at", Instant.now().toString());
                done.setProperty("running", "false");
                done.setProperty("last-action", action);
                done.setProperty("last-outcome", outcome);
                storage.writeConfig(name, STATS_FILE, done);
            } catch (IOException | RuntimeException unwritable) {
                // the next pass overwrites; a stale "running" flag ages out after STALE_PASS
            }
        });
        return true;
    }

    /** Marks the project running {@code action} unless a pass younger than {@link #STALE_PASS} is; compare-and-set on
     *  the stats file, so of two nodes one starts. */
    private static boolean begin(CacheStorage storage, String name, String action) throws IOException {
        String path = name + "/" + STATS_FILE;
        for (int tries = 0; tries < Retries.COMPARE_AND_SET; tries++) {
            Object version = storage.fileVersion(path);
            Properties stored = storage.readFile(path);
            if (Boolean.parseBoolean(stored.getProperty("running", "false"))) {
                String started = stored.getProperty("started", "");
                if (!started.isBlank() && Instant.parse(started).isAfter(Instant.now().minus(STALE_PASS))) {
                    return false;
                }
            }
            stored.setProperty("running", "true");
            stored.setProperty("started", Instant.now().toString());
            stored.setProperty("last-action", action);
            if (storage.writeFileVersioned(path, stored, version)) {
                return true;
            }
            Retries.backoff(tries);
        }
        throw new Retries.Contended(path);
    }

    /**
     * Deletes the project - entries, settings and figures - in the background, audited first; answers whether this call
     * started it. Refused while a sweep runs, whose figures written back would revive the project; it writes nothing
     * back on success, and a failed deletion is finished by another. A credential's grant naming the project outlives
     * it.
     */
    public boolean deleteProject(String name) throws IOException {
        requireProject(name);
        CacheStorage storage = storage();
        if (!begin(storage, name, "delete")) {
            return false;
        }
        audit("cache.project.delete", name);
        passes.start("cache-delete-" + name, () -> {
            try {
                storage.deleteDir(name);
            } catch (IOException | RuntimeException failure) {
                try {
                    Properties failed = storage.readConfig(name, STATS_FILE);
                    failed.setProperty("running", "false");
                    failed.setProperty("last-action", "delete");
                    failed.setProperty("last-outcome", "failed: " + failure);
                    storage.writeConfig(name, STATS_FILE, failed);
                } catch (IOException | RuntimeException unwritable) {
                    // the "running" flag ages out after STALE_PASS, and a second deletion finishes this one
                }
            }
        });
        return true;
    }

    /** How many build-cache projects the signed-in tenant has: the names {@link #listProjects()} lists, without
     *  the figures it reads per row. */
    public int projectCount() {
        return projectNames().size();
    }

    /** The signed-in tenant's project names, in order. */
    private List<String> projectNames() {
        List<String> names = new ArrayList<>();
        String cursor = null;
        while (true) {
            Traversal.Result page = storage().projects(cursor, CacheStorage.PAGE, names::add);
            if (page.exhausted()) {
                break;
            }
            cursor = page.cursor().orElseThrow();
        }
        names.sort(Comparator.naturalOrder());
        return names;
    }

    /**
     * Every project of the selected tenant with its policy and counts. The project enumeration is followed to
     * exhaustion, since projects are provisioned by operators rather than inflated by clients; their entries are never
     * enumerated here.
     */
    public List<ProjectSummary> listProjects() {
        List<ProjectSummary> summaries = new ArrayList<>();
        List<String> names = projectNames();
        SettingsAdmin.ProjectConfigs configs = unchecked(() -> settings.projectConfigs(current.name()));
        for (String name : names) {
            UnaryOperator<String> config = unchecked(() -> configs.of(name));
            Stats stats = stats(name);                      // the stored figure, never a sweep per row
            Optional<CacheStorage.Project> record = storage().project(name);
            summaries.add(new ProjectSummary(name, record.map(CacheStorage.Project::type).orElse(null),
                    record.map(CacheStorage.Project::description).orElse(""), stats.entryCount(), stats.totalBytes(),
                    orEmpty(config.apply(ProjectPolicy.SIZE)), orEmpty(config.apply(ProjectPolicy.TTL)), stats));
        }
        return summaries;
    }

    public ProjectDetail project(String name) {
        requireProject(name);
        UnaryOperator<String> config = unchecked(() -> settings.projectConfig(current.name(), name));
        Stats stats = stats(name);
        Optional<CacheStorage.Project> record = storage().project(name);
        return new ProjectDetail(name, record.map(CacheStorage.Project::type).orElse(null),
                record.map(CacheStorage.Project::description).orElse(""), orEmpty(config.apply(ProjectPolicy.SIZE)),
                ProjectPolicy.lru(config.apply(ProjectPolicy.LRU)), orEmpty(config.apply(ProjectPolicy.TTL)),
                stats.entryCount(), stats.totalBytes(), stats);
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    /** A settings read whose failure is raised unchecked, as the storage reads beside it are. */
    private static <T> T unchecked(Read<T> read) {
        try {
            return read.get();
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    @FunctionalInterface
    private interface Read<T> {
        T get() throws IOException;
    }

    /** Creates a project of {@code type}, granting no access; it inherits its policy until given its own. */
    public void createProject(String name, String type, String description) throws IOException {
        createProject(name, type, description, Map.of());
    }

    /**
     * Creates a project as one build tool's cache, described, with {@code values} as its own settings, as the wizard,
     * {@code POST /api/cache/projects} and {@code jenrepo projects create} do. Every value is validated first; the
     * settings are stored before the provisioning marker, so a project exists configured, and one stopped between the
     * two lists as a build-made project does.
     *
     * @throws IllegalArgumentException when the name, the type, the description or any value is refused, or the project
     *     exists already.
     */
    public void createProject(String name, String type, String description, Map<String, String> values)
            throws IOException {
        String validated = validateName(name);
        Optional<String> wrongType = typeRefusal(type);
        if (wrongType.isPresent()) {
            throw new IllegalArgumentException(wrongType.get());
        }
        String line = RepositoryDocument.description(description);
        SortedMap<String, String> refused = settings.refusals(Setting.Scope.PROJECT, values, true);
        if (!refused.isEmpty()) {
            throw new IllegalArgumentException(String.join(" ", refused.values()));
        }
        if (storage().projectExists(validated)) {
            throw new IllegalArgumentException("Project already exists: " + name);
        }
        if (!values.isEmpty()) {
            settings.saveProject(current.name(), validated, values);
        }
        storage().createProject(validated, type, line);
    }

    /** What refuses {@code type} for a new project, empty when it names a build tool this deployment serves. */
    public static Optional<String> typeRefusal(String type) {
        if (type == null || type.isBlank()) {
            return Optional.of("A project is a cache for one build tool: choose one of " + String.join(", ", TYPES)
                    + ".");
        }
        return TYPES.contains(type) ? Optional.empty()
                : Optional.of("'" + type + "' is not a build tool this deployment serves: choose one of "
                        + String.join(", ", TYPES) + ".");
    }

    /** Replace a project's description, answering whether it changed: an unchanged one writes and records nothing.
     *  Audited.
     *  @throws IllegalArgumentException when it is longer than a description may be */
    public boolean describeProject(String name, String description) throws IOException {
        requireProject(name);
        String line = RepositoryDocument.description(description);
        if (storage().project(name).map(CacheStorage.Project::description).orElse("").equals(line)) {
            return false;
        }
        storage().describeProject(name, line);
        audit("cache.project.describe", name);
        return true;
    }

    /** What refuses a new project's name, empty when a creation would be accepted; the creation decides again. */
    public Optional<String> nameRefusal(String name) {
        if (name == null || name.isBlank()) {
            return Optional.of("A project needs a name.");
        }
        try {
            String validated = validateName(name);
            return storage().projectExists(validated) ? Optional.of("Project '" + validated + "' exists already.")
                    : Optional.empty();
        } catch (IllegalArgumentException refused) {
            return Optional.of(refused.getMessage());
        }
    }

    /** A project's settings, grouped for its screen - see {@link SettingsAdmin#projectGroups}. */
    public List<SettingsAdmin.Group> settings(String name) throws IOException {
        requireProject(name);
        return settings.projectGroups(current.name(), name);
    }

    /** Set or clear one of a project's settings through the catalogue. */
    public void saveSetting(String name, String key, String value) throws IOException {
        requireProject(name);
        settings.saveProject(current.name(), name, Map.of(key, value));
    }

    /** Starts the size-cap sweep in the background, audited before it runs so a crash still records it; answers
     *  whether it started. */
    public boolean enforceSizeCap(String name) throws IOException {
        requireProject(name);
        audit("cache.evict.size", name);
        ProjectPolicy policy = policy(name);
        return pass(name, "size cap", storage -> Eviction.enforceSizeCap(storage, name, policy.size(), policy.lru()));
    }

    public boolean expireTtl(String name) throws IOException {
        requireProject(name);
        audit("cache.evict.ttl", name);
        Duration ttl = policy(name).ttl();
        return pass(name, "expire stale", storage -> Eviction.expireTtl(storage, name, ttl));
    }

    public boolean clearAll(String name) throws IOException {
        requireProject(name);
        audit("cache.evict.clear", name);
        return pass(name, "clear", storage -> Eviction.clearAll(storage, name));
    }

    /** The sweeps themselves - what the background passes run, and the test seam. */
    public Eviction.Result enforceSizeCapNow(String name) {
        requireProject(name);
        ProjectPolicy policy = policy(name);
        return Eviction.enforceSizeCap(storage(), name, policy.size(), policy.lru());
    }

    public Eviction.Result expireTtlNow(String name) {
        requireProject(name);
        return Eviction.expireTtl(storage(), name, policy(name).ttl());
    }

    /** The policy a sweep started here applies: the project's effective settings, parsed as the cache parses them. */
    private ProjectPolicy policy(String name) {
        return ProjectPolicy.of(unchecked(() -> settings.projectConfig(current.name(), name)));
    }

    public Eviction.Result clearAllNow(String name) {
        requireProject(name);
        return Eviction.clearAll(storage(), name);
    }

    private void requireProject(String name) {
        if (!storage().projectExists(validateName(name))) {
            throw new IllegalArgumentException("No such project: " + name);
        }
    }

    private static String validateName(String name) {
        if (name == null || !Names.isProject(name.trim())) {
            throw new IllegalArgumentException(
                    "Invalid project name '" + name + "': use letters, digits and underscores only.");
        }
        return name.trim();
    }
}
