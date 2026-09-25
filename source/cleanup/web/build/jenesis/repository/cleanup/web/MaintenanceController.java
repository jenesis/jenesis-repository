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
 * The repository-maintenance surface - retention policy, the cleanup sweep and pins - peeled out of the
 * {@code RepositoryController} monolith into its own thin {@code web} adapter and contributed through the
 * {@code ServerModuleProvider} seam. A cleanup or retention operation runs through the framework-free
 * {@link RetentionSweeper} (resolved through {@link Repositories}); with no retention module installed
 * the sweep and retention endpoints answer {@code 501} that retention is not available on this deployment, exactly as
 * they did in the monolith. The sweep's garbage-collection leg is the discovered {@link GarbageCollectorProvider}
 * capability with the pointer roots the inventory derives from the installed formats; its dry run rides
 * {@link GarbageCollector#plan} beside retention's. With no collector resolved nothing is ever reclaimed and the
 * report's {@code gc} view says garbage collection is off - the SPI's no-op-by-absence default. The pin operations run through the repository's {@link StoreRepositoryInventory} and stand
 * on their own. Every mapping is an operation on one repository, {@code /api/repository/...?repo=<repository>}, and
 * is gated {@code repository:read}/{@code repository:write} on that repository by the security chain before the
 * request is reached, exactly as its artifacts are ({@link RepositoryRouting#operated}). It answers beside the
 * repository rather than inside its URL space, so no artifact path of any format can collide with it. The tenant is
 * the one the deployment's routing answers for a request that names none, so two tenants never sweep or pin into each
 * other's space, and a repository name that is not routable is a {@code 400}.
 */
@RestController
public class MaintenanceController {

    private final Repositories repositories;
    private final RepositoryRouting routing;
    private final LiveConfig live;
    private final ObservationRegistry observations;
    private final AuditTrail audit;
    private final MaintenanceScheduler maintenance;

    /** The collector providers, discovered once: two routes here asked per request, and which providers are
     *  installed cannot change within a JVM. Which one is *selected* is still decided per call, from the
     *  effective configuration. */
    private final List<GarbageCollectorProvider> collectors = GarbageCollectorProvider.providers();

    public MaintenanceController(Repositories repositories, RepositoryRouting routing, LiveConfig live,
                                 ObservationRegistry observations, AuditTrail audit, MaintenanceScheduler maintenance) {
        this.repositories = repositories;
        this.routing = routing;
        this.live = live;
        this.observations = observations;
        this.audit = audit;
        this.maintenance = maintenance;
    }

    /** The tenant an operation answers for - the routing's, for a request that names none in its URL - once the
     *  repository it names has been checked as a routable name, since it is about to scope the store. */
    private String tenant(String repo, HttpServletRequest request) {
        if (!Repositories.valid(repo)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a routable repository name");
        }
        return routing.tenant(request);
    }

    /** Record a privileged maintenance mutation on the audit trail - a cleanup sweep, a retention-policy change and
     *  a pin toggle are as destructive (or deletion-shaping) as a storage purge, which already records one. */
    private void audited(String tenant, String key, String action, String detail) throws IOException {
        audit.record(tenant, key == null ? "anonymous" : Authorization.hash(key), action, detail);
    }

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
        // The on-demand sweep takes the same single-writer lease name the scheduled cleanup pass historically locked
        // on, so two admin-triggered runs never sweep the same deployment concurrently; a held lease is a 409, not a
        // wait. (The scheduled pass itself cooperates over the shared walk's segment claims where a walk is
        // installed, and the provider-resolved collector is safe under a concurrent pass by its condemn-then-collect
        // contract - the lease here keeps the on-demand endpoint single-shot, not the fleet.)
        // The block-with-return binds this to the value-returning exclusively overload (an expression lambda would be
        // ambiguous against the void overload the scheduler uses).
        Optional<CleanupReport> outcome = maintenance.exclusively("cleanup", Instant.now(), () -> {
            return Observations.observe(observations, "jenreg.cleanup", repo, tenant,
                    observation -> {
                        StoreRepositoryInventory inventory = new StoreRepositoryInventory(repositories.store(tenant, repo));
                        CleanupPlan plan = sweeper.get()
                                .sweep(inventory, retention(tenant, repo), Instant.now());
                        // Retention evicts, then the discovered collector reclaims - the scheduled pass order. With
                        // no collector resolved nothing is ever reclaimed (no-op by absence) and the report says so.
                        Optional<GarbageCollector> collector = GarbageCollectorProvider.resolve(collectors, maintenance.config());
                        GcView gc = GcView.OFF;
                        if (collector.isPresent()) {
                            // Never on an incomplete root set: an ecosystem no installed format can place has
                            // its serving pointers named by no root, so the mark cannot see them and the sweep would
                            // delete the bytes. The root set carries that refusal to the collector, which declines the
                            // pass itself - so the report renders the collector's own answer, cause and all, instead
                            // of a REFUSED view this endpoint had to remember to substitute.
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

    /**
     * Forget one ecosystem's durable records in this repository - the operator's explicit retirement of data whose
     * format module is gone (or toggled off), and the way out of the refusal every reclaiming pass answers an
     * unplaceable ecosystem with. Refused with {@code 409} while an installed format still places the ecosystem;
     * after it, the collector judges the repository again and reclaims the format's now-unreferenced content blobs
     * itself, and the format module's manifest purge ({@code POST /api/admin/purge}) reaps the stray pointers and
     * listings that remain.
     */
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
        // The dry run previews both legs: what retention would evict, and what the discovered collector would
        // reclaim right now (GarbageCollector.plan is strictly read-only - it writes not even a marker). With no
        // collector resolved the view says garbage collection is off rather than previewing an empty reclaim.
        Optional<GarbageCollector> collector = GarbageCollectorProvider.resolve(collectors, maintenance.config());
        GcView gc = GcView.OFF;
        if (collector.isPresent()) {
            // The preview mirrors what the sweep would actually do, refusal included: previewing a reclaim
            // the collect leg will decline is a dry run that lies, and this one would name artifact bytes. It mirrors
            // it by construction now - the same three-valued root set reaches the same seam, and the dry run of a
            // refusal is the collector's own refusal rather than a second spelling of it here.
            gc = GcView.of(collector.get().plan(repositories.store(tenant, repo),
                    StoreRepositoryInventory.pointerRoots(repositories.store(tenant, repo)), Instant.now()));
        }
        return new CleanupReport(0, evicted(inventory, plan), plan.evictions().size(), gc);
    }

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

    @PutMapping("/api/repository/retention")
    public void setRetention(@RequestParam("repo") String repo,
                             @RequestParam(value = "keepLast", defaultValue = "0") int keepLast,
                             @RequestParam(value = "maxAge", defaultValue = "") String maxAge,
                             @RequestParam(value = "prereleaseExpiry", defaultValue = "") String prereleaseExpiry,
                             @RequestParam(value = "notDownloadedFor", defaultValue = "") String notDownloadedFor,
                             @RequestHeader(value = Repositories.KEY, required = false) String key,
                             HttpServletRequest request, HttpServletResponse response) throws IOException {
        String tenant = tenant(repo, request);
        if (repositories.retentionSweeper().isEmpty()) {
            respondRetentionNotInstalled(response);
            return;
        }
        RetentionPolicy policy;
        try {
            policy = RetentionPolicy.parse(keepLast, maxAge, prereleaseExpiry, notDownloadedFor);
        } catch (IllegalArgumentException e) {
            // A malformed or non-positive dial is the caller's error (400), never a 500 - and never stored, since a
            // stored bad policy would fail (or, inverted, mass-delete) at sweep time instead of at the operator's desk.
            response.setStatus(400);
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write(e.getMessage() == null ? "invalid retention policy" : e.getMessage());
            return;
        }
        new StoreRepositoryInventory(repositories.store(tenant, repo)).writeRetention(policy);
        audited(tenant, key, AuditActions.REPOSITORY_RETENTION, repo + " keepLast=" + keepLast
                + (maxAge.isBlank() ? "" : " maxAge=" + maxAge)
                + (prereleaseExpiry.isBlank() ? "" : " prereleaseExpiry=" + prereleaseExpiry)
                + (notDownloadedFor.isBlank() ? "" : " notDownloadedFor=" + notDownloadedFor));
        response.setStatus(200);
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

    /** The eviction rows for the report/dry-run, each screened for served-view parity (Audit-28 A2-F2). Retention
     *  operates over EVERY published release - a withheld version still ages past the policy and enters the plan - so a
     *  withheld eviction candidate's {@code coordinate:version} would otherwise be disclosed here, and the dry-run
     *  ({@code GET /api/repository/cleanup/plan}) is served at {@code repository:read}, a normal-consumer scope. Each row's
     *  coordinate is routed through the membership seam under {@code HIDE_WITHHELD} (reads only the tiny quarantine
     *  pointers / {@code withheld/<hash>} markers, stats no blob): a held member's name is replaced with a neutral
     *  {@code <withheld>} marker while the reason survives, so the operator still sees a candidate and why without the
     *  name leaking, exactly as the served listings screen withheld names. Fail-closed per row: a probe that throws
     *  hides that name rather than surfacing it or failing the whole report. */
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

    /** With no retention module installed the cleanup and retention endpoints answer {@code 501}, after the auth check
     *  so {@code 401}/{@code 403} still precede. */
    private static void respondRetentionNotInstalled(HttpServletResponse response) throws IOException {
        response.setStatus(501);
        response.setContentType("text/plain;charset=UTF-8");
        response.getWriter().write("retention is not installed on this deployment");
    }

    /** The sweep's outcome: {@code blobsReclaimed} keeps its historical meaning (what this run deleted - the
     *  collector's {@code collected} count, {@code 0} from the dry run), {@code evicted} is retention's leg, and
     *  {@code gc} the collector's full report - {@code installed=false} says garbage collection is off on this
     *  deployment (no collector module resolved: nothing is ever reclaimed). */
    /** The most evictions a report names; {@code evictedCount} is the whole count, so a sweep over a very large
     *  repository answers with a bounded body. */
    private static final int EVICTED_SAMPLE = 200;

    /** {@code evicted} names the first {@link #EVICTED_SAMPLE} evictions; {@code evictedCount} counts them all. */
    public record CleanupReport(long blobsReclaimed, List<String> evicted, int evictedCount, GcView gc) {
    }

    /** The garbage-collection leg of a sweep or dry run, mirroring the {@code GcPlan}: whether a collector is
     *  installed at all, whether the judgment rests on a completed enumeration, what this pass newly condemned,
     *  spared (re-linked content un-condemned) and collected, a bounded hash sample for a console preview, and
     *  {@code refusal} - why the pass declined to judge anything at all, when it did.
     *
     *  <p>{@code refusal} is what tells the operator's two "nothing happened" answers apart, and it is why this view
     *  no longer carries a hand-made REFUSED constant beside {@code of(GcPlan)}. A pass another node still holds
     *  segments of and a pass refused because an ecosystem's roots cannot be named both report
     *  {@code complete=false} with zero counters; only the second is an action item, and only the second names the
     *  module to reinstall. It is the empty string when the pass was not refused - the whole of the ordinary case. */
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
    /** The repository's stored retention policy, or the deployment's live default where none was written - the
     *  read the kernel used to make for every surface, and now the retention surface's own. */
    private RetentionPolicy retention(String tenant, String repo) throws IOException {
        return new StoreRepositoryInventory(repositories.store(tenant, repo)).readRetention().orElse(live.retention());
    }
}
