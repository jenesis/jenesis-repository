package build.jenesis.repository.cli;

import module java.base;

/**
 * The deployment-administration verbs: {@code settings} reads and writes the runtime settings, {@code quota} /
 * {@code rate-limit} / {@code audit} the tenant ceilings and trail, {@code capabilities} reports what the server
 * carries, {@code repos} / {@code upstreams} define runtime repositories and format proxies, {@code deploy}
 * publishes a file (or explodes an archive per entry), and {@code import} walks an incumbent's repository.
 */
final class AdminCommands {

    private AdminCommands() {
    }

    /** The first-run setup guide: the same steps the console's setup screen walks, read from {@code /api/setup},
     *  each setting with its own documentation, default and current value; {@code setup set} writes through the
     *  same {@code /api/settings} endpoint every other setting write takes. */
    static int setup(String[] args, Path home) throws Exception {
        RepositoryClient client = CliSupport.client(home);
        if (args.length >= 2 && args[1].equals("set")) {
            if (args.length < 4) {
                throw new IllegalArgumentException("Usage: setup set <key> <value>");
            }
            client.setSetting(args[2], args[3]);
            System.out.println("Set " + args[2] + ".");
            return 0;
        }
        for (RepositoryClient.SetupStep step : client.setup().steps()) {
            System.out.println(step.title());
            System.out.println("  " + step.why());
            for (RepositoryClient.Setting setting : step.settings()) {
                String state = setting.pinned() ? "pinned by " + setting.pinnedBy()
                        : setting.overridden() ? "override" : "default";
                String display = "SECRET".equals(setting.kind())
                        ? (setting.overridden() || setting.pinned() ? "(set)" : "(unset)")
                        : setting.value() == null || setting.value().isEmpty() ? "(empty)" : setting.value();
                System.out.printf("  %-26s %-30s %s%s%n", setting.key(), display, state,
                        setting.appliesImmediately() ? "" : " (restart)");
                if (setting.description() != null && !setting.description().isBlank()) {
                    System.out.println("      " + setting.description());
                }
            }
            System.out.println();
        }
        System.out.println("Decide one with: setup set <key> <value>");
        return 0;
    }

    static int settings(String[] args, Path home) throws Exception {
        // A `--tenant <name>` flag (anywhere in the line) scopes the whole verb to one tenant's overridable slice - the
        // same per-tenant view the console shows; without it the verb reads and writes the deployment-wide settings.
        String tenant = null;
        List<String> rest = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--tenant") && i + 1 < args.length) {
                tenant = args[++i];
            } else {
                rest.add(args[i]);
            }
        }
        String scope = tenant == null ? "deployment" : "tenant " + tenant;
        RepositoryClient client = CliSupport.client(home);
        if (rest.isEmpty()) {
            for (RepositoryClient.Setting setting : tenant == null ? client.settings() : client.settings(tenant)) {
                // A pinned key is fixed above the store (env var, -D, command line or a config file), so its stored
                // value is inert and a `settings set` would be refused; name what pins it instead of "override".
                String state = setting.pinned() ? "pinned by " + setting.pinnedBy()
                        : setting.overridden() ? "override" : "default";
                // A SECRET value is never read back (the server returns null), so show only whether it is set - never
                // its plaintext, matching the console's masking.
                String display = "SECRET".equals(setting.kind())
                        ? (setting.overridden() || setting.pinned() ? "(set)" : "(unset)")
                        : setting.value() == null || setting.value().isEmpty() ? "(empty)" : setting.value();
                System.out.printf("%-26s %-30s %s%s%n", setting.key(), display, state,
                        setting.appliesImmediately() ? "" : " (restart)");
            }
            return 0;
        }
        switch (rest.get(0)) {
            case "set" -> {
                if (rest.size() < 3) {
                    throw new IllegalArgumentException("Usage: settings set <key> <value> [--tenant <name>]");
                }
                if (tenant == null) {
                    client.setSetting(rest.get(1), rest.get(2));
                } else {
                    client.setSetting(tenant, rest.get(1), rest.get(2));
                }
                System.out.println("Set " + rest.get(1) + " (" + scope + ").");
            }
            case "clear" -> {
                if (rest.size() < 2) {
                    throw new IllegalArgumentException("Usage: settings clear <key> [--tenant <name>]");
                }
                if (tenant == null) {
                    client.clearSetting(rest.get(1));
                } else {
                    client.clearSetting(tenant, rest.get(1));
                }
                System.out.println("Cleared " + rest.get(1) + " (" + scope + "); reverted to its default.");
            }
            case "export" -> {
                // The bundle prints to stdout (redirect to a file); a path argument writes it there instead.
                String bundle = tenant == null ? client.exportSettings() : client.exportSettings(tenant);
                if (rest.size() > 1) {
                    Files.writeString(Path.of(rest.get(1)), bundle);
                    System.out.println("Exported the " + scope + " settings to " + rest.get(1) + ".");
                } else {
                    System.out.print(bundle);
                }
            }
            case "import" -> {
                if (rest.size() < 2) {
                    throw new IllegalArgumentException("Usage: settings import <file> [--tenant <name>]");
                }
                String bundle = Files.readString(Path.of(rest.get(1)));
                if (tenant == null) {
                    client.importSettings(bundle);
                } else {
                    client.importSettings(tenant, bundle);
                }
                System.out.println("Imported the " + scope + " settings from " + rest.get(1) + ".");
            }
            default -> throw new IllegalArgumentException("Unknown settings command '" + rest.get(0) + "'");
        }
        return 0;
    }

    static int quota(String[] args, Path home) throws Exception {
        RepositoryClient client = CliSupport.client(home);
        if (args.length > 1 && args[1].equals("set")) {
            if (args.length < 3) {
                throw new IllegalArgumentException("Usage: quota set <bytes>  (0 clears the quota)");
            }
            client.setQuota(Long.parseLong(args[2]));
            System.out.println("Set the storage quota.");
            return 0;
        }
        RepositoryClient.QuotaView view = client.quota();
        System.out.println("limit: " + (view.maxBytes() == 0 ? "unlimited" : view.maxBytes() + " bytes"));
        System.out.println("used:  " + view.usedBytes() + " bytes");
        return 0;
    }

    static int rateLimit(String[] args, Path home) throws Exception {
        RepositoryClient client = CliSupport.client(home);
        if (args.length > 1 && args[1].equals("set")) {
            if (args.length < 3) {
                throw new IllegalArgumentException("Usage: rate-limit set <permits-per-minute>  (0 uses the default)");
            }
            if (!client.setRateLimit(Long.parseLong(args[2]))) {
                System.out.println("Rate limiting is not installed on this deployment.");
                return 1;
            }
            System.out.println("Set the rate limit.");
            return 0;
        }
        RepositoryClient.RateLimitView view = client.rateLimit();
        if (view == null) {
            System.out.println("Rate limiting is not installed on this deployment.");
            return 0;
        }
        System.out.println(view.permitsPerMinute() == 0
                ? "No tenant ceiling (falls back to the deployment default)."
                : view.permitsPerMinute() + " permits per minute.");
        return 0;
    }

    static int audit(String[] args, Path home) throws Exception {
        String from = null;
        String to = null;
        String action = null;
        boolean csv = false;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--from" -> from = CliSupport.flag(args, ++i);
                case "--to" -> to = CliSupport.flag(args, ++i);
                case "--action" -> action = CliSupport.flag(args, ++i);
                case "--csv" -> csv = true;
                default -> throw new IllegalArgumentException("Unknown audit flag '" + args[i] + "'");
            }
        }
        RepositoryClient client = CliSupport.client(home);
        if (csv) {
            String body = client.auditCsv(from, to, action);
            if (body == null) {
                System.out.println("Audit is not installed on this deployment.");
                return 0;
            }
            System.out.print(body);
            return 0;
        }
        List<RepositoryClient.AuditEvent> events = client.audit(from, to, action);
        if (events == null) {
            System.out.println("Audit is not installed on this deployment.");
            return 0;
        }
        if (events.isEmpty()) {
            System.out.println("No audit events.");
            return 0;
        }
        for (RepositoryClient.AuditEvent event : events) {
            System.out.printf("%s  %-20s %-24s %s%n", event.at(), event.actor(), event.action(), event.target());
        }
        return 0;
    }

    /** Print what the server's deployment carries, so an operator discovers the installed formats, import sources
     *  and features without reading the deployment's module list. */
    static int capabilities(String[] args, Path home) throws Exception {
        RepositoryClient.Capabilities capabilities = CliSupport.client(home).capabilities();
        if (capabilities == null) {
            System.out.println("The server does not report capabilities (an older version).");
            return 1;
        }
        System.out.println("formats:        " + names(capabilities.formats().stream()
                .map(RepositoryClient.Format::name).toList()));
        System.out.println("import sources: " + names(capabilities.importSources().stream()
                .map(RepositoryClient.ImportSource::name).toList()));
        System.out.println("report columns: " + names(capabilities.signals().stream()
                .map(RepositoryClient.Signal::name).toList()));
        RepositoryClient.Features features = capabilities.features();
        System.out.println("advisories:     " + installed(features.advisories(), features.advisoriesEnabled()));
        System.out.println("staging:        " + (features.staging() ? "installed" : "not installed"));
        System.out.println("retention:      " + (features.retention() ? "installed" : "not installed"));
        System.out.println("scheduled scan: " + (capabilities.scan() ? "installed" : "not installed"));
        System.out.println("provenance:     " + installed(capabilities.provenance(), features.provenanceEnabled()));
        System.out.println("audit:          " + (capabilities.audit() ? "installed" : "not installed"));
        System.out.println("upstream:       " + (features.upstream()
                ? "installed (proxying and imports available)" : "not installed (local-only)"));
        System.out.println("token-exchange: " + (features.tokenExchange() ? "installed" : "not installed"));
        System.out.println("rate-limit:     " + (features.rateLimit() ? "installed" : "not installed"));
        System.out.println("dependents:     " + (capabilities.dependents() ? "installed" : "not installed"));
        System.out.println("search:         " + (capabilities.search() ? "installed" : "not installed"));
        if (capabilities.modules() != null && !capabilities.modules().isEmpty()) {
            System.out.println("modules:");
            for (RepositoryClient.Module module : capabilities.modules()) {
                String state = !module.installed() ? "not installed"
                        : module.enableKey() == null ? "always on"
                        : (module.enabled() ? "enabled" : "disabled") + (module.live() ? "" : " (restart to change)");
                System.out.printf("  %-40s %s%n", module.module(), state);
            }
        }
        return 0;
    }

    static int deploy(String[] args, Path home) throws Exception {
        String explode = null;
        List<String> rest = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--explode")) {
                explode = CliSupport.flag(args, ++i);
            } else {
                rest.add(args[i]);
            }
        }
        if (rest.size() < 3) {
            throw new IllegalArgumentException("Usage: deploy <repo> <layout-path> <file> [--explode zip]");
        }
        String repo = rest.get(0);
        String path = rest.get(1);
        String file = rest.get(2);
        if (!path.startsWith("/")) {
            StringBuilder message = new StringBuilder(
                    "The layout path must be explicit and start with its format segment, e.g. /<format>/...");
            try {
                RepositoryClient.Capabilities capabilities = CliSupport.client(home).capabilities();
                if (capabilities != null && !capabilities.formats().isEmpty()) {
                    message.append(" Installed formats: ").append(String.join(", ",
                            capabilities.formats().stream().map(RepositoryClient.Format::name).toList())).append('.');
                }
            } catch (IOException | InterruptedException _) {
                // the hint is best-effort; the error stands on its own
            }
            throw new IllegalArgumentException(message.toString());
        }
        // Stream the file straight from disk into the request body (never Files.readAllBytes it into heap - the
        // artifact may be large, and the streaming principle forbids buffering a whole artifact on an upload path).
        Path source = Path.of(file);
        if (explode != null) {
            if (!explode.equalsIgnoreCase("zip")) {
                throw new IllegalArgumentException("Only --explode zip is supported.");
            }
            return deployExplode(CliSupport.client(home), repo, path, source);
        }
        int status = CliSupport.client(home).deploy(repo, path, source);
        return switch (status) {
            case 201 -> {
                System.out.println("Published " + path + ".");
                yield 0;
            }
            case 202 -> {
                System.out.println("Quarantined " + path + " for review.");
                yield 0;
            }
            case 422 -> {
                System.out.println("Rejected " + path + " by the compliance gate.");
                yield 1;
            }
            case 405 -> {
                System.out.println("Repository '" + repo + "' does not accept writes.");
                yield 1;
            }
            default -> {
                System.out.println("Deploy failed (HTTP " + status + ").");
                yield 1;
            }
        };
    }

    /** A batch explode: the archive is walked server-side and each entry published through the compliance gate. Prints
     *  the per-entry manifest and exits non-zero if any member was gate-rejected (so a pipeline sees the refusal). When
     *  batch upload is off on the deployment the header is inert and the archive is stored verbatim as one artifact. */
    private static int deployExplode(RepositoryClient client, String repo, String path, Path archive)
            throws Exception {
        RepositoryClient.ExplodeResult result = client.deployExplode(repo, path, archive);
        RepositoryClient.ExplodeManifest manifest = result.manifest();
        if (manifest == null) {
            return switch (result.status()) {
                case 200, 201, 202 -> {
                    System.out.println("Batch upload is off; stored the archive verbatim as one artifact (HTTP "
                            + result.status() + ").");
                    yield 0;
                }
                case 405 -> {
                    System.out.println("Repository '" + repo + "' does not accept writes.");
                    yield 1;
                }
                default -> {
                    System.out.println("Explode failed (HTTP " + result.status() + ").");
                    yield 1;
                }
            };
        }
        for (RepositoryClient.ExplodeEntry entry : manifest.entries()) {
            String reason = entry.reason() == null || entry.reason().isEmpty() ? "" : " (" + entry.reason() + ")";
            System.out.printf("%-10s %s%s%n", entry.status(), entry.path(), reason);
        }
        if (manifest.capped()) {
            System.out.println("(capped at the entry ceiling; the rest of the archive was not read)");
        }
        if (manifest.error() != null) {
            System.out.println("archive error: " + manifest.error());
            return 1;
        }
        boolean rejected = manifest.entries().stream().anyMatch(entry -> "rejected".equals(entry.status()));
        return rejected ? 1 : 0;
    }

    /** How often an import job's counters move: the cadence a bare {@code --refresh} watches it at. */
    private static final Duration IMPORT_PROGRESS = Duration.ofSeconds(5);

    /**
     * Print one import job's state, and say whether there is any point asking again.
     *
     * <p>One rendering for the one-shot read and the watched one, so the two cannot describe the same job
     * differently - which is the whole reason {@code --json} is not a second renderer either.
     */
    private static Refresh.Poll.State importState(RepositoryClient client, String repo, String job)
            throws Exception {
        RepositoryClient.ImportStatus status = client.importStatus(repo, job);
        if (status == null) {
            System.out.println("No import job '" + job + "' in " + repo + ".");
            return Refresh.Poll.State.done(1);
        }
            System.out.println("state:    " + status.state());
            System.out.println("imported: " + status.imported());
            System.out.println("skipped:  " + status.skipped());
            if (status.skippedFormats() != null && !status.skippedFormats().isEmpty()) {
                System.out.println("no importer for: " + String.join(", ", status.skippedFormats()));
            }
            // Printed even when zero rows were imported - especially then. A completed job reading
            // "imported: 0, skipped: 0" with no further line is exactly how a wholly refused source used to look
            // identical to an empty one.
            if (status.droppedTotal() > 0) {
                System.out.println("dropped:  " + status.droppedTotal() + " row(s) the source offered were refused");
                status.dropped().forEach((reason, count) ->
                        System.out.println("            " + count + " " + reason
                                + ("UNSAFE_PATH".equals(reason) ? "  <- paths that would have written out of scope"
                                                                : "")));
            }
            if (status.asset() != null && !status.asset().isEmpty()) {
                System.out.println("at asset: " + status.asset());
            }
            if (status.error() != null && !status.error().isEmpty()) {
                System.out.println("error:    " + status.error());
            }
        // A job that is neither running nor queued has finished, one way or the other; an error in it is
        // still a finished job, and the exit code says which.
        boolean running = "running".equalsIgnoreCase(status.state()) || "queued".equalsIgnoreCase(status.state());
        if (running) {
            return Refresh.Poll.State.running();
        }
        return Refresh.Poll.State.done(status.error() == null || status.error().isEmpty() ? 0 : 1);
    }

    static int importRepo(String[] args, Path home) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: import <repo> --source S --url U --source-repo R "
                    + "[--format F] [--user U --password P] [--resume JOB] | import status <repo> <job>");
        }
        RepositoryClient client = CliSupport.client(home);
        if (args[1].equals("status")) {
            if (args.length < 4) {
                throw new IllegalArgumentException("Usage: import status <repo> <job>");
            }
            if (Refresh.on()) {
                // An import advances its counters as it goes, so this is the surface where watching pays: the
                // cadence is the job's own, and the loop ends when the job does, with the exit code that state
                // deserves rather than a zero for "I looked".
                return Refresh.until(IMPORT_PROGRESS,
                        () -> importState(client, args[2], args[3]));
            }
            return importState(client, args[2], args[3]).code();
        }
        String repo = args[1];
        String source = null;
        String url = null;
        String sourceRepo = null;
        String format = null;
        String user = null;
        String password = null;
        String resume = null;
        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--source" -> source = CliSupport.flag(args, ++i);
                case "--url" -> url = CliSupport.flag(args, ++i);
                case "--source-repo" -> sourceRepo = CliSupport.flag(args, ++i);
                case "--format" -> format = CliSupport.flag(args, ++i);
                case "--user" -> user = CliSupport.flag(args, ++i);
                case "--password" -> password = CliSupport.flag(args, ++i);
                case "--resume" -> resume = CliSupport.flag(args, ++i);
                default -> throw new IllegalArgumentException("Unknown import flag '" + args[i] + "'");
            }
        }
        if (source == null || url == null || sourceRepo == null) {
            throw new IllegalArgumentException(
                    "import needs --source, --url and --source-repo (the incumbent's repository to walk).");
        }
        RepositoryClient.ImportResult result =
                client.startImport(repo, source, url, sourceRepo, format, user, password, resume);
        return switch (result.status()) {
            case 202 -> {
                System.out.println("Import started; job " + result.job()
                        + ". Poll it with: import status " + repo + " " + result.job());
                yield 0;
            }
            case 405 -> {
                System.out.println("Repository '" + repo + "' does not accept writes (a proxy target is read-only).");
                yield 1;
            }
            case 501 -> {
                System.out.println("Upstream fetching is not installed on this deployment.");
                yield 1;
            }
            case 400 -> {
                System.out.println("No import source '" + source + "' is installed, or the request is incomplete.");
                yield 1;
            }
            default -> {
                System.out.println("Import failed (HTTP " + result.status() + ").");
                yield 1;
            }
        };
    }

    static int repos(String[] args, Path home) throws Exception {
        RepositoryClient client = CliSupport.client(home);
        if (args.length == 1) {
            List<RepositoryClient.NamedValue> repos = client.repositories();
            for (RepositoryClient.NamedValue repo : repos) {
                System.out.printf("%-20s %s%n", repo.name(), repo.value());
            }
            if (repos.isEmpty()) {
                System.out.println("No runtime repository definitions.");
            }
            return 0;
        }
        switch (args[1]) {
            case "set" -> {
                if (args.length < 4) {
                    throw new IllegalArgumentException("Usage: repos set <name> <definition>");
                }
                String reach = client.setRepository(args[2], args[3]);
                System.out.println("Saved repository " + args[2] + ".");
                if (reach != null) {
                    System.out.println(reach);
                }
            }
            case "remove" -> {
                if (args.length < 3) {
                    throw new IllegalArgumentException("Usage: repos remove <name>");
                }
                client.removeRepository(args[2]);
                System.out.println("Removed repository " + args[2] + ".");
            }
            default -> throw new IllegalArgumentException("Unknown repos command '" + args[1] + "'");
        }
        return 0;
    }

    static int upstreams(String[] args, Path home) throws Exception {
        if (args.length > 1 && args[1].equals("auth")) {
            return upstreamAuth(args, home);
        }
        RepositoryClient client = CliSupport.client(home);
        if (args.length == 1) {
            List<RepositoryClient.NamedValue> upstreams = client.upstreams();
            for (RepositoryClient.NamedValue upstream : upstreams) {
                System.out.printf("%-12s %s%n", upstream.name(), upstream.value());
            }
            if (upstreams.isEmpty()) {
                System.out.println("No runtime format upstreams.");
            }
            return 0;
        }
        switch (args[1]) {
            case "set" -> {
                if (args.length < 4) {
                    throw new IllegalArgumentException("Usage: upstreams set <format> <url>");
                }
                client.setUpstream(args[2], args[3]);
                System.out.println("Saved upstream for " + args[2] + ".");
            }
            case "remove" -> {
                if (args.length < 3) {
                    throw new IllegalArgumentException("Usage: upstreams remove <format>");
                }
                client.removeUpstream(args[2]);
                System.out.println("Removed upstream for " + args[2] + ".");
            }
            default -> throw new IllegalArgumentException("Unknown upstreams command '" + args[1] + "'");
        }
        return 0;
    }

    private static int upstreamAuth(String[] args, Path home) throws Exception {
        RepositoryClient client = CliSupport.client(home);
        if (args.length == 2) {
            List<String> hosts = client.upstreamCredentialHosts();
            hosts.forEach(System.out::println);
            if (hosts.isEmpty()) {
                System.out.println("No upstream credentials.");
            }
            return 0;
        }
        switch (args[2]) {
            case "set" -> {
                if (args.length < 5) {
                    throw new IllegalArgumentException(
                            "Usage: upstreams auth set <host> bearer [<token>] | basic <username> [<password>]");
                }
                String host = args[3];
                String scheme = args[4];
                if (scheme.equalsIgnoreCase("bearer")) {
                    String token = args.length > 5 ? args[5] : prompt("Upstream token: ");
                    client.setUpstreamCredential(host, "bearer", null, null, token, null);
                } else if (scheme.equalsIgnoreCase("basic")) {
                    if (args.length < 6) {
                        throw new IllegalArgumentException(
                                "Usage: upstreams auth set <host> basic <username> [<password>]");
                    }
                    String username = args[5];
                    String password = args.length > 6 ? args[6] : prompt("Upstream password: ");
                    client.setUpstreamCredential(host, "basic", username, password, null, null);
                } else if (scheme.equalsIgnoreCase("header")) {
                    if (args.length < 6) {
                        throw new IllegalArgumentException(
                                "Usage: upstreams auth set <host> header <name> [<value>]");
                    }
                    String name = args[5];
                    String value = args.length > 6 ? args[6] : prompt("Header value: ");
                    client.setUpstreamCredential(host, "header", null, null, value, name);
                } else {
                    throw new IllegalArgumentException("Unknown scheme '" + scheme + "'; use bearer, basic or header");
                }
                System.out.println("Stored an upstream credential for " + host + ".");
            }
            case "remove" -> {
                if (args.length < 4) {
                    throw new IllegalArgumentException("Usage: upstreams auth remove <host>");
                }
                client.removeUpstreamCredential(args[3]);
                System.out.println("Removed the upstream credential for " + args[3] + ".");
            }
            default -> throw new IllegalArgumentException("Unknown upstreams auth command '" + args[2] + "'");
        }
        return 0;
    }

    private static String prompt(String label) {
        Console console = System.console();
        if (console == null) {
            throw new IllegalArgumentException("No console to read a secret; pass it as an argument instead.");
        }
        char[] entered = console.readPassword(label);
        return entered == null ? "" : new String(entered);
    }

    private static String names(List<String> names) {
        return names.isEmpty() ? "(none)" : String.join(", ", names);
    }

    private static String installed(boolean installed, boolean enabled) {
        return !installed ? "not installed" : enabled ? "installed, enabled" : "installed, not enabled";
    }
}
