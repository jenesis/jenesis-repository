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

    private static final List<String> VERDICTS = List.of("ALLOW", "QUARANTINE", "REJECT");
    private static final List<String> SEVERITIES = List.of("NONE", "LOW", "MEDIUM", "HIGH", "CRITICAL");

    @Override
    public List<Setting> settings() {
        return List.of(
                new Setting("vulnerability-threshold", "Compliance", "Vulnerability threshold",
                        "The CVSS band at or above which a vulnerability brings the vulnerability action to bear; "
                                + "NONE disables the check.",
                        Setting.Kind.CHOICE, SEVERITIES, CoreDefaults.VULNERABILITY_THRESHOLD, true).essential(),
                new Setting("malware-action", "Compliance", "Malware action",
                        "Verdict for a package a feed marks malicious. A curated malicious-package record is a more "
                                + "certain signal than a severity score, so it should not refuse less than the "
                                + "vulnerability and deny-list dimensions do. REJECT refuses the package; QUARANTINE "
                                + "holds it for review instead.",
                        Setting.Kind.CHOICE, VERDICTS, CoreDefaults.MALWARE_ACTION, true).essential(),
                new Setting("vulnerability-action", "Compliance", "Vulnerability action",
                        "Verdict for an artifact whose advisories reach the threshold above. REJECT refuses the "
                                + "publish and stores nothing; QUARANTINE stores the bytes and withholds them until a "
                                + "reviewer releases or discards them.",
                        Setting.Kind.CHOICE, VERDICTS, CoreDefaults.VULNERABILITY_ACTION, true).essential(),
                new Setting("deny-list", "Compliance", "Deny list",
                        "Comma-separated coordinates an operator forbids; always refused.",
                        Setting.Kind.STRING, "", true).standard(),
                new Setting("deny-list-action", "Compliance", "Deny list action",
                        "Verdict for a coordinate the deny list names. REJECT, the secure floor, refuses it; "
                                + "QUARANTINE holds such coordinates for review instead.",
                        Setting.Kind.CHOICE, VERDICTS, CoreDefaults.DENY_LIST_ACTION, true).standard(),
                new Setting("proxy-enabled", "Proxy", "Pull-through proxy",
                        "Proxy reads that miss locally from the upstreams, caching and bridging them.",
                        Setting.Kind.BOOLEAN, CoreDefaults.PROXY_ENABLED, true).standard(),
                new Setting("immaturity-hold-days", "Proxy", "Immaturity hold",
                        "Quarantine proxied artifacts the upstream published within this many days; 0 disables. A "
                                + "brand-new upstream version is held for review over the highest-risk window in "
                                + "which a typosquat or compromised release is usually yanked. Fail-open: it only "
                                + "bites on an upstream Last-Modified date.",
                        Setting.Kind.INTEGER, "2", true).essential(),
                new Setting("proxy-allow-internal", "Proxy", "Allow internal proxy targets",
                        "Permit proxy upstreams, and the download URLs an upstream document advertises, that are "
                                + "plain http or resolve to a loopback, private, link-local or cloud-metadata "
                                + "address. A proxy fetch carries the deployment's per-host upstream credential and "
                                + "its result is cached and re-served, so a cleartext hop hands both to any observer "
                                + "and lets an active intermediary choose what this repository caches, while an "
                                + "internal one lets an upstream steer the fetch into this deployment's own network "
                                + "(SSRF). One dial for the whole deployment and every format; enable it only for a "
                                + "trusted internal or plaintext mirror.",
                        Setting.Kind.BOOLEAN, "false", false).advanced(),
                new Setting("cleanup-lease", "Operations", "Maintenance lease",
                        "How long one node holds the background-maintenance lease; keep under the task intervals.",
                        Setting.Kind.DURATION, "PT10M", false).advanced(),
                new Setting("default-tenant", "Tenancy", "Default tenant",
                        "Tenant a request resolves to when its key carries none. Applies on the next restart, "
                                + "when the routing and the tenants directory both take it up.",
                        Setting.Kind.STRING, Scopes.DEFAULT_TENANT, false).essential(),
                new Setting("block-private-import-hosts", "Network", "Block private import hosts",
                        "Reject a migration URL - an import's source or an export's target - that is plaintext http, "
                                + "or that resolves to a loopback, link-local or private address. A migration runs "
                                + "server-side with a credential attached, so a plaintext URL hands it to any "
                                + "observer on the path and a private one turns the migration into a request against "
                                + "this deployment's own network (SSRF). Enforced identically on the API and console "
                                + "legs; set it false to migrate from or to an internal or plaintext repository - the "
                                + "one dial, covering both, so neither can be opted out of alone.",
                        Setting.Kind.BOOLEAN, "true", false).advanced(),
                new Setting("trusted-proxies", "Network", "Trusted proxies",
                        "Comma-separated CIDRs of reverse proxies whose X-Forwarded-For, X-Forwarded-Proto and "
                                + "X-Forwarded-Host are believed.",
                        Setting.Kind.STRING, "", false).standard(),
                new Setting("public-url", "Network", "Public URL",
                        "The address clients reach this deployment at (https://repo.example.com), for the absolute "
                                + "URLs generated indexes carry. Set it behind a front door that rewrites paths or "
                                + "sends no forwarded headers; unset, the request's own scheme and host are used, or "
                                + "a trusted proxy's forwarded ones.",
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
