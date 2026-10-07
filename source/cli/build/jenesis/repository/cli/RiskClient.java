package build.jenesis.repository.cli;

import module java.base;
import module java.net.http;
import module tools.jackson.databind;

/**
 * What the feeds, the ledgers and the policies say about what a repository holds: the vulnerability report, the
 * findings ledger and its verdicts, the declared licences and the enforcement preview, maintainer health and the
 * proxy-hardening verdict.
 *
 * <p>Reached through {@link RepositoryClient#risk()}.
 */
public final class RiskClient extends ClientCalls {

    RiskClient(ClientCalls calls) {
        super(calls);
    }

    /** The license inventory of a repository as its last count left it: the per-category and per-SPDX-id counts of
     *  its versions, or the state that stands in for them - not counted yet, running, or failed. */
    public LicensesView licenses(String repo) throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/licenses?repo=" + enc(repo), null, null);
        require(response, 200, "read the license inventory of " + repo);
        return JSON.readValue(response.body(), LicensesView.class);
    }

    /** Start a count of a repository's licenses in the background, and answer the inventory as it then stands with
     *  whether this call started the count or found one already running. */
    public LicenseCountStart countLicenses(String repo) throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/licenses?repo=" + enc(repo) + "&refresh=true", null, null);
        require(response, 200, "start a license count of " + repo);
        return new LicenseCountStart(!"running".equals(response.headers().firstValue("Jenesis-Refresh").orElse("")),
                JSON.readValue(response.body(), LicensesView.class));
    }

    /** The maintainer health stored for a repository's coordinates, one page. Before the first ranking is built the
     *  server answers {@code 503} with {@code ranked} false and no entries, which is a state of the report rather than
     *  a failure to read it. */
    public HealthReport health(String repo) throws IOException, InterruptedException {
        return health(repo, null);
    }

    /** One page of the {@link #health(String) health report}, resumed after {@code cursor}. */
    public HealthReport health(String repo, String cursor) throws IOException, InterruptedException {
        return healthReport(send("GET", "/api/health?repo=" + enc(repo)
                + (cursor == null ? "" : "&after=" + enc(cursor)), null, null), repo);
    }

    /** Start a re-score of every coordinate off the request path: whether this request started it or found one
     *  running, and the ledger as it stands. */
    public HealthRefreshStart refreshHealth(String repo) throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/health?repo=" + enc(repo) + "&refresh=true", null, null);
        return new HealthRefreshStart(!"running".equals(response.headers().firstValue("Jenesis-Refresh").orElse("")),
                healthReport(response, repo));
    }

    private static HealthReport healthReport(HttpResponse<String> response, String repo) throws IOException {
        if (response.statusCode() != 503) {
            require(response, 200, "read the health of " + repo);
        }
        return JSON.readValue(response.body(), HealthReport.class);
    }

    /** The answer {@code GET /api/health} gives: the scored coordinates, when the scores were last refreshed, and
     *  whether a refresh is running now. */
    public record HealthReport(boolean available, boolean ranked, List<HealthEntry> entries, String next,
                               int total, String lastScanned, boolean refreshing) {
    }

    /** What starting a health refresh answered: whether this request started it, and the report as it stood. */
    public record HealthRefreshStart(boolean started, HealthReport report) {
    }

    /** One coordinate's maintainer health: the overall score and its three components ({@code -1} when unknown). */
    public record HealthEntry(String ecosystem, String coordinate, String sourceRepository, double overall,
                              double maintenance, double review, double provenance, String scannedAt) {
    }

    /** Re-scan a repository's published coordinates against the advisory feed, reporting each one the feed knows a
     *  vulnerability for with the advisory id, its severity, whether it is a malicious package, and the fixed
     *  version. {@code scanned} is false when no feed is configured (so an empty report is not a clean bill). */
    public VulnerabilityReport vulnerabilities(String repo) throws IOException, InterruptedException {
        return vulnerabilities(repo, null);
    }

    /** The {@link #vulnerabilities(String) vulnerability report} narrowed to one call-graph reachability verdict
     *  ({@code reachable} / {@code not-reachable} / {@code unknown}; blank or {@code null} shows everything) - a
     *  view facet the server applies, never a change to what is stored. An un-analyzed advisory matches the
     *  {@code unknown} facet, so "everything not proven unreachable" hides nothing the engine has not reached. */
    public VulnerabilityReport vulnerabilities(String repo, String reachability)
            throws IOException, InterruptedException {
        return vulnerabilities(repo, reachability, null);
    }

    /** The {@link #vulnerabilities(String, String) vulnerability report} additionally narrowed by the AI
     *  applicability opinion ({@code applies} / {@code not-applicable} / {@code unknown}; blank or {@code null}
     *  shows everything) - the same server-side view facet rule: a never-judged advisory matches {@code unknown},
     *  and nothing stored changes. */
    public VulnerabilityReport vulnerabilities(String repo, String reachability, String applicability)
            throws IOException, InterruptedException {
        return pagedVulnerabilities(repo, reachability, applicability);
    }

    /** Start the explicit re-scan (the write path): the enabled feeds are asked again for every cached copy and the
     *  findings persisted, off the request path, so the answer says whether this request started it or found one
     *  running, with the first page of the report as it stood. The read-only {@link #vulnerabilities(String)} then
     *  says {@code scanning} until the re-scan lands. */
    public RescanStart rescanVulnerabilities(String repo) throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/vulnerabilities?repo=" + enc(repo) + "&refresh=true",
                null, null);
        require(response, 200, "rescan " + repo);
        return new RescanStart(!"running".equals(response.headers().firstValue("Jenesis-Refresh").orElse("")),
                JSON.readValue(response.body(), VulnerabilityReport.class));
    }

    /** What starting a re-scan answered: whether this request started it, and the first page of the report. */
    public record RescanStart(boolean started, VulnerabilityReport report) {
    }

    /** Accumulate every worst-first page the server serves into one report: the server bounds each response to a page
     *  (so it never buffers the whole vulnerable set in heap) and hands back a {@code next}; the client follows it
     *  to the last page, so a caller still receives the whole ranked report. The reachability/applicability facets
     *  ride every page so each is narrowed identically, and what describes the scan rather than a page of rows - the
     *  feed warnings, the count, whether it is partial, when it was taken and whether a re-scan is running - is the
     *  first page's. */
    private VulnerabilityReport pagedVulnerabilities(String repo, String reachability, String applicability)
            throws IOException, InterruptedException {
        List<VulnerableArtifact> vulnerable = new ArrayList<>();
        boolean scanned = false;
        List<Signal> signals = null;
        List<String> feedWarnings = List.of();
        int total = 0;
        boolean partial = false;
        String lastScanned = null;
        boolean scanning = false;
        String cursor = null;
        boolean first = true;
        do {
            StringBuilder path = new StringBuilder("/api/vulnerabilities?repo=").append(enc(repo));
            appendFilter(path, "reachability", reachability);
            appendFilter(path, "applicability", applicability);
            if (cursor != null) {
                path.append("&after=").append(enc(cursor));
            }
            HttpResponse<String> response = send("GET", path.toString(), null, null);
            require(response, 200, "scan " + repo);
            VulnerabilityReport page = JSON.readValue(response.body(), VulnerabilityReport.class);
            if (first) {
                scanned = page.scanned();
                signals = page.signals();
                // Only the first page's: it is a property of the scan behind the report, not of a page of rows.
                feedWarnings = page.feedWarnings() == null ? List.of() : page.feedWarnings();
                total = page.total();
                partial = page.partial();
                lastScanned = page.lastScanned();
                scanning = page.scanning();
                first = false;
            }
            if (page.vulnerable() != null) {
                vulnerable.addAll(page.vulnerable());
            }
            cursor = page.next();
        } while (cursor != null && !cursor.isBlank());
        return new VulnerabilityReport(scanned, signals, vulnerable, null, feedWarnings, total, partial, lastScanned,
                scanning);
    }

    /** The persisted findings ledger of a repository, filterable by coordinate (bare or {@code coordinate:version}),
     *  kind, source, category and severity - superseded findings included with their mark. */
    public FindingsReport findings(String repo, String coordinate, String kind, String source, String category,
                                   String severity) throws IOException, InterruptedException {
        return findings(repo, null, coordinate, kind, source, category, severity, null);
    }

    /** One page of {@link #findings(String, String, String, String, String, String) the findings ledger}, narrowed
     *  by {@code ecosystem} too and resumed {@code after} a previous page's {@code next}, which the answer carries
     *  while another page follows. */
    public FindingsReport findings(String repo, String ecosystem, String coordinate, String kind, String source,
                                   String category, String severity, String after)
            throws IOException, InterruptedException {
        StringBuilder path = new StringBuilder("/api/findings?repo=").append(enc(repo));
        appendFilter(path, "ecosystem", ecosystem);
        appendFilter(path, "after", after);
        appendFilter(path, "coordinate", coordinate);
        appendFilter(path, "kind", kind);
        appendFilter(path, "source", source);
        appendFilter(path, "category", category);
        appendFilter(path, "severity", severity);
        HttpResponse<String> response = send("GET", path.toString(), null, null);
        require(response, 200, "query findings in " + repo);
        return JSON.readValue(response.body(), FindingsReport.class);
    }

    private static void appendFilter(StringBuilder path, String name, String value) {
        if (value != null && !value.isBlank()) {
            path.append('&').append(name).append('=').append(enc(value));
        }
    }

    /** The retroactive-license-enforcement dry-run plan for a repository - what enabling enforcement would newly hold
     *  under the current policy. With {@code unknown} it additionally previews holding the coordinates whose license
     *  could not be identified. */
    public RetroPlan retroPlan(String repo, boolean unknown) throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET",
                "/api/licenses/retro/plan?repo=" + enc(repo) + "&unknown=" + unknown, null, null);
        require(response, 200, "plan retroactive license enforcement for " + repo);
        return JSON.readValue(response.body(), RetroPlan.class);
    }

    /** Start the plan off the request - it assesses every release - answering whether this request started it or
     *  found one running, and the plan as it stood. */
    public RetroStart computeRetroPlan(String repo, boolean unknown) throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET",
                "/api/licenses/retro/plan?repo=" + enc(repo) + "&unknown=" + unknown + "&refresh=true", null, null);
        require(response, 200, "plan retroactive license enforcement for " + repo);
        return new RetroStart(!"running".equals(response.headers().firstValue("Jenesis-Refresh").orElse("")),
                JSON.readValue(response.body(), RetroPlan.class));
    }

    /** What starting the plan answered: whether this request started it, and the plan as it stood. */
    public record RetroStart(boolean started, RetroPlan plan) {
    }

    /** What proxy hardening would do with the bytes at a path. */
    public String hardeningVerdict(String repo, String path) throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/hardening/verdict?repo=" + enc(repo)
                + (path == null ? "" : "&path=" + enc(path)), null, null);
        require(response, 200, "read the hardening verdict for " + repo);
        return response.body();
    }

    /**
     * One finding, named the way the ledger keys it: the version it was found in, the scanner that reported it and
     * its id within that scanner. The findings listing prints the first three on each version's heading and the last
     * two on each row, so what a person reads is what they type back.
     */
    public record FindingKey(String ecosystem, String coordinate, String version, String source, String id) {
    }

    /** Record a person's decision on an AI-produced finding: {@code confirmed} or {@code dismissed}, with an
     *  optional note. The endpoint binds every field from the query, and reads no body. */
    public void reviewFinding(String repo, FindingKey finding, String decision, String note)
            throws IOException, InterruptedException {
        String query = identify(repo, finding) + "&decision=" + enc(decision)
                + (note == null ? "" : "&note=" + enc(note));
        require(send("POST", "/api/findings/review?" + query, null, null), 200, "review finding " + finding.id());
    }

    /** Accept the risk of an advisory-derived finding until {@code expires}, an ISO-8601 instant in the future,
     *  with an optional note justifying it. The endpoint binds every field from the query, and reads no body. */
    public void waiveFinding(String repo, FindingKey finding, String expires, String note)
            throws IOException, InterruptedException {
        String query = identify(repo, finding) + "&expires=" + enc(expires)
                + (note == null ? "" : "&note=" + enc(note));
        require(send("POST", "/api/findings/waiver?" + query, null, null), 200, "waive finding " + finding.id());
    }

    /** Post a scanner's report about one stored version, the request document read from {@code file} as it is. */
    public ReportAnswer reportFindings(String repo, Path file) throws IOException, InterruptedException {
        HttpResponse<String> response = send("POST", "/api/findings/report?repo=" + enc(repo),
                HttpRequest.BodyPublishers.ofFile(file), "application/json");
        require(response, 200, "report findings from " + file.getFileName() + " into " + repo);
        return JSON.readValue(response.body(), ReportAnswer.class);
    }

    /** What a report did: findings recorded, the gate's verdict, whether the version is withheld, and why. */
    public record ReportAnswer(int recorded, String verdict, boolean held, List<String> reasons) {
    }

    /** Withdraw a finding's waiver; the endpoint binds the finding from the query, and reads no body. */
    public void revokeWaiver(String repo, FindingKey finding) throws IOException, InterruptedException {
        require(send("POST", "/api/findings/waiver/revoke?" + identify(repo, finding), null, null), 200,
                "revoke the waiver on finding " + finding.id());
    }

    /** The query that names one finding in one repository. */
    private static String identify(String repo, FindingKey finding) {
        return "repo=" + enc(repo) + "&ecosystem=" + enc(finding.ecosystem())
                + "&coordinate=" + enc(finding.coordinate()) + "&version=" + enc(finding.version())
                + "&source=" + enc(finding.source()) + "&id=" + enc(finding.id());
    }

    /** {@code signals} lists the report columns the server's installed signal modules contribute; {@code null} when
     *  an older server answers without them. {@code next} is the server's paging cursor - the client follows it
     *  to accumulate every worst-first page, so the report a caller receives is the whole set even though the server
     *  serves it a bounded page at a time; it is {@code null} on a fully-accumulated report. {@code total} counts the
     *  ranked lines, {@code partial} says they were ranked from a bounded window because the index is not built yet,
     *  {@code lastScanned} is when the scan behind them was taken ({@code null} before the first), and
     *  {@code scanning} says a re-scan is running, so the report is the one it will replace. */
    public record VulnerabilityReport(boolean scanned, List<Signal> signals, List<VulnerableArtifact> vulnerable,
                                      String next, List<String> feedWarnings, int total, boolean partial,
                                      String lastScanned, boolean scanning) {
    }

    /** One vulnerable coordinate, how its dependents are said ({@code Used by 3}, empty where none is known), and
     *  its advisories. */
    public record VulnerableArtifact(String coordinate, String usedByText, List<Advisory> advisories) {
    }

    /** {@code signals} carries one evaluated cell per report column - the known-exploited flag and the EPSS
     *  probability among them, read back through {@link #knownExploited()} and {@link #epss()}. {@code reachability}
     *  is the call-graph verdict the server's reachability sweep labelled onto the stored finding ({@code reachable} /
     *  {@code not-reachable} / {@code unknown}); empty when the coordinate was never analyzed, {@code null} from an
     *  older server without the engine. {@code applicability} is the AI applicability pass's opinion on whether the
     *  advisory applies in this artifact's context ({@code applies} / {@code not-applicable} / {@code unknown});
     *  empty when never judged, {@code null} from an older server. */
    public record Advisory(String id, String severity, boolean malicious,
                           String fixed, String reachability, String applicability, List<Cell> signals) {

        /** Whether the CISA known-exploited (KEV) catalogue flags this advisory, read from the {@code known-exploited}
         *  signal cell (its rank is positive when the catalogue lists a CVE alias); false when the signal is absent. */
        public boolean knownExploited() {
            return signalRank("known-exploited") > 0;
        }

        /** This advisory's EPSS exploitation probability, read from the {@code epss} signal cell's rank; 0 when the
         *  signal is absent. */
        public double epss() {
            return signalRank("epss");
        }

        private double signalRank(String name) {
            if (signals != null) {
                for (Cell cell : signals) {
                    if (cell.name().equals(name)) {
                        return cell.rank();
                    }
                }
            }
            return 0.0;
        }
    }

    public record Signal(String name, String label) {
    }

    public record Cell(String name, String label, String value, double rank) {
    }

    /** The findings ledger's answer: one page of the persisted findings matching the query, each fully attributed,
     *  and whether more match. */
    public record FindingsReport(boolean available, List<FindingRow> findings, String next) {
    }

    /** One persisted finding: its coordinate, identity, attribution, categorization, the persisted description and
     *  references, kind-specific attributes (e.g. {@code fixed}), sighting instants, the supersession mark
     *  ({@code null} while it stands) and any attached labels. */
    public record FindingRow(String ecosystem, String coordinate, String version, String id, String source,
                             String kind, String category, String severity, double confidence, String description,
                             List<String> references, String provenance, Map<String, String> attributes,
                             String firstSeen, String lastSeen, String supersededBy, List<FindingLabel> labels) {
    }

    public record FindingLabel(String source, String name, String value, double confidence, String when) {
    }

    /** The license inventory: its {@code state} ({@code not-counted}, {@code running}, {@code done} or
     *  {@code failed}), when the count started and finished, why it failed, how many versions it counted, the counts
     *  per category and per SPDX id, how many rows the count produced, whether the rows shown stop short, and the
     *  {@code previous} finished count while a new one runs or after one failed. */
    public record LicensesView(String state, String startedAt, String finishedAt, String failure, long versions,
                               List<LicenseCount> categories, List<LicenseCount> licenses, int rows,
                               boolean truncated, LicensesView previous) {
    }

    /** One count: a category or an SPDX id, and how many versions carry it. */
    public record LicenseCount(String value, long versions) {
    }

    /** What asking for a count answered: whether it started one, and the inventory as it then stood. */
    public record LicenseCountStart(boolean started, LicensesView inventory) {
    }

    /** The retroactive-license dry-run plan for one repository as its last run left it: the mode previewed, the run's
     *  state ({@code not-computed}, {@code running}, {@code done} or {@code failed}), how many releases enabling
     *  enforcement would newly hold and the first of them with their reasons, when it was computed, and why a run
     *  failed. */
    public record RetroPlan(String mode, String state, int count, List<RetroHeld> held, String computedAt,
                            String failure) {
    }

    /** One release the enforcement sweep would hold, with the human-readable reasons. */
    public record RetroHeld(String ecosystem, String coordinate, String version, List<String> reasons) {
    }
}
