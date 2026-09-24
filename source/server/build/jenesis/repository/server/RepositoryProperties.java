package build.jenesis.repository.server;
import module java.base;

import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.settings.CoreDefaults;
import build.jenesis.repository.settings.ImportHostGuard;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The repository server's configuration, bound from {@code jenreg.*} - every dial the server itself binds, in one
 * class because there is one prefix and one deployment reading it. The storage backend reads its own root / bucket /
 * connection string from the same environment through {@code ArtifactStoreProvider}, so every composition shares one
 * config surface: the backend name ({@code filesystem} by default), the artifact space a request resolves to,
 * whether the wire is gated by the {@link Authorization} credential model (enforced by default; anonymous is an
 * explicit opt-out), the request routing and its per-repository definitions, an optional storage
 * {@link #getQuota() quota} and rate ceiling, the pull-through {@link #getProxy() proxy} upstreams keyed by format
 * name ({@code jenreg.proxy.<format>}), the compliance verdicts {@code LiveConfig} reads as the running gate's
 * fallback, and the cleanup dials.
 *
 * <p><b>It was two classes binding the same prefix</b>, one here and one in the server kernel layered above it,
 * and eleven dials - {@code store}, {@code auth}, {@code rate-limit}, {@code batch-upload},
 * {@code batch-upload-max-entries}, {@code demo}, {@code read-only}, {@code anonymous-rights},
 * {@code bootstrap-key} and the two credential lifetimes - were declared in both, each with its own copy of the
 * default. Both beans were registered in every composed deployment, so each of those keys was bound twice into two
 * objects and the values agreed only for as long as nobody edited one side. {@code rate-limit} had already come
 * apart that way once, which is why its default carries the account above. Holding copies equal is not a fix, so
 * there is one class.
 *
 * <p><b>One key names the fixed deployment's tenant</b>: {@link #getDefaultTenant() default-tenant}, which the
 * routing, the browse and the maintenance surfaces resolve against, and which two nodes over one store must agree
 * on. A repository is always named by the request.
 */
@ConfigurationProperties(prefix = "jenreg")
public class RepositoryProperties {

    private String store = "filesystem";

    /** Enforce per-credential authorization. On by default - the secure default: a fresh deployment authorizes every
     *  request against a per-credential key. Anonymous/open mode is an <em>explicit opt-out</em>: an operator sets
     *  {@code jenreg.auth=false} (env {@code JENREG_AUTH=false}), and the server logs a loud
     *  boot warning that it is running open so the choice is never silent. */
    private boolean auth = true;

    /**
     * A key the deployment provisions at boot if it is not already known - the way an operator gets their FIRST
     * credential on an enforcing deployment.
     *
     * <p>Without it a fresh install is unusable as configured: {@code auth} is on, a keyless caller is rejected,
     * and every route that could mint a key itself requires one. The only remaining advice was to turn
     * authentication off, which is not a bootstrap, it is a different deployment.
     *
     * <p>Empty by default, so nothing changes for a deployment that already has keys. Set it once
     * ({@code JENREG_BOOTSTRAP_KEY}), use it to issue the credentials you actually want, then unset it: it grants
     * every right on every repository of its tenant, and it is a deploy-time secret rather than a stored one, so
     * it is re-provisioned on every boot for as long as it is set. The server logs a loud SECURITY line while it
     * is in effect, for the same reason {@code anonymous-rights} does.
     *
     * <p>It must be a well-formed key - {@code jenk_<tenant>.<secret><checksum>}, as {@code Authorization.mint}
     * produces - because the tenant it belongs to is read out of the key itself. A malformed value is refused at
     * boot rather than silently ignored.
     */
    private String bootstrapKey = "";

    /** The strictly-opt-in anonymous role (WANON.1): the rights a keyless (no-credential) caller is granted under an
     *  enforcing deployment ({@code auth=true}). <em>Empty by default</em> - a keyless caller is then rejected exactly
     *  as enforcing does today. A non-empty value is a comma-list in the existing grant grammar: a bare
     *  {@code <surface>:<verb>} token ({@code repository:read}, {@code repository:write}, {@code manage:read},
     *  {@code manage:write}, a per-surface {@code <surface>:*}, or the all-privileges {@code *}) is granted on every
     *  repository, and a {@code <repository>=<token>} entry scopes a token to one named repository - the same
     *  {@code <scope>/<surface>:<verb>} vocabulary a minted credential carries, so there is no new right vocabulary.
     *  Only meaningful under {@code auth=true}; a non-empty value under {@code auth=false} is redundant (already fully
     *  open) and warns. The env spelling is {@code JENREG_ANONYMOUS_RIGHTS}. Paired with
     *  {@code jenreg.read-only=true} and {@code anonymous-rights=repository:read} this is the public-mirror
     *  pattern (WRO.1): reads served anonymously while writes/admin stay key-gated and the store write-gate refuses
     *  internal writes. */
    private String anonymousRights = "";

    /** The lifetime stamped on a credential minted without an explicit expiry, as an ISO-8601 duration. Blank keeps
     *  the {@code Authorization} default of 90 days. */
    private String credentialDefaultLifetime = "";

    /** The ceiling on any credential's lifetime, as an ISO-8601 duration. Blank leaves the deployment uncapped, which
     *  is the shipped posture: a ceiling is an operator's decision about their own key hygiene, and imposing one by
     *  default would silently shorten every existing deployment's credentials on upgrade. */
    private String credentialMaxLifetime = "";

    private String quota = "";

    /**
     * Requests per minute per tenant a deployment serves before refusing, and the value every edition ships with.
     *
     * <p>{@code 6000} is a hundred a second per tenant - the secure floor: a fresh deployment caps a runaway or
     * abusive client instead of serving unlimited requests, while staying well clear of legitimate parallel CI. An
     * operator raises it, lowers it, or sets {@code 0} to restore unlimited.
     *
     * <p><strong>It lives here, and it used to differ by edition.</strong> This core shipped {@code 0} and the
     * downstream edition's own properties flipped it to {@code 6000}, so which posture a deployment got depended on
     * which image it ran - and both javadocs argued their side sincerely, which is how a difference like that
     * survives. A downstream edition adds capability; it does not change what this core decided. So the floor is
     * the decision, it is made once, and it is made here.
     */
    public static final long DEFAULT_RATE_LIMIT = 6000;

    private long rateLimit = DEFAULT_RATE_LIMIT;

    private Map<String, String> proxy = new LinkedHashMap<>();

    private Duration proxyMissTtl = Duration.ofSeconds(60);

    private boolean batchUpload = false;

    private int batchUploadMaxEntries = 10_000;

    /** The recent-logs ring size: how many most-recent log entries the in-memory recent-logs buffer retains
     *  before the oldest is evicted, the bound behind {@code GET /api/logs}. Sized once at startup. */
    private int logsBuffer = LogRingBuffer.DEFAULT_CAPACITY;

    private boolean demo = false;

    private boolean readOnly = false;

    /** Whether pull-through proxy reads that miss locally are fetched from the upstreams, caching and bridging them.
     *  Named {@code proxy-enabled} to sit alongside the {@code jenreg.proxy.<format>} map,
     *  which owns the per-format upstream URLs (this distribution extends the schema rather than redefining its
     *  {@code proxy} key). */
    private boolean proxyEnabled = Boolean.parseBoolean(CoreDefaults.PROXY_ENABLED);

    /** Per-repository backing definitions, by name. The generalized grammar (EPIC 25) is
     *  {@code ( writable | fallback <source> [nocache] [harden] [unscreened] )*}; the old spellings {@code hosted} |
     *  {@code proxy <url> [nocache] [harden]} | {@code group a,b} desugar to it. Write-delegation ({@code group … push=x})
     *  is a hard parse refusal now (§2.3): declare the front repository {@code writable} instead. Named
     *  {@code repositories} rather than {@code repository} because a scalar of that name used to hold the
     *  fixed-space name, and one prefix cannot be both a string and a map; the scalar is gone and the plural
     *  stays, since renaming a live key to reclaim a dead one would move every deployment's configuration. */
    private Map<String, String> repositories = new LinkedHashMap<>();

    /** Tenant a request resolves to when its key carries none (anonymous or keyless). */
    private String defaultTenant = "default";

    /** Request routing over the shared {@code <tenant>/<repository>/...} store layout. {@code fixed} (the default,
     *  and the default of every image this product ships) binds <em>every</em> request to the one
     *  {@code default-tenant} through {@code FixedTenantRouting}, the repository named by the path and the key
     *  routing nowhere - the single-tenant deployment, which is the shape most deployments are and the
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

    /** Count store operations by key family as well as by name, for a run measuring where its store cost goes.
     *  Off by default: it costs a map lookup and a concatenation on the store's hottest path. */
    private boolean storeFamilies = false;

    public String getStore() {
        return store;
    }

    public void setStore(String store) {
        this.store = store;
    }

    public String getQuota() {
        return quota;
    }

    public void setQuota(String quota) {
        this.quota = quota;
    }

    public long getRateLimit() {
        return rateLimit;
    }

    public void setRateLimit(long rateLimit) {
        this.rateLimit = rateLimit;
    }

    /** The repository-wide storage ceiling in bytes: a plain count or a number with a {@code K/M/G/T} (1024-based)
     *  suffix; {@code 0} (the default) leaves storage uncapped. */
    public long quotaBytes() {
        String value = quota == null ? "" : quota.trim();
        if (value.isEmpty()) {
            return 0L;
        }
        int split = 0;
        while (split < value.length() && (Character.isDigit(value.charAt(split)) || value.charAt(split) == '.')) {
            split++;
        }
        double number = Double.parseDouble(value.substring(0, split));
        long multiplier = switch (value.substring(split).trim().toUpperCase(Locale.ROOT)) {
            case "", "B" -> 1L;
            case "K", "KB", "KIB" -> 1024L;
            case "M", "MB", "MIB" -> 1024L * 1024;
            case "G", "GB", "GIB" -> 1024L * 1024 * 1024;
            case "T", "TB", "TIB" -> 1024L * 1024 * 1024 * 1024;
            default -> throw new IllegalArgumentException("Unrecognized storage quota unit in: " + value);
        };
        return (long) (number * multiplier);
    }

    public String getBootstrapKey() {
        return bootstrapKey;
    }

    public void setBootstrapKey(String bootstrapKey) {
        this.bootstrapKey = bootstrapKey;
    }

    public boolean isAuth() {
        return auth;
    }

    public void setAuth(boolean auth) {
        this.auth = auth;
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

    /** The strictly-opt-in anonymous-role grant (WANON.1), empty by default (no anonymous access whatsoever). See the
     *  field javadoc for the grammar. Read by the composition that builds the
     *  {@link Authorization} a keyless request is decided against, so the routed path honours the anonymous role
     *  identically to the keyless branch. */
    public String getAnonymousRights() {
        return anonymousRights;
    }

    public void setAnonymousRights(String anonymousRights) {
        this.anonymousRights = anonymousRights == null ? "" : anonymousRights;
    }

    public Map<String, String> getProxy() {
        return proxy;
    }

    public void setProxy(Map<String, String> proxy) {
        this.proxy = proxy;
    }

    /** How long an upstream {@code 404} is remembered so a build tool's repeated probes for an artifact that is not
     *  there (a version range, a missing SNAPSHOT, an optional classifier) are answered from memory rather than
     *  re-hitting the upstream every time; {@code 0} disables the negative cache. */
    public Duration getProxyMissTtl() {
        return proxyMissTtl;
    }

    public void setProxyMissTtl(Duration proxyMissTtl) {
        this.proxyMissTtl = proxyMissTtl;
    }

    /** Whether a publish request carrying the {@code Jenesis-Explode} header is walked as an archive and exploded
     *  into a per-entry publish through {@link BatchIngestion}; off by default, so the header is inert and an archive
     *  is stored verbatim as one artifact unless a deployment opts in. A live {@code batch-upload} setting overrides
     *  it. */
    public boolean isBatchUpload() {
        return batchUpload;
    }

    public void setBatchUpload(boolean batchUpload) {
        this.batchUpload = batchUpload;
    }

    /** The ceiling on how many members one exploded archive may publish - the zip-bomb axis that matters, since every
     *  entry streams and its size is irrelevant; a walk stops at the cap and reports it in the manifest. A live
     *  {@code batch-upload-max-entries} setting overrides it. */
    public int getBatchUploadMaxEntries() {
        return batchUploadMaxEntries;
    }

    public void setBatchUploadMaxEntries(int batchUploadMaxEntries) {
        this.batchUploadMaxEntries = batchUploadMaxEntries;
    }

    public int getLogsBuffer() {
        return logsBuffer;
    }

    public void setLogsBuffer(int logsBuffer) {
        this.logsBuffer = logsBuffer;
    }

    /** Whether demo mode seeds a fresh, completely empty repository with real artifacts through the formats' own
     *  pull-through paths so an evaluator has data to look at; off by default, and a no-op against a non-empty store
     *  (a seeded or in-use repository is never re-seeded), so turning it on in production is harmless. A live {@code demo}
     *  setting overrides it, though it takes effect on the next restart since the seed runs once at boot. */
    public boolean isDemo() {
        return demo;
    }

    public void setDemo(boolean demo) {
        this.demo = demo;
    }

    /** Whether the deployment runs read-only: every write - a hosted publish, staging deploy, promotion, import and
     *  every mutating admin action, plus internal writes (write-through proxy caching, import replay, a background
     *  sweep) - is refused at the {@link build.jenesis.repository.store.ReadOnlyArtifactStore} store choke point, while
     *  browse, download, search and all read APIs work normally. Off by default; a demo or a public read-only mirror
     *  turns it on. The env spelling is {@code JENREG_READ_ONLY}. */
    public boolean isReadOnly() {
        return readOnly;
    }

    public void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
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
}
