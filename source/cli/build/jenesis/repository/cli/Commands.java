package build.jenesis.repository.cli;

import module java.base;

/**
 * Every command the CLI answers to, declared once.
 *
 * <p><b>Why a registry rather than a printed banner.</b> A usage string beside a separate verb map drifts the
 * moment either is edited alone - a verb with no line, a line for a verb that has been renamed, and nothing to say
 * so. Here the help <em>is</em> the registry: {@link Cli} renders it, so a
 * verb that exists is documented by construction and one that is documented exists.
 */
public final class Commands {

    private Commands() {
    }

    /**
     * One action under a noun.
     *
     * @param form    exactly what to type, with {@code <required>} and {@code [optional]} - the whole form, so a
     *                reader never has to assemble it from a noun heading and a fragment.
     * @param summary what it does, in one line.
     */
    public record Action(String form, String summary) {
    }

    /** One noun: a subject the repository has, with the actions available on it. */
    public record Noun(String name, String summary, List<Action> actions, CliCommand handler) {
    }

    /**
     * A heading in the help, so it reads as a map of the product rather than an alphabetical dump.
     *
     * <p>The headings are the console's own vocabulary, in the console's order: after {@code Session}, which is the
     * command line's alone, come the topics a repository's pages are listed under - Contents, Review, Risk,
     * Provenance, Lifecycle - and then the groups beside the repositories - Build cache, Access, Operations,
     * Settings. A reader who knows where a page is on one surface finds its command under the same heading on the
     * other, and a noun with no console page of its own is filed where its page would be.
     */
    public record Section(String title, List<Noun> nouns) {
    }

    private static Noun noun(String name, String summary, CliCommand handler, Action... actions) {
        return new Noun(name, summary, List.of(actions), handler);
    }

    private static Action act(String form, String summary) {
        return new Action(form, summary);
    }

    public static final List<Section> SECTIONS = List.of(
            new Section("Session", List.of(
                    noun("login", "store the repository URL and key under ~/.jenesis", AuthCommands::login,
                            act("login <url> [--key <key>]", "save the session used by every other command")),
                    noun("logout", "forget the stored session", AuthCommands::logout,
                            act("logout", "delete the stored session")),
                    noun("whoami", "show the current session", AuthCommands::whoami,
                            act("whoami", "the stored URL and whether a key is held")))),

            new Section("Contents", List.of(
                    noun("browse", "list what is published under a path", DiscoveryCommands::browse,
                            act("browse <repo> [prefix]", "the entries under a path"),
                            act("browse children <repo> [prefix] [--limit N] [--cursor T]",
                                    "one window of the children under a path, each a folder or an artifact with its "
                                            + "size, and the cursor to the next")),
                    noun("search", "find published coordinates", DiscoveryCommands::search,
                            act("search <repo> [query]", "coordinates whose name starts with the query, or matching "
                                    + "it anywhere where the repository's full-text search is on")),
                    noun("assets", "export the published-asset walk", DiscoveryCommands::assets,
                            act("assets <repo> [--limit N] [--cursor T] [--all]",
                                    "path, size and SHA-256 of every published asset")),
                    noun("deploy", "publish a file into a repository", AdminCommands::deploy,
                            act("deploy <repo> <path> <file> [--explode zip]",
                                    "deploy a file, or explode an archive entry by entry")),
                    noun("import", "import from another repository manager", AdminCommands::importRepo,
                            act("import <repo> --source S --url U --source-repo R [--format F]"
                                    + " [--user U --password P] [--resume JOB]", "start an import"),
                            act("import status <repo> <job>", "the state and counts of an import job")),
                    noun("staging", "the staging repositories", 
                            LifecycleCommands::staging,
                            act("staging <repo>", "the open and sealed staging ids"),
                            act("staging promote <repo> <id>", "promote a staged id into the release layout"),
                            act("staging drop <repo> <id>", "drop a staged id and its held blobs")),
                    noun("index", "the published-index status", 
                            LifecycleCommands::index,
                            act("index <repo>", "generation, chunks and record count")))),

            new Section("Review", List.of(
                    noun("quarantine", "what the compliance gate held for review",
                            ComplianceCommands::quarantine,
                            act("quarantine <repo>", "the review queue"),
                            act("quarantine release <repo> <path>", "release a held artifact into the layout"),
                            act("quarantine discard <repo> <path>", "discard a held artifact")),
                    noun("ai-review", "the findings a code audit proposed, waiting for a person to confirm or dismiss "
                                    + "them", ComplianceCommands::aiReview,
                            act("ai-review <repo>", "the proposed findings, as the findings ledger records them")))),

            new Section("Risk", List.of(
                    noun("vulnerabilities", "re-scan against the installed advisory feeds",
                            ComplianceCommands::vulnerabilities,
                            act("vulnerabilities <repo> [--reachability reachable|not-reachable|unknown]"
                                    + " [--applicability applies|not-applicable|unknown]",
                                    "the advisory verdicts, narrowed by call-graph and applicability facets")),
                    noun("findings", "the persisted findings ledger", ComplianceCommands::findings,
                            act("findings <repo> [--coordinate C] [--kind K] [--source S] [--category C]"
                                    + " [--severity S]", "the durable, attributed findings"),
                            act("findings review <repo> <ecosystem> <coordinate> <version> <source> <id>"
                                    + " <confirmed|dismissed> [--note N]",
                                    "confirm or dismiss an AI-produced finding, as the listing names it"),
                            act("findings waiver <repo> <ecosystem> <coordinate> <version> <source> <id> <until>"
                                    + " [--note N]", "accept an advisory's risk until an ISO-8601 instant, with a "
                                    + "justification"),
                            act("findings waiver revoke <repo> <ecosystem> <coordinate> <version> <source> <id>",
                                    "withdraw a waiver"),
                            act("findings report <repo> <file>", "hand a scanner's findings about a stored version to "
                                    + "the gate: recorded under the scanner's name, and withheld for review if the "
                                    + "gate would not admit them")),
                    noun("health", "the maintainer health of what a repository holds",
                            ComplianceCommands::health,
                            act("health <repo>", "the stored scores, lowest first where ranked"),
                            act("health refresh <repo>", "re-score every coordinate in the background, and answer "
                                    + "the scores as they stand")),
                    noun("enforcement-preview", "what enabling licence enforcement would newly hold",
                            ComplianceCommands::enforcementPreview,
                            act("enforcement-preview <repo> [--unknown]", "a dry run over what is already published")),
                    noun("licenses", "declared-license facets", 
                            ComplianceCommands::licenses,
                            act("licenses <repo>", "the per-category and per-SPDX-id counts")),
                    noun("hardening", "the proxy-hardening verdict for a path",
                            OperationsCommands::hardening,
                            act("hardening <repo> [path]", "what hardening would do with these bytes")))),

            new Section("Provenance", List.of(
                    noun("signers", "who signed the accepted versions, and everything each signed",
                            ComplianceCommands::signers,
                            act("signers <repo>", "the signers seen on accepted versions"),
                            act("signers <repo> <signer>", "the coordinates one signer signed - a key's reach before it is revoked")),
                    noun("signature", "what was made of a version's publisher signature",
                            ComplianceCommands::signature,
                            act("signature <repo> <path>", "the recorded outcome, signer, trust source, log entry and grade")),
                    noun("provenance", "the signed build attestation",
                            ComplianceCommands::provenance,
                            act("provenance <repo> <path> [--material]",
                                    "the attestation, with verification material"),
                            act("provenance key", "the signer's public key (PEM)"),
                            act("provenance cert", "the signer's certificate chain (PEM)")),
                    noun("dependents", "who depends on a coordinate", 
                            DiscoveryCommands::dependents,
                            act("dependents <repo> [coordinate]", "the blast radius of a coordinate"),
                            act("dependents <repo> --package NAME [--version V] [--cursor T]",
                                    "the versions whose manifest declares a dependency on a package, and whether "
                                            + "each requirement admits a version")),
                    noun("sbom", "a CycloneDX / SPDX bill of materials", 
                            DiscoveryCommands::sbom,
                            act("sbom <repo> [path] [--format cyclonedx|cyclonedx-xml|spdx] [--output F]",
                                    "a BOM for one coordinate, or for the whole repository")),
                    noun("origin", "where a served artifact came from", OperationsCommands::origin,
                            act("origin <repo> [path]", "the upstream or publish each path was served from")),
                    noun("attribution", "the attribution notices for what is published", 
                            OperationsCommands::attribution,
                            act("attribution <repo> [--coordinate C] [--format F]",
                                    "the assembled third-party attribution document")),
                    noun("vex", "VEX statements: what an advisory means for this product",
                            ScanCommands::vex,
                            act("vex", "the tenant's recorded statements"),
                            act("vex show <id>", "one statement"),
                            act("vex add <file>", "record a statement from an OpenVEX / CSAF document"),
                            act("vex remove <id>", "withdraw a statement"),
                            act("vex export [--output F]", "every statement as one OpenVEX document")))),

            new Section("Lifecycle", List.of(
                    noun("retention", "the retention policy", 
                            LifecycleCommands::retention,
                            act("retention <repo>", "the repository's policy"),
                            act("retention set <repo> [--keep-last N] [--max-age D] [--prerelease-expiry D]"
                                    + " [--not-downloaded-for D]", "set the policy")),
                    noun("cleanup", "run the retention sweep", 
                            LifecycleCommands::cleanup,
                            act("cleanup <repo>", "run the sweep"),
                            act("cleanup plan <repo>", "a dry run of what it would evict")),
                    noun("pins", "coordinates the sweep never reclaims", 
                            LifecycleCommands::pins,
                            act("pins <repo>", "the pinned coordinates"),
                            act("pins pin <repo> <ecosystem> <coordinate> <version>", "pin a coordinate"),
                            act("pins unpin <repo> <ecosystem> <coordinate> <version>", "lift a pin")),
                    noun("lifecycle", "deprecation and end-of-life marks",
                            LifecycleCommands::lifecycle,
                            act("lifecycle <repo>", "the marked coordinates"),
                            act("lifecycle mark <repo> <coordinate> <version> <state> [--message T]",
                                    "mark one version deprecated or yanked"),
                            act("lifecycle clear <repo> <coordinate> <version>", "remove a mark")),
                    noun("forwarding", "the publish-through outbox", 
                            LifecycleCommands::forwarding,
                            act("forwarding <repo>", "the outbox"),
                            act("forwarding retry <repo> <path>", "unpark a parked forward"),
                            act("forwarding internal <repo> <dest-tenant> <dest-repo>",
                                    "forward every accepted publish into another tenant's repository"),
                            act("forwarding internal remove <repo> <dest-tenant> <dest-repo>",
                                    "stop forwarding into it")),
                    noun("webhook", "the outbound webhook deliveries", 
                            OperationsCommands::webhook,
                            act("webhook <repo>", "recent deliveries and their state"),
                            act("webhook retry <repo> <id>", "redeliver a failed webhook")),
                    noun("export", "publish a whole repository to another one", 
                            AdminCommands::exportRepo,
                            act("export <repo> --url U [--token T | --user U --password P] [--resume JOB]",
                                    "start publishing every version to the repository a client would reach at U"),
                            act("export status <repo> <job>", "the state and counts of an export job")),
                    noun("forget-ecosystem", "drop one ecosystem's records from a repository",
                            LifecycleCommands::forgetEcosystem,
                            act("forget-ecosystem <repo> <ecosystem>", "forget every record of one ecosystem")))),

            new Section("Build cache", List.of(
                    noun("projects", "the build-cache projects on this deployment's volume",
                            CacheCommands::projects,
                            act("projects", "every project with its entry count, size and caps"),
                            act("projects show <project>", "one project's caps, counts and last pass"),
                            act("projects create <project> [--set <key>=<value>]...",
                                    "create a project, with its own settings if given; grant access from a "
                                            + "credential"),
                            act("projects evict <project> <size|ttl|clear>",
                                    "start a sweep in the background and report whether this call started it"),
                            act("projects recount <project>", "recount the project's entries and bytes"),
                            act("projects settings <project>", "a project's own settings, with what each inherits"),
                            act("projects settings <project> set <key> <value>", "set one of a project's settings"),
                            act("projects settings <project> clear <key>",
                                    "clear one of a project's settings, so it inherits again"),
                            act("projects delete <project> [--yes]", "delete a project, its entries and its settings "
                                    + "in the background, after typing 'delete <project>' - or --yes, for a script")),
                    noun("scans", "build scans: what a build ran, and what the cache saved it",
                            ScanCommands::scans,
                            act("scans", "the tenant's ingested build scans"),
                            act("scans show <id>", "one build scan"),
                            act("scans report <id>", "one build scan, rendered"),
                            act("scans ingest <file>", "ingest a build scan document"),
                            act("scans analytics", "the cache-savings view across build scans"),
                            act("scans analytics report", "the analytics as a downloadable report")),
                    noun("tests", "the test-selection and flakiness service",
                            ScanCommands::tests,
                            act("tests ingest <file>", "ingest a test run"),
                            act("tests show <id>", "one ingested run"),
                            act("tests flaky", "the tests seen to flake"),
                            act("tests select [--changed F]",
                                    "the tests worth running for a change")))),

            new Section("Access", List.of(
                    noun("credentials", "the tenant's credentials", AuthCommands::credentials,
                            act("credentials", "list the credentials"),
                            act("credentials mint [--label L]", "mint one (the key is shown once)"),
                            act("credentials revoke <id>", "revoke one"),
                            act("credentials grant <id> <scope> <tokens>", "grant tokens at a scope"),
                            act("credentials revoke-grant <id> <scope>", "remove a grant"),
                            act("credentials expiry <id> [<expiry>]", "set or clear an expiry"),
                            act("credentials rotate <id> [<overlap>]", "rotate, successor inheriting the grants"),
                            act("credentials allow-ips <id> [<cidrs>]", "set or clear a source-IP allowlist")),
                    noun("members", "the people this tenant has granted anything to", AuthCommands::members,
                            act("members", "list the people and what each holds directly"),
                            act("members grant <id> <scope> <tokens> [<expiry>]",
                                    "grant tokens at a scope to one person, optionally until a date"),
                            act("members revoke-grant <id> <scope>", "remove a grant"),
                            act("members remove <id>", "remove everything granted to them directly")),
                    noun("groups", "the tenant's groups, and who is in them", AuthCommands::groups,
                            act("groups", "list the groups and what each grants"),
                            act("groups members <name>", "who is in a group"),
                            act("groups grant <name> <scope> <tokens> [<expiry>]",
                                    "grant tokens at a scope to everyone in it, optionally until a date"),
                            act("groups revoke-grant <name> <scope>", "remove a grant"),
                            act("groups add <name> <id>", "put a provider-qualified id in the group"),
                            act("groups remove-member <name> <id>", "take one out"),
                            act("groups remove <name>", "delete the group and what it granted")),
                    noun("roles", "the tenant's named roles", AuthCommands::roles,
                            act("roles", "list the roles"),
                            act("roles set <name> <tokens>", "define a role"),
                            act("roles remove <name>", "remove a role")),
                    noun("trusts", "the OIDC trusts a CI job exchanges against", AuthCommands::trusts,
                            act("trusts", "list the trusts"),
                            act("trusts set <name> --issuer I --scope S --rights R", "define a trust"),
                            act("trusts remove <name>", "remove a trust")),
                    noun("policy", "the credential-lifetime policy", ComplianceCommands::policy,
                            act("policy", "the current policy"),
                            act("policy set [--default D] [--max D]", "set the default and maximum lifetimes")),
                    noun("audit", "the tenant's audit trail", 
                            AdminCommands::audit,
                            act("audit [--from I] [--to I] [--action A] [--csv]", "query the trail")),
                    noun("scim", "the token an identity provider presents to provision this tenant",
                            AuthCommands::scim,
                            act("scim token", "mint a token and print it once; the store keeps only its hash"),
                            act("scim token clear", "revoke the tenant's token")),
                    noun("keylogin", "the deployment's issued login keys", 
                            AuthCommands::keylogin,
                            act("keylogin list", "every issued login key"),
                            act("keylogin issue <principal> --tenant <name> [--login <display>] [--role <role>]",
                                    "issue a key and print it once; only its hash is stored"),
                            act("keylogin revoke <id>", "withdraw an issued key")))),

            new Section("Operations", List.of(
                    noun("metrics", "every metric, health state and background-task status this deployment reports",
                            OperationsCommands::metrics,
                            act("metrics", "the collected report, each entry with the description it was "
                                    + "registered with")),
                    noun("posture", "the security-posture report", 
                            OperationsCommands::posture,
                            act("posture [--tenant N]", "every advisor's verdict on this deployment")),
                    noun("caches", "the read caches over the store, on the node this tool is pointed at",
                            OperationsCommands::caches,
                            act("caches", "every cache on that node: ttl, hits, misses, entries"),
                            act("caches clear", "drop every entry on that node, and every node's authorization "
                                    + "cache - the listings are a pod, the grants are the fleet")),
                    noun("walks", "walks of the store: the schedule, what each costs, and asking for one",
                            OperationsCommands::walks,
                            act("walks", "every scheduled walk with the consumers that ride it and what its last "
                                    + "run cost, every installed consumer with what it repairs, and the standing "
                                    + "requests; the schedule itself is the 'walks' setting"),
                            act("walks run", "ask for a walk of the store now; every node picks it up within half a minute")),
                    noun("consistency", "agreement between the nodes of a cluster",
                            OperationsCommands::consistency,
                            act("consistency", "per-node fingerprints and any divergence")),
                    noun("logs", "the instance's most recent log entries",
                            OperationsCommands::logs,
                            act("logs [--level L] [--limit N]", "the tail of the in-memory log buffer")),
                    noun("redirect-dns", "the DNS-based redirect records", 
                            OperationsCommands::redirectDns,
                            act("redirect-dns record <coordinate> <url> [--formats F] [--scope S] [--ttl N]",
                                    "the TXT record to publish for a coordinate"),
                            act("redirect-dns check <coordinate> [--expect U]",
                                    "resolve the record and report what it says")))),

            new Section("Settings", List.of(
                    noun("settings", "the runtime settings", AdminCommands::settings,
                            act("settings [--tenant N]", "list the settings, or those a tenant sets"),
                            act("settings set <key> <value> [--tenant N]",
                                    "set a setting, deployment-wide or for one tenant"),
                            act("settings clear <key> [--tenant N]", "revert a setting to what it inherits"),
                            act("settings export [file] [--tenant N]", "dump the settings as a JSON bundle"),
                            act("settings import <file> [--tenant N]", "restore a bundle, validated first")),
                    noun("setup", "the first boot's wizard", AdminCommands::setup,
                            act("setup", "what a new deployment should decide: the starter credential, then each "
                                    + "essential setting with its documentation and current value"),
                            act("setup set <key> <value>", "decide one of them")),
                    noun("tenants", "the deployment's tenants (an operator key's)", 
                            AdminCommands::tenants,
                            act("tenants", "list the tenants"),
                            act("tenants create <name>", "create a tenant"),
                            act("tenants delete <name> [--yes]", "delete a tenant and everything it owns, after "
                                    + "typing 'delete <name>' - or --yes, for a script")),
                    noun("repos", "the repositories and their runtime definitions", AdminCommands::repos,
                            act("repos", "list the deployment's definitions, which a repository of each name routes by "
                                    + "unless it sets its own routing"),
                            act("repos create <name> <format> [description] [--set <key>=<value>]...",
                                    "create a repository holding one format, optionally described and with its "
                                            + "own settings - all written together, or nothing when one is refused"),
                            act("repos describe <name> <description>", "describe a repository; an empty one clears it"),
                            act("repos settings <name>", "a repository's own settings, with what each inherits"),
                            act("repos settings <name> set <key> <value>", "set one of a repository's settings"),
                            act("repos settings <name> clear <key>",
                                    "clear one of a repository's settings, so it inherits again"),
                            act("repos delete <name> [--yes]", "delete a repository and everything it holds, after "
                                    + "typing 'delete <name>' - or --yes, for a script"),
                            act("repos set <name> <definition>",
                                    "define a repository name deployment-wide in clauses (writable, fallback <url> "
                                            + "[nocache] [harden], fallback <repository>)"),
                            act("repos remove <name>", "remove a definition")),
                    noun("upstreams", "the per-format proxy upstreams", AdminCommands::upstreams,
                            act("upstreams [--tenant N]", "list the upstreams - the deployment's, or a tenant's own"),
                            act("upstreams set <format> <url> [--tenant N]", "set a format's upstream"),
                            act("upstreams remove <format> [--tenant N]", "remove a format's upstream"),
                            act("upstreams auth", "hosts holding a private-upstream credential"),
                            act("upstreams auth set <host> <bearer|basic|header|aws> ...", "store a credential; aws mints "
                                    + "ECR and CodeArtifact tokens from the server's own AWS identity"),
                            act("upstreams auth remove <host>", "remove a credential")),
                    noun("limits", "what the tenant's repositories may use together: a storage quota and a "
                                    + "request rate", AdminCommands::limits,
                            act("limits", "the storage quota with what is stored, and the request-rate ceiling"),
                            act("limits set quota <bytes>", "set the storage quota (0 falls back to the deployment's)"),
                            act("limits set rate <permits-per-minute>",
                                    "set the request-rate ceiling (0 falls back to the deployment's)")),
                    noun("capabilities", "what this deployment carries", AdminCommands::capabilities,
                            act("capabilities",
                                    "installed formats, import sources, modules, features and build tools")),
                    noun("spi", "the discovered service providers", OperationsCommands::spi,
                            act("spi", "every SPI and the providers installed against it")),
                    noun("config", "the effective server configuration", OperationsCommands::config,
                            act("config", "what the server resolved, and from where")),
                    noun("purge", "reclaim data of modules no longer installed", LifecycleCommands::purge,
                            act("purge", "report orphaned data (never deleted without asking)"),
                            act("purge <module> [--delete]", "dry-run one module's reclamation, or perform it")))));

    /** Every noun, by name - the dispatch map, derived from the same declaration the help renders. */
    public static final Map<String, Noun> BY_NAME = SECTIONS.stream()
            .flatMap(section -> section.nouns().stream())
            .collect(Collectors.toUnmodifiableMap(Noun::name, Function.identity()));
}
