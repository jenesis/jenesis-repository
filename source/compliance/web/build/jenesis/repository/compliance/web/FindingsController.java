package build.jenesis.repository.compliance.web;

import module java.base;
import build.jenesis.repository.audit.AuditActions;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.Severity;
import build.jenesis.repository.gate.store.ReportedFindings;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.findings.Finding;
import build.jenesis.repository.findings.Findings;
import build.jenesis.repository.findings.FindingsProvider;
import build.jenesis.repository.findings.ReviewLabels;
import build.jenesis.repository.findings.WaiverLabels;
import build.jenesis.repository.server.kernel.RepositoryRequests;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.store.ArtifactStore;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The findings-ledger query surface: {@code GET /api/findings?repo=} lists every persisted finding in a repository,
 * filterable by coordinate (bare or {@code coordinate:version}), kind, source, category and severity - the durable
 * "what was found, by whom, and how is it categorized" view beside the recomputing {@code /api/vulnerabilities}
 * report. Superseded findings are returned with their mark, never hidden, and every row carries its labels - the
 * categorize-never-discard ledger read raw. Gated {@code manage:read} by the security chain before the request is
 * reached; a traversal-unsafe repository or tenant name is a {@code 400}, an unknown kind or severity spelling a
 * {@code 400}, and a deployment without the findings module answers {@code 501} so absence never reads as "no
 * findings". This is a raw read of the durable ledger, not a derived surface that rebuilds itself: findings accrue as
 * the gate holds an artifact and as the vulnerability sweep scans, so an <em>empty</em> ledger means "nothing has been
 * recorded yet" (a module enabled late over pre-existing artifacts has none until a scan runs), not "definitively
 * clean". The self-healing that back-fills findings over a store that predates the module is owned by the
 * {@code /api/vulnerabilities} report, which walks the live inventory and re-derives into this same ledger; a client
 * that needs the converged view reads there, and treats this endpoint as the persisted record beside it.
 *
 * <p>{@code POST /api/findings/review} is the review-queue mutation: an operator confirms or dismisses an
 * AI-produced finding (an {@code ai-candidate} the code audit emitted, an {@code applicability} judgement), written
 * through the {@link ReviewLabels} contract as an attributed label on the still-present row - a decision, never a
 * deletion, and reversible. Held to AI-produced kinds by that contract: an advisory feed's row or a gate decision
 * is authoritative data no review label may editorialize, and asking answers {@code 400}. Gated
 * {@code manage:write} by the security chain, and each decision writes an audit event as the privileged mutation
 * it is.
 *
 * <p>{@code POST /api/findings/waiver} and {@code POST /api/findings/waiver/revoke} are the accept-risk waiver
 * workflow: an operator records (or withdraws) a time-boxed decision to accept a known vulnerability on a coordinate,
 * written through the {@link WaiverLabels} contract as an {@code accept-risk} label on the still-present advisory
 * finding - the same operator-confirmed-candidate model as a review, so the gate and the {@code /api/vulnerabilities}
 * ranking read a durable annotation rather than a side store. Held to advisory-derived kinds by that contract (a
 * licence fact or a gate decision is not a risk a waiver defers, answered {@code 400}), the expiry must be in the
 * future ({@code 400}), and a revoke relabels rather than deletes so the acceptance stops being honoured at once while
 * the finding stays fully present. Gated {@code manage:write} and audited exactly as a review is; the acceptance
 * auto-lapses at its expiry with no sweep having to retract it.
 *
 * <p>{@code POST /api/findings/report} takes findings about a version this repository serves from outside - a
 * scanner a CI job ran over an image or an archive - attributed to the scanner the report names, and treats them as
 * the gate's own through {@link ReportedFindings}: recorded in the ledger under that source, decided by the
 * deployment's gate with its threshold, action, VEX and waivers, and a verdict other than allow withholds the
 * version onto the review queue, where the ordinary release clears it. It answers what it recorded, the verdict and
 * whether the version is now withheld; a version the repository does not serve is a {@code 404}, a malformed report
 * a {@code 400}. It needs a key that may write to the repository - a report can withdraw an artifact from serving,
 * so one anyone could post would be a way to withhold someone else's - and it is audited as the mutation it is.
 */
@RestController
public class FindingsController {

    private final Repositories repositories;
    private final AuditTrail audit;
    private final Optional<FindingsProvider> findings;
    private final Function<String, ComplianceGate> gates;

    public FindingsController(Repositories repositories, AuditTrail audit, Function<String, ComplianceGate> gates) {
        this(repositories, audit, FindingsProvider.installed(), gates);
    }

    /** Embedding/test seam: bind an explicit findings provider rather than discovering one. {@code gates} answers
     *  the publish gate a tenant's reports are decided by. */
    public FindingsController(Repositories repositories, AuditTrail audit, Optional<FindingsProvider> findings,
                              Function<String, ComplianceGate> gates) {
        this.repositories = repositories;
        this.audit = audit;
        this.findings = findings;
        this.gates = gates;
    }

    /** The most findings one report may carry: a scan of a large image reports a few hundred, and the bound keeps
     *  one request from writing an unbounded ledger mutation. */
    static final int MAX_REPORTED = 10_000;

    /** A scanner's name as a report gives it and the ledger records it: short, lower-case, and safe as a label. */
    private static final Pattern SOURCE = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    @PostMapping("/api/findings/report")
    @ResponseBody
    public ReportAnswer report(@RequestParam("repo") String repo,
                               @RequestHeader(value = Repositories.KEY, required = false) String key,
                               @RequestBody ReportRequest request,
                               HttpServletResponse response) throws IOException {
        if (!Repositories.valid(repo)) {
            response.setStatus(400);
            return null;
        }
        String tenant = repositories.tenant(key);
        if (!Repositories.valid(tenant)) {
            response.setStatus(400);
            return null;
        }
        if (findings.isEmpty()) {
            response.setStatus(501);
            return null;
        }
        ReportedFindings.Report report;
        try {
            report = request.report();
        } catch (IllegalArgumentException _) {
            response.setStatus(400);
            return null;
        }
        ArtifactStore store = repositories.writable(tenant, repo);
        Optional<ReportedFindings.Outcome> outcome = ReportedFindings.apply(store,
                findings.get().over(repositories.store(tenant, repo)), gates.apply(tenant), report, Instant.now());
        if (outcome.isEmpty()) {
            response.setStatus(404);
            return null;
        }
        audit.record(tenant, key == null ? "anonymous" : Authorization.hash(key), AuditActions.FINDINGS_REPORT,
                repo + " " + report.coordinate() + ":" + report.version() + " " + report.source() + " "
                        + outcome.get().verdict());
        response.setStatus(200);
        return ReportAnswer.of(outcome.get());
    }

    @GetMapping("/api/findings")
    @ResponseBody
    public FindingsView findings(@RequestParam("repo") String repo,
                                 @RequestParam(value = "coordinate", required = false) String coordinate,
                                 @RequestParam(value = "ecosystem", required = false) String ecosystem,
                                 @RequestParam(value = "kind", required = false) String kind,
                                 @RequestParam(value = "source", required = false) String source,
                                 @RequestParam(value = "category", required = false) String category,
                                 @RequestParam(value = "severity", required = false) String severity,
                                 @RequestParam(value = "offset", defaultValue = "0") int offset,
                                 @RequestParam(value = "limit", defaultValue = "500") int limit,
                                 @RequestHeader(value = Repositories.KEY, required = false) String key,
                                 HttpServletResponse response) throws IOException {
        if (!Repositories.valid(repo)) {
            response.setStatus(400);
            return null;
        }
        String tenant = repositories.tenant(key);
        if (!Repositories.valid(tenant)) {
            response.setStatus(400);
            return null;
        }
        if (findings.isEmpty()) {
            response.setStatus(501);
            return null;
        }
        Finding.Kind kindFilter = null;
        if (kind != null && !kind.isBlank()) {
            Optional<Finding.Kind> parsed = Finding.Kind.ofWire(kind);
            if (parsed.isEmpty()) {
                response.setStatus(400);
                return null;
            }
            kindFilter = parsed.get();
        }
        Severity severityFilter = null;
        if (severity != null && !severity.isBlank()) {
            try {
                severityFilter = Severity.valueOf(severity.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException _) {
                response.setStatus(400);
                return null;
            }
        }
        ArtifactStore store = repositories.store(tenant, repo);
        Findings ledger = findings.get().over(store);
        // A bounded slice, never the whole ledger per GET (Principle 7/§10): the paged overload stops the key-tree
        // walk one row past the requested window, and `more` tells the client whether another page remains.
        Findings.Page page = ledger.all(new Findings.Filter(
                blankToNull(coordinate), kindFilter, blankToNull(source), blankToNull(category), severityFilter,
                blankToNull(ecosystem)), Math.max(0, offset), Math.clamp(limit, 1, 1000));
        List<FindingView> views = new ArrayList<>();
        for (Findings.Located located : page.located()) {
            views.add(FindingView.of(located));
        }
        // The instant the served list is honestly as-of (Principle 10). A selective query served from the
        // eventually-consistent findings-filter index carries the index's own build-time scan freshness, so the view is
        // never labelled fresher than the built index it came from - render that stamp (blank means never scanned, so
        // null). Only on the live-walk path (a coordinate/bare filter, or a not-yet-built index falling back to the
        // walk) does the page carry no built stamp; there the live scan stamp is the honest as-of, exactly as the
        // health/vulnerability rank controllers split it. {@code null} means never scanned, a client renders as such
        // rather than as "clean". No write on this read path.
        Instant lastScanned = page.builtScanStamp() != null
                ? (page.builtScanStamp().isBlank() ? null : Instant.parse(page.builtScanStamp()))
                : Findings.scanned(store).read().orElse(null);
        return new FindingsView(true, views, page.more(), lastScanned);
    }

    /**
     * Record an operator's review decision on an AI-produced finding: {@code decision} is {@code confirmed} or
     * {@code dismissed}, {@code note} an optional reviewer comment; both land as attributed labels through
     * {@link ReviewLabels#apply}, which refuses a non-AI-produced row - answered as {@code 400}, exactly like an
     * unknown finding or decision spelling. The row itself is untouched: a dismissal is a mark on a still-present,
     * still-served finding, never a removal.
     */
    @PostMapping("/api/findings/review")
    public void review(@RequestParam("repo") String repo,
                       @RequestParam("ecosystem") String ecosystem,
                       @RequestParam("coordinate") String coordinate,
                       @RequestParam("version") String version,
                       @RequestParam("source") String source,
                       @RequestParam("id") String id,
                       @RequestParam("decision") String decision,
                       @RequestParam(value = "note", required = false) String note,
                       @RequestHeader(value = Repositories.KEY, required = false) String key,
                       HttpServletResponse response) throws IOException {
        if (!Repositories.valid(repo)) {
            response.setStatus(400);
            return;
        }
        String tenant = repositories.tenant(key);
        if (!Repositories.valid(tenant)) {
            response.setStatus(400);
            return;
        }
        if (findings.isEmpty()) {
            response.setStatus(501);
            return;
        }
        Findings ledger = findings.get().over(repositories.store(tenant, repo));
        try {
            // The ledger key concatenates ecosystem and version raw (only the coordinate is URL-encoded), and the
            // security chain normalizes only the URL path, never these request parameters - so a parent-directory
            // segment here would aim the label read/write at a store key outside the findings/ subtree within the
            // repository scope. Reject it, as the sibling quarantine/provenance mutations already do their paths.
            RepositoryRequests.rejectTraversal(ecosystem);
            RepositoryRequests.rejectTraversal(coordinate);
            RepositoryRequests.rejectTraversal(version);
            ReviewLabels.apply(ledger, ecosystem, coordinate, version, source, id, decision, note, Instant.now());
        } catch (IllegalArgumentException _) {
            response.setStatus(400);
            return;
        }
        audit.record(tenant, key == null ? "anonymous" : Authorization.hash(key),
                "findings.review." + decision.toLowerCase(Locale.ROOT),
                repo + " " + coordinate + ":" + version + " " + source + ":" + id);
        response.setStatus(200);
    }

    /**
     * Record an operator's accept-risk waiver on an advisory-derived finding: {@code expires} is the ISO-8601 instant
     * the acceptance stands until (which must be in the future), {@code note} an optional justification; both land as
     * attributed labels through {@link WaiverLabels#apply}, which refuses a non-advisory row, a missing finding or a
     * past expiry - answered {@code 400}. The finding itself is untouched: the waiver is a mark on a still-present,
     * still-served row that the gate and the vulnerability ranking honour only while it is active.
     */
    @PostMapping("/api/findings/waiver")
    public void waiver(@RequestParam("repo") String repo,
                       @RequestParam("ecosystem") String ecosystem,
                       @RequestParam("coordinate") String coordinate,
                       @RequestParam("version") String version,
                       @RequestParam("source") String source,
                       @RequestParam("id") String id,
                       @RequestParam("expires") String expires,
                       @RequestParam(value = "note", required = false) String note,
                       @RequestHeader(value = Repositories.KEY, required = false) String key,
                       HttpServletResponse response) throws IOException {
        if (!Repositories.valid(repo)) {
            response.setStatus(400);
            return;
        }
        String tenant = repositories.tenant(key);
        if (!Repositories.valid(tenant)) {
            response.setStatus(400);
            return;
        }
        if (findings.isEmpty()) {
            response.setStatus(501);
            return;
        }
        Instant now = Instant.now();
        Instant expiry;
        try {
            expiry = Instant.parse(expires);
        } catch (RuntimeException _) {
            response.setStatus(400);
            return;
        }
        Findings ledger = findings.get().over(repositories.store(tenant, repo));
        try {
            RepositoryRequests.rejectTraversal(ecosystem);
            RepositoryRequests.rejectTraversal(coordinate);
            RepositoryRequests.rejectTraversal(version);
            WaiverLabels.apply(ledger, ecosystem, coordinate, version, source, id, expiry, note, now);
        } catch (IllegalArgumentException _) {
            response.setStatus(400);
            return;
        }
        audit.record(tenant, key == null ? "anonymous" : Authorization.hash(key), "findings.waiver.accept",
                repo + " " + coordinate + ":" + version + " " + source + ":" + id + " until " + expiry);
        response.setStatus(200);
    }

    /**
     * Withdraw an operator's accept-risk waiver: relabel it to revoked through {@link WaiverLabels#revoke} so it stops
     * being honoured at once, leaving the still-present finding to record that the risk was once accepted. A missing
     * finding is a {@code 400}, exactly as the apply is.
     */
    @PostMapping("/api/findings/waiver/revoke")
    public void revokeWaiver(@RequestParam("repo") String repo,
                             @RequestParam("ecosystem") String ecosystem,
                             @RequestParam("coordinate") String coordinate,
                             @RequestParam("version") String version,
                             @RequestParam("source") String source,
                             @RequestParam("id") String id,
                             @RequestHeader(value = Repositories.KEY, required = false) String key,
                             HttpServletResponse response) throws IOException {
        if (!Repositories.valid(repo)) {
            response.setStatus(400);
            return;
        }
        String tenant = repositories.tenant(key);
        if (!Repositories.valid(tenant)) {
            response.setStatus(400);
            return;
        }
        if (findings.isEmpty()) {
            response.setStatus(501);
            return;
        }
        Findings ledger = findings.get().over(repositories.store(tenant, repo));
        try {
            RepositoryRequests.rejectTraversal(ecosystem);
            RepositoryRequests.rejectTraversal(coordinate);
            RepositoryRequests.rejectTraversal(version);
            WaiverLabels.revoke(ledger, ecosystem, coordinate, version, source, id, Instant.now());
        } catch (IllegalArgumentException _) {
            response.setStatus(400);
            return;
        }
        audit.record(tenant, key == null ? "anonymous" : Authorization.hash(key), "findings.waiver.revoke",
                repo + " " + coordinate + ":" + version + " " + source + ":" + id);
        response.setStatus(200);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** The ledger's answer; {@code available} distinguishes an installed-but-empty ledger from the {@code 501} an
     *  uninstalled module answers. {@code lastScanned} is the instant the advisory sweep or an explicit rescan last
     *  refreshed this repository's findings against the feeds (Principle 10's staleness signal), {@code null} when the
     *  repository was never scanned - rendered as such, never as "clean". */
    public record FindingsView(boolean available, List<FindingView> findings, boolean more, Instant lastScanned) {
    }

    /** One located finding, every field the ledger keeps - kind in its wire spelling ({@code ai-candidate}). */
    public record FindingView(String ecosystem, String coordinate, String version, String id, String source,
                              String kind, String category, String severity, double confidence, String description,
                              List<String> references, String provenance, Map<String, String> attributes,
                              String firstSeen, String lastSeen, String supersededBy, List<LabelView> labels) {

        private static FindingView of(Findings.Located located) {
            Finding finding = located.finding();
            List<LabelView> labels = new ArrayList<>();
            for (Finding.Label label : finding.labels()) {
                labels.add(new LabelView(label.source(), label.name(), label.value(), label.confidence(),
                        label.when().toString()));
            }
            return new FindingView(located.ecosystem(), located.coordinate(), located.version(), finding.id(),
                    finding.source(), finding.kind().wire(), finding.category(), finding.severity().name(),
                    finding.confidence(), finding.description(), finding.references(), finding.provenance(),
                    finding.attributes(), finding.firstSeen().toString(), finding.lastSeen().toString(),
                    finding.supersededBy(), labels);
        }
    }

    public record LabelView(String source, String name, String value, double confidence, String when) {
    }

    /** A scanner's report about one version: the scanner's name, the version as the repository records it, and
     *  what the scanner found. */
    public record ReportRequest(String source, String ecosystem, String coordinate, String version,
                                List<Reported> findings) {

        /** The report as the gate reads it; a missing field, an unknown severity, a source that is not a plain
         *  name, a path-like coordinate or more than {@link #MAX_REPORTED} findings is an
         *  {@link IllegalArgumentException}. */
        ReportedFindings.Report report() {
            if (source == null || !SOURCE.matcher(source).matches()) {
                throw new IllegalArgumentException("source must be a plain lower-case name");
            }
            for (String part : new String[]{ecosystem, coordinate, version}) {
                if (part == null || part.isBlank()) {
                    throw new IllegalArgumentException("ecosystem, coordinate and version are required");
                }
                RepositoryRequests.rejectTraversal(part);
            }
            List<Reported> reported = findings == null ? List.of() : findings;
            if (reported.size() > MAX_REPORTED) {
                throw new IllegalArgumentException("at most " + MAX_REPORTED + " findings per report");
            }
            List<AdvisorySource.Advisory> advisories = new ArrayList<>(reported.size());
            for (Reported finding : reported) {
                advisories.add(finding.advisory());
            }
            return new ReportedFindings.Report(source, ecosystem, coordinate, version, advisories);
        }
    }

    /** One reported finding: its identifier (an advisory or CVE id), severity, any CVE aliases, the version that
     *  fixes it, and a description; {@code malicious} marks a malicious-package report rather than a flaw. */
    public record Reported(String id, String severity, List<String> cves, String fixed, String description,
                           boolean malicious) {

        AdvisorySource.Advisory advisory() {
            if (id == null || id.isBlank() || severity == null) {
                throw new IllegalArgumentException("a finding needs an id and a severity");
            }
            String text = description == null ? "" : description;
            return new AdvisorySource.Advisory(id, Severity.valueOf(severity.trim().toUpperCase(Locale.ROOT)),
                    malicious, fixed, cves == null ? List.of() : List.copyOf(cves),
                    text.length() > AdvisorySource.Advisory.DESCRIPTION_LIMIT
                            ? text.substring(0, AdvisorySource.Advisory.DESCRIPTION_LIMIT) : text);
        }
    }

    /** What a report did: findings recorded, the verdict they reached, whether the version is withheld now, and
     *  the gate's reasons. */
    public record ReportAnswer(int recorded, String verdict, boolean held, List<String> reasons) {

        static ReportAnswer of(ReportedFindings.Outcome outcome) {
            return new ReportAnswer(outcome.recorded(), outcome.verdict().name(), outcome.held(), outcome.reasons());
        }
    }
}
