package build.jenesis.repository.ui.store;

import module java.base;

import build.jenesis.repository.ui.CurrentTenant;
import build.jenesis.repository.cache.storage.CacheStorage;
import build.jenesis.repository.cache.storage.Names;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.walk.Traversal;

/**
 * Orchestrates the project operations the controllers need on top of the {@link CacheStorage} SPI.
 * The injected storage is the primary {@code tenantStorage} view, already confined to the session's
 * selected tenant, so this service simply lists projects with their stats, creates a project, edits
 * its {@code cache.properties} (size / lru / ttl), and runs per-project eviction - all within that one
 * tenant. Access control is not per project: credentials live under {@code .users/} and grant projects
 * by role. Cross-tenant disk reclaim is a super-admin concern and lives with the instances screen.
 */
public class CacheService {

    private final CacheStorage storage;
    private final AuditTrail audit;
    private final CurrentTenant current;
    private final ConsoleActor actor;
    private final Passes passes;

    /**
     * How a background pass is started.
     *
     * <p>An eviction returns as soon as it has marked the project running, and finishes on another thread - which
     * is deliberate, and {@code CacheStatsTest} has it as a subject. It is also a hazard for any fixture that owns
     * the directory the pass writes into: a test that asserts something the pass does <em>not</em> produce (the
     * audit event, written before the pass starts) has no reason to wait for it, ends, and JUnit then deletes a
     * {@code @TempDir} the pass is still writing to. That surfaces as
     * {@code Failed to delete temp directory ... DirectoryNotEmptyException} attributed to whichever test method
     * happened to be last - a fixture failure that names neither the pass nor the race, and that only appears
     * under load, because on an idle machine the pass wins.
     *
     * <p>So the choice is a seam rather than a hard-coded thread: a caller that wants the real behaviour takes
     * {@link #BACKGROUND}, and one that owns the directory takes {@link #CALLING_THREAD} and is deterministic by
     * construction instead of by polling.
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

    public CacheService(CacheStorage storage, AuditTrail audit, CurrentTenant current, ConsoleActor actor) {
        this(storage, audit, current, actor, Passes.BACKGROUND);
    }

    public CacheService(CacheStorage storage, AuditTrail audit, CurrentTenant current, ConsoleActor actor,
                        Passes passes) {
        this.passes = passes;
        this.storage = storage;
        this.audit = audit;
        this.current = current;
        this.actor = actor;
    }

    /** Record a privileged cache-eviction mutation on the shared audit trail under the selected tenant, attributed to
     *  the acting member - the console peer of the domain-layer audit seams ({@code TenantScope#audit},
     *  {@code AdminController}); best-effort, so a failed write never fails the eviction it audits. */
    private void audit(String action, String project) {
        audit.record(current.name(), actor.name(), action, project);
    }

    /** A project's stored count: how many entries and bytes the last pass found and when ({@code countedAt} null
     *  when no pass has run), whether a pass is running, and what the last eviction did. Read from one small file the
     *  passes write - a render never sweeps the cache to count it. */
    public record Stats(long entryCount, long totalBytes, Instant countedAt, boolean counting, String lastAction,
                        String lastOutcome) {

        static Stats unknown() {
            return new Stats(0, 0, null, false, "", "");
        }

        public boolean known() {
            return countedAt != null;
        }
    }

    public record ProjectSummary(String name, long entryCount, long totalBytes, String sizeCap, String ttl,
                                 Stats stats) {
    }

    public record ProjectDetail(String name, String size, boolean lru, String ttl,
                                long entryCount, long totalBytes, Stats stats) {
    }

    /** The per-project file the passes write their outcome into. */
    static final String STATS_FILE = "stats.properties";

    /** A pass older than this that still says running is taken as crashed, and a new one may start. */
    private static final Duration STALE_PASS = Duration.ofHours(1);

    /** The stored stats of a project - a point read, {@link Stats#unknown()} before any pass has run. */
    public Stats stats(String name) {
        Properties stored = storage.readConfig(name, STATS_FILE);
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
        return pass(name, "count", () -> null);
    }

    /** Run {@code action} in the background, then count what it left, and store both; refuses to start while a
     *  pass younger than {@link #STALE_PASS} is running. */
    private boolean pass(String name, String action, Callable<Eviction.Result> work) throws IOException {
        Properties stored = storage.readConfig(name, STATS_FILE);
        if (Boolean.parseBoolean(stored.getProperty("running", "false"))) {
            String started = stored.getProperty("started", "");
            if (!started.isBlank() && Instant.parse(started).isAfter(Instant.now().minus(STALE_PASS))) {
                return false;
            }
        }
        stored.setProperty("running", "true");
        stored.setProperty("started", Instant.now().toString());
        stored.setProperty("last-action", action);
        storage.writeConfig(name, STATS_FILE, stored);
        passes.start("cache-" + action + "-" + name, () -> {
            String outcome;
            try {
                Eviction.Result result = work.call();
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

    /**
     * Every project of the selected tenant, with its policy and its live entry counts, for the console listing.
     *
     * <p>The projects enumeration is paged and its remainder is <em>followed</em> to exhaustion rather than reported
     * as a bound, and that is a decision rather than an oversight: a project is created by an operator through this
     * very screen, so the set is provisioned and not client-inflatable, and the screen is a listing of all of them -
     * offering a truncated one with no way to page it would be a worse answer than the round-trips cost. What was
     * unbounded before, and is now not, is what sits <em>inside</em> each project: {@link Eviction#stats} streams a
     * project's entries page by page instead of enumerating them into a list, so this screen's footprint is the
     * project set, never the cached-entry set behind it.
     */
    public List<ProjectSummary> listProjects() {
        List<ProjectSummary> summaries = new ArrayList<>();
        List<String> names = new ArrayList<>();
        String cursor = null;
        while (true) {
            Traversal.Result page = storage.projects(cursor, CacheStorage.PAGE, names::add);
            if (page.exhausted()) {
                break;
            }
            cursor = page.cursor().orElseThrow();
        }
        names.sort(Comparator.naturalOrder());
        for (String name : names) {
            Properties cache = storage.readConfig(name, CacheConfig.FILE);
            Stats stats = stats(name);                      // the stored figure, never a sweep per row
            summaries.add(new ProjectSummary(name, stats.entryCount(), stats.totalBytes(),
                    CacheConfig.size(cache), CacheConfig.ttl(cache), stats));
        }
        return summaries;
    }

    public ProjectDetail project(String name) {
        requireProject(name);
        Properties cache = storage.readConfig(name, CacheConfig.FILE);
        Stats stats = stats(name);
        return new ProjectDetail(name, CacheConfig.size(cache), CacheConfig.lru(cache), CacheConfig.ttl(cache),
                stats.entryCount(), stats.totalBytes(), stats);
    }

    /** Create a project and seed a default cache.properties (no access is granted here - see credentials). */
    public void createProject(String name) throws IOException {
        String validated = validateName(name);
        if (storage.projectExists(validated)) {
            throw new IllegalArgumentException("Project already exists: " + name);
        }
        storage.createProject(validated);
        Properties cache = new Properties();
        CacheConfig.apply(cache, "", "true", "");
        storage.writeConfig(validated, CacheConfig.FILE, cache);
    }

    public void saveCacheConfig(String name, String size, String lru, String ttl) throws IOException {
        requireProject(name);
        Properties cache = storage.readConfig(name, CacheConfig.FILE);
        CacheConfig.apply(cache, size, lru, ttl);
        storage.writeConfig(name, CacheConfig.FILE, cache);
    }

    /** Start the size-cap sweep in the background; whether it was started (not while another pass runs). Audited
     *  before the mutation (crash-safe ordering, as the quarantine release/discard) with the project as target, so a
     *  crash mid-eviction still records that a privileged eviction was driven. The sweep walks every entry of the
     *  project, so it never runs on the request. */
    public boolean enforceSizeCap(String name) throws IOException {
        requireProject(name);
        audit("cache.evict.size", name);
        return pass(name, "size cap", () -> enforceSizeCapNow(name));
    }

    public boolean expireTtl(String name) throws IOException {
        requireProject(name);
        audit("cache.evict.ttl", name);
        return pass(name, "expire stale", () -> expireTtlNow(name));
    }

    public boolean clearAll(String name) throws IOException {
        requireProject(name);
        audit("cache.evict.clear", name);
        return pass(name, "clear", () -> clearAllNow(name));
    }

    /** The sweeps themselves - what the background passes run, and the test seam. */
    public Eviction.Result enforceSizeCapNow(String name) {
        requireProject(name);
        Properties cache = storage.readConfig(name, CacheConfig.FILE);
        return Eviction.enforceSizeCap(storage, name, CacheConfig.sizeBytes(cache), CacheConfig.lru(cache));
    }

    public Eviction.Result expireTtlNow(String name) {
        requireProject(name);
        Properties cache = storage.readConfig(name, CacheConfig.FILE);
        return Eviction.expireTtl(storage, name, CacheConfig.ttlDuration(cache));
    }

    public Eviction.Result clearAllNow(String name) {
        requireProject(name);
        return Eviction.clearAll(storage, name);
    }

    private void requireProject(String name) {
        if (!storage.projectExists(validateName(name))) {
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
