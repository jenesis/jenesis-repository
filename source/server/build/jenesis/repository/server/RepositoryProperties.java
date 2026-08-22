package build.jenesis.repository.server;
import build.jenesis.repository.server.spi.Authorization;

import org.springframework.boot.context.properties.ConfigurationProperties;

import module java.base;

/**
 * The configuration of the Spring repository server, bound from {@code jenreg.*}: the artifact-store
 * backend name ({@code filesystem} by default, chosen through {@code ArtifactStoreProvider}), the fixed
 * {@link #getTenant() tenant} / {@link #getRepository() repository} artifact space every request resolves to
 * (each {@code default} by default, the {@link FixedTenantRouting} specialization of the shared
 * {@code <tenant>/<repository>/...} store layout), whether the wire is
 * gated by the {@link Authorization} credential model (enforced by default, the secure default; anonymous is an
 * explicit opt-out), an
 * optional repository-wide storage {@link #getQuota() quota}, and the pull-through {@link #getProxy() proxy}
 * upstreams keyed by format name ({@code jenreg.proxy.<format>}), so a format that is a
 * {@link build.jenesis.repository.format.ProxyFormat} serves a local miss from the upstream.
 */
@ConfigurationProperties(prefix = "jenreg")
public class RepositoryProperties {

    private String store = "filesystem";

    private String tenant = "default";

    private String repository = "default";

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

    private long rateLimit = 0;

    private Map<String, String> proxy = new LinkedHashMap<>();

    private Duration proxyMissTtl = Duration.ofSeconds(60);

    private boolean batchUpload = false;

    private int batchUploadMaxEntries = 10_000;

    /** The recent-logs ring size: how many most-recent log entries the in-memory recent-logs buffer retains
     *  before the oldest is evicted, the bound behind {@code GET /api/logs}. Sized once at startup. */
    private int logsBuffer = LogRingBuffer.DEFAULT_CAPACITY;

    private boolean demo = false;

    private boolean readOnly = false;

    public String getStore() {
        return store;
    }

    public void setStore(String store) {
        this.store = store;
    }

    /** The tenant of the fixed artifact space this deployment serves; a multi-tenant routing ignores it and reads
     *  the tenant from the request instead. */
    public String getTenant() {
        return tenant;
    }

    public void setTenant(String tenant) {
        this.tenant = tenant;
    }

    /** The repository of the fixed artifact space this deployment serves; a multi-tenant routing ignores it and
     *  reads the repository from the request path instead. */
    public String getRepository() {
        return repository;
    }

    public void setRepository(String repository) {
        this.repository = repository;
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

    /** The strictly-opt-in anonymous-role grant (WANON.1), empty by default (no anonymous access whatsoever). See the
     *  field javadoc for the grammar. */
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

    /** Whether a publish request carrying the {@code X-Jenesis-Explode} header is walked as an archive and exploded
     *  into a per-entry publish through {@link BatchIngestion}; off by default, so the header is inert and an archive
     *  is stored verbatim as one artifact unless a deployment opts in. */
    public boolean isBatchUpload() {
        return batchUpload;
    }

    public void setBatchUpload(boolean batchUpload) {
        this.batchUpload = batchUpload;
    }

    /** The ceiling on how many members one exploded archive may publish - the zip-bomb axis that matters, since every
     *  entry streams and its size is irrelevant; a walk stops at the cap and reports it in the manifest. */
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
     *  (a seeded or in-use repository is never re-seeded), so turning it on in production is harmless. */
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
}
