package build.jenesis.repository.cli;

import module java.base;

/**
 * The compliance and governance verbs: {@code vulnerabilities} and {@code findings} read the advisory and findings
 * ledgers, {@code licenses} the declared-license facets, {@code quarantine} the compliance gate's holds,
 * {@code provenance} the signed attestations, {@code policy} the credential-lifetime policy, and {@code retro-plan}
 * dry-runs what enabling license enforcement would newly hold.
 */
final class ComplianceCommands {

    private ComplianceCommands() {
    }

    static int health(String[] args, Path home) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: health <repo> [--refresh]");
        }
        boolean refresh = Arrays.asList(args).subList(2, args.length).contains("--refresh");
        RepositoryClient.HealthReport report = CliSupport.client(home).health(args[1], refresh);
        if (!report.available()) {
            System.out.println("No health source is configured on this deployment.");
            return 0;
        }
        for (RepositoryClient.HealthEntry entry : report.entries()) {
            System.out.printf(Locale.ROOT, "%5.1f  %s %s (maintenance %s, review %s, provenance %s)%n",
                    entry.overall(), entry.ecosystem(), entry.coordinate(), score(entry.maintenance()),
                    score(entry.review()), score(entry.provenance()));
        }
        System.out.println(report.entries().size() + " of " + report.total() + " scored"
                + (report.lastScanned() == null ? ", never refreshed" : ", as of " + report.lastScanned())
                + (refresh ? "; a refresh has been started" : "") + ".");
        return 0;
    }

    /** A component score, or {@code unknown} for the {@code -1} a source could not evaluate. */
    private static String score(double value) {
        return value < 0 ? "unknown" : String.format(Locale.ROOT, "%.1f", value);
    }

    static int vulnerabilities(String[] args, Path home) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: vulnerabilities <repo> [--reachability "
                    + "reachable|not-reachable|unknown] [--applicability applies|not-applicable|unknown]");
        }
        String reachability = null;
        String applicability = null;
        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--reachability" -> reachability = CliSupport.flag(args, ++i);
                case "--applicability" -> applicability = CliSupport.flag(args, ++i);
                default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
            }
        }
        RepositoryClient.VulnerabilityReport report = CliSupport.client(home).vulnerabilities(args[1], reachability,
                applicability);
        // Before anything reassuring: an empty result prints "No known vulnerabilities" below, and a feed that
        // never answered produces exactly that empty result. A script reads the same fact out of --json, where the
        // field rides the server's own answer.
        for (String warning : report.feedWarnings() == null ? List.<String>of() : report.feedWarnings()) {
            System.out.println("Warning: " + warning);
        }
        if (!report.scanned()) {
            System.out.println("Vulnerability scanning is off (no advisory feed installed or enabled).");
            return 0;
        }
        if (report.vulnerable().isEmpty()) {
            System.out.println((reachability == null || reachability.isBlank())
                            && (applicability == null || applicability.isBlank())
                    ? "No known vulnerabilities."
                    : "No known vulnerabilities match this view (the filters narrow the view only; run without "
                            + "them for everything).");
            return 0;
        }
        for (RepositoryClient.VulnerableArtifact artifact : report.vulnerable()) {
            System.out.println(artifact.coordinate());
            for (RepositoryClient.Advisory advisory : artifact.advisories()) {
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
                    for (RepositoryClient.Cell cell : advisory.signals()) {
                        if (cell.value() != null && !cell.value().isEmpty()) {
                            line.append(", ").append(cell.value());
                        }
                    }
                }
                System.out.println(line.append(") - ").append(fix));
            }
        }
        return 0;
    }

    static int findings(String[] args, Path home) throws Exception {
        if (args.length > 1 && (args[1].equals("review") || args[1].equals("waiver"))) {
            return verdict(args, home);
        }
        if (args.length > 1 && args[1].equals("report")) {
            return report(args, home);
        }

        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: findings <repo> [--coordinate C] [--kind K] [--source S] "
                    + "[--category C] [--severity S]");
        }
        String coordinate = null;
        String kind = null;
        String source = null;
        String category = null;
        String severity = null;
        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--coordinate" -> coordinate = CliSupport.flag(args, ++i);
                case "--kind" -> kind = CliSupport.flag(args, ++i);
                case "--source" -> source = CliSupport.flag(args, ++i);
                case "--category" -> category = CliSupport.flag(args, ++i);
                case "--severity" -> severity = CliSupport.flag(args, ++i);
                default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
            }
        }
        RepositoryClient.FindingsReport report = CliSupport.client(home).findings(args[1], coordinate, kind, source,
                category, severity);
        if (report == null) {
            System.out.println("The findings store is not installed on this deployment.");
            return 0;
        }
        if (report.findings().isEmpty()) {
            System.out.println("No recorded findings match.");
            return 0;
        }
        String at = null;
        for (RepositoryClient.FindingRow row : report.findings()) {
            String key = row.coordinate() + ":" + row.version();
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
            if (row.labels() != null) {
                for (RepositoryClient.FindingLabel label : row.labels()) {
                    System.out.println("    label " + label.source() + "/" + label.name() + ": " + label.value());
                }
            }
        }
        return 0;
    }

    static int licenses(String[] args, Path home) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: licenses <repo>");
        }
        RepositoryClient.LicensesView view = CliSupport.client(home).licenses(args[1]);
        if (!view.indexed()) {
            System.out.println(
                    "License facets need the search index (search/lucene is not installed or the index is empty).");
            return 0;
        }
        if (view.categories().isEmpty() && view.licenses().isEmpty()) {
            System.out.println("No declared licenses recorded.");
            return 0;
        }
        System.out.println("categories:");
        for (RepositoryClient.LicenseCount count : view.categories()) {
            System.out.printf("  %-24s %d%n", count.value(), count.count());
        }
        System.out.println("licenses:");
        for (RepositoryClient.LicenseCount count : view.licenses()) {
            System.out.printf("  %-24s %d%n", count.value(), count.count());
        }
        return 0;
    }

    static int signers(String[] args, Path home) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: signers <repo> [<signer>]");
        }
        RepositoryClient client = CliSupport.client(home);
        if (args.length >= 3) {
            List<RepositoryClient.SignedCoordinate> signed = client.signedBy(args[1], args[2]);
            if (signed.isEmpty()) {
                System.out.println("This signer signed nothing that was accepted in " + args[1] + ".");
                return 0;
            }
            for (RepositoryClient.SignedCoordinate coordinate : signed) {
                System.out.printf("%-10s %s  %d version%s, last %s%s%n", coordinate.ecosystem(), coordinate.coordinate(),
                        coordinate.versions(), coordinate.versions() == 1 ? "" : "s", coordinate.last(),
                        coordinate.since() == null ? "" : ", since " + coordinate.since());
            }
            return 0;
        }
        List<RepositoryClient.Signer> signers = client.signers(args[1]);
        if (signers.isEmpty()) {
            System.out.println("No signed version has been accepted in " + args[1] + " yet.");
            return 0;
        }
        for (RepositoryClient.Signer signer : signers) {
            System.out.println(signer.signer());
            if (signer.issuer() != null || signer.subject() != null) {
                System.out.println("    issuer  " + signer.issuer());
                System.out.println("    subject " + signer.subject());
            }
        }
        return 0;
    }

    /** A version's recorded signature, as the artifact screen shows it: the outcome and the signer on the first
     *  line, then what the record says apart - a keyless signer's issuer and subject, how the signer came to be
     *  believed, the transparency-log entry, the grade, the material. */
    static int signature(String[] args, Path home) throws Exception {
        if (args.length < 3) {
            throw new IllegalArgumentException("Usage: signature <repo> <path>");
        }
        RepositoryClient.Signature signature = CliSupport.client(home).signature(args[1], args[2]);
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

    private static void line(String label, String value) {
        if (value != null && !value.isBlank()) {
            System.out.printf("    %-12s %s%n", label, value);
        }
    }

    static int quarantine(String[] args, Path home) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException(
                    "Usage: quarantine <repo> | quarantine release|discard <repo> <path>");
        }
        RepositoryClient client = CliSupport.client(home);
        switch (args[1]) {
            case "release" -> {
                if (args.length < 4) {
                    throw new IllegalArgumentException("Usage: quarantine release <repo> <path>");
                }
                client.releaseQuarantine(args[2], args[3]);
                System.out.println("Released " + args[3] + " into " + args[2] + ".");
                return 0;
            }
            case "discard" -> {
                if (args.length < 4) {
                    throw new IllegalArgumentException("Usage: quarantine discard <repo> <path>");
                }
                client.discardQuarantine(args[2], args[3]);
                System.out.println("Discarded " + args[3] + ".");
                return 0;
            }
            default -> {
                List<RepositoryClient.QuarantineEvent> events = client.quarantine(args[1]);
                if (events.isEmpty()) {
                    System.out.println("Nothing is held for review.");
                    return 0;
                }
                for (RepositoryClient.QuarantineEvent event : events) {
                    System.out.printf("%s  %-9s %s%n", event.when(), event.verdict(), event.path());
                    if (event.reasons() != null) {
                        for (String reason : event.reasons()) {
                            System.out.println("    " + reason);
                        }
                    }
                }
                return 0;
            }
        }
    }

    static int provenance(String[] args, Path home) throws Exception {
        if (args.length >= 2 && args[1].equals("key")) {
            String pem = CliSupport.client(home).provenanceKey();
            if (pem == null) {
                System.out.println("Provenance signing is not enabled on this deployment.");
                return 1;
            }
            System.out.print(pem);
            return 0;
        }
        if (args.length >= 2 && (args[1].equals("cert") || args[1].equals("certificate"))) {
            String pem = CliSupport.client(home).provenanceCertificate();
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
            System.out.println(client.provenance(rest.get(0), rest.get(1)));
            return 0;
        }
        RepositoryClient.ProvenanceMaterial view = client.provenanceMaterial(rest.get(0), rest.get(1));
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
            RepositoryClient.TransparencyLog log = view.transparencyLog();
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
            client.setPolicy(defaultLifetime, maxLifetime);
            System.out.println("Set the credential lifetime policy.");
            return 0;
        }
        RepositoryClient.PolicyView view = client.policy();
        System.out.println("default lifetime: " + view.defaultLifetime());
        System.out.println("max lifetime:     " + CliSupport.orDash(view.maxLifetime()));
        return 0;
    }

    static int retroPlan(String[] args, Path home) throws Exception {
        boolean unknown = false;
        String repo = null;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--unknown")) {
                unknown = true;
            } else if (repo == null) {
                repo = args[i];
            }
        }
        if (repo == null) {
            throw new IllegalArgumentException("Usage: retro-plan <repo> [--unknown]");
        }
        RepositoryClient.RetroPlan plan = CliSupport.client(home).retroPlan(repo, unknown);
        if (plan == null) {
            System.out.println("License policy is not installed on this deployment.");
            return 0;
        }
        System.out.println("mode:  " + plan.mode());
        System.out.println("count: " + plan.count());
        for (RepositoryClient.RetroHeld held : plan.held()) {
            System.out.println("  " + held.coordinate() + ":" + held.version());
            if (held.reasons() != null) {
                held.reasons().forEach(reason -> System.out.println("    " + reason));
            }
        }
        return 0;
    }

    /**
     * The two ways a human answers a finding: a recorded verdict, and a waiver that suppresses it.
     *
     * <p>Both name the coordinate as well as the finding id, because an id is only unique within a coordinate -
     * the same advisory is a separate finding against every version it touches, and a review that named the id
     * alone would be ambiguous about which of them it settled.
     */
    /** Post a scanner's report - the file is the API's own request document, sent as it is, so the CLI adds
     *  nothing a CI job could get out of step with - and say what it did. */
    private static int report(String[] args, Path home) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException("Usage: findings report <repo> <file>");
        }
        RepositoryClient.ReportAnswer answer = CliSupport.client(home).reportFindings(args[2], Path.of(args[3]));
        if (answer == null) {
            System.out.println("The findings store is not installed on this deployment.");
            return 0;
        }
        System.out.println("Recorded " + answer.recorded() + " finding(s); the gate's verdict is " + answer.verdict()
                + (answer.held() ? ", and the version is withheld for review." : "."));
        for (String reason : answer.reasons()) {
            System.out.println("  " + reason);
        }
        return 0;
    }

    private static int verdict(String[] args, Path home) throws Exception {
        if (args[1].equals("review")) {
            if (args.length < 6) {
                throw new IllegalArgumentException(
                        "Usage: findings review <repo> <coordinate> <id> <verdict> [--note <note>]");
            }
            String note = null;
            for (int i = 6; i < args.length; i++) {
                if (args[i].equals("--note")) {
                    note = CliSupport.flag(args, ++i);
                } else {
                    throw new IllegalArgumentException("Unknown review flag '" + args[i] + "'");
                }
            }
            CliSupport.client(home).reviewFinding(args[2], args[3], args[4], args[5], note);
            System.out.println("Recorded '" + args[5] + "' on " + args[4] + " for " + args[3] + ".");
            return 0;
        }
        if (args.length > 2 && args[2].equals("revoke")) {
            if (args.length < 6) {
                throw new IllegalArgumentException("Usage: findings waiver revoke <repo> <coordinate> <id>");
            }
            CliSupport.client(home).revokeWaiver(args[3], args[4], args[5]);
            System.out.println("Revoked the waiver on " + args[5] + " for " + args[4] + ".");
            return 0;
        }
        if (args.length < 5) {
            throw new IllegalArgumentException(
                    "Usage: findings waiver <repo> <coordinate> <id> [--reason R] [--until I]");
        }
        String reason = null;
        String until = null;
        for (int i = 5; i < args.length; i++) {
            switch (args[i]) {
                case "--reason" -> reason = CliSupport.flag(args, ++i);
                case "--until" -> until = CliSupport.flag(args, ++i);
                default -> throw new IllegalArgumentException("Unknown waiver flag '" + args[i] + "'");
            }
        }
        CliSupport.client(home).waiveFinding(args[2], args[3], args[4], reason, until);
        System.out.println("Waived " + args[4] + " for " + args[3] + ".");
        return 0;
    }
}
