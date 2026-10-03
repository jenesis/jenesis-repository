package build.jenesis.repository.compliance.web;

import module java.base;

import build.jenesis.repository.cleanup.StoredReport;
import build.jenesis.repository.compliance.scan.VulnerabilityRankIndex;
import build.jenesis.repository.gate.QuarantineLog;
import build.jenesis.repository.scope.Scopes;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.ui.CurrentTenant;
import build.jenesis.repository.ui.DashboardContributor;
import build.jenesis.repository.ui.DashboardPanel;
import build.jenesis.repository.ui.store.TenantScope;
import io.micrometer.observation.ObservationRegistry;

/**
 * The screening feature's dashboard panels, the two that ask for a decision: how many versions the tenant's
 * repositories hold for review, and - once a scan has ranked any - how many have a known vulnerability, each with the
 * repositories holding most and opening the screen where they are dealt with.
 *
 * <p>Both are figures of every repository, one review-queue page and one rank-index read each, so they are counted by a
 * {@link StoredReport} off the request path and read back by one point read. A landing that finds the count missing,
 * or older than {@link #FRESH}, starts a new one and shows the last, saying it is being recounted.
 */
public class ReviewDashboard extends TenantScope implements DashboardContributor {

    /** The stored report the counts are kept in, in the tenant's {@link Scopes#SYSTEM} space. */
    static final String REPORT = "dashboard-review";

    /** How old a count may be before the landing asks for a new one. */
    static final Duration FRESH = Duration.ofMinutes(5);

    /** How many held versions a repository is counted up to; past it the count reads as "at least". */
    static final int CAP = 10_000;

    private static final DashboardPanel.Noun VERSIONS = new DashboardPanel.Noun("version", "versions");
    private static final DashboardPanel.Noun WAITING =
            new DashboardPanel.Noun("version waits for a decision", "versions wait for a decision");
    private static final DashboardPanel.Noun VULNERABLE =
            new DashboardPanel.Noun("version has a known vulnerability", "versions have known vulnerabilities");

    /** The review-queue page a count reads at a time. */
    private static final int PAGE = 1_000;

    public ReviewDashboard(ArtifactStore root, CurrentTenant current, ObservationRegistry observations) {
        super(root, current, observations);
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public List<DashboardPanel> panels(Viewer viewer) throws IOException {
        ArtifactStore space = root.scope(tenant()).scope(Scopes.SYSTEM);
        Optional<StoredReport.Report> stored = StoredReport.read(space, REPORT);
        Optional<StoredReport.Report> finished = stored.flatMap(StoredReport.Report::lastFinished);
        boolean counting = stored.map(StoredReport.Report::running).orElse(false);
        boolean stale = finished.map(done -> done.finishedAt().isBefore(Instant.now().minus(FRESH))).orElse(true);
        if (!counting && stale) {
            counting = StoredReport.compute(space, REPORT, forThisTenant(this::count));
        }
        String failed = stored.filter(report -> report.status() == StoredReport.Status.FAILED)
                .map(report -> "The last count failed: " + report.failure()).orElse("");
        if (finished.isEmpty()) {
            return List.of(new DashboardPanel("Held for review", "/ui/repositories", "", "",
                    DashboardPanel.Tone.NEUTRAL, List.of(), Optional.empty(), counting ? "Counting…" : failed,
                    Optional.empty(), counting));
        }
        List<Row> all = finished.get().rows().stream().map(Row::parse).toList();
        // The first row is the tenant's totals, the rest its repositories, most held first.
        Row totals = all.getFirst();
        List<Row> rows = all.subList(1, all.size());
        Optional<Instant> asOf = Optional.of(finished.get().finishedAt());
        String note = String.join(" ", Stream.of(counting ? "Recounting…" : "", failed)
                .filter(said -> !said.isEmpty()).toList());
        int held = totals.held();
        boolean heldCapped = totals.capped();
        List<DashboardPanel.Line> holding = rows.stream().filter(row -> row.held() > 0)
                .sorted(Comparator.comparingInt(Row::held).reversed().thenComparing(Row::repository))
                .map(row -> new DashboardPanel.Line(row.repository(), VERSIONS.counted(row.held(), row.capped()),
                        "/ui/repositories/" + row.repository() + "/quarantine"))
                .toList();
        int vulnerable = totals.vulnerable();
        List<DashboardPanel.Line> ranked = rows.stream().filter(row -> row.vulnerable() > 0)
                .sorted(Comparator.comparingInt(Row::vulnerable).reversed().thenComparing(Row::repository))
                .map(row -> new DashboardPanel.Line(row.repository(), VERSIONS.counted(row.vulnerable(), false),
                        "/ui/repositories/" + row.repository() + "/vulnerabilities"))
                .toList();
        List<DashboardPanel> panels = new ArrayList<>();
        // A panel opens where its first line leads - the repository holding most - since that is where the work is.
        panels.add(new DashboardPanel("Held for review", first(holding),
                held == 0 ? "" : DashboardPanel.Noun.figure(held, heldCapped),
                held == 0 ? "Nothing waits for a decision" : WAITING.of(held, heldCapped),
                held > 0 ? DashboardPanel.Tone.ATTENTION : DashboardPanel.Tone.CLEAR, holding, Optional.empty(), note,
                asOf, counting));
        if (totals.scanned()) {
            panels.add(new DashboardPanel("Vulnerabilities", first(ranked),
                    vulnerable == 0 ? "" : DashboardPanel.Noun.figure(vulnerable, false),
                    vulnerable == 0 ? "No version has a known vulnerability" : VULNERABLE.of(vulnerable, false),
                    vulnerable > 0 ? DashboardPanel.Tone.ATTENTION : DashboardPanel.Tone.CLEAR, ranked,
                    Optional.empty(), note, asOf, counting));
        }
        return panels;
    }

    /** One repository's counts: the versions it holds for review (up to {@link #CAP}), the versions its last scan
     *  ranked vulnerable, and whether a scan has ranked it at all. */
    private record Row(String repository, int held, boolean capped, int vulnerable, boolean scanned) {

        String format() {
            return repository + "\t" + held + "\t" + capped + "\t" + vulnerable + "\t" + scanned;
        }

        static Row parse(String row) {
            String[] fields = row.split("\t", -1);
            return new Row(fields[0], Integer.parseInt(fields[1]), Boolean.parseBoolean(fields[2]),
                    Integer.parseInt(fields[3]), Boolean.parseBoolean(fields[4]));
        }
    }

    /** Count every repository: a version is reviewed whole, so its held files count once. The rows are the totals,
     *  then the repositories with anything to show, most held first. */
    private StoredReport.Rows count() throws IOException {
        List<Row> counted = new ArrayList<>();
        int repositories = 0;
        for (String name : root.scope(tenant()).list("")) {
            if (!validRepository(name)) {
                continue;
            }
            repositories++;
            ArtifactStore repository = scope(name);
            QuarantineLog log = new QuarantineLog(repository);
            Set<String> versions = new HashSet<>();
            String after = null;
            do {
                QuarantineLog.QueuePage page = log.reviewQueue(after, PAGE);
                for (QuarantineLog.Held one : page.holds()) {
                    versions.add(one.event().map(QuarantineLog.Event::coordinate).orElse(one.path()));
                }
                after = page.next();
            } while (after != null && versions.size() < CAP);
            VulnerabilityRankIndex.Page ranked = new VulnerabilityRankIndex(repository).read(null, 1);
            Row row = new Row(name, Math.min(versions.size(), CAP), after != null,
                    ranked == null ? 0 : ranked.total(), ranked != null);
            if (row.held() > 0 || row.vulnerable() > 0 || row.scanned()) {
                counted.add(row);
            }
        }
        counted.sort(Comparator.comparingInt(Row::held).reversed()
                .thenComparing(Comparator.comparingInt(Row::vulnerable).reversed()).thenComparing(Row::repository));
        List<String> rows = new ArrayList<>();
        rows.add(new Row("", counted.stream().mapToInt(Row::held).sum(), counted.stream().anyMatch(Row::capped),
                counted.stream().mapToInt(Row::vulnerable).sum(), counted.stream().anyMatch(Row::scanned)).format());
        counted.stream().limit(StoredReport.SAMPLE - 1).map(Row::format).forEach(rows::add);
        return new StoredReport.Rows(repositories, rows);
    }

    /** Where a panel opens: its first line's screen, or the repositories while it has none. */
    private static String first(List<DashboardPanel.Line> lines) {
        return lines.isEmpty() ? "/ui/repositories" : lines.getFirst().href();
    }

}
