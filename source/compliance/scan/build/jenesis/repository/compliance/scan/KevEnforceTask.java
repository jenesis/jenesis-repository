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
 * Retroactive known-exploited enforcement: the scheduled gauge {@link VulnerabilityScanTask} only reports what a
 * repository holds, while a gate verdict is reached once, at publish or proxy time, so an artifact admitted before its
 * CVE landed on a known-exploited catalogue keeps serving. This pass closes that gap - and only for the narrow,
 * actively-exploited set. It walks each repository's inventory and, for a release whose advisories carry a CVE the
 * {@link KnownExploitedSource} lists, writes the same hold the gate writes: a {@code /quarantine} pointer per served
 * artifact path (serving retracts at once through the gate screen's {@code withheld} read side) and a
 * {@link QuarantineLog} row, so the artifact lands in the normal review queue and the existing release/discard flow
 * applies unchanged. Everything below the known-exploited set stays report-only (the gauge pass): a broad new CVE must
 * never mass-hold a repository.
 *
 * <p>Exclusive - it writes holds, so a replicated deployment runs it on one node per interval under the
 * {@code kev-enforce} lease, beside the non-exclusive gauge {@code scan}. Enforcement is read per pass from the
 * {@code kev-auto-hold} setting (default on: KEV is the narrow actively-exploited set and this is a security product);
 * flipping it applies on the next pass without a restart, and with it off the pass writes nothing (the gauge still
 * counts any holds a prior pass left). Idempotent: an already-held release or an operator-overridden one is skipped, a
 * crash mid-pass re-runs cleanly (the KEV hold record is written before the pointers, the pointer before its log row),
 * and an operator's release sticks through an {@code overrides/kev} marker the pass consults - a human's release is
 * never re-held, though a new, different known-exploited CVE on the same release may hold it again. A KEV delisting
 * never auto-releases here - that is the {@link ReanalysisTask} sweep's job, and with its {@code kev-auto-release}
 * off a held artifact persists until human review.
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
        // Fold a held-count over the streamed published set rather than buffering every Release in heap: a repository
        // with millions of versions must not materialise them all just to enforce KEV holds on a background sweep.
        long[] held = {0};
        // A fail-closed alarm (not an abort): a release the sweep WOULD hold (a KEV CVE, not overridden) whose
        // blobs-namespace format resolves no served path or content hash cannot be withheld, so serving is not
        // retracted. Throwing would let one broken format DoS the whole repository sweep, and an evicted-but-still-
        // enumerated version legitimately resolves to nothing; so this counts + WARNs rather than fails, alarming on a
        // future blobs-namespace format that forgets to wire blobKeys/servedPaths (the F-1 regression this closes).
        long[] unenforceable = {0};
        // Every release every Nth pass; between, the releases published since the last full one - and a full pass
        // at once when the known-exploited catalogue changed, which the signal refresh asks for by name, since a
        // listing that now names an old release is exactly what the incremental leg cannot see.
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
                    // KevHold.hold UNIONS into the record, never replaces: a pass where the advisory feed transiently
                    // dropped a recorded CVE (an outage, a retraction, or the paginated-feed bugs) must not erase it,
                    // or ReanalysisTask loses the CVE that justified the hold and auto-releases while it is still on
                    // the KEV catalogue.
                    KevHold.hold(store, eco, coordinate, version, kevCves);
                }
                // Converge a PARTIALLY held release rather than skipping at version level - the shared leg, so a
                // crash after the first path's pointer or a path added later is held on the next pass.
                RetroactiveHolds.converge(store, publication, inventory, log, context.now(), eco, coordinate, version,
                        paths, "KEV retroactive: " + String.join(", ",
                                kevCves.stream().filter(cve -> !overridden.contains(cve)).toList()),
                        release.coordinate() + ":" + version);
                held[0]++;
                return;   // already held (idempotent)
            }
            if (pointerHeld) {
                return;   // held by the publish-time gate, not this pass - serving already retracted, leave it
            }
            List<String> enforcing = kevCves.stream().filter(cve -> !overridden.contains(cve)).toList();
            if (RetroactiveHolds.hold(store, publication, inventory, log, context.now(), eco, coordinate, version,
                    paths, "KEV retroactive: " + String.join(", ", enforcing), release.coordinate() + ":" + version,
                    () -> KevHold.hold(store, eco, coordinate, version, kevCves))) {
                held[0]++;
            } else if (inventory.servesFromBlobs(eco)) {
                // hold() returned false only because holdable AND blobHashes were both empty: a blobs-namespace release
                // the sweep would hold resolved nothing to withhold. Alarm on it (unwired blobKeys/servedPaths, or an
                // evicted version) rather than silently no-op.
                unenforceable[0]++;
                LOGGER.warn("Repository {}/{} cannot enforce a retroactive known-exploited hold on {} {}:{} - the "
                                + "ecosystem serves from the blobs namespace but the version resolved no served path or "
                                + "content hash (blobKeys/servedPaths unwired, or the version was evicted); serving is "
                                + "NOT retracted", context.tenant(), context.repository(), eco, coordinate, version);
            }
        });
        cadence.completed(context.now(), true);
        context.gauge("jenreg.vulnerabilities.hold.unenforceable",
                "Released coordinates a retroactive hold would cover but cannot enforce because the blobs-namespace "
                        + "format resolved no served path or content hash - a wiring-regression alarm",
                Map.of("tenant", context.tenant(), "repository", context.repository(), "sweep", "kev"),
                unenforceable[0]);
        if (held[0] > 0) {
            LOGGER.warn("Repository {}/{} is retroactively holding {} known-exploited release(s)",
                    context.tenant(), context.repository(), held[0]);
        }
        context.gauge("jenreg.vulnerabilities.kev.held",
                "Released coordinates a repository is retroactively holding because their CVE is on a "
                        + "known-exploited catalogue",
                Map.of("tenant", context.tenant(), "repository", context.repository()), held[0]);
    }

    /** The known-exploited CVEs among a release's advisories, de-duplicated in encounter order - the CVEs a hold is
     *  written and recorded for. Reads the advisory feeds by the neutral coordinate, never an artifact blob. */
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
