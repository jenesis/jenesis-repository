package build.jenesis.repository.compliance.scan;

import module java.base;
import module org.slf4j;
import build.jenesis.repository.cleanup.Release;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.KnownExploitedSource;
import build.jenesis.repository.compliance.Verdict;
import build.jenesis.repository.gate.KevHold;
import build.jenesis.repository.gate.QuarantineLog;
import build.jenesis.repository.gate.RetroactiveHolds;
import build.jenesis.repository.inventory.IncrementalPasses;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.maintenance.MaintenanceTask;
import build.jenesis.repository.maintenance.RepositoryContext;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;

/**
 * Retroactive known-exploited enforcement. A gate verdict is reached once, at publish or proxy time, so an artifact
 * admitted before its CVE landed on a known-exploited catalogue keeps serving; this pass holds such a release by
 * writing what the gate writes: a {@code /quarantine} pointer per served path (serving retracts at once through the
 * gate screen's {@code withheld} read) and a {@link QuarantineLog} row, so it lands in the normal review queue. Only
 * the known-exploited set is enforced; everything below it stays report-only ({@link VulnerabilityScanTask}), since a
 * broad new CVE must never mass-hold a repository.
 *
 * <p>Exclusive under the {@code kev-enforce} lease, since it writes holds. Enforcement is read per pass from
 * {@code kev-auto-hold} (default on); with it off the pass writes nothing. Idempotent: an already-held or
 * operator-overridden release is skipped, and a crash mid-pass re-runs cleanly because the KEV hold record is written
 * before the pointers and each pointer before its log row. An operator's release sticks through an
 * {@code overrides/kev} marker, though a new, different known-exploited CVE may hold the release again. A delisting
 * never auto-releases here; that is {@link ReanalysisTask}'s job.
 */
public final class KevEnforceTask implements MaintenanceTask {

    private static final Logger LOGGER = LoggerFactory.getLogger(KevEnforceTask.class);

    private final Duration interval;
    private final AdvisorySource advisories;
    private final KnownExploitedSource knownExploited;

    public KevEnforceTask(Duration interval, AdvisorySource advisories, KnownExploitedSource knownExploited) {
        this.interval = interval;
        this.advisories = advisories;
        this.knownExploited = knownExploited;
    }

    @Override
    public String name() {
        return "kev-enforce";
    }

    @Override
    public Duration interval() {
        return interval;
    }

    @Override
    public Exclusion exclusion() {
        return Exclusion.LEASE;
    }

    @Override
    public void repository(RepositoryContext context) throws IOException {
        UnaryOperator<String> config = context.config();
        String flag = config == null ? null : config.apply("kev-auto-hold");
        boolean autoHold = flag == null || flag.isBlank() || !"false".equalsIgnoreCase(flag.trim());
        ArtifactStore store = context.store();
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(store);
        Publication publication = new Publication(store);
        QuarantineLog log = new QuarantineLog(store);
        // Folded over the streamed published set rather than buffered, so millions of versions are never held in heap.
        long[] held = {0};
        // A release the sweep would hold whose blobs-namespace format resolves no served path or hash cannot be
        // withheld. It is counted and warned about rather than thrown: one broken format must not stop the whole sweep,
        // and an evicted but still enumerated version legitimately resolves to nothing.
        long[] unenforceable = {0};
        // Every release every Nth pass, the releases published since between; a changed catalogue asks for a full pass
        // by name, since a listing that names an old release is what the incremental leg cannot see.
        IncrementalPasses cadence = IncrementalPasses.over(store, name(), "findings/kev-enforce", context.config());
        cadence.releases(inventory, release -> {
            String eco = release.ecosystem();
            String coordinate = release.coordinate();
            String version = release.version();
            List<String> kevCves = knownExploitedCves(release);
            if (kevCves.isEmpty()) {
                return;   // report-only below KEV; a delisting leaves an existing hold in place (no auto-release)
            }
            List<String> paths = inventory.paths(eco, coordinate, version);
            boolean pointerHeld = RetroactiveHolds.anyHeld(store, paths);
            Optional<Set<String>> record = KevHold.held(store, eco, coordinate, version);
            boolean kevHeld = pointerHeld && record.isPresent();   // a KEV auto-hold (record written before pointer)
            if (!autoHold) {
                if (kevHeld) {
                    held[0]++;
                }
                return;   // flag off: write nothing this pass
            }
            Set<String> overridden = KevHold.overridden(store, eco, coordinate, version);
            if (overridden.containsAll(kevCves)) {
                if (kevHeld) {
                    held[0]++;   // a straggler path still physically held after an operator release
                }
                return;   // every current KEV CVE already released by a human - do not re-hold
            }
            if (kevHeld) {
                if (!record.get().containsAll(kevCves)) {
                    // hold() unions into the record: a pass where a feed transiently dropped a recorded CVE must not
                    // erase it, or ReanalysisTask would auto-release a release whose CVE is still listed.
                    KevHold.hold(store, eco, coordinate, version, kevCves);
                }
                // Converge a partially held release rather than skipping it, so a crash after the first pointer, or a
                // path added later, is held on the next pass.
                RetroactiveHolds.converge(store, publication, inventory, log, context.now(), eco, coordinate, version,
                        paths, List.of("KEV retroactive: " + String.join(", ",
                                kevCves.stream().filter(cve -> !overridden.contains(cve)).toList())),
                        release.coordinate() + ":" + version);
                held[0]++;
                return;   // already held (idempotent)
            }
            if (pointerHeld) {
                return;   // held by the publish-time gate, not this pass - serving already retracted, leave it
            }
            List<String> enforcing = kevCves.stream().filter(cve -> !overridden.contains(cve)).toList();
            if (RetroactiveHolds.hold(store, publication, inventory, log, context.now(), eco, coordinate, version,
                    paths, List.of("KEV retroactive: " + String.join(", ", enforcing)),
                    release.coordinate() + ":" + version,
                    () -> KevHold.hold(store, eco, coordinate, version, kevCves))) {
                held[0]++;
            } else if (inventory.servesFromBlobs(eco)) {
                // Nothing resolved to withhold for a blobs-namespace release the sweep would hold: unwired
                // blobKeys/servedPaths or an evicted version, alarmed on rather than a silent no-op.
                unenforceable[0]++;
                LOGGER.warn("Repository {}/{} cannot enforce a retroactive known-exploited hold on {} {}:{} - the "
                                + "ecosystem serves from the blobs namespace but the version resolved no served path or "
                                + "content hash (blobKeys/servedPaths unwired, or the version was evicted); serving is "
                                + "NOT retracted", context.tenant(), context.repository(), eco, coordinate, version);
            }
        });
        cadence.completed(context.now(), true);
        context.gauge("jenrepo.vulnerabilities.hold.unenforceable",
                "Released coordinates a retroactive hold would cover but cannot enforce because the blobs-namespace "
                        + "format resolved no served path or content hash - a wiring-regression alarm",
                Map.of("tenant", context.tenant(), "repository", context.repository(), "sweep", "kev"),
                unenforceable[0]);
        if (held[0] > 0) {
            LOGGER.warn("Repository {}/{} is retroactively holding {} known-exploited release(s)",
                    context.tenant(), context.repository(), held[0]);
        }
        context.gauge("jenrepo.vulnerabilities.kev.held",
                "Released coordinates a repository is retroactively holding because their CVE is on a "
                        + "known-exploited catalogue",
                Map.of("tenant", context.tenant(), "repository", context.repository()), held[0]);
    }

    /** The known-exploited CVEs among a release's advisories, de-duplicated in encounter order. Reads the advisory
     *  feeds by coordinate, never an artifact blob. */
    private List<String> knownExploitedCves(Release release) {
        LinkedHashSet<String> cves = new LinkedHashSet<>();
        for (AdvisorySource.Advisory advisory : advisories.advisories(
                release.ecosystem(), release.coordinate(), release.version())) {
            for (String cve : advisory.cves()) {
                if (knownExploited.contains(cve)) {
                    cves.add(cve);
                }
            }
        }
        return List.copyOf(cves);
    }
}
