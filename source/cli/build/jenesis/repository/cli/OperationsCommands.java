package build.jenesis.repository.cli;

import module java.base;

/**
 * The verbs an operator reaches for when running the deployment rather than curating what is in it: the posture and
 * consistency reads, the log tail, the metrics report and the SPI catalogue, the effective configuration, the outbound
 * webhooks, the DNS redirect records, and the two provenance-adjacent reads that answer
 * "where did this come from" and "what would hardening do with it".
 *
 * <p>Each is a single read the admin console already had a screen for. They print the server's answer as it comes,
 * because these are documents an operator reads or a program stores, not tables to be re-laid-out by a client that
 * would then have to be taught every new field.
 */
final class OperationsCommands {

    private OperationsCommands() {
    }

    static int posture(String[] args, Path home) throws Exception {
        String tenant = null;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--tenant")) {
                tenant = CliSupport.flag(args, ++i);
            } else {
                throw new IllegalArgumentException("Usage: posture [--tenant <name>]");
            }
        }
        System.out.println(CliSupport.client(home).operations().posture(tenant));
        return 0;
    }

    /** How long a requested walk takes to be picked up: the cadence a bare {@code --refresh} watches this at,
     *  taken from the thing itself rather than from a number that would be wrong for everything else. */
    private static final Duration WALK_PICKUP = Duration.ofSeconds(30);

    /** Each refreshable signal source as the signal-refresh pass last recorded it. */
    static int signals(String[] args, Path home) throws Exception {
        OperationsClient.Signals signals = CliSupport.client(home).operations().signals();
        if (!"recorded".equals(signals.state())) {
            System.out.println("The signal-refresh pass has not recorded anything yet; it does on its next run.");
            return 0;
        }
        System.out.println("As of " + signals.recorded() + ".");
        if (signals.sources() == null || signals.sources().isEmpty()) {
            System.out.println("No refreshable signal source is switched on.");
            return 0;
        }
        for (OperationsClient.SignalSource source : signals.sources()) {
            String state = source.failure() != null ? "refresh failed: " + source.failure()
                    : source.authoritative() ? "serving" : "not yet complete";
            System.out.println(source.name() + "  last drawn " + (source.refreshed() == null ? "never"
                    : source.refreshed()) + "  " + state);
            for (OperationsClient.SignalCopy copy : source.copies() == null ? List.<OperationsClient.SignalCopy>of()
                    : source.copies()) {
                System.out.println("  " + copy.ecosystem() + ": " + (copy.built() == null ? "no copy yet"
                        : "built " + copy.built() + ", last drawn " + copy.drawn()));
            }
        }
        return 0;
    }

    /** How long a requested scanner-tools pass takes to be picked up: one scheduler tick of the node holding it. */
    private static final Duration SCANNERS_PICKUP = Duration.ofSeconds(10);

    /** Each configured scanner's tool as the scanner-tools pass last recorded it, or a refresh of them asked for. */
    static int scanners(String[] args, Path home) throws Exception {
        RepositoryClient client = CliSupport.client(home);
        if (args.length == 2 && args[1].equals("refresh")) {
            client.operations().scannersRefresh();
            if (!Refresh.on()) {
                System.out.println("A refresh of the scanner tools is requested; the node holding the pass picks it "
                        + "up within its next scheduler tick.");
                return 0;
            }
            return Refresh.until(SCANNERS_PICKUP, () -> {
                OperationsClient.Scanners seen = client.operations().scanners();
                if (seen.requested() != null) {
                    System.out.println("Requested at " + seen.requested() + "; waiting for the pass to run.");
                    return Refresh.Poll.State.running();
                }
                return Refresh.Poll.State.done(print(seen));
            });
        }
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: scanners [refresh]");
        }
        return print(client.operations().scanners());
    }

    /** {@code scanners} as a person reads it. */
    private static int print(OperationsClient.Scanners scanners) {
        if (!"recorded".equals(scanners.state())) {
            System.out.println("The scanner-tools pass has not recorded anything yet; it does on its next run.");
            return 0;
        }
        System.out.println("As of " + scanners.recorded() + ".");
        List<OperationsClient.ScannerTool> tools = scanners.tools() == null ? List.of() : scanners.tools();
        if (tools.isEmpty()) {
            System.out.println("No configured scanner runs on a tool this deployment reports on.");
            return 0;
        }
        for (OperationsClient.ScannerTool tool : tools) {
            List<String> reasons = tool.unfit() == null ? List.of() : tool.unfit();
            String state = tool.failure() != null ? "not reachable: " + tool.failure()
                    : !reasons.isEmpty() ? "unfit: " + String.join("; ", reasons) : "fit";
            System.out.println(tool.name() + "  " + (tool.version() == null ? "version unknown" : tool.version())
                    + " (" + tool.placement() + ")  " + state);
            for (OperationsClient.ScannerDatabase database : tool.databases() == null
                    ? List.<OperationsClient.ScannerDatabase>of() : tool.databases()) {
                System.out.println("  " + database.name() + " database"
                        + (database.build() == null ? "" : " " + database.build()) + ": "
                        + (database.built() == null ? "" : "built " + database.built() + ", ")
                        + (database.fetched() == null ? "never fetched" : "fetched " + database.fetched())
                        + (database.nextUpdate() == null ? "" : ", next due " + database.nextUpdate())
                        + (database.fetched() != null && !database.current() ? "  overdue" : ""));
            }
        }
        return 0;
    }

    static int walks(String[] args, Path home) throws Exception {
        if (args.length == 2 && args[1].equals("run")) {
            RepositoryClient client = CliSupport.client(home);
            System.out.println(client.operations().walksRun());
            if (!Refresh.on()) {
                return 0;
            }
            // Asked to watch: the request is recorded and every node picks it up within half a minute, so what
            // there is to watch is the standing request draining and the walk's own account arriving after it.
            return Refresh.until(WALK_PICKUP, () -> {
                String seen = client.operations().walks();
                System.out.println(seen);
                return seen.contains("\"requests\":[]") || seen.contains("requests: none")
                        ? Refresh.Poll.State.done(0)
                        : Refresh.Poll.State.running();
            });
        }
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: walks [run]");
        }
        System.out.println(CliSupport.client(home).operations().walks());
        return 0;
    }

    static int caches(String[] args, Path home) throws Exception {
        if (args.length == 2 && args[1].equals("clear")) {
            System.out.println(CliSupport.client(home).operations().cachesClear());
            return 0;
        }
        if (args.length == 2 && args[1].equals("flush")) {
            System.out.println(CliSupport.client(home).operations().cachesFlush());
            return 0;
        }
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: caches [clear|flush]");
        }
        System.out.println(CliSupport.client(home).operations().caches());
        return 0;
    }

    static int consistency(String[] args, Path home) throws Exception {
        System.out.println(CliSupport.client(home).operations().consistency());
        return 0;
    }

    static int logs(String[] args, Path home) throws Exception {
        String level = null;
        String text = null;
        Long since = null;
        String tenant = null;
        Integer limit = null;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--level" -> level = CliSupport.flag(args, ++i);
                case "--q" -> text = CliSupport.flag(args, ++i);
                case "--since" -> since = Long.valueOf(CliSupport.flag(args, ++i));
                case "--tenant" -> tenant = CliSupport.flag(args, ++i);
                case "--limit" -> limit = Integer.valueOf(CliSupport.flag(args, ++i));
                default -> throw new IllegalArgumentException("Usage: logs [--level L] [--q TEXT] [--since SEQ] "
                        + "[--tenant T] [--limit N]");
            }
        }
        System.out.println(CliSupport.client(home).operations().logs(level, text, since, tenant, limit));
        return 0;
    }

    static int metrics(String[] args, Path home) throws Exception {
        System.out.println(CliSupport.client(home).operations().observability());
        return 0;
    }

    static int spi(String[] args, Path home) throws Exception {
        System.out.println(CliSupport.client(home).settings().spi());
        return 0;
    }

    static int config(String[] args, Path home) throws Exception {
        System.out.println(CliSupport.client(home).settings().config());
        return 0;
    }

    static int origin(String[] args, Path home) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: origin <repo> [path]");
        }
        System.out.println(CliSupport.client(home).provenance().origin(args[1], args.length > 2 ? args[2] : ""));
        return 0;
    }

    static int attribution(String[] args, Path home) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: attribution <repo> [--coordinate C] [--format F]");
        }
        String coordinate = null;
        String format = null;
        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--coordinate" -> coordinate = CliSupport.flag(args, ++i);
                case "--format" -> format = CliSupport.flag(args, ++i);
                default -> throw new IllegalArgumentException("Unknown attribution flag '" + args[i] + "'");
            }
        }
        System.out.println(CliSupport.client(home).provenance().attribution(args[1], coordinate, format));
        return 0;
    }

    static int hardening(String[] args, Path home) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: hardening <repo> [path]");
        }
        System.out.println(CliSupport.client(home).risk().hardeningVerdict(args[1], args.length > 2 ? args[2] : null));
        return 0;
    }

    static int webhook(String[] args, Path home) throws Exception {
        if (args.length > 1 && args[1].equals("retry")) {
            if (args.length < 4) {
                throw new IllegalArgumentException("Usage: webhook retry <repo> <id>");
            }
            CliSupport.client(home).lifecycle().retryWebhook(args[2], args[3]);
            System.out.println("Queued webhook " + args[3] + " for redelivery.");
            return 0;
        }
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: webhook <repo> [--cursor C] | webhook retry <repo> <id>");
        }
        String deliveries = CliSupport.client(home).lifecycle().webhooks(args[1], CliSupport.cursorOf(args, 2));
        System.out.println(deliveries);
        CliSupport.more(CliSupport.nextOf(deliveries));
        return 0;
    }

    static int redirectDns(String[] args, Path home) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: redirect-dns record <coordinate> <url> [--formats F] "
                    + "[--scope S] [--ttl N] | redirect-dns check <coordinate> [--expect U]");
        }
        switch (args[1]) {
            case "record" -> {
                if (args.length < 4) {
                    throw new IllegalArgumentException(
                            "Usage: redirect-dns record <coordinate> <url> [--formats F] [--scope S] [--ttl N]");
                }
                String formats = null;
                String scope = null;
                Long ttl = null;
                for (int i = 4; i < args.length; i++) {
                    switch (args[i]) {
                        case "--formats" -> formats = CliSupport.flag(args, ++i);
                        case "--scope" -> scope = CliSupport.flag(args, ++i);
                        case "--ttl" -> ttl = Long.valueOf(CliSupport.flag(args, ++i));
                        default -> throw new IllegalArgumentException("Unknown record flag '" + args[i] + "'");
                    }
                }
                System.out.println(CliSupport.client(home).operations()
                        .redirectRecord(args[2], args[3], formats, scope, ttl));
            }
            case "check" -> {
                if (args.length < 3) {
                    throw new IllegalArgumentException("Usage: redirect-dns check <coordinate> [--expect <url>]");
                }
                String expect = null;
                for (int i = 3; i < args.length; i++) {
                    if (args[i].equals("--expect")) {
                        expect = CliSupport.flag(args, ++i);
                    } else {
                        throw new IllegalArgumentException("Unknown check flag '" + args[i] + "'");
                    }
                }
                System.out.println(CliSupport.client(home).operations().redirectCheck(args[2], expect));
            }
            default -> throw new IllegalArgumentException("Unknown redirect-dns action '" + args[1] + "'");
        }
        return 0;
    }

}
