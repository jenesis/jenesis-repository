package build.jenesis.repository.cli;

import module java.base;

/**
 * The deployment-administration verbs: {@code settings} reads and writes the runtime settings, {@code limits} /
 * {@code audit} the tenant ceilings and trail, {@code capabilities} reports what the server
 * carries, {@code repos} / {@code upstreams} define runtime repositories and format proxies, {@code deploy}
 * publishes a file (or explodes an archive per entry), and {@code import} walks an incumbent's repository.
 */
final class AdminCommands {

    /** Said after a write the server answered with {@code Jenesis-Applies-On: restart}. */
    private static final String RESTART = " It takes effect when the server next restarts.";


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
            boolean restart = client.settings().setSetting(args[2], args[3]);
            System.out.println("Set " + args[2] + "." + (restart ? RESTART : ""));
            return 0;
        }
        for (SettingsClient.SetupStep step : client.settings().setup().steps()) {
            System.out.println(step.title());
            if (step.why() != null && !step.why().isBlank()) {
                System.out.println("  " + step.why());
            }
            for (SettingsClient.Setting setting : step.settings()) {
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
        // `--tenant <name>` (anywhere in the line) scopes the verb to that tenant's settings; without it the verb reads
        // and writes the deployment-wide ones. A repository's and a project's own settings are their nouns' -
        // `repos settings` and `projects settings` - as they are their screens' in the console.
        String tenant = null;
        List<String> rest = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--tenant") && i + 1 < args.length) {
                tenant = args[++i];
            } else {
                rest.add(args[i]);
            }
        }
        String scope = tenant != null ? "tenant " + tenant : "deployment";
        RepositoryClient client = CliSupport.client(home);
        if (rest.isEmpty()) {
            list(tenant != null ? client.settings().settings(tenant) : client.settings().settings());
            return 0;
        }
        String usage = " [--tenant <name>]";
        switch (rest.get(0)) {
            case "set" -> {
                if (rest.size() < 3) {
                    throw new IllegalArgumentException("Usage: settings set <key> <value>" + usage);
                }
                boolean restart = tenant != null
                        ? client.settings().setSetting(tenant, rest.get(1), rest.get(2))
                        : client.settings().setSetting(rest.get(1), rest.get(2));
                System.out.println("Set " + rest.get(1) + " (" + scope + ")." + (restart ? RESTART : ""));
            }
            case "clear" -> {
                if (rest.size() < 2) {
                    throw new IllegalArgumentException("Usage: settings clear <key>" + usage);
                }
                boolean restart = tenant != null
                        ? client.settings().clearSetting(tenant, rest.get(1))
                        : client.settings().clearSetting(rest.get(1));
                System.out.println("Cleared " + rest.get(1) + " (" + scope + "); it inherits the wider value again."
                        + (restart ? RESTART : ""));
            }
            case "export", "import" -> exchange(client, rest, tenant, scope);
            default -> throw new IllegalArgumentException("Unknown settings command '" + rest.get(0) + "'");
        }
        return 0;
    }

    /** Print a list of settings rows, one a line: the key, the value in force, and where it comes from. */
    static void list(List<SettingsClient.Setting> listed) {
        for (SettingsClient.Setting setting : listed) {
            // A pinned key is fixed above the store (env var, -D, command line or a config file), so its stored value
            // is inert and a `set` would be refused; name what pins it instead of "override".
            String state = setting.pinned() ? "pinned by " + setting.pinnedBy()
                    : setting.overridden() ? "override" : "default";
            // A SECRET value is never read back (the server returns null), so show only whether it is set - never its
            // plaintext, matching the console's masking.
            String display = "SECRET".equals(setting.kind())
                    ? (setting.overridden() || setting.pinned() ? "(set)" : "(unset)")
                    : setting.value() == null || setting.value().isEmpty() ? "(empty)" : setting.value();
            System.out.printf("%-26s %-30s %s%s%s%n", setting.key(), display, state,
                    setting.appliesImmediately() ? "" : " (restart)", setting.advanced() ? " (advanced)" : "");
        }
    }

    /**
     * An object's own settings - {@code <noun> settings <name>} lists them with what each inherits,
     * {@code ... set <key> <value>} sets one and {@code ... clear <key>} clears one - for a repository or a project,
     * whose settings level {@code level} reads and writes.
     */
    static int objectSettings(String noun, String[] args, ObjectSettings level) throws Exception {
        String usage = "Usage: " + noun + " settings <name> [set <key> <value> | clear <key>]";
        if (args.length < 3) {
            throw new IllegalArgumentException(usage);
        }
        String name = args[2];
        if (args.length == 3) {
            list(level.list(name));
            return 0;
        }
        switch (args[3]) {
            case "set" -> {
                if (args.length < 6) {
                    throw new IllegalArgumentException(usage);
                }
                level.set(name, args[4], args[5]);
                System.out.println("Set " + args[4] + " (" + noun + " " + name + ").");
            }
            case "clear" -> {
                if (args.length < 5) {
                    throw new IllegalArgumentException(usage);
                }
                level.clear(name, args[4]);
                System.out.println("Cleared " + args[4] + " (" + noun + " " + name
                        + "); it inherits the wider value again.");
            }
            default -> throw new IllegalArgumentException(usage);
        }
        return 0;
    }

    /** How one kind of object's settings are read and written: a repository's, or a project's. */
    interface ObjectSettings {

        List<SettingsClient.Setting> list(String name) throws IOException, InterruptedException;

        void set(String name, String key, String value) throws IOException, InterruptedException;

        void clear(String name, String key) throws IOException, InterruptedException;
    }

    private static void exchange(RepositoryClient client, List<String> rest, String tenant, String scope)
            throws Exception {
        switch (rest.get(0)) {
            case "export" -> {
                // The bundle prints to stdout (redirect to a file); a path argument writes it there instead.
                String bundle = tenant == null
                        ? client.settings().exportSettings() : client.settings().exportSettings(tenant);
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
                    client.settings().importSettings(bundle);
                } else {
                    client.settings().importSettings(tenant, bundle);
                }
                System.out.println("Imported the " + scope + " settings from " + rest.get(1) + ".");
            }
            default -> throw new IllegalArgumentException("Unknown settings command '" + rest.get(0) + "'");
        }
    }

    /**
     * {@code limits}: what the tenant's repositories may use together, read and set as the console's Limits page
     * shows them - the storage quota beside what is stored, then the request-rate ceiling. They are two API
     * documents, so under {@code --json} the answer is the array of both.
     */
    static int limits(String[] args, Path home) throws Exception {
        RepositoryClient client = CliSupport.client(home);
        if (args.length > 1 && args[1].equals("set")) {
            if (args.length != 4 || !(args[2].equals("quota") || args[2].equals("rate"))) {
                throw new IllegalArgumentException("Usage: limits set quota <bytes>  (0 falls back to the "
                        + "deployment's)\n"
                        + "       limits set rate <permits-per-minute>  (0 falls back to the deployment's)");
            }
            if (args[2].equals("quota")) {
                client.settings().setQuota(Long.parseLong(args[3]));
                System.out.println("Set the storage quota.");
                return 0;
            }
            if (!client.settings().setRateLimit(Long.parseLong(args[3]))) {
                System.out.println("Rate limiting is not installed on this deployment.");
                return 1;
            }
            System.out.println("Set the rate limit.");
            return 0;
        }
        if (args.length > 1) {
            throw new IllegalArgumentException("Unknown limits action: " + args[1]);
        }
        SettingsClient.QuotaView quota = client.settings().quota();
        System.out.println("quota: " + (quota.maxBytes() == 0 ? "unlimited" : quota.maxBytes() + " bytes"));
        System.out.println("used:  " + quota.usedBytes() + " bytes");
        SettingsClient.RateLimitView rate = client.settings().rateLimit();
        System.out.println("rate:  " + (rate == null ? "rate limiting is not installed on this deployment"
                : rate.permitsPerMinute() == 0 ? "no tenant ceiling (falls back to the deployment default)"
                : rate.permitsPerMinute() + " permits per minute"));
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
            String body = client.access().auditCsv(from, to, action);
            if (body == null) {
                System.out.println("Audit is not installed on this deployment.");
                return 0;
            }
            System.out.print(body);
            return 0;
        }
        List<AccessClient.AuditEvent> events = client.access().audit(from, to, action);
        if (events == null) {
            System.out.println("Audit is not installed on this deployment.");
            return 0;
        }
        if (events.isEmpty()) {
            System.out.println("No audit events.");
            return 0;
        }
        for (AccessClient.AuditEvent event : events) {
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
                .map(RiskClient.Signal::name).toList()));
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
            throw new IllegalArgumentException("Usage: deploy <repo> <path> <file> [--explode zip]");
        }
        String repo = rest.get(0);
        String path = rest.get(1);
        String file = rest.get(2);
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("The path must start with '/': it is the path within the repository, "
                    + "what a client appends to the repository's URL - a Maven repository's /maven/..., an npm "
                    + "repository's /<package>/-/<file>.");
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
        int status = CliSupport.client(home).contents().deploy(repo, path, source);
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
        ContentsClient.ExplodeResult result = client.contents().deployExplode(repo, path, archive);
        ContentsClient.ExplodeManifest manifest = result.manifest();
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
        for (ContentsClient.ExplodeEntry entry : manifest.entries()) {
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
        ContentsClient.ImportStatus status = client.contents().importStatus(repo, job);
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
            // "imported: 0, skipped: 0" with no further line would make a wholly refused source look identical to an
            // empty one.
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
        ContentsClient.ImportResult result =
                client.contents().startImport(repo, source, url, sourceRepo, format, user, password, resume);
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

    /** How often a watched export reprints: its counters move per version, as an import's do. */
    private static final Duration EXPORT_PROGRESS = Duration.ofSeconds(5);

    static int exportRepo(String[] args, Path home) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: export <repo> --url U [--token T | --user U --password P] "
                    + "[--resume JOB] | export status <repo> <job>");
        }
        RepositoryClient client = CliSupport.client(home);
        if (args[1].equals("status")) {
            if (args.length < 4) {
                throw new IllegalArgumentException("Usage: export status <repo> <job>");
            }
            if (Refresh.on()) {
                return Refresh.until(EXPORT_PROGRESS, () -> exportState(client, args[2], args[3]));
            }
            return exportState(client, args[2], args[3]).code();
        }
        String repo = args[1];
        String url = null;
        String token = null;
        String user = null;
        String password = null;
        String resume = null;
        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--url" -> url = CliSupport.flag(args, ++i);
                case "--token" -> token = CliSupport.flag(args, ++i);
                case "--user" -> user = CliSupport.flag(args, ++i);
                case "--password" -> password = CliSupport.flag(args, ++i);
                case "--resume" -> resume = CliSupport.flag(args, ++i);
                default -> throw new IllegalArgumentException("Unknown export flag '" + args[i] + "'");
            }
        }
        if (url == null) {
            throw new IllegalArgumentException("export needs --url: the URL the format's client would be pointed at.");
        }
        LifecycleClient.ExportResult result = client.lifecycle().startExport(repo, url, token, user, password, resume);
        if (result.status() == 202) {
            System.out.println("Export started; job " + result.job()
                    + ". Poll it with: export status " + repo + " " + result.job());
            return 0;
        }
        System.out.println("Export refused (HTTP " + result.status() + ")"
                + (result.reason() == null || result.reason().isBlank() ? "." : ": " + result.reason().strip()));
        return 1;
    }

    /** Print one export job's state, and say whether there is any point asking again. */
    private static Refresh.Poll.State exportState(RepositoryClient client, String repo, String job) throws Exception {
        LifecycleClient.ExportStatus status = client.lifecycle().exportStatus(repo, job);
        if (status == null) {
            System.out.println("No export job '" + job + "' in " + repo + ".");
            return Refresh.Poll.State.done(1);
        }
        System.out.println("state:     " + status.state());
        System.out.println("target:    " + status.target());
        System.out.println("published: " + status.published());
        System.out.println("present:   " + status.present());
        System.out.println("withheld:  " + status.withheld());
        if (status.reached() != null && !status.reached().isEmpty()) {
            System.out.println("reached:   " + status.reached());
        }
        if (status.error() != null && !status.error().isEmpty()) {
            System.out.println("error:     " + status.error());
        }
        if ("running".equalsIgnoreCase(status.state())) {
            return Refresh.Poll.State.running();
        }
        return Refresh.Poll.State.done(status.error() == null || status.error().isEmpty() ? 0 : 1);
    }

    static int tenants(String[] args, Path home) throws Exception {
        RepositoryClient client = CliSupport.client(home);
        if (args.length == 1) {
            client.settings().tenants().forEach(System.out::println);
            return 0;
        }
        if (args.length < 3) {
            throw new IllegalArgumentException("Usage: tenants create <name> | tenants delete <name> [--yes]");
        }
        String name = args[2];
        switch (args[1]) {
            case "create" -> System.out.println(client.settings().createTenant(name)
                    ? "Created tenant " + name + "."
                    : "Tenant " + name + " already exists.");
            case "delete" -> {
                if (!Arrays.asList(args).subList(3, args.length).contains("--yes") && !confirmed(name,
                        "Deleting tenant " + name + " removes everything it owns: its repositories and their "
                                + "artifacts, its credentials, its audit trail and its members. "
                                + "This cannot be undone.")) {
                    System.out.println("Nothing was deleted.");
                    return 1;
                }
                client.settings().deleteTenant(name);
                System.out.println("Deleted tenant " + name + ".");
            }
            default -> throw new IllegalArgumentException("Unknown tenants action: " + args[1]);
        }
        return 0;
    }

    /**
     * The console's deletion dialog on a terminal: what is lost and that it cannot be undone, then the name typed out.
     * With no terminal to confirm on the caller is told to pass {@code --yes}, which is how a script says it means it.
     */
    /** Whether the reader typed {@code delete <name>} after the warning; refuses outright with no terminal, which is
     *  where a script passes {@code --yes} instead. Every deleting verb asks through this one. */
    static boolean confirmed(String name, String warning) {
        Console console = System.console();
        if (console == null) {
            throw new IllegalArgumentException(warning + " With no terminal to confirm on, pass --yes.");
        }
        console.printf("%s%n", warning);
        String typed = console.readLine("Type 'delete %s' to confirm: ", name);
        return typed != null && typed.trim().equals("delete " + name);
    }

    /** A verb's line with any {@code --tenant <name>} taken out, and the tenant it named - which scopes a routing verb
     *  to that tenant's own definitions or upstreams, the ones that route its repositories over the deployment's. */
    private record Scoped(String[] args, String tenant) {

        static Scoped of(String[] args) {
            String tenant = null;
            List<String> rest = new ArrayList<>();
            for (int i = 0; i < args.length; i++) {
                if (args[i].equals("--tenant") && i + 1 < args.length) {
                    tenant = args[++i];
                } else {
                    rest.add(args[i]);
                }
            }
            return new Scoped(rest.toArray(String[]::new), tenant);
        }

        /** Refuse {@code --tenant} on a verb it does not scope, rather than dropping it silently. */
        void unscoped(String usage) {
            if (tenant != null) {
                throw new IllegalArgumentException("--tenant does not apply here. Usage: " + usage);
            }
        }
    }

    static int repos(String[] line, Path home) throws Exception {
        Scoped scoped = Scoped.of(line);
        String[] args = scoped.args();
        String tenant = scoped.tenant();
        RepositoryClient client = CliSupport.client(home);
        if (args.length == 1) {
            // The deployment's definitions: a tenant has none, its repositories route themselves (settings --repository).
            scoped.unscoped("repos");
            List<SettingsClient.NamedValue> repos = client.settings().repositories(tenant);
            for (SettingsClient.NamedValue repo : repos) {
                System.out.printf("%-20s %s%n", repo.name(), repo.value());
            }
            if (repos.isEmpty()) {
                System.out.println("No runtime repository definitions.");
            }
            return 0;
        }
        switch (args[1]) {
            case "settings" -> {
                scoped.unscoped("repos settings <name> [set <key> <value> | clear <key>]");
                SettingsClient settings = client.settings();
                return objectSettings("repos", args, new ObjectSettings() {
                    @Override
                    public List<SettingsClient.Setting> list(String name) throws IOException, InterruptedException {
                        return settings.repositorySettings(name);
                    }

                    @Override
                    public void set(String name, String key, String value) throws IOException, InterruptedException {
                        settings.setRepositorySetting(name, key, value);
                    }

                    @Override
                    public void clear(String name, String key) throws IOException, InterruptedException {
                        settings.clearRepositorySetting(name, key);
                    }
                });
            }
            case "create" -> {
                String usage = "repos create <name> <format> [description] [--set <key>=<value>]...";
                scoped.unscoped(usage);
                List<String> rest = new ArrayList<>(Arrays.asList(args));
                Map<String, String> settings = CliSupport.sets(rest);
                if (rest.size() < 4) {
                    throw new IllegalArgumentException("Usage: " + usage);
                }
                String description = rest.size() > 4 ? String.join(" ", rest.subList(4, rest.size())) : null;
                System.out.println(client.settings().createRepository(rest.get(2), rest.get(3), description, settings)
                        ? "Created " + args[3] + " repository " + args[2] + "."
                        : "Repository " + args[2] + " already holds " + args[3] + ".");
            }
            case "describe" -> {
                scoped.unscoped("repos describe <name> <description>");
                if (args.length < 3) {
                    throw new IllegalArgumentException("Usage: repos describe <name> <description>");
                }
                client.settings().describeRepository(args[2],
                    String.join(" ", Arrays.copyOfRange(args, 3, args.length)));
                System.out.println("Described repository " + args[2] + ".");
            }
            case "delete" -> {
                scoped.unscoped("repos delete <name> [--yes]");
                if (args.length < 3) {
                    throw new IllegalArgumentException("Usage: repos delete <name> [--yes]");
                }
                String name = args[2];
                if (!Arrays.asList(args).subList(3, args.length).contains("--yes") && !confirmed(name,
                        "Deleting repository " + name + " removes everything it holds: every artifact, index, staged "
                                + "upload and pin, and what it is defined as. This cannot be undone.")) {
                    System.out.println("Nothing was deleted.");
                    return 1;
                }
                System.out.println(client.settings().deleteRepository(name));
            }
            case "set" -> {
                scoped.unscoped("repos set <name> <definition>");
                if (args.length < 4) {
                    throw new IllegalArgumentException("Usage: repos set <name> <definition>");
                }
                client.settings().setRepository(tenant, args[2], args[3]);
                System.out.println("Saved repository " + args[2] + ".");
            }
            case "remove" -> {
                scoped.unscoped("repos remove <name>");
                if (args.length < 3) {
                    throw new IllegalArgumentException("Usage: repos remove <name>");
                }
                client.settings().removeRepository(tenant, args[2]);
                System.out.println("Removed repository " + args[2] + ".");
            }
            default -> throw new IllegalArgumentException("Unknown repos command '" + args[1] + "'");
        }
        return 0;
    }

    static int upstreams(String[] line, Path home) throws Exception {
        Scoped scoped = Scoped.of(line);
        String[] args = scoped.args();
        String tenant = scoped.tenant();
        if (args.length > 1 && args[1].equals("auth")) {
            // A credential is keyed by the host it is sent to, whichever tenant's upstream names that host.
            scoped.unscoped("upstreams auth [set|remove] ...");
            return upstreamAuth(args, home);
        }
        RepositoryClient client = CliSupport.client(home);
        if (args.length == 1) {
            List<SettingsClient.NamedValue> upstreams = client.settings().upstreams(tenant);
            for (SettingsClient.NamedValue upstream : upstreams) {
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
                    throw new IllegalArgumentException("Usage: upstreams set <format> <url> [--tenant N]");
                }
                client.settings().setUpstream(tenant, args[2], args[3]);
                System.out.println("Saved upstream for " + args[2] + ".");
            }
            case "remove" -> {
                if (args.length < 3) {
                    throw new IllegalArgumentException("Usage: upstreams remove <format> [--tenant N]");
                }
                client.settings().removeUpstream(tenant, args[2]);
                System.out.println("Removed upstream for " + args[2] + ".");
            }
            default -> throw new IllegalArgumentException("Unknown upstreams command '" + args[1] + "'");
        }
        return 0;
    }

    private static int upstreamAuth(String[] args, Path home) throws Exception {
        RepositoryClient client = CliSupport.client(home);
        if (args.length == 2) {
            List<String> hosts = client.settings().upstreamCredentialHosts();
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
                            "Usage: upstreams auth set <host> bearer [<token>] | basic <username> [<password>]"
                                    + " | header <name> [<value>] | aws");
                }
                String host = args[3];
                String scheme = args[4];
                if (scheme.equalsIgnoreCase("bearer")) {
                    String token = args.length > 5 ? args[5] : prompt("Upstream token: ");
                    client.settings().setUpstreamCredential(host, "bearer", null, null, token, null);
                } else if (scheme.equalsIgnoreCase("basic")) {
                    if (args.length < 6) {
                        throw new IllegalArgumentException(
                                "Usage: upstreams auth set <host> basic <username> [<password>]");
                    }
                    String username = args[5];
                    String password = args.length > 6 ? args[6] : prompt("Upstream password: ");
                    client.settings().setUpstreamCredential(host, "basic", username, password, null, null);
                } else if (scheme.equalsIgnoreCase("header")) {
                    if (args.length < 6) {
                        throw new IllegalArgumentException(
                                "Usage: upstreams auth set <host> header <name> [<value>]");
                    }
                    String name = args[5];
                    String value = args.length > 6 ? args[6] : prompt("Header value: ");
                    client.settings().setUpstreamCredential(host, "header", null, null, value, name);
                } else if (scheme.equalsIgnoreCase("aws")) {
                    // No secret to type: the server mints the token from its own AWS identity for this host.
                    client.settings().setUpstreamCredential(host, "aws", null, null, null, null);
                } else {
                    throw new IllegalArgumentException(
                            "Unknown scheme '" + scheme + "'; use bearer, basic, header or aws");
                }
                System.out.println("Stored an upstream credential for " + host + ".");
            }
            case "remove" -> {
                if (args.length < 4) {
                    throw new IllegalArgumentException("Usage: upstreams auth remove <host>");
                }
                client.settings().removeUpstreamCredential(args[3]);
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
