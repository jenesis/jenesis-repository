package build.jenesis.repository.compliance.web;

import module java.base;
import build.jenesis.repository.server.RepositoryRouting;
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
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The findings ledger's HTTP surface.
 *
 * <p>{@code GET /api/findings?repo=} lists the persisted findings of a repository, filterable by coordinate (bare or
 * {@code coordinate:version}), kind, source, category and severity, superseded rows included with their mark and
 * every row with its labels. An empty ledger means nothing was recorded yet, not clean; {@code /api/vulnerabilities}
 * back-fills it. Gated {@code manage:read}; an unsafe name or unknown spelling is a {@code 400}, and without the
 * findings module the answer is {@code 501}.
 *
 * <p>{@code POST /api/findings/review} confirms or dismisses an AI-produced finding through {@link ReviewLabels}, and
 * {@code POST /api/findings/waiver} and {@code /waiver/revoke} record or withdraw a time-boxed accept-risk waiver
 * through {@link WaiverLabels}; each is a label on the still-present row, gated {@code manage:write} and audited.
 *
 * <p>{@code POST /api/findings/report} takes an outside scanner's findings about a version the repository serves,
 * through {@link ReportedFindings}: recorded under the scanner's name and decided by the deployment's gate, so a verdict
 * other than allow withholds the version for review. It needs a key that may write to the repository, since a report
 * can withdraw an artifact; an unserved version is a {@code 404}, a malformed report a {@code 400}.
 */
@RestController
public class FindingsController {

    private final Repositories repositories;
    private final RepositoryRouting routing;
    private final AuditTrail audit;
    private final Optional<FindingsProvider> findings;
    private final Function<String, ComplianceGate> gates;

    public FindingsController(Repositories repositories, RepositoryRouting routing, AuditTrail audit,
                              Function<String, ComplianceGate> gates) {
        this(repositories, routing, audit, FindingsProvider.installed(), gates);
    }

    /** With an explicit findings provider; {@code gates} answers the gate a tenant's reports are decided by. */
    public FindingsController(Repositories repositories, RepositoryRouting routing, AuditTrail audit,
                              Optional<FindingsProvider> findings, Function<String, ComplianceGate> gates) {
        this.repositories = repositories;
        this.routing = routing;
        this.audit = audit;
        this.findings = findings;
        this.gates = gates;
    }

    /** The most findings one report may carry, bounding one request's ledger write. */
    static final int MAX_REPORTED = 10_000;

    /** A scanner's name as a report gives it and the ledger records it: short, lower-case, and safe as a label. */
    private static final Pattern SOURCE = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    /** Records an outside scanner's report and decides it through the tenant's gate. */
    @PostMapping("/api/findings/report")
    @ResponseBody
    public ReportAnswer report(@RequestParam("repo") String repo,
                               @RequestHeader(value = Repositories.KEY, required = false) String key,
                               @RequestBody ReportRequest request,
                               HttpServletRequest http, HttpServletResponse response) throws IOException {
        if (!Repositories.valid(repo)) {
            response.setStatus(400);
            return null;
        }
        String tenant = routing.tenant(http);
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
                                 @RequestParam(value = "after", required = false) String after,
                                 @RequestParam(value = "limit", defaultValue = "500") int limit,
                                 HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!Repositories.valid(repo)) {
            response.setStatus(400);
            return null;
        }
        String tenant = routing.tenant(request);
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
        // The ledger pages by row, so the opaque cursor a page hands out is the row the next one starts at.
        int offset;
        try {
            offset = after == null || after.isBlank() ? 0 : Integer.parseInt(after);
        } catch (NumberFormatException _) {
            offset = -1;
        }
        if (offset < 0) {
            response.setStatus(400);
            return null;
        }
        ArtifactStore store = repositories.store(tenant, repo);
        Findings ledger = findings.get().over(store);
        // A bounded slice; `next` says whether another page remains, and where it starts.
        Findings.Page page = ledger.all(new Findings.Filter(
                blankToNull(coordinate), kindFilter, blankToNull(source), blankToNull(category), severityFilter,
                blankToNull(ecosystem)), offset, Math.clamp(limit, 1, 1000));
        List<FindingView> views = new ArrayList<>();
        for (Findings.Located located : page.located()) {
            views.add(FindingView.of(located));
        }
        // An index-served page is as of the index's build, a live-walk page as of the live scan stamp; null is never
        // scanned.
        Instant lastScanned = page.builtScanStamp() != null
                ? (page.builtScanStamp().isBlank() ? null : Instant.parse(page.builtScanStamp()))
                : Findings.scanned(store).read().orElse(null);
        return new FindingsView(true, views,
                page.more() ? Integer.toString(offset + page.located().size()) : null, lastScanned);
    }

    /**
     * Records a review decision ({@code confirmed} or {@code dismissed}, with an optional note) through
     * {@link ReviewLabels#apply}; a refusal is a {@code 400}.
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
                       HttpServletRequest http, HttpServletResponse response) throws IOException {
        if (!Repositories.valid(repo)) {
            response.setStatus(400);
            return;
        }
        String tenant = routing.tenant(http);
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
            // These parameters reach the ledger key raw, and the chain normalises only the URL path.
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
     * Records an accept-risk waiver until {@code expires}, with an optional note, through {@link WaiverLabels#apply}; a
     * refusal is a {@code 400}.
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
                       HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!Repositories.valid(repo)) {
            response.setStatus(400);
            return;
        }
        String tenant = routing.tenant(request);
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
     * Withdraws an accept-risk waiver through {@link WaiverLabels#revoke}; a missing finding is a {@code 400}.
     */
    @PostMapping("/api/findings/waiver/revoke")
    public void revokeWaiver(@RequestParam("repo") String repo,
                             @RequestParam("ecosystem") String ecosystem,
                             @RequestParam("coordinate") String coordinate,
                             @RequestParam("version") String version,
                             @RequestParam("source") String source,
                             @RequestParam("id") String id,
                             @RequestHeader(value = Repositories.KEY, required = false) String key,
                             HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!Repositories.valid(repo)) {
            response.setStatus(400);
            return;
        }
        String tenant = routing.tenant(request);
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

    /** The ledger's answer, with the cursor of the next page as {@code next} - passed back as {@code after},
     *  {@code null} on the last page - and the last feed refresh as {@code lastScanned}, {@code null} for never
     *  scanned. */
    public record FindingsView(boolean available, List<FindingView> findings, String next, Instant lastScanned) {
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
