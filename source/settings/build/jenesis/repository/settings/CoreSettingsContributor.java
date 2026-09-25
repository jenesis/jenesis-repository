package build.jenesis.repository.settings;

import module java.base;
import build.jenesis.repository.scope.Scopes;

/**
 * The neutral core's own {@link SettingsContributor} - the product's built-in runtime dials (the compliance verdict
 * knobs and deny list, the pull-through proxy toggle and immaturity hold, the maintenance lease and the deployment
 * defaults), dogfooding the same SPI 28 plugin modules already use rather than being inlined by hand in every
 * administration surface. Before this, the catalogue was duplicated byte-for-byte in the {@code /api/settings} adapter
 * and the console's {@code SettingsAdmin}; now both collapse to {@link SettingsContributor#all()} and the core is
 * described once, here.
 *
 * <p>The defaults are the product defaults - the same values a pristine {@code RepositoryProperties} carries - held as
 * constants so this contributor needs nothing but {@code java.base}, like every other contract in this module. It is
 * marked {@link #neutral() neutral}: its keys belong to the {@link SettingsDocuments#NEUTRAL neutral} document and to no
 * plugin module, and their tenant/global scope stays classified by {@link SettingsScopes}, so the deduplication changes
 * where the catalogue is authored without moving a stored value, adding a module row, or reclassifying a scope. A new
 * core dial is added here, not in a call site.
 */
public final class CoreSettingsContributor implements SettingsContributor {

    private static final List<String> VERDICTS = List.of("ALLOW", "QUARANTINE", "REJECT");
    private static final List<String> SEVERITIES = List.of("NONE", "LOW", "MEDIUM", "HIGH", "CRITICAL");

    @Override
    public List<Setting> settings() {
        return List.of(
                new Setting("vulnerability-threshold", "Compliance", "Vulnerability threshold",
                        "Reject vulnerabilities at or above this CVSS band; NONE disables the check. Defaults to "
                                + "CRITICAL (the secure floor); an operator sets NONE to opt out.",
                        Setting.Kind.CHOICE, SEVERITIES, CoreDefaults.VULNERABILITY_THRESHOLD, true),
                new Setting("malware-action", "Compliance", "Malware action",
                        "Verdict for a package the feed marks malicious. Defaults to REJECT, as the vulnerability "
                                + "and deny-list dimensions do: a curated malicious-package record is a more certain "
                                + "signal than a severity score, so it should not refuse less. An operator softens "
                                + "it to QUARANTINE to hold such a package for review instead.",
                        Setting.Kind.CHOICE, VERDICTS, CoreDefaults.MALWARE_ACTION, true),
                new Setting("vulnerability-action", "Compliance", "Vulnerability action",
                        "Verdict for an artifact whose advisories reach the threshold above. Defaults to REJECT "
                                + "(the secure floor); an operator softens it to QUARANTINE to review such artifacts "
                                + "rather than refuse them outright, which is what the malicious-package dimension "
                                + "has always allowed. REJECT refuses the publish and stores nothing; QUARANTINE "
                                + "stores the bytes and withholds them until a reviewer releases or discards them.",
                        Setting.Kind.CHOICE, VERDICTS, CoreDefaults.VULNERABILITY_ACTION, true),
                new Setting("deny-list", "Compliance", "Deny list",
                        "Comma-separated coordinates an operator forbids; always refused.",
                        Setting.Kind.STRING, "", true),
                new Setting("deny-list-action", "Compliance", "Deny list action",
                        "Verdict for a coordinate the deny list names. Defaults to REJECT (the secure floor); an "
                                + "operator softens it to QUARANTINE to hold such coordinates for review.",
                        Setting.Kind.CHOICE, VERDICTS, CoreDefaults.DENY_LIST_ACTION, true),
                new Setting("proxy-enabled", "Proxy", "Pull-through proxy",
                        "Proxy reads that miss locally from the upstreams, caching and bridging them.",
                        Setting.Kind.BOOLEAN, CoreDefaults.PROXY_ENABLED, true),
                new Setting("immaturity-hold-days", "Proxy", "Immaturity hold",
                        "Quarantine proxied artifacts the upstream published within this many days; 0 disables. "
                                + "Defaults to 2 (the secure floor): a brand-new upstream version is held for review "
                                + "over the highest-risk window in which a typosquat or compromised release is usually "
                                + "yanked. Fail-open (only bites on an upstream Last-Modified date); an operator raises "
                                + "it or sets 0 to disable.",
                        Setting.Kind.INTEGER, "2", true),
                new Setting("proxy-allow-internal", "Proxy", "Allow internal proxy targets",
                        "Permit proxy upstreams, and the download URLs an upstream document advertises, that are "
                                + "plain http or resolve to a loopback, private, link-local or cloud-metadata address. "
                                + "Off by default: a proxy fetch carries the deployment's per-host upstream credential "
                                + "and its result is cached and re-served, so a cleartext hop hands both to any "
                                + "observer and lets an active intermediary choose what this repository caches, while "
                                + "an internal one lets an upstream steer the fetch into this deployment's own network "
                                + "(SSRF). A deployment-global operator dial - one question, one answer, for every "
                                + "format - enable only for a trusted internal or plaintext mirror.",
                        Setting.Kind.BOOLEAN, "false", false),
                new Setting("cleanup-lease", "Operations", "Maintenance lease",
                        "How long one node holds the background-maintenance lease; keep under the task intervals.",
                        Setting.Kind.DURATION, "PT10M", false),
                new Setting("default-tenant", "Defaults", "Default tenant",
                        "Tenant a request resolves to when its key carries none. Applies on the next restart: the "
                                + "routing legs pick a new value up immediately, but the tenants directory resolves "
                                + "once at boot, so until the deployment restarts the console's instance list, the "
                                + "orphan diagnostic and the purge keep naming the previous default while keyless "
                                + "traffic has already moved.",
                        Setting.Kind.STRING, Scopes.DEFAULT_TENANT, false),
                new Setting("block-private-import-hosts", "Defaults", "Block private import hosts",
                        "Reject a migration URL - an import's source or an export's target - that is plaintext http, "
                                + "or that resolves to a loopback, link-local or private address. A migration runs "
                                + "server-side with a credential attached, so a plaintext URL hands it to any "
                                + "observer on the path and a private one turns the migration into a request against "
                                + "this deployment's own network (SSRF). On by default for every edition, enforced "
                                + "identically on the API and console legs; an operator sets it false to migrate from "
                                + "or to an internal or plaintext repository - the one dial, covering both, so "
                                + "neither can be opted out of alone.",
                        Setting.Kind.BOOLEAN, "true", false),
                new Setting("trusted-proxies", "Defaults", "Trusted proxies",
                        "Comma-separated CIDRs of reverse proxies whose X-Forwarded-For, X-Forwarded-Proto and "
                                + "X-Forwarded-Host are believed.",
                        Setting.Kind.STRING, "", false),
                new Setting("public-url", "Defaults", "Public URL",
                        "The address clients reach this deployment at (https://repo.example.com), for the absolute "
                                + "URLs generated indexes carry. Set it behind a front door that rewrites paths or "
                                + "sends no forwarded headers; unset, the request's own scheme and host are used, or "
                                + "a trusted proxy's forwarded ones.",
                        Setting.Kind.STRING, "", false));
    }

    /** The core's dials are neutral: they render in the catalogue ({@link SettingsContributor#all()}) but stay out of
     *  the plugin-oriented views, so a core setting keeps the {@link SettingsDocuments#NEUTRAL} document, adds no
     *  modules-console row and keeps its {@link SettingsScopes} scope classification. */
    @Override
    public boolean neutral() {
        return true;
    }

    /** The per-format upstreams the console's Format upstreams panel stores, one key per format under a prefix the
     *  operator's choice of format opens - {@code format-upstream.maven}, {@code format-upstream.npm}. */
    @Override
    public Set<String> startupKeys() {
        return Set.of("jenreg.format-upstream.*");
    }
}
