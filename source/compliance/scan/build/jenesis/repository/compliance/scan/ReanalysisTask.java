package build.jenesis.repository.compliance.scan;

import module java.base;
import module org.slf4j;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.GatePolicyProvider;
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
 * The continuous re-analysis sweep, the counterpart to {@link KevEnforceTask}: enforcement only tightens, so a hold
 * could outlive the intel that justified it. For a cached copy the enforcement pass held (a {@link KevHold} record
 * beside its {@code /quarantine} pointers) whose every known-exploited CVE has since cleared - delisted, or its
 * advisory retracted - this pass auto-releases: it re-points the held blob back into the release view and clears the
 * pointer. It writes no override ({@link KevHold#cleared}), so a later re-listing holds the copy again, while a human's
 * release still sticks. Only this sweep's own KEV holds are released; a gate hold or a licence hold carries no
 * {@link KevHold} record.
 *
 * <p>Over the same walk it keeps a {@link Finding.Kind#GATE} {@code kev-active} finding on every served cached copy a
 * catalogue lists, superseded (never discarded) by the auto-release. With no findings module installed the writes
 * degrade to nothing; a refused write is contained so the release convergence still runs, then raised before the unit
 * returns, so the pass counts as failed (clause 4).
 *
 * <p><b>An automated release never runs on an answer nothing could give.</b> Whether another hold kind still holds the
 * version is asked by coordinate, so the durable {@code holds/<kind>/<eco>/<coord>/<ver>} records answer it with no
 * format involved; which paths the version serves is three-valued, and a version no installed format can place keeps
 * every position - the KEV record and the {@code /quarantine} pointers stay, and the pass logs why.
 *
 * <p>Exclusive under the {@code reanalyze} lease, since it re-points hold pointers. Whether a pass releases is read per
 * pass from {@code kev-auto-release} (default on); with it off a hold waits for an operator. It holds no state and
 * converges from the store, so on first activation it releases every already-cleared hold, and a crashed or repeated
 * pass re-runs to the same store.
 */
public final class ReanalysisTask implements MaintenanceTask {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReanalysisTask.class);

    /** The source and id of the sweep's own known-exploited finding, so it never touches a feed's vulnerability
     *  rows. */
    private static final String SOURCE = "reanalyze";
    private static final String KEV_ACTIVE = "kev-active";

    /** The supersession mark the auto-release writes onto the cleared {@code kev-active} finding. */
    private static final String CLEARED = "kev-cleared";

    private final Duration interval;
    private final AdvisorySource advisories;
    private final KnownExploitedSource knownExploited;
    private final Optional<FindingsProvider> findings;

    public ReanalysisTask(Duration interval, AdvisorySource advisories, KnownExploitedSource knownExploited) {
        this(interval, advisories, knownExploited, FindingsProvider.installed());
    }

    /** Binds an explicit findings provider (empty to disable the findings updates) rather than discovering one. */
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
        if (GatePolicyProvider.Path.internal(context.config())) {
            // Its upstreams are marked internal: a copy is judged as a version published here is, asked of no feed.
            return;
        }
        UnaryOperator<String> config = context.config();
        String flag = config == null ? null : config.apply("kev-auto-release");
        boolean autoRelease = flag == null || flag.isBlank() || !"false".equalsIgnoreCase(flag.trim());
        ArtifactStore store = context.store();
        StoreRepositoryInventory inventory = new StoreRepositoryInventory(store);
        Publication publication = new Publication(store);
        Optional<Findings> ledger = findings.map(provider -> provider.over(store));
        // Folded over the streamed published set rather than buffered, so millions of versions are never held in heap.
        long[] released = {0};
        // A refused findings write is contained per release and raised below (clause 4); the release convergence itself
        // propagates.
        UnitFailures failed = context.failures("The re-analysis sweep of " + context.tenant() + '/'
                + context.repository(),
                "The findings substrate misses this pass's known-exploited status for those coordinates - a "
                + "reviewer and the AI applicability pass read that row, so a silent gap in it is a signal read as "
                + "an answer - and the pass is counted as failed rather than reading as a clean convergence.");
        // Every cached copy every Nth pass, the copies cached since between; a changed catalogue asks for a full pass
        // by name, since a delisting clears holds on old copies the incremental leg cannot see. A version published
        // here is asked of no feed, so the copies an upstream served are what this pass re-analyses.
        IncrementalPasses cadence = IncrementalPasses.over(store, name(), "findings/reanalysis", context.config());
        cadence.cached(inventory, copy -> {
            String eco = copy.ecosystem();
            String coordinate = copy.coordinate();
            String version = copy.version();
            List<String> kevCves = knownExploited.listed(advisories.advisories(eco, coordinate, version));
            if (!kevCves.isEmpty()) {
                // still actively exploited: keep the finding current
                raiseActive(ledger, context, copy, kevCves, failed);
                return;                                            // kev-enforce owns holding; nothing to release
            }
            // Delisted only if the catalogue actually answered. The contains() probes update the source's health
            // signal, so freshness is read after them: a per-CVE feed that fails soft to "not listed" during this very
            // re-confirmation then reports itself unauthoritative here.
            Optional<Set<String>> record = KevHold.held(store, eco, coordinate, version);
            boolean stillListed = record.isPresent() && record.get().stream().anyMatch(knownExploited::contains);
            Freshness catalogue = knownExploited.freshness();
            if (!catalogue.authoritative()) {
                // The catalogue could not be consulted, so an empty reading is an outage rather than a delisting.
                // Releasing, or even superseding the active finding a reviewer acts on, would fail open; every position
                // is kept until a healthy pass.
                LOGGER.warn("Holding the KEV auto-release of {}/{} {}:{}: the known-exploited catalogue is unavailable"
                                + " (last fetched {}), so a cleared reading cannot be trusted this pass",
                        context.tenant(), context.repository(), coordinate, version,
                        catalogue.refreshed().map(Instant::toString).orElse("never"));
                return;
            }
            // Supersede the finding only when the coordinate genuinely cleared. If it is still listed but a feed gap
            // left knownExploitedCves empty, the finding stays active, as the hold does below.
            if (!stillListed) {
                supersedeActive(ledger, context, copy, failed);
            }
            if (record.isEmpty()) {
                return;   // no KEV auto-hold here - a gate or license hold is not this sweep's to release
            }
            if (!autoRelease) {
                return;   // flag off: leave the hold for an operator (kev-enforce's gauge still counts it)
            }
            if (stillListed) {
                // A CVE that justified the hold is still listed: an advisory-feed gap dropped it above, and the hold
                // stays.
                return;
            }
            // The served paths are needed by both legs below. An empty list for a version whose format is absent would
            // skip the other-kind guard and re-point nothing while the KEV record was dropped, leaving /quarantine
            // pointers no registry names, so an enumeration that could not be made keeps every position.
            Known<List<String>> known = inventory.knownPaths(eco, coordinate, version);
            if (known instanceof Known.Unknown<List<String>> unknown) {
                LOGGER.warn("Holding the KEV auto-release of {}/{} {}:{}: {} - neither the kind-neutral hold guard nor "
                                + "the re-point can be carried out. The hold and its record stay until the format "
                                + "module is installed again", context.tenant(), context.repository(),
                        coordinate, version, unknown.detail());
                return;
            }
            // Past the Unknown arm, so this cannot collapse an unknown answer into an automated release.
            List<String> served = known.determined().answer().orElse(List.of());
            // Another registered hold kind may still hold the version, and clearing KEV intel releases only the KEV
            // reason. If one does, only the KEV record goes and every pointer and marker stays. Asked by coordinate, so
            // the durable holds/<kind>/<eco>/<coord>/<ver> records answer for an uninstalled kind as for an installed
            // one.
            if (HoldReleaseObserver.heldByAnotherKind(store, eco, coordinate, version, served, "kev")) {
                KevHold.cleared(store, eco, coordinate, version);
                LOGGER.info("KEV intel cleared for {}/{} {}:{}, but another hold kind remains - pointers stay held",
                        context.tenant(), context.repository(), coordinate, version);
                return;
            }
            releaseHeld(store, publication, inventory, context.now(), copy, served);
            KevHold.cleared(store, eco, coordinate, version);      // drop the record (no override) so a re-listing re-holds
            LOGGER.info("Auto-released {}/{} {}:{}: its known-exploited intel cleared", context.tenant(),
                    context.repository(), coordinate, version);
            released[0]++;
        });
        cadence.completed(context.now(), !failed.any());
        context.gauge("jenrepo.vulnerabilities.kev.autoreleased",
                "Retroactively KEV-held coordinates this pass auto-released because their known-exploited intel cleared",
                Map.of("tenant", context.tenant(), "repository", context.repository()), released[0]);
        // The release count is true of the holds this pass converged, so it is published even when the pass fails for
        // findings rows that did not land.
    }

    /**
     * Re-point every held served path of a release back into the release view and clear its {@code /quarantine}
     * pointer: the operator release minus its override. The content-addressed blob is re-linked, not copied; a path
     * with a hold pointer but no blob is just cleared, and a path with no live hold is skipped, so a second pass
     * converges.
     *
     * <p>{@code servedPaths} is passed in because the caller established the path set is knowable; re-asking could
     * answer empty if the format left the graph between the two calls.
     */
    private void releaseHeld(ArtifactStore store, Publication publication, StoreRepositoryInventory inventory,
                             Instant now, StoreRepositoryInventory.Coordinate copy, List<String> servedPaths)
            throws IOException {
        // The version's own paths are what the cross-alias guard excludes: the withhold marker is keyed by content hash
        // and the blobs-namespace serve gate reads it, so clearing a hash a byte-identical sibling still holds would
        // release that sibling too.
        Set<String> ownPaths = new HashSet<>(servedPaths);
        ArtifactDescriptor released = new ArtifactDescriptor(copy.ecosystem(), copy.coordinate(),
                copy.version(), null, null, false, null, -1L);
        // Lift the blobs-namespace markers the enforce pass wrote, so a dual-layout format serves again, unless a
        // sibling outside this version still holds the hash.
        for (String hash : inventory.blobHashes(copy.ecosystem(), copy.coordinate(), copy.version())) {
            HoldClears.clearReleased(store, hash, ownPaths, "kev-auto-release", released);
        }
        for (String path : servedPaths) {
            if (store.readVersioned(Publication.quarantineKey(path)).isEmpty()) {
                continue;
            }
            Optional<String> hash = publication.blob("/quarantine" + path);
            if (hash.isEmpty()) {
                publication.unpublish("/quarantine" + path);
                HeldSubjects.forget(store, path);   // the subject record goes with the hold it described
                continue;
            }
            HoldClears.clearReleased(store, hash.get(), ownPaths, "kev-auto-release", released.withPath(path));
            // Re-link only when the release pointer is absent: bytes re-published while held keep serving rather than
            // being rolled back to the quarantined blob.
            if (publication.blob(path).isEmpty() && !inventory.servesFromBlobs(copy.ecosystem())) {
                // A pure blobs-namespace format has no publish/ pointer and already serves again through clearReleased
                // above. A publish/ pointer written here would be a phantom entry the format never serves and retention
                // never reclaims.
                publication.link(path, hash.get());
                inventory.record(path, now);
            }
            publication.unpublish("/quarantine" + path);
            // The auto-release is one of the ways a hold ends, so it drops the held-subject record as the operator
            // release and discard do.
            HeldSubjects.forget(store, path);
        }
    }

    /** Raise or refresh the sweep's known-exploited finding for a served cached copy a catalogue lists. A refused write
     * is
     *  contained and named on {@code failed}, so the unit still raises it (clause 4). */
    private void raiseActive(Optional<Findings> ledger, RepositoryContext context,
                             StoreRepositoryInventory.Coordinate copy,
                             List<String> kevCves, UnitFailures failed) {
        if (ledger.isEmpty()) {
            return;
        }
        try {
            Finding finding = Finding.of(KEV_ACTIVE, SOURCE, Finding.Kind.GATE, "known-exploited", Severity.CRITICAL,
                            "Actively exploited: a known-exploited catalogue lists this coordinate's CVE ("
                                    + String.join(", ", kevCves) + ")", context.now())
                    .withReferences(kevCves).withProvenance("reanalysis");
            ledger.get().record(copy.ecosystem(), copy.coordinate(), copy.version(), finding);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Failed to record the known-exploited finding for {}/{} {}:{}; the ledger misses this pass's row "
                            + "and the sweep is reported FAILED", context.tenant(), context.repository(),
                    copy.coordinate(), copy.version(), e);
            failed.record("raise " + copy.ecosystem() + ' ' + copy.coordinate() + ':' + copy.version(), e);
        }
    }

    /** Supersede the sweep's known-exploited finding once its coordinate has cleared, keeping the row as history. A
     *  no-op when no active {@code kev-active} row exists, so {@link Findings#supersede}'s absent-finding guard never
     *  trips. Contained and raised like {@link #raiseActive}. */
    private void supersedeActive(Optional<Findings> ledger, RepositoryContext context,
                                 StoreRepositoryInventory.Coordinate copy,
                                 UnitFailures failed) {
        if (ledger.isEmpty()) {
            return;
        }
        try {
            Findings substrate = ledger.get();
            boolean active = substrate.of(copy.ecosystem(), copy.coordinate(), copy.version()).stream()
                    .anyMatch(finding -> SOURCE.equals(finding.source()) && KEV_ACTIVE.equals(finding.id())
                            && finding.active());
            if (active) {
                substrate.supersede(copy.ecosystem(), copy.coordinate(), copy.version(), SOURCE, KEV_ACTIVE,
                        CLEARED);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Failed to supersede the cleared known-exploited finding for {}/{} {}:{}; it stays active in the "
                            + "ledger until the next pass and the sweep is reported FAILED", context.tenant(),
                    context.repository(), copy.coordinate(), copy.version(), e);
            failed.record("supersede " + copy.ecosystem() + ' ' + copy.coordinate() + ':' + copy.version(), e);
        }
    }
}
