package build.jenesis.repository.compliance.scan;

import module java.base;
import module org.slf4j;
import build.jenesis.repository.cleanup.Release;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.Freshness;
import build.jenesis.repository.compliance.KnownExploitedSource;
import build.jenesis.repository.compliance.Severity;
import build.jenesis.repository.findings.Finding;
import build.jenesis.repository.findings.Findings;
import build.jenesis.repository.findings.FindingsProvider;
import build.jenesis.repository.gate.HoldClears;
import build.jenesis.repository.gate.HoldReleaseObserver;
import build.jenesis.repository.gate.KevHold;
import build.jenesis.repository.inventory.HeldSubjects;
import build.jenesis.repository.inventory.IncrementalPasses;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.maintenance.MaintenanceTask;
import build.jenesis.repository.maintenance.RepositoryContext;
import build.jenesis.repository.maintenance.UnitFailures;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Known;
import build.jenesis.repository.store.Publication;

/**
 * The continuous re-analysis sweep: the self-healing counterpart to the retroactive {@link KevEnforceTask}. Enforcement
 * only tightens - it holds a released artifact once its CVE lands on a known-exploited catalogue - and deliberately
 * never loosens: a KEV delisting leaves the hold in place until a human reviews it, so a hold could outlive the intel
 * that justified it. This exclusive pass closes that gap in the other direction. It walks each repository's inventory
 * of served releases, re-evaluates each against the current advisory and known-exploited feeds, and for a release the
 * enforcement pass retroactively KEV-held (a {@link KevHold} record beside its {@code /quarantine} pointers) whose
 * every known-exploited CVE has since cleared - delisted from every catalogue, or the advisory that carried it retracted
 * - <em>auto-releases</em> the hold: it re-points the held blob back into the release view (never copying it) and clears
 * the {@code /quarantine} pointer, so serving resumes at once through the gate screen's read side. Crucially it writes
 * <em>no</em> override ({@link KevHold#cleared}, not the operator-release path): an intel-driven release is only as good
 * as the current intel, so a later re-listing of the same CVE re-holds the release, while a human's release still sticks
 * as before. Only this sweep's own KEV auto-holds are ever auto-released - a publish-time gate hold or a license hold
 * carries no {@link KevHold} record and is left for its own review flow.
 *
 * <p>The pass also keeps the findings substrate current for served coordinates, over the same walk: a served release a
 * known-exploited catalogue currently lists carries a {@link Finding.Kind#GATE} {@code kev-active} finding (raised when
 * the coordinate becomes actively exploited, refreshed idempotently each pass), and the auto-release supersedes it -
 * categorize-never-discard, so the row stays as the record of what was held and cleared. The finding writes degrade
 * to nothing when no findings module is installed; a refused write is contained so the release convergence still runs
 * for every other coordinate, then named and raised before the unit returns, so the pass is counted as failed rather
 * than reading as a clean convergence over a ledger that took none of its rows (clause 4).
 *
 * <p><b>An automated release never runs on an answer nothing could give.</b> Two questions stand between
 * the cleared intel and the re-point, and both used to be spent as their empty case: whether any OTHER registered hold
 * kind still holds the version, and which request paths the version serves. The first is now asked of the COORDINATE
 * this sweep already has, so the durable {@code holds/<kind>/<eco>/<coord>/<ver>} records answer it with no format
 * involved; the second is three-valued, and a version whose ecosystem no installed format can place holds every
 * position - the KEV record is kept, the {@code /quarantine} pointers stay, and the pass logs why. Before this, an
 * uninstalled format made {@code paths()} empty, which skipped the kind-neutral guard entirely (its loop never ran) and
 * then dropped the KEV record while re-pointing nothing.
 *
 * <p>Exclusive - it re-points hold pointers, so a replicated deployment runs it on one node per interval under the
 * {@code reanalyze} lease, beside the non-exclusive gauge {@code scan} and the exclusive {@code kev-enforce}. It rides
 * the same {@code scheduled-scan} enablement and {@code scan-interval-millis} cadence as both, and whether a pass
 * actually releases is a separate, per-pass decision read from {@code kev-auto-release} (default on - the self-healing
 * mirror of the default-on {@code kev-auto-hold}), so flipping it applies on the next pass without a restart and with it
 * off a hold simply waits for an operator. Self-healing: it holds no state, converging from the store each pass, so on
 * first activation it back-fills - releasing every already-cleared hold a prior enforcement pass left behind. Idempotent
 * and crash-safe: an already-released release has no live hold pointer to clear and no record left to drop, so a second
 * pass or a crashed one re-runs to the same store, and a release is confined to the tenant that holds the copy.
 */
public final class ReanalysisTask implements MaintenanceTask {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReanalysisTask.class);

    /** The source and id of the sweep's own known-exploited finding, so it raises and supersedes exactly its row and
     *  never touches a feed's attributed vulnerability rows (a different source) beside it. */
    private static final String SOURCE = "reanalyze";
    private static final String KEV_ACTIVE = "kev-active";

    /** The supersession mark the auto-release writes onto the cleared {@code kev-active} finding: a non-null successor
     *  simply marks the row inactive (categorize-never-discard), the ledger keeping it as cleared history. */
    private static final String CLEARED = "kev-cleared";

    private final Duration interval;
    private final AdvisorySource advisories;
    private final KnownExploitedSource knownExploited;
    private final Optional<FindingsProvider> findings;

    public ReanalysisTask(Duration interval, AdvisorySource advisories, KnownExploitedSource knownExploited) {
        this(interval, advisories, knownExploited, FindingsProvider.installed());
    }

    /** Embedding/test seam: bind an explicit findings provider (empty to disable the substrate updates) rather than
     *  discovering one through {@link FindingsProvider#installed()}. */
    public ReanalysisTask(Duration interval, AdvisorySource advisories, KnownExploitedSource knownExploited,
                          Optional<FindingsProvider> findings) {
        this.interval = interval;
        this.advisories = advisories;
        this.knownExploited = knownExploited;
        this.findings = findings;
    }

    @Override
    public String name() {
        return "reanalyze";
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
        String flag = config == null ? null : config.apply("kev-auto-release");
        boolean autoRelease = flag == null || flag.isBlank() || !"false".equalsIgnoreCase(flag.trim());
        ArtifactStore store = context.store();
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(store);
        Publication publication = new Publication(store);
        Optional<Findings> ledger = findings.map(provider -> provider.over(store));
        // Fold a released-count over the streamed published set rather than buffering every Release in heap: a
        // repository with millions of versions must not materialise them all just to re-confirm KEV holds on a sweep.
        long[] released = {0};
        // A refused findings write is contained per release so one coordinate does not cost the rest of the walk its
        // convergence, then named and raised below: a contained failure is still the unit's failure (clause 4,
        //). The release convergence itself has always propagated and still does.
        UnitFailures failed = context.failures("The re-analysis sweep of " + context.tenant() + '/'
                + context.repository(),
                "The findings substrate misses this pass's known-exploited status for those coordinates - a "
                + "reviewer and the AI applicability pass read that row, so a silent gap in it is a signal read as "
                + "an answer - and the pass is counted as failed rather than reading as a clean convergence.");
        // Every release every Nth pass; between, the releases published since the last full one - and a full pass
        // at once when a catalogue changed, which the signal refresh asks for by name: a delisting clears holds on
        // old releases, which is exactly what the incremental leg cannot see.
        IncrementalPasses cadence = IncrementalPasses.over(store, name(), "findings/reanalysis", context.config());
        cadence.releases(inventory, release -> {
            String eco = release.ecosystem();
            String coordinate = release.coordinate();
            String version = release.version();
            List<String> kevCves = knownExploitedCves(release);
            if (!kevCves.isEmpty()) {
                // still actively exploited: keep the finding current
                raiseActive(ledger, context, release, kevCves, failed);
                return;                                            // kev-enforce owns holding; nothing to release
            }
            // No known-exploited catalogue lists this coordinate any more - IF the catalogue actually answered.
            // Re-confirm the hold's own recorded CVEs against the catalogue, then require the catalogue to be
            // authoritative. The contains() probes update the source's live-health signal, so freshness is read AFTER
            // them: a per-CVE feed (VulnCheck) that fail-softs to "not listed" during this very re-confirmation reports
            // itself unauthoritative here, and a whole-catalogue feed (CISA) reports its last load. Reading freshness
            // before the probes would read a stale-healthy signal and could still auto-release on a feed that is in fact
            // down for these exact CVEs.
            Optional<Set<String>> record = KevHold.held(store, eco, coordinate, version);
            boolean stillListed = record.isPresent() && record.get().stream().anyMatch(knownExploited::contains);
            Freshness catalogue = knownExploited.freshness();
            if (!catalogue.authoritative()) {
                // The catalogue could not be consulted this pass: an empty knownExploitedCves (and a not-listed
                // re-confirmation) is then a feed outage, not a confirmed delisting. Auto-releasing - or even
                // superseding the ledger's active finding to "kev-cleared", which a reviewer or the AI applicability
                // pass may act on - would fail open on an outage. Hold every position; a later healthy pass (or an
                // operator) moves it. The last-fetch instant rides the log line so an operator can tell a catalogue
                // that has never loaded from one whose refresh started failing an hour ago.
                LOGGER.warn("Holding the KEV auto-release of {}/{} {}:{}: the known-exploited catalogue is unavailable"
                                + " (last fetched {}), so a cleared reading cannot be trusted this pass",
                        context.tenant(), context.repository(), coordinate, version,
                        catalogue.refreshed().map(Instant::toString).orElse("never"));
                return;
            }
            // Converge the findings substrate to kev-cleared ONLY when the coordinate has GENUINELY cleared: the
            // catalogue answered (checked above) AND no recorded CVE is still listed. If it is still listed but an
            // advisory-feed gap left knownExploitedCves empty this pass, superseding the active finding to kev-cleared
            // would corrupt the signal a reviewer or the AI applicability pass reads - the SAME fail-open the outage
            // guard above prevents - even though the hold itself survives via the stillListed check below. So leave the
            // finding active until the coordinate truly delists; a later pass with the CVE genuinely gone clears it.
            if (!stillListed) {
                supersedeActive(ledger, context, release, failed);
            }
            if (record.isEmpty()) {
                return;   // no KEV auto-hold here - a gate or license hold is not this sweep's to release
            }
            if (!autoRelease) {
                return;   // flag off: leave the hold for an operator (kev-enforce's gauge still counts it)
            }
            if (stillListed) {
                // A CVE that justified the hold is still listed by the (authoritative) catalogue. This defends the release
                // against an advisory-feed gap that dropped the advisory carrying the CVE - leaving knownExploitedCves
                // empty above - while the coordinate is in fact still actively exploited: the hold stays.
                return;
            }
            // Which request paths the version serves is layout knowledge, and both legs below need it: the kind-neutral
            // guard's provider fan-out is asked per path, and releaseHeld re-points and clears the /quarantine pointer
            // per path. With the owning format's module off the graph inventory.paths() answered the EMPTY LIST, and
            // emptiness was spent twice over: the guard loop never ran a single iteration, so the kind-neutral question
            // was skipped rather than answered, and releaseHeld then re-pointed nothing while KevHold.cleared
            // dropped the record - an automated sweep deleting the durable statement that this version is held because
            // a module was absent, with the /quarantine pointers still standing for a later accepted re-publish to
            // launder past a registry that no longer names the kind. Three-valued now (knownPaths): an
            // enumeration that could not be made holds every position, and the pass says so.
            Known<List<String>> known = inventory.knownPaths(eco, coordinate, version);
            if (known instanceof Known.Unknown<List<String>> unknown) {
                LOGGER.warn("Holding the KEV auto-release of {}/{} {}:{}: {} - neither the kind-neutral hold guard nor "
                                + "the re-point can be carried out. The hold and its record stay until the format "
                                + "module is installed again", context.tenant(), context.repository(),
                        coordinate, version, unknown.detail());
                return;
            }
            // Past the Unknown arm above, so this narrowing can never raise - it is the fail-closed exit rather than
            // a collapse, and this is an automated RELEASE, where a collapse is exactly what is forbidden.
            List<String> served = known.determined().answer().orElse(List.of());
            // The same version may ALSO be held by another registered hold kind (license, reachability, any future
            // kind added through the HoldReleaseObserver registry): the KEV intel clearing releases only the KEV
            // reason. Re-linking the pointers here would serve an artifact still held for another reason until that
            // kind's next pass re-held it. Ask the kind-neutral registry - not a hardcoded kind list - whether any
            // OTHER kind still holds the version; if so drop only the KEV record (the other kind's record now owns the
            // hold) and leave every pointer and marker in place. Asked by COORDINATE: this sweep already has
            // the triple the durable holds/<kind>/<eco>/<coord>/<ver> records are keyed by, so the authoritative leg
            // needs no format resolution and answers for an uninstalled kind exactly as for an installed one, while the
            // path-keyed provider fan-out rides the paths just proved enumerable.
            if (HoldReleaseObserver.heldByAnotherKind(store, eco, coordinate, version, served, "kev")) {
                KevHold.cleared(store, eco, coordinate, version);
                LOGGER.info("KEV intel cleared for {}/{} {}:{}, but another hold kind remains - pointers stay held",
                        context.tenant(), context.repository(), coordinate, version);
                return;
            }
            releaseHeld(store, publication, inventory, context.now(), release, served);
            KevHold.cleared(store, eco, coordinate, version);      // drop the record (no override) so a re-listing re-holds
            LOGGER.info("Auto-released {}/{} {}:{}: its known-exploited intel cleared", context.tenant(),
                    context.repository(), coordinate, version);
            released[0]++;
        });
        cadence.completed(context.now(), !failed.any());
        context.gauge("jenreg.vulnerabilities.kev.autoreleased",
                "Retroactively KEV-held coordinates this pass auto-released because their known-exploited intel cleared",
                Map.of("tenant", context.tenant(), "repository", context.repository()), released[0]);
        // The release count above is a true statement about the holds this pass really converged, so it is published
        // either way; the pass is still reported FAILED for the findings rows that did not land.
    }

    /** Re-point every held served path of a release back into the release view and clear its {@code /quarantine}
     *  pointer, so serving resumes - the operator-release promotion minus the override the human path records. The blob
     *  is already stored content-addressed, so the release path is re-linked to the same hash rather than copied. A
     *  straggler path with a hold pointer but no blob is just cleared; a path with no live hold (already released) is
     *  skipped, so a second pass converges.
     *
     *  <p>{@code servedPaths} is handed in rather than re-enumerated here: the caller has already established that this
     *  version's path set is <em>knowable</em>, and re-asking would silently degrade to the empty list - a
     *  release that re-points nothing while the record is dropped - if the owning format left the graph between the
     *  two calls. */
    private void releaseHeld(ArtifactStore store, Publication publication, StoreRepositoryInventory inventory,
                             Instant now, Release release, List<String> servedPaths) throws IOException {
        // The released version's own served paths: the excluded set the cross-alias guard reasons over, so a
        // byte-identical SIBLING coordinate's still-standing hold keeps its shared content-addressed marker up. The
        // withhold marker is keyed by content hash (one marker withholds the bytes wherever served) and the
        // blobs-namespace serve gate keys withheld on the marker, not the per-path /quarantine pointer, so clearing a
        // hash another coordinate still holds would un-withhold that sibling.
        Set<String> ownPaths = new HashSet<>(servedPaths);
        ArtifactDescriptor released = new ArtifactDescriptor(release.ecosystem(), release.coordinate(),
                release.version(), null, null, false, null, -1L);
        // Lift the blobs-namespace withhold markers the enforce pass wrote beside its pointers, so a dual-layout
        // format's serving resumes with the publish-namespace paths (idempotent, absent markers are no-ops) - unless a
        // byte-identical sibling coordinate outside this version still holds the hash.
        for (String hash : inventory.blobHashes(release.ecosystem(), release.coordinate(), release.version())) {
            // Routed through the HoldClears owner: it applies the cross-alias guard (a byte-identical sibling still
            // holding the hash keeps the marker standing) and re-marks if a sibling was held in the clear window.
            HoldClears.clearReleased(store, hash, ownPaths, "kev-auto-release", released);
        }
        for (String path : servedPaths) {
            if (store.readVersioned(Publication.quarantineKey(path)).isEmpty()) {
                continue;
            }
            Optional<String> hash = publication.blob("/quarantine" + path);
            if (hash.isEmpty()) {
                publication.unpublish("/quarantine" + path);
                HeldSubjects.forget(store, path);   // the subject record goes with the hold it described (D-103)
                continue;
            }
            HoldClears.clearReleased(store, hash.get(), ownPaths, "kev-auto-release", released.withPath(path));
            // Re-link only when the release pointer is ABSENT: a version re-published with corrected bytes while
            // held (the gate's sweep-owned guard keeps the hold pointer through an accepted re-publish) must not be
            // silently rolled back to the quarantined blob by an intel delisting - the corrected blob keeps serving
            // and only the hold is lifted.
            if (publication.blob(path).isEmpty() && !inventory.servesFromBlobs(release.ecosystem())) {
                // Mirror HoldLifecycle.release's blobs-namespace guard: a pure blobs-namespace format (npm/PyPI/NuGet/
                // RubyGems/Debian/Go) serves from the shared blobs namespace and is deliberately given NO publish/
                // pointer, so its serving already resumed above via HoldClears.clearReleased. Synthesizing a publish/ pointer here
                // (as the operator release path deliberately does not) would strand a phantom publish/ entry the format
                // never serves through and retention - reverse-mapping only through ArtifactLayout - never reclaims,
                // and would corrupt the namespace classification on a subsequent KEV re-listing. Only publish-namespace
                // releases get the pointer re-linked.
                publication.link(path, hash.get());
                inventory.record(path, now);
            }
            publication.unpublish("/quarantine" + path);
            // Reclaimed with the hold, by the lifecycle that ends it rather than by a sweep of its own: this
            // intel-driven auto-release is one of the four ways a hold ends, so it drops the record here exactly as
            // the operator release and discard legs do.
            HeldSubjects.forget(store, path);
        }
    }

    /** Raise (or idempotently refresh) the sweep's own known-exploited finding for a served release a catalogue lists,
     *  so the findings substrate carries the actively-exploited status the retroactive hold acts on. A refused write
     *  is contained so it never costs the rest of the walk its release convergence, and named on {@code failed} so
     *  the unit still raises it (clause 4); with no findings module installed the sweep behaves as if it were
     *  never asked to. */
    private void raiseActive(Optional<Findings> ledger, RepositoryContext context, Release release,
                             List<String> kevCves, UnitFailures failed) {
        if (ledger.isEmpty()) {
            return;
        }
        try {
            Finding finding = Finding.of(KEV_ACTIVE, SOURCE, Finding.Kind.GATE, "known-exploited", Severity.CRITICAL,
                            "Actively exploited: a known-exploited catalogue lists this coordinate's CVE ("
                                    + String.join(", ", kevCves) + ")", context.now())
                    .withReferences(kevCves).withProvenance("reanalysis");
            ledger.get().record(release.ecosystem(), release.coordinate(), release.version(), finding);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Failed to record the known-exploited finding for {}/{} {}:{}; the ledger misses this pass's row "
                            + "and the sweep is reported FAILED", context.tenant(), context.repository(),
                    release.coordinate(), release.version(), e);
            failed.record("raise " + release.ecosystem() + ' ' + release.coordinate() + ':' + release.version(), e);
        }
    }

    /** Supersede the sweep's known-exploited finding once its coordinate has cleared, keeping the row as cleared
     *  history. A no-op when no active {@code kev-active} row is present (the coordinate was never actively exploited,
     *  or a prior pass already superseded it), so it never trips {@link Findings#supersede}'s absent-finding guard.
     *  Contained and raised at the end of the unit, like the raise. */
    private void supersedeActive(Optional<Findings> ledger, RepositoryContext context, Release release,
                                 UnitFailures failed) {
        if (ledger.isEmpty()) {
            return;
        }
        try {
            Findings substrate = ledger.get();
            boolean active = substrate.of(release.ecosystem(), release.coordinate(), release.version()).stream()
                    .anyMatch(finding -> SOURCE.equals(finding.source()) && KEV_ACTIVE.equals(finding.id())
                            && finding.active());
            if (active) {
                substrate.supersede(release.ecosystem(), release.coordinate(), release.version(), SOURCE, KEV_ACTIVE,
                        CLEARED);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Failed to supersede the cleared known-exploited finding for {}/{} {}:{}; it stays active in the "
                            + "ledger until the next pass and the sweep is reported FAILED", context.tenant(),
                    context.repository(), release.coordinate(), release.version(), e);
            failed.record("supersede " + release.ecosystem() + ' ' + release.coordinate() + ':' + release.version(), e);
        }
    }

    /** The known-exploited CVEs among a release's advisories, de-duplicated in encounter order - the CVEs whose whole
     *  clearance frees a retroactive hold. Reads the advisory feeds by the neutral coordinate, never an artifact blob. */
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
