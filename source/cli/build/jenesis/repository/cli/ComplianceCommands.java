package build.jenesis.repository.cli;

import module java.base;
import module tools.jackson.databind;

/**
 * The compliance and governance verbs: {@code vulnerabilities} and {@code findings} read the advisory and findings
 * ledgers, {@code licenses} the counted license inventory, {@code quarantine} the compliance gate's holds,
 * {@code ai-review} the findings a code audit proposed, {@code provenance} the signed attestations, {@code policy}
 * the credential-lifetime policy, and {@code enforcement-preview} dry-runs what enabling license enforcement would
 * newly hold.
 */
final class ComplianceCommands {

    private ComplianceCommands() {
    }

    /** How long a health refresh takes to move: a probe per held coordinate, so seconds on a small repository and
     *  minutes on a large one - the cadence a bare {@code --refresh} watches it at. */
    private static final Duration HEALTH_REFRESH = Duration.ofSeconds(5);

    /** A page of the images a repository has had scanned, each with its state and every scanner's run. */
    static int imageScans(String[] args, Path home) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: image-scans <repo> [--cursor C]");
        }
        RiskClient.ImageScans page = CliSupport.client(home).risk().imageScans(args[1], CliSupport.cursorOf(args, 2));
        if (page.scans() == null || page.scans().isEmpty()) {
            System.out.println("No image has been scanned in " + args[1] + ".");
            return 0;
        }
        for (RiskClient.ImageScan scan : page.scans()) {
            System.out.println(scan.name() + "@" + scan.digest() + "  " + scan.state() + "  requested "
                    + scan.requested());
            for (RiskClient.ImageScanTarget target : scan.targets() == null ? List.<RiskClient.ImageScanTarget>of()
                    : scan.targets()) {
                System.out.println("  " + (target.cached() ? "cached as " : "published as ") + target.coordinate()
                        + ":" + target.version());
            }
            for (RiskClient.ImageScanRun run : scan.runs() == null ? List.<RiskClient.ImageScanRun>of()
                    : scan.runs()) {
                String said = switch (run.state()) {
                    case "completed" -> "reported " + run.advisories() + " advisories"
                            + (run.packages() == null ? "" : ", a bill of " + run.packages() + " packages")
                            + ", " + run.completed();
                    case "failed" -> "failed: " + run.failure();
                    case "submitted" -> "scanning, submitted " + run.submitted();
                    default -> "not asked yet";
                };
                System.out.println("  " + run.scanner() + ": " + said);
            }
        }
        CliSupport.more(page.next());
        return 0;
    }

    static int health(String[] args, Path home) throws Exception {
        // A re-score is an action rather than a flag: --refresh belongs to the whole command line, which watches
        // work that outlives a request, and takes it off the line before any handler reads it.
        boolean refresh = args.length > 1 && args[1].equals("refresh");
        if (args.length < (refresh ? 3 : 2) || (refresh && args.length != 3)) {
            throw new IllegalArgumentException("Usage: health <repo> [--cursor C] | health refresh <repo>");
        }
        String repo = args[refresh ? 2 : 1];
        String cursor = refresh ? null : CliSupport.cursorOf(args, 2);
        RiskClient risk = CliSupport.client(home).risk();
        // The first reading starts the refresh when asked to, so a watched refresh and a single answer are the same
        // sequence of requests, and under --json the start call's answer is forgotten like any other poll.
        AtomicBoolean start = new AtomicBoolean(refresh);
        Refresh.Poll poll = () -> {
            if (start.getAndSet(false)) {
                RiskClient.HealthRefreshStart started = risk.refreshHealth(repo);
                System.out.println(started.started() ? "Started a health refresh of " + repo + "."
                        : "A health refresh of " + repo + " was already running.");
                return healthState(repo, started.report());
            }
            return healthState(repo, risk.health(repo, cursor));
        };
        return Refresh.on() ? Refresh.until(HEALTH_REFRESH, poll) : poll.once().code();
    }

    /** Print one reading of the health report, and say whether a refresh is still running behind it. */
    private static Refresh.Poll.State healthState(String repo, RiskClient.HealthReport report) {
        if (!report.available()) {
            System.out.println("No health source is configured on this deployment.");
            return Refresh.Poll.State.done(0);
        }
        String running = report.refreshing() ? "; a refresh is running, and --refresh watches it finish" : "";
        if (!report.ranked()) {
            // Not a failure: the ranking is built by a pass or a refresh, and until then there is nothing to rank by.
            System.out.println("The health of " + repo + " has not been ranked yet"
                    + (report.lastScanned() == null ? "" : " (last scored " + report.lastScanned() + ")")
                    + (report.refreshing() ? running : "; health refresh " + repo + " scores and ranks it") + ".");
        } else {
            for (RiskClient.HealthEntry entry : report.entries()) {
                System.out.printf(Locale.ROOT, "%5.1f  %s %s (maintenance %s, review %s, provenance %s)%n",
                        entry.overall(), entry.ecosystem(), entry.coordinate(), score(entry.maintenance()),
                        score(entry.review()), score(entry.provenance()));
            }
            System.out.println(report.entries().size() + " of " + report.total() + " scored"
                    + (report.lastScanned() == null ? ", never refreshed" : ", as of " + report.lastScanned())
                    + running + ".");
            CliSupport.more(report.next());
        }
        return report.refreshing() ? Refresh.Poll.State.running() : Refresh.Poll.State.done(0);
    }

    /** A component score, or {@code unknown} for the {@code -1} a source could not evaluate. */
    private static String score(double value) {
        return value < 0 ? "unknown" : String.format(Locale.ROOT, "%.1f", value);
    }

    /** How long a re-scan takes to move: a feed query per cached copy, so seconds on a small repository and minutes
     *  on a large one - the cadence a bare {@code --refresh} watches it at. */
    private static final Duration RESCAN = Duration.ofSeconds(5);

    static int vulnerabilities(String[] args, Path home) throws Exception {
        // A re-scan is an action, as a health re-score is: --refresh belongs to the whole command line and watches it.
        boolean rescan = args.length > 1 && args[1].equals("rescan");
        int first = rescan ? 2 : 1;
        if (args.length <= first) {
            throw new IllegalArgumentException("Usage: vulnerabilities [rescan] <repo> [--reachability "
                    + "reachable|not-reachable|unknown] [--applicability applies|not-applicable|unknown]");
        }
        String repo = args[first];
        String reachability = null;
        String applicability = null;
        for (int i = first + 1; i < args.length; i++) {
            switch (args[i]) {
                case "--reachability" -> reachability = CliSupport.flag(args, ++i);
                case "--applicability" -> applicability = CliSupport.flag(args, ++i);
                default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
            }
        }
        RiskClient risk = CliSupport.client(home).risk();
        String reached = reachability;
        String applies = applicability;
        boolean filtered = (reachability != null && !reachability.isBlank())
                || (applicability != null && !applicability.isBlank());
        // The first reading starts the re-scan when asked to, so a watched re-scan and a single answer are the same
        // sequence of requests, and under --json the start call's answer is forgotten like any other poll.
        AtomicBoolean start = new AtomicBoolean(rescan);
        Refresh.Poll poll = () -> {
            if (start.getAndSet(false)) {
                System.out.println(risk.rescanVulnerabilities(repo).started() ? "Started a re-scan of " + repo + "."
                        : "A re-scan of " + repo + " was already running.");
            }
            return vulnerabilityState(repo, risk.vulnerabilities(repo, reached, applies), filtered);
        };
        return Refresh.on() ? Refresh.until(RESCAN, poll) : poll.once().code();
    }

    /** Print one reading of the vulnerability report, and say whether a re-scan is still running behind it. */
    private static Refresh.Poll.State vulnerabilityState(String repo, RiskClient.VulnerabilityReport report,
                                                         boolean filtered) {
        // Before anything reassuring: an empty result prints "No known vulnerabilities" below, and a feed that
        // never answered produces exactly that empty result. A script reads the same fact out of --json, where the
        // field rides the server's own answer.
        for (String warning : report.feedWarnings() == null ? List.<String>of() : report.feedWarnings()) {
            System.out.println("Warning: " + warning);
        }
        if (!report.scanned()) {
            System.out.println("Vulnerability scanning is off (no advisory feed installed or enabled).");
            return Refresh.Poll.State.done(0);
        }
        // What the report is of, said before the rows: when it was taken, whether a re-scan will replace it, and
        // whether it is the bounded ranking a repository gets before its index is first built.
        if (report.scanning()) {
            System.out.println("A re-scan of " + repo + " is running; this is the report as it stood"
                    + (report.lastScanned() == null ? "" : " at " + report.lastScanned())
                    + ", and --refresh watches the re-scan finish.");
        } else {
            System.out.println(report.lastScanned() == null ? "Never re-scanned; vulnerabilities rescan " + repo
                    + " asks the feeds." : "As of " + report.lastScanned() + ".");
        }
        if (report.partial()) {
            System.out.println("Partial: the ranking index is not built yet, so this ranks a bounded window of "
                    + "findings and more may match.");
        }
        Refresh.Poll.State state = report.scanning() ? Refresh.Poll.State.running() : Refresh.Poll.State.done(0);
        if (report.vulnerable().isEmpty()) {
            System.out.println(filtered
                    ? "No known vulnerabilities match this view (the filters narrow the view only; run without "
                            + "them for everything)."
                    : "No known vulnerabilities.");
            return state;
        }
        for (RiskClient.VulnerableArtifact artifact : report.vulnerable()) {
            System.out.println(artifact.coordinate() + (artifact.usedByText() == null
                    || artifact.usedByText().isEmpty() ? "" : "  " + artifact.usedByText()));
            for (RiskClient.Advisory advisory : artifact.advisories()) {
                String fix = advisory.fixed() == null || advisory.fixed().isBlank()
                        ? "no fix available"
                        : "fixed in " + advisory.fixed();
                StringBuilder line = new StringBuilder("  ").append(advisory.id())
                        .append(" (").append(advisory.severity());
                if (advisory.malicious()) {
                    line.append(", malicious");
                }
                // The call-graph verdict badge the reachability sweep labelled onto the stored finding; an
                // un-analyzed advisory carries none and renders without one.
                if (advisory.reachability() != null && !advisory.reachability().isEmpty()) {
                    line.append(", ").append("not-reachable".equals(advisory.reachability())
                            ? "not reachable" : advisory.reachability());
                }
                // The AI applicability opinion, separately attributed - a filter aid beside the row, never a
                // gate on it; a never-judged advisory carries none and renders without one.
                if (advisory.applicability() != null && !advisory.applicability().isEmpty()) {
                    line.append(", AI: ").append("not-applicable".equals(advisory.applicability())
                            ? "not applicable" : advisory.applicability());
                }
                if (advisory.signals() != null) {
                    // The server names its signal columns - the known-exploited flag and the EPSS probability among
                    // them; the CLI renders whatever the deployment contributes.
                    for (RiskClient.Cell cell : advisory.signals()) {
                        if (cell.value() != null && !cell.value().isEmpty()) {
                            line.append(", ").append(cell.value());
                        }
                    }
                }
                System.out.println(line.append(") - ").append(fix));
            }
        }
        return state;
    }

    /**
     * The AI review queue: the findings ledger narrowed to what a code audit proposed and nobody has yet confirmed or
     * dismissed - the console's page of the same name reads the same ledger through the same filter.
     */
    static int aiReview(String[] args, Path home) throws Exception {
        if (args.length != 2 && !(args.length == 4 && args[2].equals("--cursor"))) {
            throw new IllegalArgumentException("Usage: ai-review <repo> [--cursor C]");
        }
        List<String> forwarded = new ArrayList<>(List.of("findings", args[1], "--kind", "ai-candidate"));
        forwarded.addAll(List.of(args).subList(2, args.length));
        return findings(forwarded.toArray(String[]::new), home);
    }

    static int findings(String[] args, Path home) throws Exception {
        if (args.length > 1 && (args[1].equals("review") || args[1].equals("waiver"))) {
            return verdict(args, home);
        }
        if (args.length > 1 && args[1].equals("report")) {
            return report(args, home);
        }
        if (args.length > 1 && args[1].equals("export")) {
            return export(args, home);
        }

        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: findings <repo> [--ecosystem E] [--coordinate C] [--kind K] "
                    + "[--source S] [--category C] [--severity S] [--cursor C]");
        }
        String ecosystem = null;
        String cursor = null;
        String coordinate = null;
        String kind = null;
        String source = null;
        String category = null;
        String severity = null;
        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--ecosystem" -> ecosystem = CliSupport.flag(args, ++i);
                case "--cursor" -> cursor = CliSupport.flag(args, ++i);
                case "--coordinate" -> coordinate = CliSupport.flag(args, ++i);
                case "--kind" -> kind = CliSupport.flag(args, ++i);
                case "--source" -> source = CliSupport.flag(args, ++i);
                case "--category" -> category = CliSupport.flag(args, ++i);
                case "--severity" -> severity = CliSupport.flag(args, ++i);
                default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
            }
        }
        RiskClient.FindingsReport report = CliSupport.client(home).risk().findings(args[1], ecosystem, coordinate,
                kind, source, category, severity, cursor);
        if (report.findings().isEmpty()) {
            System.out.println("No recorded findings match.");
            return 0;
        }
        String at = null;
        for (RiskClient.FindingRow row : report.findings()) {
            // The heading names the version as the review and waiver actions take it back: ecosystem, coordinate
            // and version, then each row its source and id.
            String key = row.ecosystem() + " " + row.coordinate() + " " + row.version();
            if (!key.equals(at)) {
                System.out.println(key);
                at = key;
            }
            StringBuilder line = new StringBuilder("  [").append(row.kind()).append("] ").append(row.id())
                    .append(" (").append(row.severity()).append(", ").append(row.source());
            if (row.category() != null && !row.category().isEmpty()) {
                line.append(", ").append(row.category());
            }
            line.append(")");
            if (row.supersededBy() != null) {
                line.append(" [superseded by ").append(row.supersededBy()).append("]");
            }
            if (row.description() != null && !row.description().isEmpty()) {
                line.append(" - ").append(row.description());
            }
            String fixed = row.attributes() == null ? null : row.attributes().get("fixed");
            if (fixed != null && !fixed.isBlank()) {
                line.append(" (fixed in ").append(fixed).append(")");
            }
            System.out.println(line);
            for (String detail : detail(row.vulnerability())) {
                System.out.println("    " + detail);
            }
            if (row.labels() != null) {
                for (RiskClient.FindingLabel label : row.labels()) {
                    System.out.println("    label " + label.source() + "/" + label.name() + ": " + label.value());
                }
            }
        }
        CliSupport.more(report.next());
        return 0;
    }

    /** How long a license count takes to move: one read of each version's document, so seconds on a small
     *  repository and minutes on a large one - the cadence a bare {@code --refresh} watches it at. */
    private static final Duration LICENSE_COUNT = Duration.ofSeconds(5);

    /**
     * The license inventory as the last count left it; {@code --count} starts a count first. A count runs in the
     * background, so the command answers at once with the state it found, and {@code --refresh} watches it until it
     * finishes - exit code 0 for a finished count, 1 for a failed one.
     */
    static int licenses(String[] args, Path home) throws Exception {
        String repo = null;
        boolean count = false;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--count")) {
                count = true;
            } else if (repo == null && !args[i].startsWith("--")) {
                repo = args[i];
            } else {
                throw new IllegalArgumentException("Usage: licenses <repo> [--count]");
            }
        }
        if (repo == null) {
            throw new IllegalArgumentException("Usage: licenses <repo> [--count]");
        }
        String repository = repo;
        RiskClient risk = CliSupport.client(home).risk();
        // The first reading starts the count when asked to, so a watched count and a single answer are the same
        // sequence of requests, and under --json the start call's answer is forgotten like any other poll.
        AtomicBoolean start = new AtomicBoolean(count);
        Refresh.Poll poll = () -> {
            if (start.getAndSet(false)) {
                RiskClient.LicenseCountStart started = risk.countLicenses(repository);
                System.out.println(started.started() ? "Started a license count of " + repository + "."
                        : "A license count of " + repository + " was already running.");
                return licenseState(repository, started.inventory());
            }
            return licenseState(repository, risk.licenses(repository));
        };
        return Refresh.on() ? Refresh.until(LICENSE_COUNT, poll) : poll.once().code();
    }

    /** Print one reading of the license inventory, and say whether there is any point asking again. */
    private static Refresh.Poll.State licenseState(String repo, RiskClient.LicensesView view) {
        switch (view.state()) {
            case "not-counted" -> {
                System.out.println("The licenses of " + repo + " have not been counted yet; "
                        + "licenses " + repo + " --count starts a count.");
                return Refresh.Poll.State.done(0);
            }
            case "running" -> {
                System.out.println("A license count of " + repo + " is running, started " + view.startedAt() + ".");
                previous(view);
                return Refresh.Poll.State.running();
            }
            case "done" -> {
                System.out.println(view.versions() + " version(s) counted, as of " + view.finishedAt() + ".");
                System.out.println("categories:");
                for (RiskClient.LicenseCount each : view.categories()) {
                    System.out.printf(Locale.ROOT, "  %-24s %d%n", each.value(), each.versions());
                }
                System.out.println("licenses:");
                for (RiskClient.LicenseCount each : view.licenses()) {
                    System.out.printf(Locale.ROOT, "  %-24s %d%n", each.value(), each.versions());
                }
                if (view.truncated()) {
                    System.out.println("More licenses were counted than are kept; showing the "
                            + view.licenses().size() + " with the most versions.");
                }
                return Refresh.Poll.State.done(0);
            }
            default -> {
                System.out.println("The last license count of " + repo + ", started " + view.startedAt()
                        + ", failed: " + view.failure());
                previous(view);
                return Refresh.Poll.State.done(1);
            }
        }
    }

    /** The finished count a running or failed one leaves standing, in one line. */
    private static void previous(RiskClient.LicensesView view) {
        if (view.previous() != null) {
            System.out.println("The last finished count, as of " + view.previous().finishedAt() + ", counted "
                    + view.previous().versions() + " version(s).");
        }
    }

    static int signers(String[] args, Path home) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: signers <repo> [<signer>] [--cursor C]");
        }
        List<String> positional = new ArrayList<>();
        String cursor = null;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--cursor")) {
                cursor = CliSupport.flag(args, ++i);
            } else {
                positional.add(args[i]);
            }
        }
        RepositoryClient client = CliSupport.client(home);
        if (positional.size() >= 2) {
            RepositoryClient.Page<ProvenanceClient.SignedCoordinate> signed =
                    client.provenance().signedBy(positional.get(0), positional.get(1), cursor);
            if (signed.items().isEmpty()) {
                System.out.println("This signer signed nothing that was accepted in " + positional.get(0) + ".");
                return 0;
            }
            for (ProvenanceClient.SignedCoordinate coordinate : signed.items()) {
                System.out.printf("%-10s %s  %d version%s, last %s%s%n", coordinate.ecosystem(), coordinate.coordinate(),
                        coordinate.versions(), coordinate.versions() == 1 ? "" : "s", coordinate.last(),
                        coordinate.since() == null ? "" : ", since " + coordinate.since());
            }
            CliSupport.more(signed.next());
            return 0;
        }
        RepositoryClient.Page<ProvenanceClient.Signer> signers = client.provenance().signers(positional.get(0), cursor);
        if (signers.items().isEmpty()) {
            System.out.println("No signed version has been accepted in " + positional.get(0) + " yet.");
            return 0;
        }
        for (ProvenanceClient.Signer signer : signers.items()) {
            System.out.println(signer.signer());
            if (signer.issuer() != null || signer.subject() != null) {
                System.out.println("    issuer  " + signer.issuer());
                System.out.println("    subject " + signer.subject());
            }
        }
        CliSupport.more(signers.next());
        return 0;
    }

    /** A version's recorded signature, as the artifact screen shows it: the outcome and the signer on the first
     *  line, then what the record says apart - a keyless signer's issuer and subject, how the signer came to be
     *  believed, the transparency-log entry, the grade, the material. */
    static int signature(String[] args, Path home) throws Exception {
        if (args.length < 3) {
            throw new IllegalArgumentException("Usage: signature <repo> <path>");
        }
        ProvenanceClient.Signature signature = CliSupport.client(home).provenance().signature(args[1], args[2]);
        if (signature == null) {
            System.out.println("No signature has been recorded for " + args[2] + " in " + args[1]
                    + ": it was published before signatures were checked here, or its format carries none.");
            return 0;
        }
        System.out.println(signature.outcome() + (signature.signer() == null ? "" : "  " + signature.signer()));
        Map<String, String> details = signature.details() == null ? Map.of() : signature.details();
        line("issuer", details.get("issuer"));
        line("subject", details.get("subject"));
        line("admitted by", signature.admittedBy());
        String index = details.get("log-index");
        String recorded = details.get("integrated-time");
        line("log entry", index == null ? null : index + (recorded == null ? "" : ", recorded " + recorded));
        line("quality", signature.grade());
        line("material", signature.location());
        return 0;
    }

    static int closure(String[] args, Path home) throws Exception {
        if (args.length < 5) {
            throw new IllegalArgumentException("Usage: closure <repo> <ecosystem> <coordinate> <version>");
        }
        ProvenanceClient.Closure closure = CliSupport.client(home).provenance().closure(args[1], args[2], args[3],
                args[4]);
        String subject = closure.coordinate() + " " + closure.version();
        ProvenanceClient.ScreenedThrough screened = closure.screenedThrough();
        if (screened != null) {
            System.out.println(switch (screened.basis()) {
                case "FEEDS" -> subject + " is screened by its coordinate through "
                        + String.join(", ", screened.feeds()) + ".";
                case "UNCOVERED" -> subject + " is unscreened: no enabled advisory feed covers " + closure.ecosystem()
                        + ", so no finding is not the same as clean.";
                case "NOTHING" -> subject + " is unscreened: a version published here is asked of no feed, and its "
                        + "closure is not resolved yet.";
                default -> subject + " is screened through what its resolved closure reaches.";
            });
        }
        switch (closure.state()) {
            case "CACHED" -> {
                System.out.println(subject + " is a cached copy: it has no closure of its own, and is screened by "
                        + "its own coordinate.");
                return 0;
            }
            case "PENDING" -> {
                System.out.println(subject + " is not resolved yet: the closure pass resolves a release shortly "
                        + "after its publish, unless the repository's closure resolution is off.");
                return 0;
            }
            case "UNDECLARED" -> {
                System.out.println(subject + " declares no dependencies this repository can read, so it has no "
                        + "closure - which is not the same as an empty one.");
                return 0;
            }
            default -> {
            }
        }
        printResolved(subject, closure);
        if (closure.exposure() != null) {
            printExposure(closure.exposure());
        }
        return 0;
    }

    /** What a resolved closure holds: its components, where it was cut, and the packages of other ecosystems. */
    private static void printResolved(String subject, ProvenanceClient.Closure closure) {
        List<ProvenanceClient.ClosureComponent> components = closure.components();
        List<ProvenanceClient.ClosureCut> cuts = closure.cuts();
        System.out.println(subject + "  " + closure.state().toLowerCase(Locale.ROOT)
                + ("BILL".equals(closure.kind()) ? ", from the bill it carries, read " : ", resolved ")
                + (closure.source().isBlank() ? "" : "by " + closure.source() + " ")
                + closure.resolved() + ": " + components.size() + " component(s), " + cuts.size() + " unresolved");
        for (ProvenanceClient.ClosureComponent component : components) {
            String repository = component.repository().isBlank() ? "" : " of " + component.repository();
            System.out.printf(Locale.ROOT, "    %s %s  %s%s, depth %d%n", component.coordinate(), component.version(),
                    component.cached() ? "a cached copy" : "a release", repository, component.depth());
        }
        for (ProvenanceClient.ClosureCut cut : cuts) {
            System.out.println("    unresolved: " + cut.coordinate()
                    + (cut.requirement().isBlank() ? "" : " " + cut.requirement()) + " - " + cut.reason());
        }
        List<ProvenanceClient.ClosureForeign> foreign = closure.foreign();
        if (!foreign.isEmpty()) {
            System.out.println("  its bill names " + foreign.size() + " package(s) of other ecosystems, followed by "
                    + "coordinate across the tenant:");
            for (ProvenanceClient.ClosureForeign entry : foreign) {
                System.out.printf(Locale.ROOT, "    %s %s  %s, depth %d%s%n", entry.coordinate(), entry.version(),
                        entry.ecosystem(), entry.depth(),
                        entry.purl() == null || entry.purl().isBlank() ? "" : "  " + entry.purl());
            }
        }
        if (closure.truncated()) {
            System.out.println("    the closure stopped at its bound; what lies past it is not listed");
        }
    }

    /** The versions a closure reaches that are held for review or carry findings, each with its path. */
    private static void printExposure(ProvenanceClient.ClosureExposure exposure) {
        List<ProvenanceClient.ClosureReached> reached = exposure.reached();
        System.out.println("  relies on " + exposure.held() + " version(s) held for review and "
                + exposure.vulnerable() + " carrying findings, as of " + exposure.derived());
        for (ProvenanceClient.ClosureReached version : reached) {
            String repository = version.repository().isBlank() ? "" : " of " + version.repository();
            String ecosystem = version.ecosystem().isBlank() ? "" : " (" + version.ecosystem() + ")";
            System.out.println("    " + version.coordinate() + " " + version.version() + ecosystem + repository
                    + "  "
                    + (version.held() ? "held for review" : "")
                    + (version.held() && version.findings() > 0 ? ", " : "")
                    + (version.findings() > 0 ? version.findings() + " finding(s), the worst "
                    + version.worst().toLowerCase(Locale.ROOT) : ""));
            through(version.path());
        }
    }

    /** The dependencies a path goes through before the version it ends at, on a line of its own, where there are
     *  any. */
    static void through(List<ProvenanceClient.ClosureHop> path) {
        if (path == null || path.size() < 2) {
            return;
        }
        System.out.println("      through " + String.join(" -> ", path.subList(0, path.size() - 1).stream()
                .map(hop -> hop.coordinate() + " " + hop.version()).toList()));
    }

    private static void line(String label, String value) {
        if (value != null && !value.isBlank()) {
            System.out.printf("    %-12s %s%n", label, value);
        }
    }

    static int quarantine(String[] args, Path home) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException(
                    "Usage: quarantine <repo> [--cursor C] | quarantine release|discard <repo> <path>...");
        }
        RepositoryClient client = CliSupport.client(home);
        switch (args[1]) {
            case "release" -> {
                if (args.length < 4) {
                    throw new IllegalArgumentException("Usage: quarantine release <repo> <path>...");
                }
                List<String> paths = List.of(args).subList(3, args.length);
                client.review().releaseQuarantine(args[2], paths);
                System.out.println("Released " + String.join(", ", paths) + " into " + args[2] + ".");
                return 0;
            }
            case "hold" -> {
                if (args.length != 6) {
                    throw new IllegalArgumentException("Usage: quarantine hold <repo> <ecosystem> <coordinate> <version>");
                }
                boolean held = client.review().holdVersion(args[2], args[3], args[4], args[5]);
                System.out.println(held
                        ? "Held " + args[4] + ":" + args[5] + " for review in " + args[2] + "."
                        : "Nothing of " + args[4] + ":" + args[5] + " serves in " + args[2] + ", so nothing was held.");
                return 0;
            }
            case "discard" -> {
                if (args.length < 4) {
                    throw new IllegalArgumentException("Usage: quarantine discard <repo> <path>...");
                }
                ReviewClient.Discarded answer =
                        client.review().discardQuarantine(args[2], List.of(args).subList(3, args.length));
                if (!answer.discarded().isEmpty()) {
                    System.out.println("Discarded " + String.join(", ", answer.discarded()) + ".");
                }
                if (!answer.absent().isEmpty()) {
                    System.out.println("Nothing was held at " + String.join(", ", answer.absent())
                            + " - already released or discarded.");
                }
                return 0;
            }
            default -> {
                String cursor = null;
                for (int i = 2; i < args.length; i++) {
                    if (args[i].equals("--cursor")) {
                        cursor = CliSupport.flag(args, ++i);
                    } else {
                        throw new IllegalArgumentException("Unknown option: " + args[i]);
                    }
                }
                ReviewClient.QuarantineView queue = client.review().quarantine(args[1], cursor);
                List<ReviewClient.QuarantineEvent> events = queue.events();
                if (events.isEmpty()) {
                    System.out.println("Nothing is held for review.");
                }
                // A version's files are reviewed together, as the console shows them: the version once, the reasons
                // each file was held for under its path.
                Map<String, List<ReviewClient.QuarantineEvent>> versions = new LinkedHashMap<>();
                for (ReviewClient.QuarantineEvent event : events) {
                    versions.computeIfAbsent(event.coordinate() == null ? event.path() : event.coordinate(),
                            _ -> new ArrayList<>()).add(event);
                }
                for (Map.Entry<String, List<ReviewClient.QuarantineEvent>> version : versions.entrySet()) {
                    ReviewClient.QuarantineEvent first = version.getValue().getFirst();
                    Set<String> rules = new LinkedHashSet<>();
                    version.getValue().stream().filter(event -> event.rules() != null)
                            .forEach(event -> rules.addAll(event.rules()));
                    System.out.printf("%s  %s%s%n", first.when(), version.getKey(),
                            rules.isEmpty() ? "" : "  held for " + String.join(", ", rules));
                    Set<String> notes = new LinkedHashSet<>();
                    version.getValue().stream().filter(event -> event.notes() != null)
                            .forEach(event -> notes.addAll(event.notes()));
                    notes.forEach(note -> System.out.println("    note: " + note));
                    for (ReviewClient.QuarantineEvent event : version.getValue()) {
                        System.out.println("    " + event.path());
                        if (event.reasons() != null) {
                            for (String reason : event.reasons()) {
                                System.out.println("        " + reason);
                            }
                        }
                    }
                }
                CliSupport.more(queue.next());
                // A refusal left nothing to release, so it is listed apart from the queue, as the console lists it.
                List<ReviewClient.QuarantineEvent> refusals = queue.refusals() == null ? List.of() : queue.refusals();
                if (!refusals.isEmpty()) {
                    System.out.println("Recently refused:");
                    for (ReviewClient.QuarantineEvent refusal : refusals) {
                        System.out.println("  " + refusal.when() + "  " + refusal.path()
                                + (refusal.reasons() == null || refusal.reasons().isEmpty() ? ""
                                        : "  " + String.join("; ", refusal.reasons())));
                    }
                }
                return 0;
            }
        }
    }

    static int provenance(String[] args, Path home) throws Exception {
        if (args.length >= 2 && args[1].equals("key")) {
            String pem = CliSupport.client(home).provenance().provenanceKey();
            if (pem == null) {
                System.out.println("Provenance signing is not enabled on this deployment.");
                return 1;
            }
            System.out.print(pem);
            return 0;
        }
        if (args.length >= 2 && (args[1].equals("cert") || args[1].equals("certificate"))) {
            String pem = CliSupport.client(home).provenance().provenanceCertificate();
            if (pem == null) {
                System.out.println("No provenance certificate (a bare-key signer, or signing is not enabled).");
                return 1;
            }
            System.out.print(pem);
            return 0;
        }
        boolean material = false;
        List<String> rest = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--material")) {
                material = true;
            } else {
                rest.add(args[i]);
            }
        }
        if (rest.size() < 2) {
            throw new IllegalArgumentException(
                    "Usage: provenance <repo> <path> [--material] | provenance key | provenance cert");
        }
        RepositoryClient client = CliSupport.client(home);
        if (!material) {
            System.out.println(client.provenance().provenance(rest.get(0), rest.get(1)));
            return 0;
        }
        ProvenanceClient.ProvenanceMaterial view = client.provenance().provenanceMaterial(rest.get(0), rest.get(1));
        if (view == null) {
            System.out.println("Provenance signing is not enabled on this deployment.");
            return 1;
        }
        System.out.println(view.envelope());
        if (view.certificateChain() != null && !view.certificateChain().isEmpty()) {
            System.out.println("certificate chain:");
            System.out.println(view.certificateChain());
        }
        if (view.transparencyLog() != null) {
            ProvenanceClient.TransparencyLog log = view.transparencyLog();
            System.out.println("transparency log: " + log.uuid() + " (index " + log.logIndex() + ")");
        }
        return 0;
    }

    static int policy(String[] args, Path home) throws Exception {
        RepositoryClient client = CliSupport.client(home);
        if (args.length > 1 && args[1].equals("set")) {
            // The endpoint replaces the whole policy, so an omitted flag clears that lifetime rather than leaving it.
            String defaultLifetime = null;
            String maxLifetime = null;
            for (int i = 2; i < args.length; i++) {
                switch (args[i]) {
                    case "--default" -> defaultLifetime = CliSupport.flag(args, ++i);
                    case "--max" -> maxLifetime = CliSupport.flag(args, ++i);
                    default -> throw new IllegalArgumentException("Unknown policy flag '" + args[i] + "'");
                }
            }
            client.access().setPolicy(defaultLifetime, maxLifetime);
            System.out.println("Set the credential lifetime policy.");
            return 0;
        }
        AccessClient.PolicyView view = client.access().policy();
        System.out.println("default lifetime: " + view.defaultLifetime());
        System.out.println("max lifetime:     " + CliSupport.orDash(view.maxLifetime()));
        return 0;
    }

    /** How long the plan takes to move: it assesses every release, so seconds on a small repository and minutes on a
     *  large one - the cadence a bare {@code --refresh} watches it at. */
    private static final Duration RETRO_PLAN = Duration.ofSeconds(5);

    static int enforcementPreview(String[] args, Path home) throws Exception {
        // A computation is an action, as a health refresh is; --refresh belongs to the whole line and watches it.
        boolean compute = args.length > 1 && args[1].equals("compute");
        boolean unknown = false;
        String found = null;
        for (int i = compute ? 2 : 1; i < args.length; i++) {
            if (args[i].equals("--unknown")) {
                unknown = true;
            } else if (found == null) {
                found = args[i];
            }
        }
        if (found == null) {
            throw new IllegalArgumentException("Usage: enforcement-preview [compute] <repo> [--unknown]");
        }
        String repo = found;
        boolean withUnknown = unknown;
        RiskClient risk = CliSupport.client(home).risk();
        // The first reading starts the plan when asked to, so a watched computation and a single answer are the same
        // sequence of requests, and under --json the start call's answer is forgotten like any other poll.
        AtomicBoolean start = new AtomicBoolean(compute);
        Refresh.Poll poll = () -> {
            if (start.getAndSet(false)) {
                RiskClient.RetroStart started = risk.computeRetroPlan(repo, withUnknown);
                System.out.println(started.started() ? "Started the enforcement preview of " + repo + "."
                        : "The enforcement preview of " + repo + " was already running.");
                return retroState(repo, started.plan());
            }
            return retroState(repo, risk.retroPlan(repo, withUnknown));
        };
        return Refresh.on() ? Refresh.until(RETRO_PLAN, poll) : poll.once().code();
    }

    /** Print one reading of the plan, and say whether its run is still going. */
    private static Refresh.Poll.State retroState(String repo, RiskClient.RetroPlan plan) {
        switch (plan.state()) {
            case "not-computed" -> {
                System.out.println("No enforcement preview of " + repo + " has been computed; enforcement-preview "
                        + "compute " + repo + " computes one.");
                return Refresh.Poll.State.done(0);
            }
            case "running" -> System.out.println("The enforcement preview of " + repo + " is running"
                    + (plan.computedAt() == null ? "." : "; the last one, as of " + plan.computedAt() + ", follows."));
            case "failed" -> System.out.println("The last enforcement preview of " + repo + " stopped: "
                    + plan.failure());
            default -> System.out.println("As of " + plan.computedAt() + ".");
        }
        if (plan.computedAt() != null) {
            System.out.println("mode:  " + plan.mode());
            System.out.println("count: " + plan.count());
            for (RiskClient.RetroHeld held : plan.held()) {
                System.out.println("  " + held.coordinate() + ":" + held.version());
                if (held.reasons() != null) {
                    held.reasons().forEach(reason -> System.out.println("    " + reason));
                }
            }
            if (plan.count() > plan.held().size()) {
                System.out.println("The first " + plan.held().size() + " of " + plan.count() + " are listed.");
            }
        }
        return switch (plan.state()) {
            case "running" -> Refresh.Poll.State.running();
            case "failed" -> Refresh.Poll.State.done(1);
            default -> Refresh.Poll.State.done(0);
        };
    }

    /** Post a scanner's report - the file is the API's own request document, sent as it is, so the CLI adds
     *  nothing a CI job could get out of step with - and say what it did. */
    /**
     * What a finding's source said beyond its identifier and severity, a line each, from CycloneDX's
     * {@code vulnerability} object the API answers: who published it and where, each rating with who gave it, its
     * method, score and vector, the other identifiers it goes by, its weaknesses, its advisories and its dates.
     */
    static List<String> detail(JsonNode vulnerability) {
        List<String> lines = new ArrayList<>();
        if (vulnerability == null || !vulnerability.isObject()) {
            return lines;
        }
        String published = named(vulnerability.path("source"));
        if (published != null) {
            lines.add("published by " + published);
        }
        for (JsonNode rating : vulnerability.path("ratings")) {
            StringJoiner line = new StringJoiner(" ", "rated ", "");
            for (String part : new String[]{rating.path("source").path("name").asString(null),
                    rating.path("method").asString(null), rating.path("score").isNumber()
                    ? rating.path("score").asString() : null, rating.path("severity").asString(null),
                    rating.path("vector").asString(null)}) {
                if (part != null && !part.isBlank()) {
                    line.add(part);
                }
            }
            lines.add(line.toString());
        }
        List<String> aliases = new ArrayList<>();
        for (JsonNode reference : vulnerability.path("references")) {
            String where = named(reference.path("source"));
            aliases.add(reference.path("id").asString("") + (where == null ? "" : " (" + where + ")"));
        }
        if (!aliases.isEmpty()) {
            lines.add("also " + String.join(", ", aliases));
        }
        List<String> cwes = new ArrayList<>();
        vulnerability.path("cwes").forEach(cwe -> cwes.add("CWE-" + cwe.asString()));
        if (!cwes.isEmpty()) {
            lines.add("weakness " + String.join(", ", cwes));
        }
        for (JsonNode advisory : vulnerability.path("advisories")) {
            String title = advisory.path("title").asString(null);
            lines.add("advisory " + (title == null ? "" : title + " ") + advisory.path("url").asString(""));
        }
        for (String date : new String[]{"published", "updated"}) {
            String when = vulnerability.path(date).asString(null);
            if (when != null) {
                lines.add(date + " " + when);
            }
        }
        return lines;
    }

    // A source as a line names it: its name and its address, either alone, or null for neither.
    private static String named(JsonNode source) {
        String name = source.path("name").asString(null);
        String url = source.path("url").asString(null);
        return name == null ? url : url == null ? name : name + " " + url;
    }

    /** One version's standing vulnerability and malware findings as a CycloneDX document, printed or written to
     *  {@code --output}. */
    private static int export(String[] args, Path home) throws Exception {
        String output = null;
        List<String> positional = new ArrayList<>();
        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--output", "-o" -> output = CliSupport.flag(args, ++i);
                default -> {
                    if (args[i].startsWith("--")) {
                        throw new IllegalArgumentException("Unknown option: " + args[i]);
                    }
                    positional.add(args[i]);
                }
            }
        }
        if (positional.size() != 4) {
            throw new IllegalArgumentException(
                    "Usage: findings export <repo> <ecosystem> <coordinate> <version> [--output F]");
        }
        String bom = CliSupport.client(home).risk().findingsCycloneDx(positional.get(0), positional.get(1),
                positional.get(2), positional.get(3));
        if (output != null) {
            Files.writeString(Path.of(output), bom, StandardCharsets.UTF_8);
            System.out.println("Wrote the findings of " + positional.get(2) + " " + positional.get(3) + " to "
                    + output + ".");
        } else {
            System.out.print(bom);
        }
        return 0;
    }

    private static int report(String[] args, Path home) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException("Usage: findings report <repo> <file>");
        }
        RiskClient.ReportAnswer answer = CliSupport.client(home).risk().reportFindings(args[2], Path.of(args[3]));
        System.out.println("Recorded " + answer.recorded() + " finding(s); the gate's verdict is " + answer.verdict()
                + (answer.held() ? ", and the version is withheld for review." : "."));
        for (String reason : answer.reasons()) {
            System.out.println("  " + reason);
        }
        return 0;
    }

    /**
     * The two ways a person answers a finding: a decision on an AI-produced one, and a waiver that accepts an
     * advisory's risk until a date - and the waiver's withdrawal.
     *
     * <p>Each names the finding the way the ledger keys it - ecosystem, coordinate, version, source and id - because
     * an id is unique only within one scanner's report on one version: the same advisory is a separate finding against
     * every version it touches, and a decision that named less would be ambiguous about which of them it settled.
     */
    private static int verdict(String[] args, Path home) throws Exception {
        if (args[1].equals("review")) {
            if (args.length < 9) {
                throw new IllegalArgumentException("Usage: findings review <repo> <ecosystem> <coordinate> <version>"
                        + " <source> <id> <confirmed|dismissed> [--note N]");
            }
            String note = note(args, 9);
            CliSupport.client(home).risk().reviewFinding(args[2], finding(args, 3), args[8], note);
            System.out.println("Recorded '" + args[8] + "' on " + args[7] + " for " + args[4] + " " + args[5] + ".");
            return 0;
        }
        if (args.length > 2 && args[2].equals("revoke")) {
            if (args.length != 9) {
                throw new IllegalArgumentException("Usage: findings waiver revoke <repo> <ecosystem> <coordinate>"
                        + " <version> <source> <id>");
            }
            CliSupport.client(home).risk().revokeWaiver(args[3], finding(args, 4));
            System.out.println("Revoked the waiver on " + args[8] + " for " + args[5] + " " + args[6] + ".");
            return 0;
        }
        if (args.length < 9) {
            throw new IllegalArgumentException("Usage: findings waiver <repo> <ecosystem> <coordinate> <version>"
                    + " <source> <id> <until> [--note N]");
        }
        String note = note(args, 9);
        CliSupport.client(home).risk().waiveFinding(args[2], finding(args, 3), args[8], note);
        System.out.println("Waived " + args[7] + " for " + args[4] + " " + args[5] + " until " + args[8] + ".");
        return 0;
    }

    /** The finding named by the five arguments from {@code from}: ecosystem, coordinate, version, source, id. */
    private static RiskClient.FindingKey finding(String[] args, int from) {
        return new RiskClient.FindingKey(args[from], args[from + 1], args[from + 2], args[from + 3], args[from + 4]);
    }

    /** The {@code --note} among the arguments from {@code from}, the only flag a decision takes. */
    private static String note(String[] args, int from) {
        String note = null;
        for (int i = from; i < args.length; i++) {
            if (args[i].equals("--note")) {
                note = CliSupport.flag(args, ++i);
            } else {
                throw new IllegalArgumentException("Unknown flag '" + args[i] + "'");
            }
        }
        return note;
    }
}
