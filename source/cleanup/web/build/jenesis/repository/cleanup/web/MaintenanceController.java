package build.jenesis.repository.cleanup.web;

import module java.base;
import build.jenesis.repository.audit.AuditActions;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.cleanup.CleanupPlan;
import build.jenesis.repository.cleanup.RetentionPolicy;
import build.jenesis.repository.cleanup.RetentionSweeper;
import build.jenesis.repository.gc.GarbageCollector;
import build.jenesis.repository.gc.GarbageCollectorProvider;
import build.jenesis.repository.gc.GcPlan;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.server.Observations;
import build.jenesis.repository.server.kernel.MaintenanceScheduler;
import build.jenesis.repository.server.RepositoryRouting;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.store.ServableNames;
import build.jenesis.repository.server.kernel.LiveConfig;
import build.jenesis.repository.server.kernel.SettingsEditor;
import io.micrometer.observation.ObservationRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The repository-maintenance surface - retention policy, the cleanup sweep and pins - contributed through the
 * {@code ServerModuleProvider} seam. Cleanup and retention run through the {@link RetentionSweeper} resolved by
 * {@link Repositories}; without a retention module those endpoints answer {@code 501}. The sweep's collection leg is
 * the discovered {@link GarbageCollectorProvider} with the pointer roots the inventory derives from the installed
 * formats, and its dry run is {@link GarbageCollector#plan}; with no collector nothing is reclaimed and the report's
 * {@code gc} view says so. Pins run through the repository's {@link StoreRepositoryInventory}.
 *
 * <p>Every mapping operates on one repository, {@code /api/repository/...?repo=<repository>}, gated
 * {@code repository:read}/{@code repository:write} on it by the security chain as its artifacts are
 * ({@link RepositoryRouting#operated}), beside the repository rather than in its URL space so no artifact path can
 * collide. The tenant is the routing's for a request naming none, so tenants never sweep or pin into each other, and an
 * unroutable repository name is a {@code 400}.
 *
 * <p>Settings reads here are the tenant's and the deployment's settings documents - one object per module under a
 * constant prefix.
 */
@RestController
public class MaintenanceController {

    private final Repositories repositories;
    private final RepositoryRouting routing;
    private final LiveConfig live;
    /** The one place a setting is changed: a repository's retention rules are its settings. */
    private final SettingsEditor editor;
    private final ObservationRegistry observations;
    private final AuditTrail audit;
    private final MaintenanceScheduler maintenance;

    /** The collector providers, discovered once - which are installed cannot change within a JVM; which is selected is
     *  still decided per call from the effective configuration. */
    private final List<GarbageCollectorProvider> collectors = GarbageCollectorProvider.providers();

    public MaintenanceController(Repositories repositories, RepositoryRouting routing, LiveConfig live,
                                 SettingsEditor editor, ObservationRegistry observations, AuditTrail audit,
                                 MaintenanceScheduler maintenance) {
        this.editor = editor;
        this.repositories = repositories;
        this.routing = routing;
        this.live = live;
        this.observations = observations;
        this.audit = audit;
        this.maintenance = maintenance;
    }

    /** The tenant an operation answers for - the routing's, for a request naming none - once the repository name is
     *  checked as routable, since it is about to scope the store. */
    private String tenant(String repo, HttpServletRequest request) {
        if (!Repositories.valid(repo)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a routable repository name");
        }
        return routing.tenant(request);
    }

    /** Record a privileged maintenance mutation on the audit trail: a sweep, a retention change and a pin shape
     *  deletion as a storage purge does. */
    private void audited(String tenant, String key, String action, String detail) throws IOException {
        audit.record(tenant, key == null ? "anonymous" : Authorization.hash(key), action, detail);
    }

    /** Run a cleanup sweep over the repository: retention, then collection. */
    @PostMapping("/api/repository/cleanup")
    @ResponseBody
    public CleanupReport cleanup(@RequestParam("repo") String repo,
                                 @RequestHeader(value = Repositories.KEY, required = false) String key,
                                 HttpServletRequest request, HttpServletResponse response) throws IOException {
        String tenant = tenant(repo, request);
        Optional<RetentionSweeper> sweeper = repositories.retentionSweeper();
        if (sweeper.isEmpty()) {
            respondRetentionNotInstalled(response);
            return null;
        }
        // The on-demand sweep takes the scheduled pass's single-writer lease name, so two triggered runs never sweep
        // concurrently; a held lease is a 409, not a wait. The lease keeps this endpoint single-shot; the walk-riding
        // passes and the condemn-then-collect collector are safe under concurrency on their own. The block-with-return
        // selects the value-returning exclusively overload over the scheduler's void one.
        Optional<CleanupReport> outcome = maintenance.exclusively("cleanup", Instant.now(), () -> {
            return Observations.observe(observations, "jenrepo.cleanup", repo, tenant,
                    observation -> {
                        StoreRepositoryInventory inventory = new StoreRepositoryInventory(repositories.store(tenant, repo));
                        CleanupPlan plan = sweeper.get()
                                .sweep(inventory, retention(tenant, repo), Instant.now());
                        // Retention evicts, then the collector reclaims, as the scheduled pass orders them; with no
                        // collector the report says nothing is reclaimed.
                        Optional<GarbageCollector> collector = GarbageCollectorProvider.resolve(collectors, maintenance.config());
                        GcView gc = GcView.OFF;
                        if (collector.isPresent()) {
                            // An incomplete root set reaches the collector, which refuses the pass itself, so the
                            // report renders the collector's own refusal and cause.
                            gc = GcView.of(collector.get().collect(repositories.store(tenant, repo),
                                    StoreRepositoryInventory.pointerRoots(repositories.store(tenant, repo)),
                                    Instant.now()));
                        }
                        observation.lowCardinalityKeyValue("evicted", Integer.toString(plan.evictions().size()));
                        return new CleanupReport(gc.collected(), evicted(inventory, plan),
                                plan.evictions().size(), gc);
                    });
        });
        if (outcome.isEmpty()) {
            response.setStatus(409);
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write("a cleanup sweep is already running on another node");
            return null;
        }
        CleanupReport report = outcome.get();
        audited(tenant, key, AuditActions.REPOSITORY_CLEANUP,
                repo + " (" + report.evictedCount() + " evicted, "
                        + (report.gc().installed() ? report.blobsReclaimed() + " blobs reclaimed" : "GC off") + ")");
        return report;
    }

    /** Forget one ecosystem's durable records in this repository - the operator's retirement of data whose format
     *  module is gone or switched off, and the way out of the refusal every reclaiming pass gives an unplaceable
     *  ecosystem. {@code 409} while an installed format still places it; after it, the collector reclaims the
     *  now-unreferenced blobs, and the module purge ({@code POST /api/admin/purge}) reaps the remaining pointers and
     *  listings. */
    @PostMapping("/api/repository/forget-ecosystem")
    @ResponseBody
    public Map<String, Object> forgetEcosystem(@RequestParam("repo") String repo,
                                               @RequestParam("ecosystem") String ecosystem,
                                               @RequestHeader(value = Repositories.KEY, required = false) String key,
                                               HttpServletRequest request, HttpServletResponse response) throws IOException {
        String tenant = tenant(repo, request);
        long removed;
        try {
            removed = new StoreRepositoryInventory(repositories.store(tenant, repo)).forgetEcosystem(ecosystem);
        } catch (IllegalStateException stillPlaced) {
            response.setStatus(409);
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write(stillPlaced.getMessage());
            return null;
        }
        audited(tenant, key, AuditActions.REPOSITORY_FORGET_ECOSYSTEM, repo + " " + ecosystem + " (" + removed + " records)");
        return Map.of("ecosystem", ecosystem, "removed", removed);
    }

    /** Preview a cleanup sweep: what retention would evict and what the collector would reclaim now. */
    @GetMapping("/api/repository/cleanup/plan")
    @ResponseBody
    public CleanupReport cleanupPlan(@RequestParam("repo") String repo,
                                     @RequestHeader(value = Repositories.KEY, required = false) String key,
                                     HttpServletRequest request, HttpServletResponse response) throws IOException {
        String tenant = tenant(repo, request);
        Optional<RetentionSweeper> sweeper = repositories.retentionSweeper();
        if (sweeper.isEmpty()) {
            respondRetentionNotInstalled(response);
            return null;
        }
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(repositories.store(tenant, repo));
        CleanupPlan plan = sweeper.get()
                .plan(inventory, retention(tenant, repo), Instant.now());
        // The dry run previews both legs; GarbageCollector.plan writes nothing. With no collector the view says
        // collection is off rather than previewing an empty reclaim.
        Optional<GarbageCollector> collector = GarbageCollectorProvider.resolve(collectors, maintenance.config());
        GcView gc = GcView.OFF;
        if (collector.isPresent()) {
            // The same three-valued root set reaches the same seam, so a refused collection previews as the collector's
            // own refusal rather than naming bytes the sweep would never touch.
            gc = GcView.of(collector.get().plan(repositories.store(tenant, repo),
                    StoreRepositoryInventory.pointerRoots(repositories.store(tenant, repo)), Instant.now()));
        }
        return new CleanupReport(0, evicted(inventory, plan), plan.evictions().size(), gc);
    }

    /** The repository's retention policy and where each rule comes from. */
    @GetMapping("/api/repository/retention")
    @ResponseBody
    public RetentionView retention(@RequestParam("repo") String repo,
                                   @RequestHeader(value = Repositories.KEY, required = false) String key,
                                   HttpServletRequest request, HttpServletResponse response) throws IOException {
        String tenant = tenant(repo, request);
        if (repositories.retentionSweeper().isEmpty()) {
            respondRetentionNotInstalled(response);
            return null;
        }
        RetentionPolicy policy = retention(tenant, repo);
        return new RetentionView(policy.keepLast(),
                policy.maxAge() == null ? "" : policy.maxAge().toString(),
                policy.prereleaseExpiry() == null ? "" : policy.prereleaseExpiry().toString(),
                policy.notDownloadedFor() == null ? "" : policy.notDownloadedFor().toString());
    }

    /** Set a repository's retention rules, each given one as its repository setting: a value sets it here, an empty one
     *  clears it to inherit from the tenant and deployment, and {@code none} on a duration rule switches it off here; a
     *  rule not given is left alone. Every value is validated through the catalogue before any is stored, so a
     *  malformed or non-positive rule is a {@code 400} that never reaches a sweep. The change is recorded as each
     *  rule's setting change, as the console's retention page records it. */
    @PutMapping("/api/repository/retention")
    public void setRetention(@RequestParam("repo") String repo,
                             @RequestParam(value = "keepLast", required = false) String keepLast,
                             @RequestParam(value = "maxAge", required = false) String maxAge,
                             @RequestParam(value = "prereleaseExpiry", required = false) String prereleaseExpiry,
                             @RequestParam(value = "notDownloadedFor", required = false) String notDownloadedFor,
                             @RequestHeader(value = Repositories.KEY, required = false) String key,
                             HttpServletRequest request, HttpServletResponse response) throws IOException {
        String tenant = tenant(repo, request);
        if (repositories.retentionSweeper().isEmpty()) {
            respondRetentionNotInstalled(response);
            return;
        }
        Map<String, String> rules = new LinkedHashMap<>();
        given(rules, RetentionPolicy.KEEP_LAST, keepLast);
        given(rules, RetentionPolicy.MAX_AGE, maxAge);
        given(rules, RetentionPolicy.PRERELEASE_EXPIRY, prereleaseExpiry);
        given(rules, RetentionPolicy.NOT_DOWNLOADED_FOR, notDownloadedFor);
        try {
            editor.repository(tenant, repo, rules, false,
                    new SettingsEditor.Actor(tenant, key == null ? "anonymous" : Authorization.hash(key)));
        } catch (IllegalArgumentException e) {
            // A malformed or non-positive dial is a 400 and never stored, since a stored bad rule would fail - or,
            // inverted, mass-delete - at sweep time.
            response.setStatus(400);
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write(e.getMessage() == null ? "invalid retention policy" : e.getMessage());
            return;
        }
        response.setStatus(200);
    }

    private static void given(Map<String, String> rules, String rule, String value) {
        if (value != null) {
            rules.put(rule, value.trim());
        }
    }

    @PostMapping("/api/repository/pin")
    public void pin(@RequestParam("repo") String repo,
                    @RequestParam("ecosystem") String ecosystem,
                    @RequestParam("coordinate") String coordinate,
                    @RequestParam("version") String version,
                    @RequestHeader(value = Repositories.KEY, required = false) String key,
                    HttpServletRequest request, HttpServletResponse response) throws IOException {
        String tenant = tenant(repo, request);
        try {
            new StoreRepositoryInventory(repositories.store(tenant, repo)).pin(ecosystem, coordinate, version);
        } catch (IllegalArgumentException e) {
            respondBadSegment(response, e);                  // a traversal-unsafe ecosystem/version is a 400
            return;
        }
        audited(tenant, key, AuditActions.REPOSITORY_PIN, repo + " " + ecosystem + ":" + coordinate + ":" + version);
        response.setStatus(200);
    }

    @DeleteMapping("/api/repository/pin")
    public void unpin(@RequestParam("repo") String repo,
                      @RequestParam("ecosystem") String ecosystem,
                      @RequestParam("coordinate") String coordinate,
                      @RequestParam("version") String version,
                      @RequestHeader(value = Repositories.KEY, required = false) String key,
                      HttpServletRequest request, HttpServletResponse response) throws IOException {
        String tenant = tenant(repo, request);
        try {
            new StoreRepositoryInventory(repositories.store(tenant, repo)).unpin(ecosystem, coordinate, version);
        } catch (IllegalArgumentException e) {
            respondBadSegment(response, e);                  // a traversal-unsafe ecosystem/version is a 400
            return;
        }
        audited(tenant, key, AuditActions.REPOSITORY_UNPIN, repo + " " + ecosystem + ":" + coordinate + ":" + version);
        response.setStatus(200);
    }

    private static void respondBadSegment(HttpServletResponse response, IllegalArgumentException e) throws IOException {
        response.setStatus(400);
        response.setContentType("text/plain;charset=UTF-8");
        response.getWriter().write(e.getMessage() == null ? "invalid coordinate segment" : e.getMessage());
    }

    @GetMapping("/api/repository/pins")
    @ResponseBody
    public PinsView pins(@RequestParam("repo") String repo,
                         @RequestHeader(value = Repositories.KEY, required = false) String key,
                         HttpServletRequest request, HttpServletResponse response) throws IOException {
        String tenant = tenant(repo, request);
        return new PinsView(new StoreRepositoryInventory(repositories.store(tenant, repo)).pins());
    }

    /** The eviction rows of a report or dry run, screened for served-view parity. Retention judges every published
     *  release, withheld ones included, and the dry run is served at {@code repository:read}, so each row's coordinate
     *  goes through the membership seam under {@code HIDE_WITHHELD} (reading only quarantine pointers and
     *  {@code withheld/<hash>} markers): a held member's name becomes {@code <withheld>} while its reason stays.
     *  Fail-closed per row: a failing probe hides the name. */
    private static List<String> evicted(StoreRepositoryInventory inventory, CleanupPlan plan) {
        List<String> evicted = new ArrayList<>();
        for (CleanupPlan.Eviction eviction : plan.evictions()) {
            if (evicted.size() >= EVICTED_SAMPLE) {
                break;                                      // the report names a sample; evictedCount carries the total
            }
            String display = eviction.release().coordinate() + ":" + eviction.release().version();
            boolean disclosable;
            try {
                disclosable = inventory.disclosableDisplay(display, ServableNames.Policy.HIDE_WITHHELD);
            } catch (IOException e) {
                disclosable = false;
            }
            evicted.add((disclosable ? display : "<withheld>") + " - " + eviction.reason());
        }
        return evicted;
    }

    /** Without a retention module the cleanup and retention endpoints answer {@code 501}, after the auth check. */
    private static void respondRetentionNotInstalled(HttpServletResponse response) throws IOException {
        response.setStatus(501);
        response.setContentType("text/plain;charset=UTF-8");
        response.getWriter().write("retention is not installed on this deployment");
    }

    /** The most evictions a report names; {@code evictedCount} is the whole count, so the body stays bounded. */
    private static final int EVICTED_SAMPLE = 200;

    /** The sweep's outcome: {@code blobsReclaimed} is what this run deleted (the collector's {@code collected},
     *  {@code 0} from a dry run), {@code evicted} names the first {@link #EVICTED_SAMPLE} of retention's evictions and
     *  {@code evictedCount} counts them all, and {@code gc} is the collector's report ({@code installed=false} when
     *  none is resolved). */
    public record CleanupReport(long blobsReclaimed, List<String> evicted, int evictedCount, GcView gc) {
    }

    /**
     * The collection leg of a sweep or dry run, mirroring {@code GcPlan}: whether a collector is installed, whether the
     * judgment rests on a completed enumeration, what was condemned, spared and collected, a bounded hash sample, and
     * {@code refusal} - why the pass declined to judge anything, empty in the ordinary case.
     *
     * <p>{@code refusal} tells the two "nothing happened" answers apart: a pass another node holds segments of, and a
     * pass refused because an ecosystem's roots cannot be named, both report {@code complete=false} with zero counters;
     * only the second is an action item, naming the module to reinstall.
     */
    public record GcView(boolean installed, boolean complete, long condemned, long spared, long collected,
                         List<String> sample, String refusal) {

        /** No collector resolved - the no-op-by-absence default: garbage collection is off, nothing is reclaimed. */
        static final GcView OFF = new GcView(false, false, 0, 0, 0, List.of(), "");

        static GcView of(GcPlan plan) {
            return new GcView(true, plan.complete(), plan.condemned(), plan.spared(), plan.collected(), plan.sample(),
                    plan.refusal().map(Object::toString).orElse(""));
        }
    }

    public record RetentionView(int keepLast, String maxAge, String prereleaseExpiry, String notDownloadedFor) {
    }

    public record PinsView(List<String> pinned) {
    }
    /** The policy the repository runs under: its rules through its own settings over its tenant's and the deployment's,
     *  the chain the scheduled sweep and the console read too. */
    private RetentionPolicy retention(String tenant, String repo) {
        return live.retention(tenant, repo);
    }
}
