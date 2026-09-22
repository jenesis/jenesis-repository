package build.jenesis.repository.cli;

import module java.base;

/**
 * The verbs an operator reaches for when running the deployment rather than curating what is in it: the posture and
 * consistency reads, the log tail, the observability and SPI catalogues, the effective configuration, the outbound
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
        System.out.println(CliSupport.client(home).posture(tenant));
        return 0;
    }

    /** How long a requested walk takes to be picked up: the cadence a bare {@code --refresh} watches this at,
     *  taken from the thing itself rather than from a number that would be wrong for everything else. */
    private static final Duration WALK_PICKUP = Duration.ofSeconds(30);

    static int walks(String[] args, Path home) throws Exception {
        if (args.length == 2 && args[1].equals("run")) {
            RepositoryClient client = CliSupport.client(home);
            System.out.println(client.walksRun());
            if (!Refresh.on()) {
                return 0;
            }
            // Asked to watch: the request is recorded and every node picks it up within half a minute, so what
            // there is to watch is the standing request draining and the walk's own account arriving after it.
            return Refresh.until(WALK_PICKUP, () -> {
                String seen = client.walks();
                System.out.println(seen);
                return seen.contains("\"requests\":[]") || seen.contains("requests: none")
                        ? Refresh.Poll.State.done(0)
                        : Refresh.Poll.State.running();
            });
        }
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: walks [run]");
        }
        System.out.println(CliSupport.client(home).walks());
        return 0;
    }

    static int caches(String[] args, Path home) throws Exception {
        if (args.length == 2 && args[1].equals("clear")) {
            System.out.println(CliSupport.client(home).cachesClear());
            return 0;
        }
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: caches [clear]");
        }
        System.out.println(CliSupport.client(home).caches());
        return 0;
    }

    static int consistency(String[] args, Path home) throws Exception {
        System.out.println(CliSupport.client(home).consistency());
        return 0;
    }

    static int logs(String[] args, Path home) throws Exception {
        String level = null;
        Integer limit = null;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--level" -> level = CliSupport.flag(args, ++i);
                case "--limit" -> limit = Integer.valueOf(CliSupport.flag(args, ++i));
                default -> throw new IllegalArgumentException("Usage: logs [--level <level>] [--limit <n>]");
            }
        }
        System.out.println(CliSupport.client(home).logs(level, limit));
        return 0;
    }

    static int observability(String[] args, Path home) throws Exception {
        System.out.println(CliSupport.client(home).observability());
        return 0;
    }

    static int spi(String[] args, Path home) throws Exception {
        System.out.println(CliSupport.client(home).spi());
        return 0;
    }

    static int config(String[] args, Path home) throws Exception {
        System.out.println(CliSupport.client(home).config());
        return 0;
    }

    static int origin(String[] args, Path home) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: origin <repo> [path]");
        }
        System.out.println(CliSupport.client(home).origin(args[1], args.length > 2 ? args[2] : ""));
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
        System.out.println(CliSupport.client(home).attribution(args[1], coordinate, format));
        return 0;
    }

    static int hardening(String[] args, Path home) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: hardening <repo> [path]");
        }
        System.out.println(CliSupport.client(home).hardeningVerdict(args[1], args.length > 2 ? args[2] : null));
        return 0;
    }

    static int webhook(String[] args, Path home) throws Exception {
        if (args.length > 1 && args[1].equals("retry")) {
            if (args.length < 4) {
                throw new IllegalArgumentException("Usage: webhook retry <repo> <id>");
            }
            CliSupport.client(home).retryWebhook(args[2], args[3]);
            System.out.println("Queued webhook " + args[3] + " for redelivery.");
            return 0;
        }
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: webhook <repo> | webhook retry <repo> <id>");
        }
        System.out.println(CliSupport.client(home).webhooks(args[1]));
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
                System.out.println(CliSupport.client(home).redirectRecord(args[2], args[3], formats, scope, ttl));
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
                System.out.println(CliSupport.client(home).redirectCheck(args[2], expect));
            }
            default -> throw new IllegalArgumentException("Unknown redirect-dns action '" + args[1] + "'");
        }
        return 0;
    }

}
