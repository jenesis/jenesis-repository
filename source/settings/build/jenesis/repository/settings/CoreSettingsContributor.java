package build.jenesis.repository.settings;

import module java.base;
import build.jenesis.repository.scope.Scopes;

/**
 * The core's own {@link SettingsContributor}: the built-in runtime dials (the compliance verdict knobs and deny list,
 * the pull-through proxy toggle and immaturity hold, the maintenance lease, the deployment defaults), declared once
 * through the same SPI as every module. Defaults the server kernel also binds come from {@link CoreDefaults}. It is
 * {@link #neutral() neutral}, so its keys live in the {@link SettingsDocuments#NEUTRAL neutral} document and are scoped
 * by {@link SettingsScopes}. A new core dial is added here.
 */
public final class CoreSettingsContributor implements SettingsContributor {

    private static final List<String> SEVERITIES = List.of("NONE", "LOW", "MEDIUM", "HIGH", "CRITICAL");

    @Override
    public List<Setting> settings() {
        return List.of(
                new Setting("vulnerability-threshold", "Compliance", "Vulnerability threshold",
                        "The CVSS band at or above which a vulnerability brings the vulnerability action to bear; "
                                + "choosing none switches the check off.",
                        Setting.Kind.CHOICE, SEVERITIES, CoreDefaults.VULNERABILITY_THRESHOLD, true).essential(),
                new Setting("malware-action", "Compliance", "Malware action",
                        "Verdict for a package a feed marks malicious. A curated malicious-package record is a more "
                                + "certain signal than a severity score, so it should not refuse less than the "
                                + "vulnerability and deny-list dimensions do. It can refuse the package or hold it for "
                                + "review instead.",
                        Setting.Choice.VERDICTS, CoreDefaults.MALWARE_ACTION, true).essential()
                                ,
                new Setting("vulnerability-action", "Compliance", "Vulnerability action",
                        "Verdict for an artifact whose advisories reach the threshold above. Holding it for review, "
                                + "the default, stores the bytes and withholds them until a reviewer releases or "
                                + "discards them, so there is something to look at; refusing it stores nothing.",
                        Setting.Choice.VERDICTS, CoreDefaults.VULNERABILITY_ACTION, true).essential()
                                ,
                new Setting("vulnerability-risk-threshold", "Compliance", "Vulnerability risk threshold",
                        "The CVSS band from which a version's findings mark it as a risk, on its package's list of "
                                + "versions and on its own page. A finding below it is still listed with the "
                                + "repository's vulnerabilities but marks nothing. What is held is the vulnerability "
                                + "threshold's to decide.",
                        Setting.Kind.CHOICE, SEVERITIES, CoreDefaults.VULNERABILITY_RISK_THRESHOLD, true).standard(),
                new Setting("deny-list", "Compliance", "Deny list",
                        "Comma-separated coordinates an operator forbids; always refused.",
                        Setting.Kind.STRING, "", true).standard(),
                new Setting("deny-list-action", "Compliance", "Deny list action",
                        "Verdict for a coordinate the deny list names. Refusing it is the secure floor; holding such "
                                + "coordinates for review is the alternative.",
                        Setting.Choice.VERDICTS, CoreDefaults.DENY_LIST_ACTION, true).standard()
                                ,
                new Setting("proxy-enabled", "Proxy", "Pull-through proxy",
                        "Proxy reads that miss locally from the upstreams, caching and bridging them.",
                        Setting.Kind.BOOLEAN, CoreDefaults.PROXY_ENABLED, true).standard(),
                new Setting("immaturity-hold-days", "Proxy", "Immaturity hold",
                        "Quarantine proxied artifacts the upstream published within this many days; zero switches the "
                                + "hold off. A brand-new upstream version is held for review over the highest-risk "
                                + "window in which a typosquat or compromised release is usually yanked. Fail-open: it "
                                + "only bites on an upstream Last-Modified date.",
                        Setting.Kind.INTEGER, "2", true).essential(),
                new Setting("proxy-allow-internal", "Proxy", "Allow internal proxy targets",
                        "Permit proxy upstreams, and the download URLs an upstream document advertises, that are plain "
                                + "http or resolve to a loopback, private, link-local or cloud-metadata address. A "
                                + "proxy fetch carries the upstream credential and its result is cached and re-served, "
                                + "so cleartext exposes both on the path, and an internal address lets an upstream "
                                + "steer fetches into this network (SSRF). Enable it only for a trusted internal or "
                                + "plaintext mirror.",
                        Setting.Kind.BOOLEAN, "false", false).advanced(),
                new Setting("cleanup-lease", "Operations", "Maintenance lease",
                        "How long one node holds the background-maintenance lease; keep under the task intervals.",
                        Setting.Kind.DURATION, "PT10M", false).advanced(),
                new Setting("default-tenant", "Tenancy", "Default tenant",
                        "Tenant a request resolves to when its key carries none. Applies on the next restart, "
                                + "when the routing and the tenants directory both take it up.",
                        Setting.Kind.STRING, Scopes.DEFAULT_TENANT, false).standard(),
                new Setting("block-private-import-hosts", "Network", "Block private import hosts",
                        "Reject a migration URL - an import's source or an export's target - that is plaintext http or "
                                + "resolves to a loopback, link-local or private address. A migration runs server-side "
                                + "with a credential attached, so a plaintext URL exposes it on the path and a private "
                                + "one turns the migration against this deployment's own network (SSRF). Set it false "
                                + "to migrate from or to an internal or plaintext repository; it covers the API and "
                                + "the console alike.",
                        Setting.Kind.BOOLEAN, "true", false).advanced(),
                new Setting("trusted-proxies", "Network", "Trusted proxies",
                        "Comma-separated CIDRs of reverse proxies whose X-Forwarded-For, X-Forwarded-Proto and "
                                + "X-Forwarded-Host are believed.",
                        Setting.Kind.STRING, "", false).standard(),
                new Setting("public-url", "Network", "Public URL",
                        "The address clients reach this deployment at, for the absolute URLs generated indexes carry. "
                                + "Set it behind a front door that rewrites paths or sends no forwarded headers; "
                                + "unset, the request's own scheme and host are used, or a trusted proxy's forwarded "
                                + "ones.",
                        Setting.Kind.STRING, "", false).standard());
    }

    /** The core's dials are {@link SettingsContributor#neutral() neutral}. */
    @Override
    public boolean neutral() {
        return true;
    }

    /** The per-format upstreams the console stores, one key per format ({@code format-upstream.maven}). */
    @Override
    public Set<String> startupKeys() {
        return Set.of("jenrepo.format-upstream.*");
    }
}
