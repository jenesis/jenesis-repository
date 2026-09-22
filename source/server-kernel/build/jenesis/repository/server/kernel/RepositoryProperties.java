package build.jenesis.repository.server.kernel;

import module java.base;
import build.jenesis.repository.settings.CoreDefaults;
import build.jenesis.repository.settings.ImportHostGuard;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The repository server's configuration, bound from {@code jenreg.*} (each also accepting
 * its relaxed-binding {@code JENREG_*} environment variable). The storage backend reads its own root /
 * bucket / connection string from the same environment through {@code ArtifactStoreProvider}, so every app shares
 * one config surface.
 */
@ConfigurationProperties(prefix = "jenreg")
public class RepositoryProperties {

    /** Storage backend name (filesystem, s3, azure-blob, ...). */
    private String store = "filesystem";
    /** Whether pull-through proxy reads that miss locally are fetched from the upstreams, caching and bridging them.
     *  Named {@code proxy-enabled} to sit alongside the {@code jenreg.proxy.<format>} map,
     *  which owns the per-format upstream URLs (this distribution extends the schema rather than redefining its
     *  {@code proxy} key). */
    private boolean proxyEnabled = Boolean.parseBoolean(CoreDefaults.PROXY_ENABLED);
    /** Per-repository backing definitions, by name. The generalized grammar (EPIC 25) is
     *  {@code ( writable | fallback <source> [nocache] [harden] [unscreened] )*}; the old spellings {@code hosted} |
     *  {@code proxy <url> [nocache] [harden]} | {@code group a,b} desugar to it. Write-delegation ({@code group … push=x})
     *  is a hard parse refusal now (§2.3): declare the front repository {@code writable} instead. Named
     *  {@code repositories} (the {@code jenreg.repository} is a single {@code String}, the
     *  fixed-space name) so the two schemas compose over one {@code jenreg.repository} prefix without a type clash. */
    private Map<String, String> repositories = new LinkedHashMap<>();
    /** Enforce per-credential authorization. On by default - the secure default: a fresh deployment authorizes every
     *  request against a per-credential key. Anonymous/open mode is an <em>explicit opt-out</em>: an operator sets
     *  {@code jenreg.auth=false} (env {@code JENREG_AUTH=false}), and the server logs a loud
     *  boot warning that it is running open so the choice is never silent. */
    private boolean auth = true;
    /** Tenant a request resolves to when its key carries none (anonymous or keyless). */
    private String defaultTenant = "default";
    /** Request routing over the shared {@code <tenant>/<repository>/...} store layout. {@code fixed} (the default,
     *  and the default of every image this product ships) binds <em>every</em> request to the one
     *  {@code default-tenant} / {@code default-repository} space through {@code FixedTenantRouting}, the key
     *  no longer routing anywhere - the single-tenant deployment, which is the shape most deployments are and the
     *  the one a plain composition ships, carrying every installed feature.
     *
     *  <p>Multi-tenancy is opted into rather than inherited. {@code multi} resolves the tenant from the key the
     *  request presents and the repository from the first path segment. {@code host} and {@code path} (WROUTE.1)
     *  let a request name a non-default tenant
     *  <em>without a key</em>, so an anonymous / redirect-served request can reach any tenant - the single-deployment
     *  multi-tenant CDN. Under {@code host} the tenant is the {@link #tenantHosts} mapping of the request Host (an
     *  unmapped host falls to the {@code default-tenant}) and the repository is the first path segment, as {@code multi};
     *  under {@code path} the tenant is the first path segment under {@code /repository/}, the repository the second,
     *  the residual path the rest. Both confine exactly as {@code multi}: the host/path tenant is validated
     *  traversal-free and, when a key is also present, must name the key's own tenant (else a {@code 403}), so a key
     *  for tenant A can never be turned against tenant B by forging the Host or path. All four modes address the same
     *  layout, so a deployment can be flipped between them by a restart and finds its data where it was left. */
    private String tenancy = "fixed";
    /** The host→tenant mapping consulted under {@code tenancy=host} (WROUTE.1): a comma-separated list of
     *  {@code <host>=<tenant>} pairs (e.g. {@code acme.cdn.example.com=acme,foo.cdn.example.com=foo}), the host matched
     *  case-insensitively against the request's server name (the Host header with any port stripped). A host with no
     *  mapping resolves to the {@code default-tenant} - the same fall-through a keyless {@code multi} request takes -
     *  so an unlisted CDN edge or a direct-to-origin request still serves the default tenant rather than being refused;
     *  the mapped tenant is validated as a traversal-free name before it scopes the store. A single plain {@code String}
     *  (not a {@code Map}) so a hostname's dots never become nested binding keys; the env spelling is
     *  {@code JENREG_TENANT_HOSTS}. Only consulted under {@code tenancy=host}; ignored otherwise. */
    private String tenantHosts = "";
    /** Tenant whose credentials may drive the deployment-global API routes ({@code /api/settings}, {@code
     *  /api/repositories}, {@code /api/upstreams}); empty means the {@code default-tenant}. A tenant administering
     *  only its own credentials cannot reach these shared-configuration routes even with a {@code manage} right. */
    private String operatorTenant = "";
    /** Whether to reject a repository-migration URL that resolves to a loopback, link-local or private address - the
     *  import SSRF guard's deployment-level env-field. Tri-state: an explicit {@code true}/{@code false} forces it
     *  either way; unset, the guard <em>fails closed (blocks) for every edition</em>, since an import URL is fetched
     *  server-side and a private-host target would be a server-side request against an internal service or cloud
     *  metadata (an SSRF). A single-tenant / {@code fixed} operator migrating from an internal Nexus/Artifactory at a
     *  private address opts out <em>explicitly</em> (this field, or the stored {@code block-private-import-hosts}
     *  setting, set to {@code false}) - the old tenancy-derived "off for fixed" default was the SSRF hole and is gone.
     *  This field is only the middle layer: resolve through {@link #importHostsGuarded(Boolean)} (stored setting over
     *  this env-field over the fail-closed default), which folds it into the {@link ImportHostGuard} both import legs
     *  share, never the raw field. */
    private Boolean blockPrivateImportHosts;
    /** Repository the console browses by default; requests always name their repository in the path. */
    private String defaultRepository = "releases";
    // The three licence dials (license-allowed, license-denied, license-unknown) used to be fields here, with their
    // defaults written out a second time beside the ones the dimension actually applies. They are gone. Licence is a
    // discovered plugin dimension: its dials reach the gate through the settings lookup layered over the Spring
    // environment, so a jenreg.license-* set in a properties file or as an environment variable always worked without
    // this bean, and binding it here only created a second value that had to equal the first. It did not: moving the
    // unknown-licence default left this class saying QUARANTINE while the gate served, and the deployment-info
    // surface reported this one. A dial the server itself binds (malware-action, vulnerability-threshold,
    // proxy-enabled below) still belongs here, because LiveConfig really does read it as the core gate's fallback.
    /** Reject vulnerabilities at or above this CVSS band (NONE disables the vulnerability check). Defaults to
     *  {@code CRITICAL} - the secure floor: a fresh deployment with an advisory feed active gates the most severe
     *  CVEs rather than admitting them silently. An operator loosens it to {@code NONE} (the explicit opt-out) or
     *  tightens it to {@code HIGH}/{@code MEDIUM}/{@code LOW}. */
    private String vulnerabilityThreshold = CoreDefaults.VULNERABILITY_THRESHOLD;
    /** Verdict for a package the feed marks malicious (carries no CVSS score): QUARANTINE, REJECT or ALLOW to disable. */
    private String malwareAction = CoreDefaults.MALWARE_ACTION;

    private String vulnerabilityAction = CoreDefaults.VULNERABILITY_ACTION;

    private String denyListAction = CoreDefaults.DENY_LIST_ACTION;
    /** Comma-separated coordinates an operator forbids ({@code group:artifact}, {@code group:artifact:version}, or a {@code group:*} prefix); always refused. */
    private String denyList = "";
    /** Quarantine proxied artifacts the upstream published within this many days (0 disables the immaturity hold).
     *  Defaults to {@code 2} - the secure floor: a freshly-published upstream version is the exact vehicle of a
     *  typosquat or account-compromise supply-chain attack, and the ecosystems (npm/PyPI/OSV) yank the great majority
     *  of malicious uploads within the first day or two. A short hold quarantines a brand-new pull-through into the
     *  review queue over that highest-risk window rather than serving it blind, while staying small so a legitimate
     *  fresh release is only briefly held (and QUARANTINE, not REJECT - an operator releases it on review). The hold is
     *  fail-open by design: it bites only when the upstream returns a {@code Last-Modified} date, so a source that
     *  publishes none is unaffected. An operator raises it (7/14/30 are common stricter cooldowns), or sets {@code 0}
     *  to restore no hold. Kept a {@link RepositoryProperties} field, so - like the {@link #rateLimit} ceiling - it
     *  floors every deployment, embedders included. It makes no outbound call, which is what lets it floor a bare
     *  embed or test boot as safely as a deployment. */
    private int immaturityHoldDays = 2;
    /** Default request rate ceiling in permits per minute per tenant (0 disables it); a per-tenant value overrides
     *  it. The value is the {@code DEFAULT_RATE_LIMIT} and is <em>not</em> restated here: this kernel
     *  used to carry its own {@code 6000} against a default of {@code 0}, so which posture a deployment got
     *  depended on which image it ran. An edition adds capability; it does not change what the core decided. What
     *  this module adds is the per-tenant ceiling that overrides it, the screen that shows it and the trail that
     *  records it. */
    private long rateLimit = build.jenesis.repository.server.RepositoryProperties.DEFAULT_RATE_LIMIT;
    /** Keep at most this many newest versions per coordinate when cleaning up (0 disables the count cap). */
    private int keepLast = 0;
    /** Evict versions older than this ISO-8601 duration when cleaning up (empty disables age eviction). */
    private String maxAge = "";
    /** Evict prereleases older than this ISO-8601 duration when cleaning up (empty disables prerelease expiry). */
    private String prereleaseExpiry = "";
    /** Evict versions not downloaded within this ISO-8601 duration (empty disables; needs download tracking). */
    private String notDownloadedFor = "";
    /** Comma-separated CIDRs of trusted reverse proxies; only from these is X-Forwarded-For believed when resolving a
     *  request's source address for the per-credential IP allowlist. Empty means the connection peer is always used. */
    private String trustedProxies = "";
    /** How long one node holds the background-maintenance lease, so a replicated deployment runs an exclusive
     *  pass on one node; keep under the task intervals. */
    private String cleanupLease = "PT10M";
    /** Whether a publish carrying the {@code Jenesis-Explode} header is walked as an archive and exploded into a
     *  per-entry publish (batch archive ingestion); off by default. A live {@code batch-upload} setting overrides it. */
    private boolean batchUpload = false;

    /** Count store operations by key family as well as by name, for a run measuring where its store cost goes.
     *  Off by default: it costs a map lookup and a concatenation on the store's hottest path. */
    private boolean storeFamilies = false;
    /** The ceiling on how many members one exploded archive may publish - the zip-bomb axis, since every entry streams
     *  and its size is irrelevant. A live {@code batch-upload-max-entries} setting overrides it. */
    private int batchUploadMaxEntries = 10_000;
    /** Whether demo mode seeds a fresh, completely empty repository with real artifacts (through the formats' own
     *  pull-through paths) and layers in a small demo gate config; off by default. A live {@code demo} setting
     *  overrides it, though it takes effect on the next restart since the seed runs once at boot. */
    private boolean demo = false;

    private boolean readOnly = false;

    /** The strictly-opt-in anonymous role (WANON.1): the rights a keyless (no-credential) caller is granted, a
     *  comma-list in the existing grant grammar ({@code repository:read}, {@code manage:write}, a
     *  {@code <repository>=<token>} scope, or the all-privileges {@code *}) - the same {@code <surface>:<verb>}
     *  vocabulary a minted credential carries, no new right vocabulary. <b>Default empty</b> ⇒ a keyless request is
     *  rejected byte-for-byte as an enforcing deployment rejects it today; it takes an explicit, non-empty value to
     *  grant a keyless caller anything. Only meaningful under {@code auth=true} (enforcing); a non-empty value under
     *  {@code auth=false} is redundant (already fully open) and warns. Decided at the one choke point the
     *  multi-tenant path shares with the dispatcher - {@link build.jenesis.repository.server.spi.Authorization},
     *  handed this grant set by {@code StoreConfig} - so the enforcing multi-tenant path honours the anonymous role
     *  identically to the keyless branch. Paired with {@code jenreg.read-only=true} this is the
     *  public-mirror pattern (browsable but immutable). The env spelling is
     *  {@code JENREG_ANONYMOUS_RIGHTS}. */
    private String anonymousRights = "";

    /** The key provisioned at boot with every right on every repository of the tenant it names, so a fresh enforcing
     *  deployment can mint its first real credential - {@code jenreg.bootstrap-key}, the same contract the free
     *  server carries. Blank provisions nothing; a malformed value refuses to boot. */
    private String bootstrapKey = "";

    /** The lifetime stamped on a credential minted without an explicit expiry, as an ISO-8601 duration. Blank keeps
     *  the {@code Authorization} default of 90 days. */
    private String credentialDefaultLifetime = "";

    /** The ceiling on any credential's lifetime, as an ISO-8601 duration. Blank leaves the deployment uncapped. */
    private String credentialMaxLifetime = "";

    public String getStore() {
        return store;
    }

    public void setStore(String store) {
        this.store = store;
    }

    public boolean isProxyEnabled() {
        return proxyEnabled;
    }

    public void setProxyEnabled(boolean proxyEnabled) {
        this.proxyEnabled = proxyEnabled;
    }

    public Map<String, String> getRepositories() {
        return repositories;
    }

    public void setRepositories(Map<String, String> repositories) {
        this.repositories = repositories;
    }

    public boolean isStoreFamilies() {
        return storeFamilies;
    }

    public void setStoreFamilies(boolean storeFamilies) {
        this.storeFamilies = storeFamilies;
    }

    public boolean isAuth() {
        return auth;
    }

    public void setAuth(boolean auth) {
        this.auth = auth;
    }

    public String getDefaultTenant() {
        return defaultTenant;
    }

    public void setDefaultTenant(String defaultTenant) {
        this.defaultTenant = defaultTenant;
    }

    public String getTenancy() {
        return tenancy;
    }

    public void setTenancy(String tenancy) {
        this.tenancy = tenancy;
    }

    public String getTenantHosts() {
        return tenantHosts;
    }

    public void setTenantHosts(String tenantHosts) {
        this.tenantHosts = tenantHosts == null ? "" : tenantHosts;
    }

    /** Parse {@link #tenantHosts} into the immutable, lowercased {@code host → tenant} map the {@code host} routing
     *  looks a request's server name up in (WROUTE.1). Each comma-separated entry is {@code <host>=<tenant>}; blank
     *  entries are skipped. A malformed entry - no {@code =}, or an empty host or tenant - <em>fails fast at startup
     *  naming the bad value</em> (the same fail-closed discipline {@code trusted-proxies} uses): a silently-dropped
     *  mapping would route that host to the default tenant instead of the intended one, a routing misconfiguration
     *  that must never pass unnoticed. The host is lowercased for the case-insensitive Host match; the tenant is left
     *  as configured and validated as a traversal-free name by the routing before it scopes the store. */
    public Map<String, String> tenantHosts() {
        return tenantHosts(tenantHosts);
    }

    /** The same parse, off a raw value: {@code HostTenantRoutingProvider} reads the mapping from the routing
     *  context's configuration rather than from this bean, and one grammar with two parsers is how the two
     *  drift. */
    public static Map<String, String> tenantHosts(String value) {
        Map<String, String> mapping = new LinkedHashMap<>();
        for (String entry : (value == null ? "" : value).split(",")) {
            String pair = entry.trim();
            if (pair.isEmpty()) {
                continue;
            }
            int equals = pair.indexOf('=');
            String host = equals < 0 ? "" : pair.substring(0, equals).trim();
            String tenant = equals < 0 ? "" : pair.substring(equals + 1).trim();
            if (host.isEmpty() || tenant.isEmpty()) {
                throw new IllegalArgumentException("Malformed jenreg.tenant-hosts entry '" + pair
                        + "': expected <host>=<tenant> such as acme.cdn.example.com=acme");
            }
            mapping.put(host.toLowerCase(Locale.ROOT), tenant);
        }
        return Map.copyOf(mapping);
    }

    public String getOperatorTenant() {
        return operatorTenant;
    }

    public void setOperatorTenant(String operatorTenant) {
        this.operatorTenant = operatorTenant;
    }

    public Boolean getBlockPrivateImportHosts() {
        return blockPrivateImportHosts;
    }

    public void setBlockPrivateImportHosts(Boolean blockPrivateImportHosts) {
        this.blockPrivateImportHosts = blockPrivateImportHosts;
    }

    /** Whether an import URL's host must resolve to a public address before a migration will fetch it, resolved through
     *  the {@link ImportHostGuard} both import legs share: the {@code storedSetting} (the runtime
     *  {@code block-private-import-hosts} value, {@code null} when unset) wins, else this deployment's
     *  {@link #blockPrivateImportHosts} env-field when explicitly set, else <em>fail-closed to block for every
     *  edition</em>. This is the only value the import path may consult - a bare {@code getBlockPrivateImportHosts()}
     *  would read a stock deployment (env-field null) as SSRF-open, and the previous tenancy-derived default left the
     *  {@code fixed} edition open. */
    public boolean importHostsGuarded(Boolean storedSetting) {
        return ImportHostGuard.blockPrivateHosts(storedSetting, blockPrivateImportHosts);
    }

    public String getDefaultRepository() {
        return defaultRepository;
    }

    public void setDefaultRepository(String defaultRepository) {
        this.defaultRepository = defaultRepository;
    }

    public String getVulnerabilityThreshold() {
        return vulnerabilityThreshold;
    }

    public void setVulnerabilityThreshold(String vulnerabilityThreshold) {
        this.vulnerabilityThreshold = vulnerabilityThreshold;
    }

    public String getVulnerabilityAction() {
        return vulnerabilityAction;
    }

    public void setVulnerabilityAction(String vulnerabilityAction) {
        this.vulnerabilityAction = vulnerabilityAction;
    }

    public String getDenyListAction() {
        return denyListAction;
    }

    public void setDenyListAction(String denyListAction) {
        this.denyListAction = denyListAction;
    }

    public String getMalwareAction() {
        return malwareAction;
    }

    public void setMalwareAction(String malwareAction) {
        this.malwareAction = malwareAction;
    }

    public String getDenyList() {
        return denyList;
    }

    public void setDenyList(String denyList) {
        this.denyList = denyList;
    }

    public int getImmaturityHoldDays() {
        return immaturityHoldDays;
    }

    public void setImmaturityHoldDays(int immaturityHoldDays) {
        this.immaturityHoldDays = immaturityHoldDays;
    }

    public long getRateLimit() {
        return rateLimit;
    }

    public void setRateLimit(long rateLimit) {
        this.rateLimit = rateLimit;
    }

    public int getKeepLast() {
        return keepLast;
    }

    public void setKeepLast(int keepLast) {
        this.keepLast = keepLast;
    }

    public String getMaxAge() {
        return maxAge;
    }

    public void setMaxAge(String maxAge) {
        this.maxAge = maxAge;
    }

    public String getPrereleaseExpiry() {
        return prereleaseExpiry;
    }

    public void setPrereleaseExpiry(String prereleaseExpiry) {
        this.prereleaseExpiry = prereleaseExpiry;
    }

    public String getNotDownloadedFor() {
        return notDownloadedFor;
    }

    public void setNotDownloadedFor(String notDownloadedFor) {
        this.notDownloadedFor = notDownloadedFor;
    }

    public String getTrustedProxies() {
        return trustedProxies;
    }

    public void setTrustedProxies(String trustedProxies) {
        this.trustedProxies = trustedProxies;
    }

    public String getCleanupLease() {
        return cleanupLease;
    }

    public void setCleanupLease(String cleanupLease) {
        this.cleanupLease = cleanupLease;
    }

    public boolean isBatchUpload() {
        return batchUpload;
    }

    public void setBatchUpload(boolean batchUpload) {
        this.batchUpload = batchUpload;
    }

    public int getBatchUploadMaxEntries() {
        return batchUploadMaxEntries;
    }

    public void setBatchUploadMaxEntries(int batchUploadMaxEntries) {
        this.batchUploadMaxEntries = batchUploadMaxEntries;
    }

    public boolean isDemo() {
        return demo;
    }

    public void setDemo(boolean demo) {
        this.demo = demo;
    }

    /** Whether the deployment runs read-only: every write - a hosted publish, staging deploy, promotion, import and
     *  every mutating admin action, plus internal writes and background sweeps - is refused at the
     *  {@link build.jenesis.repository.store.ReadOnlyArtifactStore} store choke point, while browse, download, search and all read APIs work
     *  normally. Off by default; a demo or a public read-only mirror turns it on. The env spelling is
     *  {@code JENREG_READ_ONLY}. */
    public boolean isReadOnly() {
        return readOnly;
    }

    public void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
    }

    /** The strictly-opt-in anonymous-role grant (WANON.1); empty (the default) means no anonymous access - a keyless
     *  request is rejected exactly as an enforcing deployment rejects it today. Read by {@code StoreConfig} to build the
     *  {@link build.jenesis.repository.server.spi.Authorization} the multi-tenant path decides a keyless request against,
     *  mirroring the keyless branch. */
    public String getAnonymousRights() {
        return anonymousRights;
    }

    public String getBootstrapKey() {
        return bootstrapKey;
    }

    public void setBootstrapKey(String bootstrapKey) {
        this.bootstrapKey = bootstrapKey;
    }

    public String getCredentialDefaultLifetime() {
        return credentialDefaultLifetime;
    }

    public void setCredentialDefaultLifetime(String credentialDefaultLifetime) {
        this.credentialDefaultLifetime = credentialDefaultLifetime;
    }

    public String getCredentialMaxLifetime() {
        return credentialMaxLifetime;
    }

    public void setCredentialMaxLifetime(String credentialMaxLifetime) {
        this.credentialMaxLifetime = credentialMaxLifetime;
    }

    public void setAnonymousRights(String anonymousRights) {
        this.anonymousRights = anonymousRights == null ? "" : anonymousRights;
    }
}
