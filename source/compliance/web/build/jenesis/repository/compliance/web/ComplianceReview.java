package build.jenesis.repository.compliance.web;

import build.jenesis.repository.ui.store.ConsoleActor;
import build.jenesis.repository.ui.store.RepositoryBrowse;
import build.jenesis.repository.ui.store.TenantScope;
import module java.base;

import build.jenesis.repository.server.kernel.RepositoryRequests;
import build.jenesis.repository.ui.CurrentTenant;
import build.jenesis.repository.audit.AuditActions;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.cleanup.StoredReport;
import build.jenesis.repository.compliance.AdvisoryReport;
import build.jenesis.repository.compliance.AdvisorySignal;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.FeedRefresh;
import build.jenesis.repository.compliance.HealthSource.Health;
import build.jenesis.repository.compliance.HealthSource;
import build.jenesis.repository.compliance.RefreshableSource;
import build.jenesis.repository.compliance.SignalSourceProvider;
import build.jenesis.repository.compliance.SignerIdentity;
import build.jenesis.repository.compliance.signatures.SignerIndex;
import build.jenesis.repository.compliance.scan.VulnerabilityReports;
import build.jenesis.repository.compliance.scan.VulnerabilityRankIndexTask;
import build.jenesis.repository.compliance.scan.VulnerabilityRanking;
import build.jenesis.repository.dependents.spi.DependentsQuery;
import build.jenesis.repository.dependents.spi.DependentsQueryProvider;
import build.jenesis.repository.findings.AdvisoryFindings;
import build.jenesis.repository.findings.AiReachabilityLabels;
import build.jenesis.repository.findings.ApplicabilityLabels;
import build.jenesis.repository.findings.Finding;
import build.jenesis.repository.findings.FindingMarks;
import build.jenesis.repository.findings.Findings;
import build.jenesis.repository.findings.FindingsProvider;
import build.jenesis.repository.findings.ReachabilityLabels;
import build.jenesis.repository.findings.ReviewLabels;
import build.jenesis.repository.gate.store.ComplianceScreen;
import build.jenesis.repository.gate.store.HoldLifecycle;
import build.jenesis.repository.gate.store.ReviewQueue;
import build.jenesis.repository.gate.QuarantineLog;
import build.jenesis.repository.gate.RetroLicensePlanner;
import build.jenesis.repository.health.HealthLedger;
import build.jenesis.repository.health.HealthLedgerProvider;
import build.jenesis.repository.icon.Mark;
import build.jenesis.repository.icon.Marks;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.maintenance.MaintenanceTaskProvider;
import build.jenesis.repository.store.ArtifactStore;
import io.micrometer.observation.ObservationRegistry;

/**
 * The console's review of what the compliance gate and the scanners recorded for a repository, scoped to the
 * signed-in tenant: the quarantine hold queue and its release/discard, the vulnerability panel and its rescan, the
 * persisted findings screen and its AI review decisions, and the license retro blast radius.
 */
public class ComplianceReview extends TenantScope {

    /** How many weakest-scored coordinates one maintainer-health panel page carries - a bound so a repository with a
     *  very large scored set renders the least-maintained projects first without ever holding the whole set (the rank
     *  index pages the rest behind a "next" link; a caller past this many follows the page cursor rather than truncating). */
    private static final int HEALTH_PAGE_SIZE = 500;

    /** The most findings rows the console table holds at once. The facet fold still streams the whole ledger, but the
     *  rendered rows stop here so a repository with a very large finding set does not buffer one row object (and one
     *  HTML table row) per finding on every console GET; the panel says "showing N of M" and the paged
     *  {@code /api/findings} serves the full set. */
    private static final int MAX_ROWS = 500;

    /** The installed findings ledger, resolved once like the search index; empty when the findings module is
     *  absent, in which case the vulnerability panel recomputes live and the findings screen says the store is
     *  not installed. */
    private final Optional<FindingsProvider> findingsLedger = FindingsProvider.installed();

    /** The installed durable maintainer-health ledger; empty when the health module is absent, in which case the
     *  health panel says the store is not installed and the rescan is a no-op. */
    private final Optional<HealthLedgerProvider> healthLedger = HealthLedgerProvider.installed();

    /** The lookup that turns a stored finding's recorded {@code source} into the mark the screen draws for it.
     *  Resolved once here: discovery is static for the life of the JVM, and the findings table asks it once per
     *  rendered row. */
    private final FindingMarks marks = installedFindingWriters();

    public ComplianceReview(ArtifactStore repositoryStore, CurrentTenant current, ObservationRegistry observations,
                            AuditTrail audit, ConsoleActor actor) {
        super(repositoryStore, current, observations, audit, actor);
    }

    /**
     * Everything on this deployment that writes a finding under a name, gathered where all three of them are visible.
     * The composition is the console's because no single module below it can see all three, and it is deliberately
     * explicit rather than a discovery pass of its own: a family that starts writing findings must be added here, and
     * the day it is forgotten its rows render as orphans - loudly wrong - rather than as silently plausible.
     *
     * <ul>
     *   <li>The <b>signal providers</b> come in as contributors: {@code SignalSourceProvider.name()} is literally the
     *       string an advisory it produced is recorded under, and the provider can also declare a mark, so a feed
     *       that ships one is drawn with it.</li>
     *   <li>The <b>maintenance task providers</b> come in as names: the reachability, re-analysis, AI-audit,
     *       AI-applicability and vulnerability-scan tasks each record findings under their own provider name, but a
     *       task provider is a scheduler entry rather than a console-facing plug-in family, so it declares no mark
     *       and its findings draw the generated figure.</li>
     *   <li>The <b>publish screen's two stage names</b> come in as names too, taken from the module that writes them
     *       rather than copied: they are installed wherever the gate is, so they must never read as orphaned, and
     *       they name a stage rather than a plug-in, which is the gap records.</li>
     * </ul>
     * Anything else a ledger holds - a source from a module this deployment no longer has - is by construction not in
     * here, which is exactly the orphan the screen shows.
     */
    private static FindingMarks installedFindingWriters() {
        Set<String> names = new TreeSet<>(MaintenanceTaskProvider.installed());
        names.add(ComplianceScreen.GATE_SOURCE);
        names.add(ComplianceScreen.INSPECTION_SOURCE);
        return new FindingMarks(SignalSourceProvider.contributors(), names);
    }

    /** A held artifact as the console reviews it: when it was held, its path and coordinate, the verdict and the
     *  reasons the gate recorded, and the retroactive hold kinds standing on its coordinate - each drawn with the
     *  same three-state mark a finding's source gets, so a kind whose module has been uninstalled reads as orphaned
     *  rather than vanishing. Empty {@code holds} for a gate hold whose findings name no kind (a CVSS threshold, a
     *  deny-list rule). */
    public record QuarantineView(String when, String path, String coordinate, String verdict, List<String> reasons,
                                 List<Mark> holds) {
    }

    /**
     * The mark for a retroactive hold kind: its own generated figure while a provider answers to it, the same figure
     * in a dashed tile once none does. The rule is {@link FindingMarks}' for a bare installed name, applied to the
     * other durable string this console renders that outlives the module that wrote it - and it is deliberately the
     * same three-state vocabulary rather than a second one, because the operator question is the same question
     * ("which plug-in is this, and is it still here?").
     *
     * <p>There is no {@code DECLARED} state here and there should not be: a hold kind is a
     * {@code HoldReleaseObserver.kind()} token, a store key segment with no mark-bearing seam behind it, exactly like
     * a maintenance-task provider's name on the findings screen.
     *
     * <p>Orphaned never means invalid. The hold still holds - the gate answers from the record, not from
     * the registry - and this mark is the console saying so out loud, so the operator releasing it knows they are
     * releasing a hold no installed module can re-evaluate.
     */
    private static Mark holdMark(ReviewQueue.HeldKind held) {
        return held.installed() ? Marks.generated(held.kind()) : Marks.orphaned(held.kind());
    }

    /** The artifacts the compliance gate is currently holding for a repository, with the verdict and the reasons it
     *  recorded. Derived from the live {@code /quarantine} store pointers - the hold itself, the truth serving reads
     *  through {@code withheld()} - and only <em>enriched</em> from the {@link QuarantineLog}: a hold whose log row
     *  never landed (the row is the un-contained second write of the gate's {@code committed()} leg) still appears,
     *  with a placeholder verdict, so it is always visible and releasable in the console rather than withheld-but-
     *  invisible. The log stays the audit trail, never the index. The queue clears itself as each hold is released or
     *  discarded (the pointer goes), newest first with any log-less holds last. */
    public List<QuarantineView> quarantine(String repository) throws IOException {
        return quarantine(repository, null, Integer.MAX_VALUE - 1).holds();
    }

    /**
     * One bounded page of the review queue: at most {@code limit} holds in path order after the pointer key
     * {@code after} ({@code null} from the top), and the key to continue from - the gate's {@link ReviewQueue} page,
     * which the API serves as it is, drawn here with a mark per hold kind.
     */
    public QuarantinePage quarantine(String repository, String after, int limit) throws IOException {
        // The gate composes the page - the same rows the API serves - and this surface only draws each kind as a mark.
        ReviewQueue.Page page = ReviewQueue.page(scope(repository), after, limit);
        List<QuarantineView> views = new ArrayList<>();
        for (ReviewQueue.Row row : page.rows()) {
            views.add(new QuarantineView(row.when(), row.path(), row.coordinate(), row.verdict(), row.reasons(),
                    row.holds().stream().map(ComplianceReview::holdMark).toList()));
        }
        return new QuarantinePage(List.copyOf(views), page.next());
    }

    /** A page of the review queue and the pointer key the next page starts after ({@code null} on the last). */
    public record QuarantinePage(List<QuarantineView> holds, String next) {
    }

    /** A signer seen on the repository's accepted versions: the wire identity, its short form, the hash the index
     *  files it under, and - for a keyless identity - the issuer and the subject it joins, shown apart, with the
     *  subject as a link where it is one. */
    public record SignerView(String signer, String abbreviated, String id, String issuer, String subject,
                             String link) {

        static SignerView of(SignerIdentity identity, String id) {
            Optional<SignerIdentity.Sigstore> keyless = identity.sigstore();
            return new SignerView(identity.wire(), identity.abbreviated(), id,
                    keyless.map(SignerIdentity.Sigstore::issuer).orElse(null),
                    keyless.map(SignerIdentity.Sigstore::subject).orElse(null),
                    keyless.map(SignerIdentity.Sigstore::subject).filter(RepositoryBrowse::linkable).orElse(null));
        }
    }

    /** A page of signers and the cursor the next page starts after ({@code null} on the last). */
    public record SignersPage(List<SignerView> signers, String next) {
    }

    /** One coordinate a signer signed: how many of its versions, since when, and the last one counted. */
    public record SignedView(String ecosystem, String coordinate, int versions, String since, String last) {
    }

    /** One signer's coordinates, a page at a time, the signer shown as the signers page shows it. */
    public record SignedPage(String signer, String abbreviated, String issuer, String subject, String link,
                             List<SignedView> coordinates, String next) {
    }

    /**
     * Who signed this repository's accepted versions, a page at a time - the same {@link SignerIndex} read the
     * {@code /api/signers} endpoint serves, so the two cannot drift. Bounded: a page of names and one point read
     * each, never a walk.
     */
    public SignersPage signers(String repository, String after, int limit) throws IOException {
        SignerIndex.Page<SignerIndex.Signer> page = SignerIndex.signers(scope(repository), after, limit);
        return new SignersPage(page.rows().stream()
                .map(row -> SignerView.of(row.signer(), row.id())).toList(),
                page.next());
    }

    /** Everything one signer signed here - the blast radius of the key when it is revoked - a page at a time;
     *  {@code signer} is the wire form, and one that is not an identity is refused. */
    public SignedPage signedBy(String repository, String signer, String after, int limit) throws IOException {
        SignerIdentity identity = SignerIdentity.ofWire(signer)
                .orElseThrow(() -> new IllegalArgumentException("Not a signer identity: " + signer));
        SignerIndex.Page<SignerIndex.Signed> page = SignerIndex.signedBy(scope(repository), identity, after, limit);
        SignerView shown = SignerView.of(identity, SignerIndex.id(identity));
        return new SignedPage(identity.wire(), identity.abbreviated(), shown.issuer(), shown.subject(), shown.link(),
                page.rows().stream()
                .map(row -> new SignedView(row.ecosystem(), row.coordinate(), row.versions(),
                        row.since() == null ? "" : row.since().toString(), row.last())).toList(), page.next());
    }

    public void releaseQuarantined(String repository, String path) throws IOException {
        RepositoryRequests.rejectTraversal(path);
        // Audit before the mutation and with the same action/target the /api QuarantineController emits, so a console
        // release is never silently unaudited on a crash and reads identically to an API release in the trail.
        audit(AuditActions.QUARANTINE_RELEASE, repository + path);
        // The shared HoldLifecycle primitive - the same implementation the HTTP review surface uses, so the two can
        // never disagree on crash-window ordering: the override markers are made durable BEFORE the hold pointer is
        // cleared (a crash leaves the hold held-and-overridden; a re-run converges and the enforce sweeps never
        // re-hold the human's release), and the release pointer is linked only when absent, so a version
        // re-published with corrected bytes while held is never rolled back to the quarantined blob.
        HoldLifecycle.release(scope(repository), path);
    }

    /**
     * Discard a held artifact without releasing it, through the shared {@link HoldLifecycle} primitive: the
     * quarantine log rows, findings document and retroactive {@code holds/} records are reaped, and a retroactive
     * hold's still-held release pointer is evicted so the discarded artifact does not resume serving.
     *
     * @return whether anything was actually held. The primitive is idempotent - a duplicate or stale discard
     *         strips no served version's history - but idempotent is not the same as indistinguishable, and the
     *         answer has to reach the operator. This method used to drop it, so the console reported
     *         "Discarded {@code <path>}" for a path nothing was holding: a reviewer who discarded the wrong row,
     *         or raced another reviewer, was told the discard had happened. Returning it is what lets the surface
     *         say which of the two occurred.
     */
    public boolean discardQuarantined(String repository, String path) throws IOException {
        RepositoryRequests.rejectTraversal(path);
        // Audit before the mutation for the same reason as release above: never let a crash end a privileged
        // discard unrecorded; the best-effort trail cannot block the discard.
        audit(AuditActions.QUARANTINE_DISCARD, repository + path);
        return HoldLifecycle.discard(scope(repository), path);
    }

    /**
     * The refused-publish panel beside the {@link #quarantine hold queue}: the recent {@code REJECT} decisions this
     * repository recorded, newest first, read purely from the durable {@link QuarantineLog} (a bounded page of the
     * recent ledger, no re-screen and no fetch - §10 reads render).
     *
     * <p>A refusal is the one gate decision with nothing else to see it by: a quarantined artifact is stored
     * and linked, so it stands in the queue until a reviewer resolves it, while a refused one keeps no bytes and links
     * no pointer and is therefore never in a queue at all. Until this panel existed the console showed the queue alone,
     * so a publish the licence gate denied outright - a 422 to the publisher, nothing to anyone else - left the
     * operator with no record it had happened.
     *
     * <p>Every leg's refusal is here, because "what has this repository refused" is one question: the publish gate's
     * pre-commit denial, the proxy screen's, and the hardened proxy leg's typed structural refusals (oversize, stalled,
     * drift, unparseable, inspector error), each row's reasons naming which leg answered. The gateway-wide drift alarm
     * is a live in-JVM signal of the repository <em>server</em> and surfaces on its deployment health/metrics and
     * verdict API, not from this store-only console read.
     */
    public List<Refusal> refusals(String repository, int limit) throws IOException {
        List<Refusal> refusals = new ArrayList<>();
        for (QuarantineLog.Event refusal : new QuarantineLog(scope(repository)).refusals(limit)) {
            refusals.add(new Refusal(refusal.when().toString(), refusal.path(), refusal.coordinate(),
                    refusal.verdict().name(), refusal.reasons()));
        }
        return refusals;
    }

    /** One refusal as the console renders it: when, the coordinate refused, the verdict, and the reasons naming it. */
    public record Refusal(String when, String path, String coordinate, String verdict, List<String> reasons) {
    }

    /** A repository's vulnerability scan as the console renders it: whether a live feed answered, the report
     *  columns the installed signal modules contribute, the vulnerable coordinates ordered by those signals, and the
     *  instant the ledger was last refreshed against the feeds ({@code null} = never scanned - which the panel must
     *  render as such, never as "clean"). */
    /** One page of the scan: {@code next} resumes after it (null on the last page), {@code total} counts the ranked
     *  lines, {@code partial} says the page was assembled from a bounded window because the ranking has not been
     *  built yet, {@code scanning} that an explicit rescan is running. */

    /** A repository's vulnerability panel, reporting each vulnerable published coordinate ordered by the installed
     *  signal columns (the most urgent signal first, then by coordinate) - so a reviewer prioritises what is actually
     *  being exploited. With the findings module installed this renders purely from the durable ledger (what the
     *  scans, sweeps and an explicit {@linkplain #rescanVulnerabilities rescan} persisted) - no feed round-trip and no
     *  write on the read path, so the panel stands when the feeds are unreachable and a render never pays for a scan.
     *  {@code scanned} is false when no live advisory feed is enabled and nothing served from the store, so an empty
     *  report reads as "scanning is off", not "nothing is wrong". */
    public VulnerabilityReports.VulnerabilityReport vulnerabilities(String repository)
            throws IOException {
        return vulnerabilities(repository, null, null);
    }

    /**
     * A repository's maintainer-health panel as the console renders it: whether the durable health module is
     * {@code available}, whether a weakest-first {@code ranked} view has been built for this repository at all, each
     * scored coordinate's OpenSSF Scorecard-style health (weakest first, so a reviewer meets the least-maintained
     * projects first), and the instant the health was last swept ({@code null} = never scanned, which the panel must
     * render as such, never as "healthy").
     *
     * <p>{@code entries} is {@code null} - not an empty list - whenever there is no ranking to show, which is both the
     * not-installed case and the not-yet-built one. The panel therefore cannot fall through to its empty-list branch
     * and tell a reviewer that no project is unhealthy: it has to render the state it was handed. On a ranked report
     * {@code lastScanned} is the instant the ranking was built at (never a later sweep it has not folded in); on an
     * unranked one it is the ledger's own last sweep, which separates "scored, ranking to follow" from "nothing has
     * ever run here".
     */
    /** @param scanning whether a rescan is running right now, so the panel says so instead of showing a stale
     *                  page with no explanation for why the button did nothing. Mirrors the vulnerability panel. */
    public record MaintainerHealthReport(boolean available, boolean ranked, List<HealthEntry> entries,
                                         String nextCursor, int total, Instant lastScanned, boolean scanning) {
    }

    /** One coordinate's stored maintainer-health: its coordinate, the overall Scorecard score and the three component
     *  signals (a component the source could not evaluate is {@code -1}, told apart from a real zero), the source
     *  repository the score was computed on, and the instant it was scored. */
    public record HealthEntry(String coordinate, String sourceRepository, double overall, double maintenance,
                              double review, double provenance, String scannedAt) {
    }

    /** One weakest-first page of a repository's maintainer-health panel, rendered purely from the durable health ledger
     *  the sweep (and an explicit {@linkplain #rescanMaintainerHealth rescan}) populated - no deps.dev probe and no write
     *  on the read path, so the panel stands when deps.dev is unreachable and a render never pays for a scan (Principle
     *  10). Served from the durable weakest-first rank index the scheduled pass commits, so a repository with a very
     *  large scored set never buffers and sorts every record in heap on a render; {@code cursor} resumes after a
     *  previous page (empty for the first). Three states the panel must keep apart, none of which is an empty table:
     *  {@code available} false is "the health module is not installed"; {@code ranked} false is "no pass has built a
     *  ranking yet" (with the ledger's last sweep beside it); and a ranked page with no entries is "the ranking is
     *  built and holds nothing". {@code lastScanned} null means never scanned, rendered as exactly that. */
    public MaintainerHealthReport maintainerHealth(String repository, String cursor) throws IOException {
        if (healthLedger.isEmpty()) {
            return new MaintainerHealthReport(false, false, null, null, 0, null, false);
        }
        return renderHealth(repository, false, cursor);
    }

    /** Re-scan the repository's published coordinates against the live maintainer-health source - the explicit write
     *  path behind the panel's rescan action: every held coordinate is probed and what the source scores is upserted
     *  into the ledger (version-independent, so each coordinate once), then the freshness is stamped, the rank index
     *  brought current, and the refreshed first page served from the store. The source is built from the same settings
     *  the repository server reads; with it off the rescan is a no-op that touches no network. */
    public boolean rescanMaintainerHealth(String repository) throws IOException {
        if (healthLedger.isEmpty()) {
            return false;
        }
        audit("health.rescan", repository);
        // Off the request thread, for the same reason the vulnerability rescan is. This walks every published
        // coordinate and asks a live source about each one, so its cost is a network round trip per coordinate -
        // unbounded in wall-clock terms and answerable only by the source. Run inline it held the request open
        // until something timed out, and an operator who pressed the button twice started a second full pass over
        // the first. StoredReport records it running before it starts, so the second press is declined and the
        // screen can say what is happening.
        return StoredReport.compute(scope(repository), HEALTH_SCAN, () -> {
            MaintainerHealthReport scanned = renderHealth(repository, true, null);
            return StoredReport.Rows.of(List.of(scanned.total() + " coordinates scored"));
        });
    }

    /** Whether a health rescan is running right now - read from the same stored report the pass writes. */
    private boolean scanning(String repository) throws IOException {
        return StoredReport.read(scope(repository), HEALTH_SCAN)
                .map(StoredReport.Report::running)
                .orElse(false);
    }

    /** Run the health rescan now and answer the first page - the test seam, and what the background pass calls. */
    public MaintainerHealthReport rescanMaintainerHealthNow(String repository) throws IOException {
        if (healthLedger.isEmpty()) {
            return new MaintainerHealthReport(false, false, null, null, 0, null, false);
        }
        return renderHealth(repository, true, null);
    }

    private MaintainerHealthReport renderHealth(String repository, boolean rescan, String cursor) throws IOException {
        HealthLedger ledger = healthLedger.get().over(scope(repository));
        if (rescan) {
            HealthSource source = HealthSource.resolve(settings()::getProperty);
            if (source != HealthSource.none()) {
                Set<String> probed = new HashSet<>();
                // Streamed over the coordinate walk rather than a buffered coordinate list, so re-probing a repository
                // of millions of versions never materialises the whole fleet in heap just to score project health.
                inventory(repository).coordinates(held -> {
                    if (!probed.add(held.ecosystem() + ' ' + held.coordinate())) {
                        return;                                 // health is version-independent: probe each coordinate once
                    }
                    Optional<Health> looked;
                    try {
                        looked = source.health(held.ecosystem(), held.coordinate());
                    } catch (RuntimeException _) {
                        return;                                 // the live source degrades to empty on its own; a throw defers
                    }
                    if (looked.isPresent()) {
                        try {
                            ledger.record(held.ecosystem(), held.coordinate(), looked.get(), Instant.now());
                        } catch (IOException | RuntimeException _) {
                            // best-effort: the sweep persists the coordinate on its next pass
                        }
                    }
                });
            }
            HealthLedger.scanned(scope(repository)).mark(Instant.now());
            // The rank index is NOT rebuilt on this request thread: it mutates shared durable state (reclaims a
            // generation, flips the marker with a plain write) and must stay on the exclusive HealthRankIndexTask's
            // single-writer lease, never racing a scheduled pass or a sibling rescan. So the rescan's own response
            // shows whatever ranking currently stands - or, before the first pass, says there is none yet; what it
            // does NOT do is derive a ranking here to look busy (mirroring /api/health?refresh=true, which likewise
            // persists and leaves the ranking to the pass).
        }
        // One weakest-first bounded page of the ranking the exclusive rank-index pass committed - on the rescan render
        // too, which is why the rescan above deliberately persists and stops. Before that pass has committed one there
        // is no ranking, and the panel says so rather than deriving one here: a whole-ledger sort on the request
        // thread would wear a "weakest first" label over whatever the ledger happened to buffer.
        return switch (ledger.worstFirst(cursor, HEALTH_PAGE_SIZE)) {
            // No entries at all, not an empty list: the panel has to render this as its own state, so a reviewer can
            // never read "the ranking has not been computed yet" as "nothing here is unhealthy". The instant is the
            // ledger's own last sweep, which after a rescan is that rescan - "scored just now, ranking to follow".
            case HealthLedger.Ranking.NotBuilt notBuilt ->
                    new MaintainerHealthReport(true, false, null, null, 0, notBuilt.scannedAt().orElse(null),
                            scanning(repository));
            case HealthLedger.Ranking.Ranked ranked -> {
                List<HealthEntry> entries = new ArrayList<>(ranked.entries().size());
                for (HealthLedger.Located located : ranked.entries()) {
                    Health health = located.health();
                    entries.add(new HealthEntry(located.coordinate(), health.sourceRepository(), health.overall(),
                            health.maintenance(), health.review(), health.provenance(),
                            located.scannedAt().toString()));
                }
                // The eventually-consistent page carries the ranking's own build-time freshness, so it is never shown
                // fresher than it is (Principle 10) - never a later scan the ranking has not folded in.
                yield new MaintainerHealthReport(true, true, entries, ranked.nextCursor(), ranked.total(),
                        ranked.scannedAt().orElse(null), scanning(repository));
            }
        };
    }

    /** The {@link #vulnerabilities(String) vulnerability scan} narrowed by the AI-labelled facets - each a facet
     *  over the view like the license facets, blank or {@code null} showing everything. {@code reachability} keys
     *  on the call-graph verdict ({@code reachable} / {@code not-reachable} / {@code unknown}; the {@code ai:} and
     *  {@code agreed:} spellings key it on the AI classifier's label or the two labels' agreement instead - see
     *  {@link AiReachabilityLabels#matches}); {@code applicability} keys on the AI applicability opinion
     *  ({@code applies} / {@code not-applicable} / {@code unknown} - see {@link ApplicabilityLabels#matches}).
     *  Categorize, never discard: the filters remove nothing from the ledger, and an un-analyzed or un-judged row
     *  matches the {@code unknown} facet so "everything not proven unreachable (or inapplicable)" never hides what
     *  no engine has reached yet. */
    public VulnerabilityReports.VulnerabilityReport vulnerabilities(String repository,
            String reachability, String applicability)
            throws IOException {
        return vulnerabilities(repository, reachability, applicability, null, VULNERABLE_PAGE);
    }

    /** The rows a vulnerability page shows, and the most a page is asked for. */
    public static final int VULNERABLE_PAGE = 200;

    /** How many stored advisory findings per kind the report assembles when the ranking has not been built yet, and
     *  how many held versions the feed-only report (no findings module) queries - the bound that keeps the screen
     *  answering before the scheduled index pass has run on a very large repository. */
    static final int LIVE_WINDOW = 500;

    /** The name under which the explicit rescan stores its progress and outcome. */
    public static final String VULNERABILITY_SCAN = "vulnerability-scan";

    /** The maintainer-health rescan's stored report, so the pass runs off the request and the screen reads it back. */
    public static final String HEALTH_SCAN = "health-scan";

    /**
     * One page of the scan, worst first - assembled by {@link VulnerabilityReports}, which the API's own endpoint
     * assembles through as well.
     *
     * <p>This screen used to build its own report over the same ledger. The two drifted, and the drift reached a
     * deployment: their feed-refresh loops ended up with opposite failure behaviour over one SPI, so a console
     * reported a clean scan over a feed that never loaded. There is one assembly now, so the next difference
     * between the surfaces cannot be a difference of opinion about what the repository holds.
     *
     * <p>What stays here is what is this console's: its tenant, its settings, and the stored report its own rescan
     * button runs under - a console button and an API {@code refresh=true} are separate write paths, so each says
     * which report tracks it.
     */
    public VulnerabilityReports.VulnerabilityReport vulnerabilities(String repository, String reachability,
                                                                    String applicability, String after, int limit)
            throws IOException {
        Properties settings = settings();
        ArtifactStore store = scope(repository);
        return VulnerabilityReports.read(store, new StoreRepositoryInventory(store),
                AdvisorySource.resolve(settings::getProperty), AdvisorySignal.resolve(settings::getProperty),
                findingsLedger.map(provider -> provider.over(store)),
                DependentsQueryProvider.installed().map(provider -> provider.over(store)),
                reachability, applicability, after, Math.max(1, Math.min(limit, VULNERABLE_PAGE)),
                VULNERABILITY_SCAN, List.of());
    }

    private static final class Enough extends RuntimeException {
        private Enough() {
            super(null, null, false, false);
        }
    }

    /**
     * Start the explicit rescan in the background: every held version is queried against the enabled feeds, the
     * answers are persisted to the findings ledger, the scan stamp moves and the ranking is rebuilt, so the next page
     * read shows the outcome. Answers whether a run was started - a rescan already running is not started twice. A
     * deployment without the findings module has nothing to persist; its report is assembled live on every read.
     */
    public boolean rescanVulnerabilities(String repository) throws IOException {
        ArtifactStore store = scope(repository);
        return StoredReport.compute(store, VULNERABILITY_SCAN, () -> rescanNow(repository));
    }

    /** Run the rescan now and answer the first page afterwards - the test seam, and what the background run does. */
    public VulnerabilityReports.VulnerabilityReport rescanVulnerabilitiesNow(String repository) throws IOException {
        ArtifactStore store = scope(repository);
        Instant started = Instant.now();
        StoredReport.Rows rows = rescanNow(repository);
        StoredReport.write(store, VULNERABILITY_SCAN, started, Instant.now(), rows);
        return vulnerabilities(repository);
    }

    private StoredReport.Rows rescanNow(String repository) throws IOException {
        Properties settings = settings();
        SequencedMap<String, AdvisorySource> feeds = AdvisorySource.named(settings::getProperty);
        List<AdvisorySignal> signals = AdvisorySignal.resolve(settings::getProperty);
        List<String> unrefreshed = FeedRefresh.refreshAll(signals);
        ArtifactStore store = scope(repository);
        Optional<Findings> ledger = findingsLedger.map(provider -> provider.over(store));
        if (ledger.isEmpty()) {
            return StoredReport.Rows.of(List.of("no findings module installed - the report is assembled live"));
        }
        int[] scanned = {0};
        int[] flagged = {0};
        inventory(repository).coordinates(held -> {
            scanned[0]++;
            if (!queryAndPersist(ledger.get(), feeds, held).isEmpty()) {
                flagged[0]++;
            }
        });
        Findings.scanned(store).mark(Instant.now());
        VulnerabilityRankIndexTask.reindex(store, ledger.get(), signals,
                DependentsQueryProvider.installed().map(provider -> provider.over(store)));
        // The feed warnings lead, because Rows.of keeps a bounded sample and a warning truncated away is a warning
        // nobody sees.
        List<String> rows = new ArrayList<>(unrefreshed);
        rows.add(scanned[0] + " versions scanned");
        rows.add(flagged[0] + " with advisories");
        return StoredReport.Rows.of(rows);
    }

    /** The rescan primitive for one held coordinate: query each enabled feed, persist every advisory attributed to
     *  its feed (best-effort - the panel must not fail on a write; an upsert by feed and advisory id, so labels and
     *  history survive a refresh), and answer the de-duplicated union exactly as the combined source would. */
    private static List<AdvisorySource.Advisory> queryAndPersist(Findings ledger,
                                                                 SequencedMap<String, AdvisorySource> feeds,
                                                                 StoreRepositoryInventory.Coordinate held) {
        List<AdvisorySource.Advisory> merged = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Instant now = Instant.now();
        for (Map.Entry<String, AdvisorySource> feed : feeds.entrySet()) {
            for (AdvisorySource.Advisory advisory : feed.getValue().advisories(
                    held.ecosystem(), held.coordinate(), held.version())) {
                try {
                    ledger.record(held.ecosystem(), held.coordinate(), held.version(),
                            AdvisoryFindings.of(advisory, feed.getKey(), "console-report", now));
                } catch (IOException | RuntimeException _) {
                    // best-effort: the live answer stands; the sweep persists the coordinate on its next pass
                }
                Set<String> identifiers = new HashSet<>(advisory.cves());
                identifiers.add(advisory.id());
                if (identifiers.stream().noneMatch(seen::contains)) {
                    merged.add(advisory);
                    seen.addAll(identifiers);
                }
            }
        }
        return merged;
    }

    /** Stored findings rendered as advisories, de-duplicated by id and CVE alias for the panel only - the ledger
     *  keeps every feed's attributed row. */
    private static List<AdvisorySource.Advisory> deduplicated(List<Finding> stored) {
        List<AdvisorySource.Advisory> merged = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Finding finding : stored) {
            AdvisorySource.Advisory advisory = AdvisoryFindings.advisory(finding);
            Set<String> identifiers = new HashSet<>(advisory.cves());
            identifiers.add(advisory.id());
            if (identifiers.stream().noneMatch(seen::contains)) {
                merged.add(advisory);
                seen.addAll(identifiers);
            }
        }
        return merged;
    }

    /** The findings screen as the console renders it: whether a persistence module is installed, the filtered
     *  rows (a bounded window of the {@code matched} total), the distinct kind/source/category facets recorded in
     *  the repository for the filter form, the instant the ledger was last refreshed against the advisory feeds
     *  ({@code null} = never scanned - rendered as such, never as clean; Principle 10's staleness line), whether the
     *  shown rows were {@code truncated} below the match count, and the full {@code matched} total so the view can say
     *  "showing N of M" and point at the paged {@code /api/findings} for the rest. */
    public record FindingsPanel(boolean available, List<FindingRow> rows, List<String> kinds, List<String> sources,
                                List<String> categories, Instant lastScanned, boolean truncated) {
    }

    /** One persisted finding as the console renders it: its coordinate, identity and attribution, its
     *  categorization, the persisted description and references, the fixed version where one was recorded, its
     *  first/last sighting, the supersession mark ({@code null} while the finding stands) and any attached labels
     *  rendered {@code source/name: value}. The review trio drives the AI review queue: {@code reviewable} says
     *  the row is AI-produced (an {@code ai-candidate} or an {@code applicability} judgement) and so takes a
     *  confirm/dismiss decision; {@code review} is the recorded decision ({@code confirmed} / {@code dismissed},
     *  empty while pending); {@code ecosystem} and {@code version} carry the ledger address a decision posts
     *  back to. {@code mark} is {@code source} resolved against what is installed <em>now</em> - the plug-in's own
     *  mark, its generated figure, or the dashed orphan figure when nothing answers to that name any more - so a row
     *  says who reported it and whether that reporter is still here, without the operator cross-checking a module
     *  list. */
    public record FindingRow(String coordinate, String ecosystem, String bareCoordinate, String version, String id,
                             String source, Mark mark, String kind, String category, String severity,
                             String description, String references, String fixed, String firstSeen, String lastSeen,
                             String supersededBy, List<String> labels, boolean reviewable, String review) {
    }

    /**
     * The findings screen's read: the persisted findings in a repository, filterable by coordinate (bare or
     * {@code coordinate:version} - the per-artifact view), kind (wire spelling), source, category and severity.
     * Superseded findings are returned with their mark, never hidden, beside their labels. {@code available} is
     * false when no findings module is installed, so the screen says so rather than reading as "no findings"; the
     * facet lists carry the distinct kinds, sources and categories actually recorded, for the filter form.
     */
    /**
     * The findings console: one bounded window of the findings a filter matches, and the filter's choices. The rows
     * come from the ledger's paged read - the filter index when it stands, else a bounded walk - never a fold over
     * the whole ledger; the choices come from the index's facet buckets, or from one unfiltered window while no
     * index has been built. {@code truncated} says more findings match than the window shows.
     */
    public FindingsPanel findings(String repository, String coordinate, String kind, String source, String category,
                                  String severity) throws IOException {
        if (findingsLedger.isEmpty()) {
            return new FindingsPanel(false, List.of(), List.of(), List.of(), List.of(), null, false);
        }
        Findings ledger = findingsLedger.get().over(scope(repository));
        Finding.Kind kindFilter = kind == null || kind.isBlank() ? null
                : Finding.Kind.ofWire(kind).orElseThrow(() -> new IllegalArgumentException("Unknown kind: " + kind));
        build.jenesis.repository.compliance.Severity severityFilter = severity == null || severity.isBlank() ? null
                : build.jenesis.repository.compliance.Severity.valueOf(severity.toUpperCase(Locale.ROOT));
        Findings.Filter filter = new Findings.Filter(
                coordinate == null || coordinate.isBlank() ? null : coordinate,
                kindFilter, source == null || source.isBlank() ? null : source,
                category == null || category.isBlank() ? null : category, severityFilter, null);
        Findings.Page page = ledger.all(filter, 0, MAX_ROWS);
        Set<String> kinds = new TreeSet<>();
        Set<String> sources = new TreeSet<>();
        Set<String> categories = new TreeSet<>();
        List<FindingRow> rows = new ArrayList<>();
        for (Findings.Located located : page.located()) {
            Finding finding = located.finding();
            kinds.add(finding.kind().wire());
            sources.add(finding.source());
            if (!finding.category().isEmpty()) {
                categories.add(finding.category());
            }
            List<String> labels = new ArrayList<>();
            for (Finding.Label label : finding.labels()) {
                labels.add(label.source() + "/" + label.name() + ": " + label.value());
            }
            rows.add(new FindingRow(located.coordinate() + ":" + located.version(), located.ecosystem(),
                    located.coordinate(), located.version(), finding.id(), finding.source(),
                    marks.of(finding.source()),
                    finding.kind().wire(), finding.category(), finding.severity().name(), finding.description(),
                    String.join(", ", finding.references()), finding.attributes().getOrDefault("fixed", ""),
                    finding.firstSeen().toString(), finding.lastSeen().toString(), finding.supersededBy(), labels,
                    ReviewLabels.reviewable(finding.kind()), ReviewLabels.reviewOf(finding).orElse("")));
        }
        Optional<Findings.Facets> facets = ledger.facets();
        if (facets.isPresent()) {
            kinds.addAll(facets.get().kinds());
            sources.addAll(facets.get().sources());
            categories.addAll(facets.get().categories());
        } else if (filter.kind() != null || filter.source() != null || filter.category() != null
                || filter.severity() != null || filter.coordinate() != null) {
            // No index yet: the choices fold over one unfiltered window, so a narrowed view keeps offering the
            // other values - bounded exactly as the rows are, never the whole ledger.
            for (Findings.Located located : ledger.all(Findings.Filter.none(), 0, MAX_ROWS).located()) {
                kinds.add(located.finding().kind().wire());
                sources.add(located.finding().source());
                if (!located.finding().category().isEmpty()) {
                    categories.add(located.finding().category());
                }
            }
        }
        return new FindingsPanel(true, rows, List.copyOf(kinds), List.copyOf(sources), List.copyOf(categories),
                Findings.scanned(scope(repository)).read().orElse(null), page.more());
    }

    public void review(String repository, String ecosystem, String coordinate, String version, String source,
                       String id, String decision, String note) throws IOException {
        if (findingsLedger.isEmpty()) {
            throw new IllegalStateException("No findings module is installed");
        }
        ReviewLabels.apply(findingsLedger.get().over(scope(repository)), ecosystem, coordinate, version,
                source, id, decision, note, Instant.now());
    }

    /** The retroactive-license-enforcement dry-run for a repository - the console blast-radius panel over the discovered
     *  {@link RetroLicensePlanner} (provided by the {@code compliance/licenses} module): what a fresh enabling pass
     *  <em>would</em> newly hold under the current license policy, so a reviewer sees the blast radius before turning
     *  enforcement on. {@code includeUnknown} widens the preview to the riskier {@code denied+unknown} mode (holding the
     *  coordinates whose license could not be identified) versus the high-confidence {@code denied}-only set. The plan
     *  is read-only (it reads the license sidecars and hold/override markers, never an artifact blob and never a write)
     *  and, like the {@code /api/licenses/retro/plan} surface it mirrors, lists only what an enabling pass would newly
     *  hold. Reports {@code installed=false} (empty held list) when no license-policy module contributes a planner - the
     *  same graceful degrade the endpoint answers {@code 501} on - so the panel states it is not installed. */
    public BlastRadiusView blastRadius(String repository, boolean includeUnknown) throws IOException {
        return blastRadius(repository, includeUnknown, RetroLicensePlanner.installed());
    }

    /**
     * The stored blast-radius report for the mode, or the not-installed / not-computed degrade: the screen reads
     * what the last pass found and never runs the pass itself - the pass assesses every release in the repository.
     * The explicit-planner overload is the embedding/test seam for the not-installed degrade.
     */
    public BlastRadiusView blastRadius(String repository, boolean includeUnknown,
                                       Optional<RetroLicensePlanner> planner) throws IOException {
        String mode = includeUnknown ? "denied+unknown" : "denied";
        if (planner.isEmpty()) {
            return new BlastRadiusView(false, mode, 0, List.of(), null, false, false);
        }
        Optional<StoredReport.Report> report = StoredReport.read(scope(repository), blastRadiusReport(includeUnknown));
        if (report.isEmpty()) {
            return new BlastRadiusView(true, mode, 0, List.of(), null, false, false);
        }
        StoredReport.Report stored = report.get();
        List<BlastRadiusHeld> held = new ArrayList<>();
        for (String row : stored.rows()) {
            String[] parts = row.split("\t", 4);
            if (parts.length == 4) {
                held.add(new BlastRadiusHeld(parts[0], parts[1], parts[2],
                        parts[3].isEmpty() ? List.of() : List.of(parts[3].split("; "))));
            }
        }
        return new BlastRadiusView(true, mode, stored.count(), held,
                stored.finishedAt() == null ? stored.startedAt() : stored.finishedAt(), stored.running(), true);
    }

    /** Start the blast-radius pass for the mode in the background; answers whether a run was started. */
    public boolean computeBlastRadius(String repository, boolean includeUnknown) throws IOException {
        Optional<RetroLicensePlanner> planner = RetroLicensePlanner.installed();
        if (planner.isEmpty()) {
            return false;
        }
        Properties settings = settings();
        ArtifactStore store = scope(repository);
        return StoredReport.compute(store, blastRadiusReport(includeUnknown),
                () -> rows(planner.get().plan(settings::getProperty, store, includeUnknown)));
    }

    /** Run the pass now and store its report - the test seam, and what a scheduled enforcement pass may call when it
     *  already holds the plan; the screens read the result through {@link #blastRadius}. */
    public BlastRadiusView computeBlastRadiusNow(String repository, boolean includeUnknown,
                                                 RetroLicensePlanner planner) throws IOException {
        Properties settings = settings();
        ArtifactStore store = scope(repository);
        Instant started = Instant.now();
        StoredReport.Rows rows = rows(planner.plan(settings::getProperty, store, includeUnknown));
        StoredReport.write(store, blastRadiusReport(includeUnknown), started, Instant.now(), rows);
        return blastRadius(repository, includeUnknown, Optional.of(planner));
    }

    private static StoredReport.Rows rows(RetroLicensePlanner.Plan plan) {
        List<String> rows = new ArrayList<>();
        for (RetroLicensePlanner.Held entry : plan.held()) {
            rows.add(entry.ecosystem() + "\t" + entry.coordinate() + "\t" + entry.version() + "\t"
                    + String.join("; ", entry.reasons()));
        }
        return new StoredReport.Rows(plan.count(), List.copyOf(rows.subList(0, Math.min(rows.size(),
                StoredReport.SAMPLE))));
    }

    private static String blastRadiusReport(boolean includeUnknown) {
        return includeUnknown ? "blast-radius-unknown" : "blast-radius";
    }

    /** The blast-radius screen's model: what the last pass found ({@code held} is the first rows of {@code count}),
     *  when it finished, whether a pass is running now, and whether one has ever been computed. */
    public record BlastRadiusView(boolean installed, String mode, int count, List<BlastRadiusHeld> held,
                                  Instant computedAt, boolean running, boolean computed) {
    }

    /** One release a retroactive-enforcement pass would newly hold, with the human-readable reasons behind the hold. */
    public record BlastRadiusHeld(String ecosystem, String coordinate, String version, List<String> reasons) {
    }






}
