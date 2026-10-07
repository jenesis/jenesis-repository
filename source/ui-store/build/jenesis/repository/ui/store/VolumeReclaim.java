package build.jenesis.repository.ui.store;

import module java.base;
import build.jenesis.repository.cache.storage.CacheStorage;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.cleanup.StoredReport;
import build.jenesis.repository.store.ArtifactStore;

/**
 * The super-admin's volume-wide disk reclaim: {@link Eviction#reclaim} across every tenant's cache until the free-space
 * target is met, the cross-tenant counterpart of {@link CacheService}'s eviction. It belongs to no tenant, so it is
 * audited in the operator scope, best-effort.
 *
 * <p>A sweep of every project, so it runs off the request as a stored report: {@link #start} answers at once, and the
 * cache-volume screen reads {@link #last} back and polls while one runs.
 */
public class VolumeReclaim {

    /** The stored report a reclaim runs under. */
    static final String REPORT = "volume-reclaim";

    private final CacheStorage rootStorage;
    private final ArtifactStore reports;
    private final AuditTrail audit;
    private final ConsoleActor actor;
    private final String operatorTenant;

    /** @param reports the deployment-wide space the reclaim's report is kept in - never the cache's own segment,
     *                where it would read as a project. */
    public VolumeReclaim(CacheStorage rootStorage, ArtifactStore reports, AuditTrail audit, ConsoleActor actor,
                         String operatorTenant) {
        this.rootStorage = rootStorage;
        this.reports = reports;
        this.audit = audit;
        this.actor = actor;
        this.operatorTenant = operatorTenant;
    }

    /** Reclaims until {@code minFree} bytes and {@code minFreePercent} are free, then records the freed totals, even for
     *  a no-op. */
    public Eviction.Result reclaim(long minFree, int minFreePercent) {
        return reclaim(minFree, minFreePercent, actor.name());
    }

    /**
     * Start a reclaim off the request; answers whether this call started it rather than finding one running. The
     * actor is read here, on the request, since the sweep runs where no request is.
     */
    public boolean start(long minFree, int minFreePercent) throws IOException {
        String who = actor.name();
        return StoredReport.compute(reports, REPORT, () -> {
            Eviction.Result result = reclaim(minFree, minFreePercent, who);
            return StoredReport.Rows.of(List.of(result.entriesDeleted() + "\t" + result.bytesFreed()));
        });
    }

    /** What the last reclaim did, or that none has run, or that one is running now: one point read. */
    public View last() throws IOException {
        Optional<StoredReport.Report> stored = StoredReport.read(reports, REPORT);
        if (stored.isEmpty()) {
            return new View("not-run", null, 0, 0, null);
        }
        StoredReport.Report report = stored.get();
        Optional<StoredReport.Report> finished = report.lastFinished();
        long deleted = 0;
        long freed = 0;
        if (finished.isPresent() && !finished.get().rows().isEmpty()) {
            String[] totals = finished.get().rows().getFirst().split("\t");
            deleted = Long.parseLong(totals[0]);
            freed = Long.parseLong(totals[1]);
        }
        boolean failed = report.status() == StoredReport.Status.FAILED;
        return new View(report.running() ? "running" : failed ? "failed" : "done",
                finished.map(StoredReport.Report::finishedAt).orElse(null), deleted, freed,
                failed ? report.failure() : null);
    }

    /** A reclaim's {@code state} ({@code not-run}, {@code running}, {@code done} or {@code failed}), when the last one
     *  finished ({@code null} before one has), what it deleted and freed, and why the latest stopped. */
    public record View(String state, Instant finishedAt, long entriesDeleted, long bytesFreed, String failure) {

        /** Whether a reclaim is going now - what the screen polls on. */
        public boolean running() {
            return "running".equals(state);
        }
    }

    private Eviction.Result reclaim(long minFree, int minFreePercent, String who) {
        Eviction.Result result = Eviction.reclaim(rootStorage, minFree, minFreePercent);
        audit.record(operatorTenant, who, "cache.reclaim",
                "root (" + result.entriesDeleted() + " entries, " + result.bytesFreed() + " bytes)");
        return result;
    }
}
