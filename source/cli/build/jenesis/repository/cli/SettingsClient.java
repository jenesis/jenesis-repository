package build.jenesis.repository.cli;

import module java.base;
import module java.net.http;
import module tools.jackson.databind;

/**
 * How the deployment is configured: the runtime settings and the first-run guide, the tenants, the repositories and
 * their definitions, the proxy upstreams, the tenant's limits, the installed service providers, the effective
 * configuration and the reclamation of removed modules' data.
 *
 * <p>Reached through {@link RepositoryClient#settings()}.
 */
public final class SettingsClient extends ClientCalls {

    SettingsClient(ClientCalls calls) {
        super(calls);
    }

    /** The deployment's runtime settings: each key with its effective value, default, override state and whether a
     *  change applies live. */
    public List<Setting> settings() throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/settings", null, null);
        require(response, 200, "read settings");
        return List.of(JSON.readValue(response.body(), Setting[].class));
    }

    /** Set a deployment-wide setting; {@code true} when the server says the change waits for its next restart. */
    public boolean setSetting(String name, String value) throws IOException, InterruptedException {
        return waitsForRestart(send("PUT", "/api/settings/" + name, body(Map.of("value", value)), "application/json"),
                "set " + name);
    }

    /** Clear a deployment-wide setting; {@code true} when the server says the change waits for its next restart. */
    public boolean clearSetting(String name) throws IOException, InterruptedException {
        return waitsForRestart(send("DELETE", "/api/settings/" + name, null, null), "clear " + name);
    }

    /** Whether a setting write the server accepted applies only once it restarts, as its answer says. */
    private static boolean waitsForRestart(HttpResponse<String> response, String action) throws IOException {
        require(response, 200, action);
        return response.headers().firstValue("Jenesis-Applies-On").filter("restart"::equals).isPresent();
    }

    /** The first boot's wizard: its steps, each with the settings rows it asks, as the console runs it. */
    public Setup setup() throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/setup", null, null);
        require(response, 200, "read the setup guide");
        return JSON.readValue(response.body(), Setup.class);
    }

    /** The deployment's stored settings as one JSON bundle (the per-module documents), for backup or transfer. The raw
     *  response body is returned unparsed, so it re-imports byte-identically. Credential-free by construction: the
     *  server excludes every SECRET-kind key, so a stored secret (the keyless identity token) never appears in the
     *  bundle. */
    public String exportSettings() throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/settings/export", null, null);
        require(response, 200, "export settings");
        return response.body();
    }

    /** Restore a settings bundle produced by {@link #exportSettings}. The server validates it (a dry resolve) and
     *  writes each module document, or answers 400 for a malformed or unresolvable bundle. */
    public void importSettings(String bundle) throws IOException, InterruptedException {
        require(send("POST", "/api/settings/import", HttpRequest.BodyPublishers.ofString(bundle), "application/json"),
                200, "import settings");
    }

    /** The runtime settings a tenant may override, resolved through the pin &gt; tenant &gt; global &gt; default chain -
     *  the same effective view the console's per-tenant screen shows. An operator manages any tenant's slice. */
    public List<Setting> settings(String tenant) throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/settings?tenant=" + enc(tenant), null, null);
        require(response, 200, "read settings for tenant " + tenant);
        return List.of(JSON.readValue(response.body(), Setting[].class));
    }

    /** Set a tenant's setting; {@code true} when the server says the change waits for its next restart. */
    public boolean setSetting(String tenant, String name, String value) throws IOException, InterruptedException {
        return waitsForRestart(send("PUT", "/api/settings/" + name + "?tenant=" + enc(tenant),
                body(Map.of("value", value)), "application/json"), "set " + name + " for tenant " + tenant);
    }

    /** Clear a tenant's setting; {@code true} when the server says the change waits for its next restart. */
    public boolean clearSetting(String tenant, String name) throws IOException, InterruptedException {
        return waitsForRestart(send("DELETE", "/api/settings/" + name + "?tenant=" + enc(tenant), null, null),
                "clear " + name + " for tenant " + tenant);
    }

    /** A repository's settings: each repository setting with the repository's effective value, what it inherits from
     *  its tenant and the deployment, and whether it set its own. */
    public List<Setting> repositorySettings(String repository) throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/repository/settings?repo=" + enc(repository), null, null);
        require(response, 200, "read the settings of repository " + repository);
        return List.of(JSON.readValue(response.body(), Setting[].class));
    }

    public void setRepositorySetting(String repository, String name, String value)
            throws IOException, InterruptedException {
        require(send("PUT", "/api/repository/settings/" + name + "?repo=" + enc(repository),
                body(Map.of("value", value)), "application/json"), 200, "set " + name + " for " + repository);
    }

    public void clearRepositorySetting(String repository, String name) throws IOException, InterruptedException {
        require(send("DELETE", "/api/repository/settings/" + name + "?repo=" + enc(repository), null, null),
                200, "clear " + name + " for " + repository);
    }

    /** A build-cache project's settings, as {@link #repositorySettings} are a repository's. */
    public List<Setting> projectSettings(String project) throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/cache/projects/" + enc(project) + "/settings", null, null);
        require(response, 200, "read the settings of project " + project);
        return List.of(JSON.readValue(response.body(), Setting[].class));
    }

    public void setProjectSetting(String project, String name, String value) throws IOException, InterruptedException {
        require(send("PUT", "/api/cache/projects/" + enc(project) + "/settings/" + name,
                body(Map.of("value", value)), "application/json"), 200, "set " + name + " for project " + project);
    }

    public void clearProjectSetting(String project, String name) throws IOException, InterruptedException {
        require(send("DELETE", "/api/cache/projects/" + enc(project) + "/settings/" + name, null, null),
                200, "clear " + name + " for project " + project);
    }

    /** A tenant's stored settings slice as one JSON bundle, for backup or transfer (SECRET keys excluded, as in the
     *  deployment-wide export). The raw body is returned unparsed so it re-imports byte-identically. */
    public String exportSettings(String tenant) throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/settings/export?tenant=" + enc(tenant), null, null);
        require(response, 200, "export settings for tenant " + tenant);
        return response.body();
    }

    public void importSettings(String tenant, String bundle) throws IOException, InterruptedException {
        require(send("POST", "/api/settings/import?tenant=" + enc(tenant),
                HttpRequest.BodyPublishers.ofString(bundle), "application/json"),
                200, "import settings for tenant " + tenant);
    }

    /** The tenant's storage quota: the byte ceiling ({@code 0} when unlimited) and the bytes currently stored. */
    public QuotaView quota() throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/quota", null, null);
        require(response, 200, "read the storage quota");
        return JSON.readValue(response.body(), QuotaView.class);
    }

    /** Set ({@code > 0}) or clear ({@code 0}, so the deployment's applies) the tenant's storage quota in bytes; the
     *  stored usage is recounted by the next cleanup pass. */
    public void setQuota(long maxBytes) throws IOException, InterruptedException {
        require(send("PUT", "/api/quota", body(Map.of("maxBytes", maxBytes)), "application/json"),
                200, "set the storage quota");
    }

    /** The tenant's request-rate ceiling in permits per minute ({@code 0} falls back to the deployment default), or
     *  {@code null} when rate limiting is not installed on this deployment (HTTP 501). */
    public RateLimitView rateLimit() throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/rate-limit", null, null);
        if (response.statusCode() == 501) {
            return null;
        }
        require(response, 200, "read the rate limit");
        return JSON.readValue(response.body(), RateLimitView.class);
    }

    /** Set ({@code > 0}) or clear ({@code 0}) the tenant's request-rate ceiling; {@code false} when rate limiting is
     *  not installed (HTTP 501). */
    public boolean setRateLimit(long permitsPerMinute) throws IOException, InterruptedException {
        HttpResponse<String> response = send("PUT", "/api/rate-limit",
                body(Map.of("permitsPerMinute", permitsPerMinute)), "application/json");
        if (response.statusCode() == 501) {
            return false;
        }
        require(response, 200, "set the rate limit");
        return true;
    }

    /** The orphaned-data report: persisted storage-manifest entries whose declaring module is no longer installed
     *  yet whose key-spaces still hold data. Operator-tenant only, read-only. */
    public OrphansView orphans() throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/admin/orphans", null, null);
        require(response, 200, "read the orphaned-data report");
        return JSON.readValue(response.body(), OrphansView.class);
    }

    /** Purge the named module's declared key-spaces - a dry run unless {@code dryRun} is explicitly {@code false} -
     *  or {@code null} when no storage-manifest entry names the module (HTTP 404). Operator-tenant only. */
    public PurgeReport purge(String namespace, boolean dryRun) throws IOException, InterruptedException {
        HttpResponse<String> response = send("POST",
                "/api/admin/purge?namespace=" + enc(namespace) + "&dryRun=" + dryRun, null, null);
        if (response.statusCode() == 404) {
            return null;
        }
        require(response, 200, (dryRun ? "plan the purge of " : "purge ") + namespace);
        return JSON.readValue(response.body(), PurgeReport.class);
    }

    /** The repositories defined at runtime: each name with its routing specification - the deployment's, or with a
     *  {@code tenant} the ones that tenant set for itself. */
    public List<NamedValue> repositories(String tenant) throws IOException, InterruptedException {
        return named("/api/repositories" + tenantQuery(tenant));
    }

    /** {@code ?tenant=<name>} for a tenant's own routing, or nothing for the deployment's. */
    private static String tenantQuery(String tenant) {
        return tenant == null ? "" : "?tenant=" + enc(tenant);
    }

    /**
     * Create a repository to hold one format; {@code true} when it was created, {@code false} when it already held
     * that format. A refusal - a format no repository can hold, or a repository holding another - carries the
     * server's own sentence.
     */
    public boolean createRepository(String name, String format) throws IOException, InterruptedException {
        return createRepository(name, format, null);
    }

    /** {@link #createRepository(String, String)}, giving the repository {@code description} when one is given. */
    public boolean createRepository(String name, String format, String description)
            throws IOException, InterruptedException {
        return createRepository(name, format, description, Map.of());
    }

    /**
     * {@link #createRepository(String, String, String)}, the repository created with {@code settings} as its own in
     * the same request - validated by the server before anything is written, and refused whole, with every refusal
     * named, when any value is refused.
     */
    public boolean createRepository(String name, String format, String description, Map<String, String> settings)
            throws IOException, InterruptedException {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("value", format);
        if (description != null) {
            request.put("description", description);
        }
        if (!settings.isEmpty()) {
            request.put("settings", settings);
        }
        HttpResponse<String> response = send("PUT", repository(name), body(request), "application/json");
        if (response.statusCode() == 201 || response.statusCode() == 200) {
            return response.statusCode() == 201;
        }
        if (response.statusCode() == 400 || response.statusCode() == 409) {
            throw new IOException(response.body());
        }
        require(response, 201, "create repository " + name);
        return false;
    }

    /** Give a repository {@code description}; an empty one clears it. A refusal carries the server's own sentence. */
    public void describeRepository(String name, String description) throws IOException, InterruptedException {
        HttpResponse<String> response = send("PUT", repository(name), body(Map.of("description", description)),
                "application/json");
        if (response.statusCode() == 400 || response.statusCode() == 404) {
            throw new IOException(response.body());
        }
        require(response, 200, "describe repository " + name);
    }

    /** Delete a repository and everything it holds; the server's own sentence about what it began. */
    public String deleteRepository(String name) throws IOException, InterruptedException {
        HttpResponse<String> response = send("DELETE", repository(name), null, null);
        if (response.statusCode() == 404) {
            throw new IOException(response.body());
        }
        require(response, 202, "delete repository " + name);
        return response.body();
    }

    /** The deployment's tenants - an operator key's view, which is the only one allowed to ask. */
    public List<String> tenants() throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/admin/tenants", null, null);
        require(response, 200, "list tenants");
        return JSON.readValue(response.body(), TenantList.class).tenants();
    }

    /** Create a tenant: {@code true} when this call created it, {@code false} when it already existed. */
    public boolean createTenant(String name) throws IOException, InterruptedException {
        HttpResponse<String> response = send("PUT", "/api/admin/tenants/" + name, null, null);
        if (response.statusCode() == 409) {
            return false;
        }
        require(response, 201, "create tenant " + name);
        return true;
    }

    /** Delete a tenant and everything it owns. */
    public void deleteTenant(String name) throws IOException, InterruptedException {
        HttpResponse<String> response = send("DELETE", "/api/admin/tenants/" + name, null, null);
        if (response.statusCode() == 404) {
            throw new IOException(response.body());
        }
        require(response, 200, "delete tenant " + name);
    }

    /** The answer {@code GET /api/admin/tenants} gives. */
    public record TenantList(List<String> tenants) {
    }

    public void setRepository(String tenant, String name, String specification)
            throws IOException, InterruptedException {
        require(send("PUT", "/api/repositories/" + name + tenantQuery(tenant), body(Map.of("value", specification)),
                "application/json"), 200, "set repository " + name);
    }

    public void removeRepository(String tenant, String name) throws IOException, InterruptedException {
        require(send("DELETE", "/api/repositories/" + name + tenantQuery(tenant), null, null), 200,
                "remove repository " + name);
    }

    /** The per-format proxy upstreams set at runtime: each format with its upstream URL - the deployment's, or with a
     *  {@code tenant} the ones that tenant set for itself. */
    public List<NamedValue> upstreams(String tenant) throws IOException, InterruptedException {
        return named("/api/upstreams" + tenantQuery(tenant));
    }

    public void setUpstream(String tenant, String format, String url) throws IOException, InterruptedException {
        require(send("PUT", "/api/upstreams/" + format + tenantQuery(tenant), body(Map.of("value", url)),
                "application/json"), 200, "set upstream " + format);
    }

    public void removeUpstream(String tenant, String format) throws IOException, InterruptedException {
        require(send("DELETE", "/api/upstreams/" + format + tenantQuery(tenant), null, null), 200,
                "remove upstream " + format);
    }

    /** The upstream hosts that carry a proxy credential (a private-registry login); the credentials are write-only,
     *  so only the hosts come back. */
    public List<String> upstreamCredentialHosts() throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/upstreams/auth", null, null);
        require(response, 200, "list upstream credentials");
        return List.of(JSON.readValue(response.body(), String[].class));
    }

    public void setUpstreamCredential(String host, String scheme, String username, String password, String token,
                                      String header) throws IOException, InterruptedException {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("scheme", scheme);
        if (username != null) {
            fields.put("username", username);
        }
        if (password != null) {
            fields.put("password", password);
        }
        if (token != null) {
            fields.put("token", token);
        }
        if (header != null) {
            fields.put("header", header);
        }
        require(send("PUT", "/api/upstreams/auth/" + host, body(fields), "application/json"),
                200, "set upstream credential for " + host);
    }

    public void removeUpstreamCredential(String host) throws IOException, InterruptedException {
        require(send("DELETE", "/api/upstreams/auth/" + host, null, null), 200,
                "remove upstream credential for " + host);
    }

    /** Every SPI and the providers installed against it. */
    public String spi() throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/admin/spi", null, null);
        require(response, 200, "read the SPI catalogue");
        return response.body();
    }

    /** What the server resolved its configuration to, and where each value came from. */
    public String config() throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/config", null, null);
        require(response, 200, "read the effective configuration");
        return response.body();
    }

    private List<NamedValue> named(String path) throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", path, null, null);
        require(response, 200, "read " + path);
        return List.of(JSON.readValue(response.body(), NamedValue[].class));
    }

    /** One runtime setting as the API returns it. {@code kind} is the value kind ({@code SECRET}, {@code CHOICE}, …)
     *  so the CLI masks a secret; a SECRET's {@code value} and {@code defaultValue} come back {@code null} - the server
     *  never reads a secret back - while {@code overridden}/{@code pinned} still say whether it is set.
     *  {@code advanced} marks tuning, whose default nearly every deployment keeps. */
    public record Setting(String key, String kind, String value, String defaultValue, boolean overridden,
                          boolean appliesImmediately, boolean pinned, String pinnedBy,
                          String group, String label, String description, boolean advanced) {
    }

    /** The first boot's wizard the server serves at {@code /api/setup}. */
    public record Setup(List<SetupStep> steps) {
    }

    /** One step of it: its id, title, what it says - empty for a settings group's - and the settings it asks. */
    public record SetupStep(String id, String title, String why, List<Setting> settings) {
    }

    public record NamedValue(String name, String value) {
    }

    /** The tenant's storage quota: the byte ceiling ({@code 0} when unlimited) and the bytes currently stored. */
    public record QuotaView(long maxBytes, long usedBytes) {
    }

    /** The tenant's request-rate ceiling in permits per minute ({@code 0} falls back to the deployment default). */
    public record RateLimitView(long permitsPerMinute) {
    }

    /** The orphaned-data report: modules whose persisted storage manifest still holds data although the module is
     *  no longer installed. Purely informational - reclaiming is the explicit {@code purge} verb. {@code unreachable}
     *  names the reserved key spaces no manifest entry can describe, with the {@code note} behind it, so an empty
     *  report reads as "no orphaned plug-in data" rather than "no data at all". */
    public record OrphansView(List<OrphanView> orphans, List<String> unreachable, String note) {
    }

    /** One orphaned module's leftovers, summed over its declared key-spaces across all tenants. */
    public record OrphanView(String namespace, long objects, long bytes) {
    }

    /** What one purge (or its dry run) covered: the per-prefix breakdown of the fully scoped key-spaces and the
     *  totals - what would be deleted on a dry run, what was deleted otherwise - plus the reserved key spaces the
     *  purge deliberately cannot reach ({@code unreachable}, with its {@code note}), so a blast radius that lists no
     *  audit rows is not read as "there is no audit data". */
    public record PurgeReport(String namespace, boolean dryRun, List<PurgeSpace> spaces, long objects, long bytes,
                              List<String> unreachable, String note) {
    }

    /** One fully scoped prefix ({@code <tenant>/<repository>/<prefix>} or {@code <tenant>/<prefix>}) and what it
     *  holds. */
    public record PurgeSpace(String prefix, long objects, long bytes) {
    }
}
