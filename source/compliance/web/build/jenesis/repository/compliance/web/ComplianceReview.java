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
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.maintenance.MaintenanceTaskProvider;
import build.jenesis.repository.store.ArtifactStore;
import io.micrometer.observation.ObservationRegistry;

/**
 * The console's review of what the compliance gate and the scanners recorded for a repository, scoped to the
 * signed-in tenant: the quarantine hold queue and its release/discard, the vulnerability panel and its rescan, the
 * persisted findings screen and its AI review decisions, and the license retro blast radius.
 */
public class ComplianceReview extends TenantScope {

    /** How many weakest-scored coordinates one maintainer-health page carries; the rank index pages the rest. */
    private static final int HEALTH_PAGE_SIZE = 500;

    /** The most findings rows the console table shows; the panel says more match, and {@code /api/findings} pages
     *  them. */
    private static final int MAX_ROWS = 500;

    /** The installed findings ledger; without it the vulnerability panel is assembled live and the findings screen says
     *  the store is not installed. */
    private final Optional<FindingsProvider> findingsLedger = FindingsProvider.installed();

    /** The installed maintainer-health ledger; without it the panel says so and a rescan is a no-op. */
    private final Optional<HealthLedgerProvider> healthLedger = HealthLedgerProvider.installed();

    /** Resolves a finding's recorded {@code source} to the mark the screen draws; discovery is fixed for the JVM. */
    private final FindingMarks marks = installedFindingWriters();

    /** The deployment's effective configuration by bare key, or {@code null} to read the stored settings alone. */
    private final UnaryOperator<String> configuration;

    /** A review over the stored settings alone, for a caller with no environment to read. */
    public ComplianceReview(ArtifactStore repositoryStore, CurrentTenant current, ObservationRegistry observations,
                            AuditTrail audit, ConsoleActor actor) {
        this(repositoryStore, current, observations, audit, actor, null);
    }

    /**
     * A review that resolves the feeds, signals and policies from {@code configuration}, the node's effective value of a
     * bare key, as the node's own gate does, so a feed switched on by an environment variable reads as on.
     */
    public ComplianceReview(ArtifactStore repositoryStore, CurrentTenant current, ObservationRegistry observations,
                            AuditTrail audit, ConsoleActor actor, UnaryOperator<String> configuration) {
        super(repositoryStore, current, observations, audit, actor);
        this.configuration = configuration;
    }

    /** A key's value as the node applies it: the effective configuration's where it names one, else the stored
     *  setting. */
    private UnaryOperator<String> effective(Properties settings) {
        if (configuration == null) {
            return settings::getProperty;
        }
        return key -> {
            String value = configuration.apply(key);
            return value != null ? value : settings.getProperty(key);
        };
    }

    /**
     * Everything on this deployment that writes findings under a name, composed here, where all three families are
     * visible: the signal providers as contributors, whose {@code name()} is the recorded source and which may declare a
     * mark; the maintenance task providers as bare names; and the publish screen's two stage names, taken from the
     * module that writes them. A family that starts writing findings is added here, or its rows render as orphans.
     */
    private static FindingMarks installedFindingWriters() {
        Set<String> names = new TreeSet<>(MaintenanceTaskProvider.installed());
        names.add(ComplianceScreen.GATE_SOURCE);
        names.add(ComplianceScreen.INSPECTION_SOURCE);
        return new FindingMarks(SignalSourceProvider.contributors(), names);
    }

    /** A held artifact as the console reviews it: when, path, coordinate, verdict, reasons and the rules that held it,
     *  and the retroactive hold kinds on its coordinate as marks, orphaned for an uninstalled kind. {@code ecosystem}
     *  and {@code bareCoordinate} open the coordinate's page, {@code null} for a path no installed layout places. */
    public record QuarantineView(String when, String path, String coordinate, String verdict, List<String> reasons,
                                 List<String> rules, List<Mark> holds, String ecosystem, String bareCoordinate) {

        /** Whether the row names a coordinate the console can open. */
        public boolean placed() {
            return ecosystem != null && bareCoordinate != null;
        }
    }

    /**
     * The mark for a retroactive hold kind, as {@link FindingMarks} draws a bare installed name: generated while a
     * provider answers to it, orphaned once none does. An orphaned hold still holds; the mark tells the operator no
     * installed module can re-evaluate it.
     */
    private static Mark holdMark(ReviewQueue.HeldKind held) {
        return held.installed() ? Marks.generated(held.kind()) : Marks.orphaned(held.kind());
    }

    /** Every artifact the gate holds for a repository, from the live {@code /quarantine} pointers, enriched from the
     *  {@link QuarantineLog} (see {@link QuarantineLog#reviewQueue()}). */
    public List<QuarantineView> quarantine(String repository) throws IOException {
        return views(repository, ReviewQueue.page(scope(repository), null, Integer.MAX_VALUE - 1));
    }

    /**
     * One page of the gate's {@link ReviewQueue}, as the API serves it, grouped by version and drawn with a mark per
     * hold kind.
     */
    public QuarantinePage quarantine(String repository, String after, int limit) throws IOException {
        ReviewQueue.Page page = ReviewQueue.page(scope(repository), after, limit);
        return new QuarantinePage(QuarantineVersion.of(views(repository, page)), page.next());
    }

    /** The rows of a review-queue page as the console draws them: each hold kind as a mark, and the coordinate page
     *  the path's layout places it on. */
    private List<QuarantineView> views(String repository, ReviewQueue.Page page) throws IOException {
        StoreRepositoryInventory inventory = inventory(repository);
        List<QuarantineView> views = new ArrayList<>();
        for (ReviewQueue.Row row : page.rows()) {
            Optional<ArtifactDescriptor> placed = placed(inventory, row.path());
            views.add(new QuarantineView(row.when(), row.path(), row.coordinate(), row.verdict(), row.reasons(),
                    row.rules(), row.holds().stream().map(ComplianceReview::holdMark).toList(),
                    placed.map(ArtifactDescriptor::ecosystem).orElse(null),
                    placed.map(ArtifactDescriptor::coordinate).orElse(null)));
        }
        return List.copyOf(views);
    }

    /** A page of the review queue and the pointer key the next page starts after ({@code null} on the last). */
    public record QuarantinePage(List<QuarantineVersion> versions, String next) {
    }

    /**
     * The held files of one version, released or discarded together, since a jar is no use without its POM: grouped by
     * the recorded coordinate, the shared reasons said once and each file keeping its own. A file whose log row was lost
     * stands alone.
     */
    public record QuarantineVersion(String coordinate, List<String> rules, List<String> reasons, List<Mark> holds,
                                    List<HeldFile> files, String ecosystem, String bareCoordinate) {

        /** The marks of the hold kinds no installed module answers to, which a reviewer must know before releasing:
         *  nothing could hold the version again for them. */
        public List<Mark> orphaned() {
            return holds.stream().filter(mark -> !mark.installed()).toList();
        }

        /** One held file of the version: its path and the reasons only it was held for. */
        public record HeldFile(String path, List<String> reasons) {
        }

        /** Whether the version names a coordinate the console can open. */
        public boolean placed() {
            return ecosystem != null && bareCoordinate != null;
        }

        /** The paths a release or a discard of the version acts on. */
        public List<String> paths() {
            return files.stream().map(HeldFile::path).toList();
        }

        static List<QuarantineVersion> of(List<QuarantineView> views) {
            Map<String, List<QuarantineView>> grouped = new LinkedHashMap<>();
            for (QuarantineView view : views) {
                grouped.computeIfAbsent(view.coordinate(), _ -> new ArrayList<>()).add(view);
            }
            List<QuarantineVersion> versions = new ArrayList<>();
            for (List<QuarantineView> files : grouped.values()) {
                List<String> shared = new ArrayList<>(files.getFirst().reasons());
                files.forEach(file -> shared.retainAll(file.reasons()));
                Set<String> rules = new LinkedHashSet<>();
                Map<String, Mark> holds = new LinkedHashMap<>();
                String ecosystem = null;
                String bare = null;
                List<HeldFile> held = new ArrayList<>();
                for (QuarantineView file : files) {
                    rules.addAll(file.rules());
                    file.holds().forEach(mark -> holds.putIfAbsent(mark.name(), mark));
                    if (ecosystem == null && file.placed()) {
                        ecosystem = file.ecosystem();
                        bare = file.bareCoordinate();
                    }
                    held.add(new HeldFile(file.path(),
                            file.reasons().stream().filter(reason -> !shared.contains(reason)).toList()));
                }
                held.sort(Comparator.comparing(HeldFile::path));
                versions.add(new QuarantineVersion(files.getFirst().coordinate(), List.copyOf(rules),
                        List.copyOf(shared), List.copyOf(holds.values()), List.copyOf(held), ecosystem, bare));
            }
            return List.copyOf(versions);
        }
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
     * Who signed this repository's accepted versions, a page of {@link SignerIndex} at a time, as
     * {@code /api/signers} serves it.
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

    /** Hold a version for review by hand, as the signed-in operator - every file of it, whatever the gate found.
     *  Answers whether anything was held; a version that serves no file holds nothing. */
    public boolean holdVersion(String repository, String ecosystem, String coordinate, String version)
            throws IOException {
        audit(AuditActions.QUARANTINE_HOLD, repository + " " + ecosystem + " " + coordinate + ":" + version);
        return HoldLifecycle.holdVersion(scope(repository), ecosystem, coordinate, version, actor.name());
    }

    /** Release the version {@code path} belongs to - every file of it held for review. A path no longer held was
     *  released with its version by an earlier call for another of its files, and is passed over. */
    public void releaseQuarantined(String repository, String path) throws IOException {
        RepositoryRequests.rejectTraversal(path);
        if (new Publication(scope(repository)).blob("/quarantine" + path).isEmpty()) {
            return;
        }
        // Audited first, as the API's release is, so a crash leaves it recorded.
        audit(AuditActions.QUARANTINE_RELEASE, repository + path);
        // The primitive the API uses, with its crash-window ordering.
        HoldLifecycle.releaseVersion(scope(repository), path);
    }

    /**
     * Discard a held artifact without releasing it, through the shared {@link HoldLifecycle} primitive: the
     * quarantine log rows, findings document and retroactive {@code holds/} records are reaped, and a retroactive
     * hold's still-held release pointer is evicted so the discarded artifact does not resume serving.
     *
     * @return whether anything was held, so a duplicate or stale discard is not reported as one
     */
    public boolean discardQuarantined(String repository, String path) throws IOException {
        RepositoryRequests.rejectTraversal(path);
        // Audited first, as the release is.
        audit(AuditActions.QUARANTINE_DISCARD, repository + path);
        return !HoldLifecycle.discardVersion(scope(repository), path).isEmpty();
    }

    /**
     * The refused-publish panel beside the {@link #quarantine hold queue}: the recent refusals of every leg, newest
     * first, from {@link QuarantineLog#refusals(int)}.
     */
    public List<Refusal> refusals(String repository, int limit) throws IOException {
        List<Refusal> refusals = new ArrayList<>();
        StoreRepositoryInventory inventory = inventory(repository);
        for (QuarantineLog.Event refusal : new QuarantineLog(scope(repository)).refusals(limit)) {
            Optional<ArtifactDescriptor> placed = placed(inventory, refusal.path());
            refusals.add(new Refusal(refusal.when().toString(), refusal.path(), refusal.coordinate(),
                    refusal.verdict().name(), refusal.reasons(), refusal.rules(),
                    placed.map(ArtifactDescriptor::ecosystem).orElse(null),
                    placed.map(ArtifactDescriptor::coordinate).orElse(null)));
        }
        return refusals;
    }

    /** The coordinate a path names by its owning layout, without a store read, or empty when none places it. */
    private static Optional<ArtifactDescriptor> placed(StoreRepositoryInventory inventory, String path) {
        return inventory.describe(path)
                .filter(descriptor -> descriptor.ecosystem() != null && descriptor.coordinate() != null);
    }

    /** One refusal as the console renders it: when, the coordinate refused, the verdict, and the reasons naming it;
     *  {@code ecosystem} and {@code bareCoordinate} as {@link QuarantineView} carries them. */
    public record Refusal(String when, String path, String coordinate, String verdict, List<String> reasons,
                          List<String> rules, String ecosystem, String bareCoordinate) {

        /** Whether the row names a coordinate the console can open. */
        public boolean placed() {
            return ecosystem != null && bareCoordinate != null;
        }
    }

    /** A repository's vulnerability panel: each vulnerable coordinate ordered by the installed signal columns, from
     *  the findings ledger alone where it is installed, so the panel stands when the feeds are down. */
    public VulnerabilityReports.VulnerabilityReport vulnerabilities(String repository)
            throws IOException {
        return vulnerabilities(repository, null, null);
    }

    /**
    /**
     * A repository's maintainer-health panel: whether the health module is {@code available}, whether a weakest-first
     * ranking is built, its entries, and when it was last swept ({@code null}: never, shown as such).
     *
     * <p>{@code entries} is {@code null}, not empty, when there is no ranking, so the panel cannot read as "nothing is
     * unhealthy". {@code lastScanned} is the ranking's build instant when ranked, the ledger's last sweep otherwise.
     *
     * @param scanning whether a rescan is running now
     */
    public record MaintainerHealthReport(boolean available, boolean ranked,
                                         List<HealthController.HealthEntryView> entries, String nextCursor, int total,
                                         Instant lastScanned, boolean scanning) {
    }

    /** One weakest-first page of the maintainer-health panel from the rank index the scheduled pass commits, with no
     *  probe or write, so it stands when the source is down; {@code cursor} resumes a previous page. */
    public MaintainerHealthReport maintainerHealth(String repository, String cursor) throws IOException {
        if (healthLedger.isEmpty()) {
            return new MaintainerHealthReport(false, false, null, null, 0, null, false);
        }
        return renderHealth(repository, false, cursor);
    }

    /** Starts the explicit rescan behind the panel's button: each held coordinate is probed once against the
     *  maintainer-health source and its score upserted, then the freshness stamped. With the source off it touches no
     *  network. */
    public boolean rescanMaintainerHealth(String repository) throws IOException {
        if (healthLedger.isEmpty()) {
            return false;
        }
        audit("health.rescan", repository);
        // Off the request: a network round trip per coordinate. StoredReport records it running, so a second press is
        // declined.
        return StoredReport.compute(scope(repository), HEALTH_SCAN, forThisTenant(() -> {
            MaintainerHealthReport scanned = renderHealth(repository, true, null);
            return StoredReport.Rows.of(List.of(scanned.total() + " coordinates scored"));
        }));
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
                // Streamed over everything the repository holds, its cached copies too, as the scheduled pass is.
                inventory(repository).holdings(held -> {
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
            // The rank index is left to its leased pass, which alone may flip its marker.
        }
        // A page of the committed ranking; before one exists the panel says so rather than sorting here.
        return switch (ledger.worstFirst(cursor, HEALTH_PAGE_SIZE)) {
            // No entries, not an empty list; the instant is the ledger's last sweep.
            case HealthLedger.Ranking.NotBuilt notBuilt ->
                    new MaintainerHealthReport(true, false, null, null, 0, notBuilt.scannedAt().orElse(null),
                            scanning(repository));
            case HealthLedger.Ranking.Ranked ranked -> {
                List<HealthController.HealthEntryView> entries = new ArrayList<>(ranked.entries().size());
                for (HealthLedger.Located located : ranked.entries()) {
                    entries.add(HealthController.HealthEntryView.of(located));
                }
                // The ranking's own build instant, so the page is never shown fresher than it is.
                yield new MaintainerHealthReport(true, true, entries, ranked.nextCursor(), ranked.total(),
                        ranked.scannedAt().orElse(null), scanning(repository));
            }
        };
    }

    /** The {@link #vulnerabilities(String) vulnerability panel} narrowed by view facets, blank showing everything:
     *  {@code reachability} as {@link AiReachabilityLabels#matches} and {@code applicability} as
     *  {@link ApplicabilityLabels#matches} filter. */
    public VulnerabilityReports.VulnerabilityReport vulnerabilities(String repository,
            String reachability, String applicability)
            throws IOException {
        return vulnerabilities(repository, reachability, applicability, null, VULNERABLE_PAGE);
    }

    /** The rows a vulnerability page shows, and the most a page is asked for. */
    public static final int VULNERABLE_PAGE = 200;

    /** How many stored findings per kind the report assembles before the ranking is built, and how many held versions
     *  a feed-only report queries. */
    static final int LIVE_WINDOW = 500;

    /** The name under which the explicit rescan stores its progress and outcome. */
    public static final String VULNERABILITY_SCAN = "vulnerability-scan";

    /** The maintainer-health rescan's stored report, so the pass runs off the request and the screen reads it back. */
    public static final String HEALTH_SCAN = "health-scan";

    /**
     * One page of the panel, worst first, assembled by {@link VulnerabilityReports} as the API's endpoint is; this
     * surface supplies its tenant, settings and the stored report its rescan button runs under.
     */
    public VulnerabilityReports.VulnerabilityReport vulnerabilities(String repository, String reachability,
                                                                    String applicability, String after, int limit)
            throws IOException {
        Properties settings = settings();
        ArtifactStore store = scope(repository);
        return VulnerabilityReports.read(store, new StoreRepositoryInventory(store),
                AdvisorySource.resolve(effective(settings)), AdvisorySignal.resolve(effective(settings)),
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
     * Starts the explicit rescan in the background: every held version is queried against the enabled feeds, the answers
     * persisted, the scan stamp moved and the ranking rebuilt. Answers whether it started. Without a findings module
     * there is nothing to persist.
     */
    public boolean rescanVulnerabilities(String repository) throws IOException {
        ArtifactStore store = scope(repository);
        return StoredReport.compute(store, VULNERABILITY_SCAN, forThisTenant(() -> rescanNow(repository)));
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
        SequencedMap<String, AdvisorySource> feeds = AdvisorySource.named(effective(settings));
        List<AdvisorySignal> signals = AdvisorySignal.resolve(effective(settings));
        List<String> unrefreshed = FeedRefresh.refreshAll(signals);
        ArtifactStore store = scope(repository);
        Optional<Findings> ledger = findingsLedger.map(provider -> provider.over(store));
        if (ledger.isEmpty()) {
            return StoredReport.Rows.of(List.of("no findings module installed - the report is assembled live"));
        }
        int[] scanned = {0};
        int[] flagged = {0};
        // Everything the repository holds, as the scheduled scan reads it: a proxied copy is served to the same builds.
        inventory(repository).holdings(held -> {
            scanned[0]++;
            if (AdvisoryFindings.record(ledger.get(), feeds, held.ecosystem(), held.coordinate(), held.version(),
                    "console-report", Instant.now())) {
                flagged[0]++;
            }
        });
        Findings.scanned(store).mark(Instant.now());
        VulnerabilityRankIndexTask.reindex(store, ledger.get(), signals,
                DependentsQueryProvider.installed().map(provider -> provider.over(store)));
        // The feed warnings lead, since Rows.of keeps a bounded sample.
        List<String> rows = new ArrayList<>(unrefreshed);
        rows.add(scanned[0] + " versions scanned");
        rows.add(flagged[0] + " with advisories");
        return StoredReport.Rows.of(rows);
    }

    /** The findings screen: whether a persistence module is installed, a bounded window of rows, the filter's facets,
     *  the last feed refresh ({@code null}: never), and whether more match than are shown. */
    public record FindingsPanel(boolean available, List<FindingRow> rows, List<String> kinds, List<String> sources,
                                List<String> categories, Instant lastScanned, boolean truncated) {
    }

    /** One persisted finding as the console renders it, its labels as {@code source/name: value}. {@code reviewable}
     *  marks an AI-produced row that takes a review decision and {@code review} the recorded one; {@code ecosystem} and
     *  {@code version} address a decision; {@code mark} is the source resolved against what is installed now. */
    public record FindingRow(String coordinate, String ecosystem, String bareCoordinate, String version, String id,
                             String source, Mark mark, String kind, String category, String severity,
                             String description, String references, String fixed, String firstSeen, String lastSeen,
                             String supersededBy, List<String> labels, boolean reviewable, String review) {
    }

    /**
     * The findings screen: one bounded window of the findings a filter matches (coordinate bare or
     * {@code coordinate:version}, kind, source, category, severity), superseded ones included with their mark, from the
     * ledger's paged read; the choices come from the filter index's facets, or one unfiltered window before it is
     * built.
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
            // No index yet: the choices come from one unfiltered window.
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

    /** The retroactive licence enforcement dry run ({@link RetroLicensePlanner}) as the blast-radius panel shows it;
     *  {@code includeUnknown} selects the {@code denied+unknown} mode. {@code installed=false} without a planner. */
    public BlastRadiusView blastRadius(String repository, boolean includeUnknown) throws IOException {
        return blastRadius(repository, includeUnknown, RetroLicensePlanner.installed());
    }

    /**
     * The stored blast-radius report for the mode, or the not-installed or not-computed state; the screen never runs the
     * pass, which assesses every release.
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
                () -> rows(planner.get().plan(effective(settings), store, includeUnknown)));
    }

    /** Run the pass now and store its report - the test seam, and what a scheduled enforcement pass may call when it
     *  already holds the plan; the screens read the result through {@link #blastRadius}. */
    public BlastRadiusView computeBlastRadiusNow(String repository, boolean includeUnknown,
                                                 RetroLicensePlanner planner) throws IOException {
        Properties settings = settings();
        ArtifactStore store = scope(repository);
        Instant started = Instant.now();
        StoredReport.Rows rows = rows(planner.plan(effective(settings), store, includeUnknown));
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
