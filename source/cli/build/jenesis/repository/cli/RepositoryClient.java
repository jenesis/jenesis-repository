package build.jenesis.repository.cli;

import module java.base;
import module java.net.http;
import module tools.jackson.databind;

import static java.nio.charset.StandardCharsets.UTF_8;
import build.jenesis.repository.scope.Scopes;

/**
 * A thin client of a repository's HTTP API, holding the base URL and the key sent on each request as the {@code
 * Jenesis-Repository-Key} header. It drives the same surface the console and the API expose - browsing and searching
 * a repository, scanning it for known vulnerabilities, deploying an artifact through the compliance gate, and
 * administering the runtime settings and the credentials - so the CLI is equal to them. JSON is read and written with
 * Jackson; the reader ignores unknown fields, so a server that adds one does not break an older client.
 */
public final class RepositoryClient {

    private static final JsonMapper JSON = JsonMapper.builder()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .build();

    /** The request header that turns a publish into a batch explode; only {@code zip} is understood. Mirrors the free
     *  core's {@code BatchIngestion.EXPLODE_HEADER}, named here because the CLI module does not depend on the server. */
    private static final String EXPLODE_HEADER = "Jenesis-Explode";

    private final URI base;
    private final String key;
    private final String tenant;
    private final HttpClient client;

    /** A client addressing the repositories of the tenant {@code key} belongs to. */
    public RepositoryClient(URI base, String key, HttpClient client) {
        this(base, key, null, client);
    }

    /**
     * A client addressing the repositories of {@code tenant} - for a deployment whose URLs name a tenant other than
     * the key's, such as a single-tenant one serving its configured tenant to an operator key minted elsewhere.
     * {@code null} is the key's tenant.
     */
    public RepositoryClient(URI base, String key, String tenant, HttpClient client) {
        if (base == null) {
            throw new IllegalArgumentException("A repository URL is required");
        }
        this.base = base;
        this.key = key;
        this.tenant = tenant;
        this.client = client;
    }

    /** The deployment's runtime settings: each key with its effective value, default, override state and whether a
     *  change applies live. */
    public List<Setting> settings() throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/settings", null, null);
        require(response, 200, "read settings");
        return List.of(JSON.readValue(response.body(), Setting[].class));
    }

    public void setSetting(String name, String value) throws IOException, InterruptedException {
        require(send("PUT", "/api/settings/" + name, body(Map.of("value", value)), "application/json"),
                200, "set " + name);
    }

    public void clearSetting(String name) throws IOException, InterruptedException {
        require(send("DELETE", "/api/settings/" + name, null, null), 200, "clear " + name);
    }

    /** The first-run setup guide: its steps, each with the settings rows it is about, as the console renders it. */
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

    public void setSetting(String tenant, String name, String value) throws IOException, InterruptedException {
        require(send("PUT", "/api/settings/" + name + "?tenant=" + enc(tenant), body(Map.of("value", value)),
                "application/json"), 200, "set " + name + " for tenant " + tenant);
    }

    public void clearSetting(String tenant, String name) throws IOException, InterruptedException {
        require(send("DELETE", "/api/settings/" + name + "?tenant=" + enc(tenant), null, null),
                200, "clear " + name + " for tenant " + tenant);
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

    /** The declared-license inventory of a repository rolled into per-category and per-SPDX-id facet counts.
     *  {@code indexed} is false when the search index is absent, so empty facets are not a clean bill. */
    public LicensesView licenses(String repo) throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/licenses?repo=" + enc(repo), null, null);
        require(response, 200, "read the license inventory of " + repo);
        return JSON.readValue(response.body(), LicensesView.class);
    }

    /** The compliance-gate holds for a repository - what was quarantined on the publish or proxy path, with the
     *  verdict and the reasons - so a reviewer can release or discard each. */
    public List<QuarantineEvent> quarantine(String repo) throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/quarantine?repo=" + enc(repo), null, null);
        require(response, 200, "read the quarantine of " + repo);
        return JSON.readValue(response.body(), QuarantineView.class).events();
    }

    /** Release a held artifact into the repository's layout. */
    public void releaseQuarantine(String repo, String path) throws IOException, InterruptedException {
        require(send("POST", "/api/quarantine/release?repo=" + enc(repo), body(Map.of("path", path)),
                "application/json"), 200, "release " + path);
    }

    /** Discard a held artifact so it is never served. */
    public void discardQuarantine(String repo, String path) throws IOException, InterruptedException {
        require(send("POST", "/api/quarantine/discard?repo=" + enc(repo), body(Map.of("path", path)),
                "application/json"), 200, "discard " + path);
    }

    /** A signer seen on a repository's accepted versions: its wire identity, the hash the index files it under,
     *  and for a keyless identity the issuer and subject the server shows apart. */
    public record Signer(String signer, String id, String issuer, String subject) {
    }

    /** One coordinate a signer signed: how many of its versions, since when, and the last one counted. */
    public record SignedCoordinate(String ecosystem, String coordinate, int versions, String since, String last) {
    }

    private record SignersView(List<Signer> signers, String next) {
    }

    private record SignedView(String signer, List<SignedCoordinate> coordinates, String next) {
    }

    /** The signers whose trusted signatures the gate accepted on a repository's versions - the first page of the
     *  index, as the console lists them. */
    public List<Signer> signers(String repo) throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/signers?repo=" + enc(repo), null, null);
        require(response, 200, "read the signers of " + repo);
        return JSON.readValue(response.body(), SignersView.class).signers();
    }

    /** Everything one signer signed in a repository - a key's reach before it is revoked; {@code signer} is the
     *  wire form, {@code <scheme>:<value>}. */
    public List<SignedCoordinate> signedBy(String repo, String signer) throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/signers/signed?repo=" + enc(repo) + "&signer=" + enc(signer),
                null, null);
        require(response, 200, "read what " + signer + " signed in " + repo);
        return JSON.readValue(response.body(), SignedView.class).coordinates();
    }

    /** The publish-through forwarding outbox of a repository: what is still queued, how many attempts each has taken,
     *  whether it is parked after a terminal failure and the last error. */
    public List<ForwardingEntry> forwarding(String repo) throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/forwarding?repo=" + enc(repo), null, null);
        require(response, 200, "read the forwarding outbox of " + repo);
        return JSON.readValue(response.body(), ForwardingView.class).entries();
    }

    /** Unpark a parked forward for another delivery attempt; {@code false} when nothing parked is queued at the path
     *  (HTTP 404, nothing to retry). */
    /** Forward every accepted publish in the caller's {@code repo} to {@code destRepo} of {@code destTenant}. The
     *  key must administer both tenants. */
    public void addInternalForward(String repo, String destTenant, String destRepo)
            throws IOException, InterruptedException {
        require(send("POST", "/api/forwarding/internal?sourceRepo=" + enc(repo) + "&destTenant=" + enc(destTenant)
                + "&destRepo=" + enc(destRepo), HttpRequest.BodyPublishers.noBody(), null), 200,
                "forward " + repo + " to " + destTenant + "/" + destRepo);
    }

    /** Stop forwarding {@code repo} to {@code destRepo} of {@code destTenant}: {@code false} when it was not. */
    public boolean removeInternalForward(String repo, String destTenant, String destRepo)
            throws IOException, InterruptedException {
        HttpResponse<String> response = send("DELETE", "/api/forwarding/internal?sourceRepo=" + enc(repo)
                + "&destTenant=" + enc(destTenant) + "&destRepo=" + enc(destRepo), null, null);
        if (response.statusCode() == 404) {
            return false;
        }
        require(response, 200, "stop forwarding " + repo + " to " + destTenant + "/" + destRepo);
        return true;
    }

    /** The maintainer health stored for a repository's coordinates, one page; {@code refresh} starts a re-score of
     *  every coordinate off the request path and answers the ledger as it stands. */
    public HealthReport health(String repo, boolean refresh) throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/health?repo=" + enc(repo)
                + (refresh ? "&refresh=true" : ""), null, null);
        require(response, 200, "read the health of " + repo);
        return JSON.readValue(response.body(), HealthReport.class);
    }

    /** The answer {@code GET /api/health} gives: the scored coordinates, and when the scores were last refreshed. */
    public record HealthReport(boolean available, boolean ranked, List<HealthEntry> entries, String nextCursor,
                               int total, String lastScanned) {
    }

    /** One coordinate's maintainer health: the overall score and its three components ({@code -1} when unknown). */
    public record HealthEntry(String ecosystem, String coordinate, String sourceRepository, double overall,
                              double maintenance, double review, double provenance, String scannedAt) {
    }

    public boolean retryForwarding(String repo, String path) throws IOException, InterruptedException {
        HttpResponse<String> response = send("POST", "/api/forwarding/retry?repo=" + enc(repo),
                body(Map.of("path", path)), "application/json");
        if (response.statusCode() == 404) {
            return false;
        }
        require(response, 200, "retry forwarding " + path);
        return true;
    }

    /** The staging ids of a repository with their state and item count, or {@code null} when staging is not installed
     *  on this deployment (HTTP 501). */
    public List<StagingEntry> staging(String repo) throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/repository/staging?repo=" + enc(repo), null, null);
        if (response.statusCode() == 501) {
            return null;
        }
        require(response, 200, "list the staging of " + repo);
        return JSON.readValue(response.body(), StagingList.class).repositories();
    }

    /** Promote a staged id into the release layout, returning the HTTP status (200 promoted, 409 already sealed,
     *  501 staging not installed). */
    public int promoteStaging(String repo, String id) throws IOException, InterruptedException {
        return send("POST", "/api/repository/staging/" + enc(id) + "/promote?repo=" + enc(repo), null, null)
                .statusCode();
    }

    /** Drop a staged id and its held blobs, returning the HTTP status (200 dropped, 409 already sealed, 501 staging
     *  not installed). */
    public int dropStaging(String repo, String id) throws IOException, InterruptedException {
        return send("POST", "/api/repository/staging/" + enc(id) + "/drop?repo=" + enc(repo), null, null).statusCode();
    }

    /** The published-index descriptor for a repository - the generation, watermark and the chain of immutable chunks
     *  (each with its record count and sizes) - or {@code null} when the published-index module is not installed
     *  (HTTP 404, the route is absent). */
    public IndexDescriptor index(String repo) throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/index?repo=" + enc(repo), null, null);
        if (response.statusCode() == 404) {
            return null;
        }
        require(response, 200, "read the published index of " + repo);
        return JSON.readValue(response.body(), IndexDescriptor.class);
    }

    /** The entries directly under a path in a repository's layout (a directory listing), for walking the tree. */
    public List<String> browse(String repo, String prefix) throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET",
                "/api/browse?repo=" + enc(repo) + "&prefix=" + enc(prefix), null, null);
        require(response, 200, "browse " + repo);
        return JSON.readValue(response.body(), Listing.class).entries();
    }

    /**
     * A repository's published coordinates ({@code group:artifact:version}) matching a substring (empty for all).
     * The endpoint answers one bounded page at a time, so this follows its cursor to the end rather than
     * returning the first page as if it were the whole match set - a client may page where a request path may not.
     * A page that reports itself truncated without offering a cursor is the no-index substring-scan degrade, which
     * has no resume point; it stops there, and the rows it returns are all the server can reach.
     */
    public List<String> search(String repo, String query) throws IOException, InterruptedException {
        List<String> results = new ArrayList<>();
        String cursor = null;
        do {
            HttpResponse<String> response = send("GET", "/api/search?repo=" + enc(repo) + "&q=" + enc(query)
                    + (cursor == null ? "" : "&cursor=" + enc(cursor)), null, null);
            require(response, 200, "search " + repo);
            Search page = JSON.readValue(response.body(), Search.class);
            results.addAll(page.results());
            String next = page.nextCursor();
            // Strictly advancing, so this terminates; a repeated or blank cursor is a server that cannot page on.
            cursor = next == null || next.isBlank() || next.equals(cursor) ? null : next;
        } while (cursor != null);
        return List.copyOf(results);
    }

    /** Re-scan a repository's published coordinates against the advisory feed, reporting each one the feed knows a
     *  vulnerability for with the advisory id, its severity, whether it is a malicious package, and the fixed
     *  version. {@code scanned} is false when no feed is configured (so an empty report is not a clean bill). */
    public VulnerabilityReport vulnerabilities(String repo) throws IOException, InterruptedException {
        return vulnerabilities(repo, null);
    }

    /** The {@link #vulnerabilities(String) vulnerability report} narrowed to one call-graph reachability verdict
     *  ({@code reachable} / {@code not-reachable} / {@code unknown}; blank or {@code null} shows everything) - a
     *  view facet the server applies, never a change to what is stored. An un-analyzed advisory matches the
     *  {@code unknown} facet, so "everything not proven unreachable" hides nothing the engine has not reached. */
    public VulnerabilityReport vulnerabilities(String repo, String reachability)
            throws IOException, InterruptedException {
        return vulnerabilities(repo, reachability, null);
    }

    /** The {@link #vulnerabilities(String, String) vulnerability report} additionally narrowed by the AI
     *  applicability opinion ({@code applies} / {@code not-applicable} / {@code unknown}; blank or {@code null}
     *  shows everything) - the same server-side view facet rule: a never-judged advisory matches {@code unknown},
     *  and nothing stored changes. */
    public VulnerabilityReport vulnerabilities(String repo, String reachability, String applicability)
            throws IOException, InterruptedException {
        return pagedVulnerabilities(repo, reachability, applicability, false);
    }

    /** The explicit re-scan (Principle 10's write path): query the enabled feeds for every published coordinate,
     *  persist the findings, and return the refreshed report - the read-only {@link #vulnerabilities(String)}
     *  renders the durable ledger only, so a vulnerability declared after the last sweep appears here first. */
    public VulnerabilityReport rescanVulnerabilities(String repo) throws IOException, InterruptedException {
        return pagedVulnerabilities(repo, null, null, true);
    }

    /** Accumulate every worst-first page the server serves into one report: the server bounds each response to a page
     *  (so it never buffers the whole vulnerable set in heap) and hands back a {@code nextCursor}; the client follows it
     *  to the last page, so a caller still receives the whole ranked report. A refresh is requested only on the first
     *  page - it triggers the (single) live re-scan and reindex, after which the remaining pages read the fresh index -
     *  and the reachability/applicability facets ride every page so each is narrowed identically. */
    private VulnerabilityReport pagedVulnerabilities(String repo, String reachability, String applicability,
                                                     boolean refresh) throws IOException, InterruptedException {
        List<VulnerableArtifact> vulnerable = new ArrayList<>();
        boolean scanned = false;
        List<Signal> signals = null;
        List<String> feedWarnings = List.of();
        String cursor = null;
        boolean first = true;
        do {
            StringBuilder path = new StringBuilder("/api/vulnerabilities?repo=").append(enc(repo));
            appendFilter(path, "reachability", reachability);
            appendFilter(path, "applicability", applicability);
            if (refresh && first) {
                path.append("&refresh=true");                   // the single live re-scan + reindex, on the first page only
            }
            if (cursor != null) {
                path.append("&after=").append(enc(cursor));
            }
            HttpResponse<String> response = send("GET", path.toString(), null, null);
            require(response, 200, (refresh ? "rescan " : "scan ") + repo);
            VulnerabilityReport page = JSON.readValue(response.body(), VulnerabilityReport.class);
            if (first) {
                scanned = page.scanned();
                signals = page.signals();
                // Only the first page's: it is a property of the scan behind the report, not of a page of rows.
                feedWarnings = page.feedWarnings() == null ? List.of() : page.feedWarnings();
                first = false;
            }
            if (page.vulnerable() != null) {
                vulnerable.addAll(page.vulnerable());
            }
            cursor = page.nextCursor();
        } while (cursor != null && !cursor.isBlank());
        return new VulnerabilityReport(scanned, signals, vulnerable, null, feedWarnings);
    }

    /** The persisted findings ledger of a repository, filterable by coordinate (bare or {@code coordinate:version}),
     *  kind, source, category and severity - superseded findings included with their mark. Returns {@code null} when
     *  the findings module is not installed on this deployment (HTTP 501). */
    public FindingsReport findings(String repo, String coordinate, String kind, String source, String category,
                                   String severity) throws IOException, InterruptedException {
        StringBuilder path = new StringBuilder("/api/findings?repo=").append(enc(repo));
        appendFilter(path, "coordinate", coordinate);
        appendFilter(path, "kind", kind);
        appendFilter(path, "source", source);
        appendFilter(path, "category", category);
        appendFilter(path, "severity", severity);
        HttpResponse<String> response = send("GET", path.toString(), null, null);
        if (response.statusCode() == 501) {
            return null;
        }
        require(response, 200, "query findings in " + repo);
        return JSON.readValue(response.body(), FindingsReport.class);
    }

    private static void appendFilter(StringBuilder path, String name, String value) {
        if (value != null && !value.isBlank()) {
            path.append('&').append(name).append('=').append(enc(value));
        }
    }

    /** The reverse-dependency ("who depends on X") + CVE blast-radius query. With a {@code coordinate}, the
     *  artifacts whose recorded dependency tree names it; without one, the coordinates the index holds. Returns
     *  {@code null} when the reverse-dependency index is not installed on this deployment (HTTP 501). */
    public DependentsReport dependents(String repo, String coordinate) throws IOException, InterruptedException {
        String path = "/api/dependents?repo=" + enc(repo);
        if (coordinate != null && !coordinate.isBlank()) {
            path += "&coordinate=" + enc(coordinate);
        }
        HttpResponse<String> response = send("GET", path, null, null);
        if (response.statusCode() == 501) {
            return null;
        }
        require(response, 200, "query dependents in " + repo);
        return JSON.readValue(response.body(), DependentsReport.class);
    }

    /** The declared tier of the reverse-dependency index: one page of the versions whose manifest declares a
     *  dependency on the package {@code dependency}, resumed after {@code cursor}. Returns {@code null} when the index
     *  is not installed on this deployment (HTTP 501). */
    public DependentsReport declarations(String repo, String dependency, String cursor)
            throws IOException, InterruptedException {
        String path = "/api/dependents?repo=" + enc(repo) + "&package=" + enc(dependency);
        if (cursor != null && !cursor.isBlank()) {
            path += "&after=" + enc(cursor);
        }
        HttpResponse<String> response = send("GET", path, null, null);
        if (response.statusCode() == 501) {
            return null;
        }
        require(response, 200, "query the declarations of " + dependency + " in " + repo);
        return JSON.readValue(response.body(), DependentsReport.class);
    }

    /**
     * The generated SBOM for a hosted coordinate ({@code path} set) or a whole repository ({@code path} null), in the
     * requested {@code format} (CycloneDX by default). The document is a small metadata index, so it is returned as a
     * string for the caller to print or write to a file.
     */
    public String sbom(String repo, String path, String format) throws IOException, InterruptedException {
        String query = "/api/sbom?repo=" + enc(repo);
        if (path != null && !path.isBlank()) {
            query += "&path=" + enc(path);
        }
        if (format != null && !format.isBlank()) {
            query += "&format=" + enc(format);
        }
        HttpResponse<String> response = send("GET", query, null, null);
        require(response, 200, "generate the SBOM for " + repo);
        return response.body();
    }

    /** What the server's deployment carries - installed formats, import sources, report columns and feature
     *  flags - or {@code null} when an older server does not answer the endpoint. */
    public Capabilities capabilities() throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/capabilities", null, null);
        if (response.statusCode() == 404) {
            return null;
        }
        require(response, 200, "read capabilities");
        return JSON.readValue(response.body(), Capabilities.class);
    }

    /**
     * The deployment's licence state, read from the {@code Jenesis-License} header any {@code /api/**} answer
     * carries - empty when the deployment sends none.
     *
     * <p>Empty is the ordinary case and never a warning: a deployment that sends no such header simply
     * does not send it, and inventing "unlicensed" out of an absence would be wrong in exactly the direction that
     * annoys people. This is the whole of the CLI's licence knowledge; there is no second endpoint to ask.
     */
    public Optional<String> licenseState() throws IOException, InterruptedException {
        return send("GET", "/api/capabilities", null, null).headers().firstValue("Jenesis-License");
    }

    /** A version's recorded signature as the API answers it: the outcome, the signer, the grade, where the material
     *  sat, the trust source's name and the same in the operator's words, and what else the material stated. */
    public record Signature(String outcome, String signer, String grade, String location, String source,
                            String admittedBy, Map<String, String> details) {
    }

    /** The signature recorded for the artifact at a path within the repository, or {@code null} when none was - a version
     *  published before signatures were checked, which is not one checked and found wanting. */
    public Signature signature(String repo, String path) throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET",
                "/api/signature?repo=" + enc(repo) + "&path=" + enc(path), null, null);
        if (response.statusCode() == 204) {
            return null;
        }
        require(response, 200, "read the signature of " + repo + path);
        return JSON.readValue(response.body(), Signature.class);
    }

    /** The signed provenance attestation (a DSSE envelope) for a published artifact at a path within the repository. */
    public String provenance(String repo, String path) throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET",
                "/api/provenance?repo=" + enc(repo) + "&path=" + enc(path), null, null);
        require(response, 200, "fetch provenance for " + repo + path);
        return response.body();
    }

    /** The attestation together with the verification material bound to it at signing time - the certificate chain and
     *  the transparency-log entry, each {@code null} where the signer has none - so a consumer verifies offline;
     *  {@code null} when provenance signing is not enabled (HTTP 404). */
    public ProvenanceMaterial provenanceMaterial(String repo, String path) throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET",
                "/api/provenance?repo=" + enc(repo) + "&path=" + enc(path) + "&material", null, null);
        if (response.statusCode() == 404) {
            return null;
        }
        require(response, 200, "fetch provenance material for " + repo + path);
        return JSON.readValue(response.body(), ProvenanceMaterial.class);
    }

    /** The signer's published public key (PEM), or {@code null} when provenance signing is not enabled (HTTP 404). */
    public String provenanceKey() throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/provenance/key", null, null);
        if (response.statusCode() == 404) {
            return null;
        }
        require(response, 200, "fetch the provenance public key");
        return response.body();
    }

    /** The certificate chain binding the signing key to an identity (PEM) for a certificate-shaped keyless signer, or
     *  {@code null} for a bare-key deployment or when signing is not enabled (HTTP 404). */
    public String provenanceCertificate() throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/provenance/certificate", null, null);
        if (response.statusCode() == 404) {
            return null;
        }
        require(response, 200, "fetch the provenance certificate");
        return response.body();
    }

    /** Deploy bytes to a repository at the given path within it (a Maven repository's {@code /maven/...}, an npm
     *  one's {@code /<package>/...}) through the compliance gate, returning the HTTP status the gate's verdict maps to
     *  (201 published, 202 quarantined, 422 rejected, 405 not writable). The client assumes no layout. */
    public int deploy(String repo, String path, byte[] bytes) throws IOException, InterruptedException {
        return send("PUT", repository(repo) + path,
                HttpRequest.BodyPublishers.ofByteArray(bytes), "application/octet-stream").statusCode();
    }

    /** Deploy a file on disk at the given path within the repository, streaming it straight from the filesystem rather than buffering
     *  the whole artifact into heap - the streaming twin of {@link #deploy(String, String, byte[])} for the CLI's
     *  upload path, where the file may be artifact-sized (stream, never buffer). */
    public int deploy(String repo, String path, Path file) throws IOException, InterruptedException {
        return send("PUT", repository(repo) + path,
                HttpRequest.BodyPublishers.ofFile(file), "application/octet-stream").statusCode();
    }

    /** Deploy an archive at a path within the repository with the batch-explode header set, so the server walks the archive and
     *  publishes each entry through the compliance gate on the entry's own format path; returns the HTTP status and,
     *  on a batch response (200 or a 400 malformed archive), the per-entry manifest ({@code path -> stored |
     *  quarantined | rejected | unclaimed}). When batch upload is off on the deployment the header is inert and the
     *  archive is stored verbatim as one artifact, so the manifest is {@code null} and the status is the plain deploy
     *  verdict. */
    public ExplodeResult deployExplode(String repo, String path, byte[] archive)
            throws IOException, InterruptedException {
        return explode(send("PUT", repository(repo) + path,
                HttpRequest.BodyPublishers.ofByteArray(archive), "application/zip",
                Map.of(EXPLODE_HEADER, "zip")));
    }

    /** {@link #deployExplode(String, String, byte[])} streaming the archive straight from a file on disk rather than
     *  buffering it into heap, for the CLI's {@code --explode} upload path. */
    public ExplodeResult deployExplode(String repo, String path, Path archive)
            throws IOException, InterruptedException {
        return explode(send("PUT", repository(repo) + path,
                HttpRequest.BodyPublishers.ofFile(archive), "application/zip",
                Map.of(EXPLODE_HEADER, "zip")));
    }

    private static ExplodeResult explode(HttpResponse<String> response) {
        ExplodeManifest manifest = null;
        String body = response.body();
        if (body != null && body.stripLeading().startsWith("{")) {
            try {
                ExplodeManifest parsed = JSON.readValue(body, ExplodeManifest.class);
                // A real batch manifest always carries an entries array (empty when nothing was published). A foreign
                // JSON object that merely starts with '{' - a server error body like {"status":403,...} rendered by
                // the framework's default error handler - parses (Jackson ignores its unknown fields) into a manifest
                // with a null entries, and must not be mistaken for one, or the caller NPEs walking a null entry list
                // instead of degrading to the "explode failed (HTTP <status>)" branch on the status alone.
                if (parsed != null && parsed.entries() != null) {
                    manifest = parsed;
                }
            } catch (RuntimeException _) {
                // a non-manifest body (a plain verbatim store, an error page) leaves the manifest null
            }
        }
        return new ExplodeResult(response.statusCode(), manifest);
    }

    /** The tenant's credentials: each id with its label, expiry and use count (the grants are omitted from this view). */
    public List<Credential> credentials() throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/credentials", null, null);
        require(response, 200, "list credentials");
        return List.of(JSON.readValue(response.body(), Credential[].class));
    }

    /** Mint a credential, returning its id and the secret key shown only once. */
    public Minted mint(String label) throws IOException, InterruptedException {
        HttpRequest.BodyPublisher body = label == null || label.isBlank()
                ? body(Map.of())
                : body(Map.of("label", label));
        HttpResponse<String> response = send("POST", "/api/credentials", body, "application/json");
        require(response, 201, "mint a credential");
        return JSON.readValue(response.body(), Minted.class);
    }

    public void revoke(String id) throws IOException, InterruptedException {
        require(send("DELETE", "/api/credentials/" + id, null, null), 200, "revoke " + id);
    }

    /** Grant a credential a comma-separated set of tokens at a scope (a project/repository subtree, {@code *} for all). */
    public void setGrant(String id, String scope, List<String> tokens) throws IOException, InterruptedException {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("scope", scope);
        fields.put("tokens", tokens);
        require(send("POST", "/api/credentials/" + id + "/grants", body(fields), "application/json"),
                200, "grant " + scope + " on " + id);
    }

    public void removeGrant(String id, String scope) throws IOException, InterruptedException {
        require(send("DELETE", "/api/credentials/" + id + "/grants/" + enc(scope), null, null),
                200, "remove grant " + scope + " on " + id);
    }

    /** The tenant's groups, each with the rights it grants. */
    public List<Group> groups() throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/groups", null, null);
        require(response, 200, "list groups");
        return List.of(JSON.readValue(response.body(), Group[].class));
    }

    /** One group's members, by provider-qualified id. */
    public List<String> groupMembers(String name) throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/groups/" + enc(name) + "/members", null, null);
        require(response, 200, "list members of " + name);
        return List.of(JSON.readValue(response.body(), String[].class));
    }

    /** Grant the group rights at a scope; every member holds them from the next request. */
    public void setGroupGrant(String name, String scope, List<String> tokens, String expires)
            throws IOException, InterruptedException {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("scope", scope);
        fields.put("tokens", tokens);
        fields.put("expires", expires);
        require(send("POST", "/api/groups/" + enc(name) + "/grants", body(fields), "application/json"),
                200, "grant " + scope + " on group " + name);
    }

    public void removeGroupGrant(String name, String scope) throws IOException, InterruptedException {
        require(send("DELETE", "/api/groups/" + enc(name) + "/grants/" + enc(scope), null, null),
                200, "remove grant " + scope + " on group " + name);
    }

    /** Put a principal in the group. The group need not exist first - one with members and no grants confers
     *  nothing, so there is no order in which an unmade decision reads as access. */
    public void addGroupMember(String name, String id) throws IOException, InterruptedException {
        require(send("POST", "/api/groups/" + enc(name) + "/members",
                        body(Map.of("id", id)), "application/json"),
                200, "add " + id + " to " + name);
    }

    /** Take a principal out of the group. The id rides as a query parameter because it carries a slash. */
    public void removeGroupMember(String name, String id) throws IOException, InterruptedException {
        require(send("DELETE", "/api/groups/" + enc(name) + "/members?id=" + enc(id), null, null),
                200, "remove " + id + " from " + name);
    }

    public void removeGroup(String name) throws IOException, InterruptedException {
        require(send("DELETE", "/api/groups/" + enc(name), null, null), 200, "remove group " + name);
    }

    /** The tenant's people, each with the rights granted to them directly. */
    public List<Principal> principals() throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/principals", null, null);
        require(response, 200, "list principals");
        return List.of(JSON.readValue(response.body(), Principal[].class));
    }

    /** Grant a person rights at a scope. The id is in the body because it carries a slash. */
    public void setPrincipalGrant(String id, String scope, List<String> tokens, String expires)
            throws IOException, InterruptedException {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("id", id);
        fields.put("scope", scope);
        fields.put("tokens", tokens);
        fields.put("expires", expires);
        require(send("POST", "/api/principals/grants", body(fields), "application/json"),
                200, "grant " + scope + " to " + id);
    }

    public void removePrincipalGrant(String id, String scope) throws IOException, InterruptedException {
        require(send("DELETE", "/api/principals/grants?id=" + enc(id) + "&scope=" + enc(scope), null, null),
                200, "remove grant " + scope + " from " + id);
    }

    public void removePrincipal(String id) throws IOException, InterruptedException {
        require(send("DELETE", "/api/principals?id=" + enc(id), null, null), 200, "remove principal " + id);
    }

    /** Set or clear (blank) a credential's expiry: a leading {@code P} is an ISO-8601 duration from now, else an
     *  absolute instant. */
    public void setExpiry(String id, String expires) throws IOException, InterruptedException {
        require(send("PUT", "/api/credentials/" + id + "/expiry", body(Map.of("expires", expires == null ? "" : expires)),
                "application/json"), 200, "set the expiry of " + id);
    }

    /** Rotate a credential: mint a successor inheriting its grants and allowlist, returning its id and secret once,
     *  the old key expiring after the overlap (ISO-8601, default a week when {@code overlap} is blank). */
    public Minted rotate(String id, String overlap) throws IOException, InterruptedException {
        HttpRequest.BodyPublisher body = overlap == null || overlap.isBlank()
                ? body(Map.of())
                : body(Map.of("overlap", overlap));
        HttpResponse<String> response = send("POST", "/api/credentials/" + id + "/rotate", body, "application/json");
        require(response, 201, "rotate " + id);
        return JSON.readValue(response.body(), Minted.class);
    }

    /** Set or clear (blank) a credential's source-IP allowlist (comma-separated CIDRs or addresses). */
    public void setAllowedAddresses(String id, String addresses) throws IOException, InterruptedException {
        require(send("PUT", "/api/credentials/" + id + "/allowed-ips",
                body(Map.of("addresses", addresses == null ? "" : addresses)), "application/json"),
                200, "set the allowlist of " + id);
    }

    /** The tenant's credential-lifetime policy: the default lifetime stamped on a blank-expiry mint and the optional
     *  ceiling beyond which no key may live ({@code null} when nothing caps it), both ISO-8601 durations. */
    public PolicyView policy() throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/policy", null, null);
        require(response, 200, "read the credential policy");
        return JSON.readValue(response.body(), PolicyView.class);
    }

    /** Set (or clear with a blank value) the tenant's default and maximum credential lifetimes (ISO-8601 durations). */
    public void setPolicy(String defaultLifetime, String maxLifetime) throws IOException, InterruptedException {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("defaultLifetime", defaultLifetime == null ? "" : defaultLifetime);
        fields.put("maxLifetime", maxLifetime == null ? "" : maxLifetime);
        require(send("PUT", "/api/policy", body(fields), "application/json"), 200, "set the credential policy");
    }

    /** The tenant's storage quota: the byte ceiling ({@code 0} when unlimited) and the bytes currently stored. */
    public QuotaView quota() throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/quota", null, null);
        require(response, 200, "read the storage quota");
        return JSON.readValue(response.body(), QuotaView.class);
    }

    /** Set ({@code > 0}) or clear ({@code 0}) the tenant's storage quota in bytes; stored usage is recounted. */
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

    /** The tenant's named roles (name to comma-separated tokens): the built-in read-only/deploy/admin plus custom ones. */
    public Map<String, String> roles() throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/roles", null, null);
        require(response, 200, "read the roles");
        Map<String, String> roles = new LinkedHashMap<>();
        if (JSON.readValue(response.body(), Object.class) instanceof Map<?, ?> parsed) {
            parsed.forEach((name, tokens) -> roles.put(String.valueOf(name), tokens == null ? "" : String.valueOf(tokens)));
        }
        return roles;
    }

    public void setRole(String name, String tokens) throws IOException, InterruptedException {
        require(send("PUT", "/api/roles/" + enc(name), body(Map.of("tokens", tokens)), "application/json"),
                200, "set role " + name);
    }

    public void removeRole(String name) throws IOException, InterruptedException {
        require(send("DELETE", "/api/roles/" + enc(name), null, null), 200, "remove role " + name);
    }

    /** The tenant's OIDC trusts: each exchanges a matching id-token for a short-lived credential at {@code /api/token}. */
    public List<TrustView> trusts() throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/trusts", null, null);
        require(response, 200, "read the trusts");
        return List.of(JSON.readValue(response.body(), TrustView[].class));
    }

    /** Add or replace an OIDC trust by name; issuer, scope and rights are required, the rest optional (blank omits). */
    public void setTrust(String name, String issuer, String audience, String subject, String scope, String rights,
                         String ttl) throws IOException, InterruptedException {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("issuer", issuer);
        if (audience != null) {
            fields.put("audience", audience);
        }
        if (subject != null) {
            fields.put("subject", subject);
        }
        fields.put("scope", scope);
        fields.put("rights", rights);
        if (ttl != null) {
            fields.put("ttl", ttl);
        }
        require(send("PUT", "/api/trusts/" + enc(name), body(fields), "application/json"), 200, "set trust " + name);
    }

    public void removeTrust(String name) throws IOException, InterruptedException {
        require(send("DELETE", "/api/trusts/" + enc(name), null, null), 200, "remove trust " + name);
    }

    /** The tenant's audit trail, newest first, optionally bounded by ISO-8601 {@code from}/{@code to} instants and a
     *  single {@code action}; {@code null} when audit is not installed on this deployment (HTTP 501). */
    public List<AuditEvent> audit(String from, String to, String action) throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/audit" + auditQuery(from, to, action), null, null);
        if (response.statusCode() == 501) {
            return null;
        }
        require(response, 200, "read the audit trail");
        return List.of(JSON.readValue(response.body(), AuditEvent[].class));
    }

    /** The same audit trail as a CSV download for off-system retention, or {@code null} when audit is not installed. */
    public String auditCsv(String from, String to, String action) throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/audit.csv" + auditQuery(from, to, action), null, null);
        if (response.statusCode() == 501) {
            return null;
        }
        require(response, 200, "export the audit trail");
        return response.body();
    }

    private static String auditQuery(String from, String to, String action) {
        StringBuilder query = new StringBuilder();
        appendParam(query, "from", from);
        appendParam(query, "to", to);
        appendParam(query, "action", action);
        return query.toString();
    }

    /** Run the retention sweep over a repository, returning what it evicted and how many blobs it reclaimed, or
     *  {@code null} when retention is not installed on this deployment (HTTP 501). */
    public CleanupReport cleanup(String repo) throws IOException, InterruptedException {
        HttpResponse<String> response = send("POST", "/api/repository/cleanup?repo=" + enc(repo), null, null);
        if (response.statusCode() == 501) {
            return null;
        }
        require(response, 200, "run cleanup on " + repo);
        return JSON.readValue(response.body(), CleanupReport.class);
    }

    /** The dry-run cleanup plan: what the sweep would evict, without deleting anything ({@code blobsReclaimed} is
     *  always 0), or {@code null} when retention is not installed (HTTP 501). */
    public CleanupReport cleanupPlan(String repo) throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/repository/cleanup/plan?repo=" + enc(repo), null, null);
        if (response.statusCode() == 501) {
            return null;
        }
        require(response, 200, "plan cleanup on " + repo);
        return JSON.readValue(response.body(), CleanupReport.class);
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

    /** A repository's retention policy, or {@code null} when retention is not installed (HTTP 501). */
    public RetentionView retention(String repo) throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/repository/retention?repo=" + enc(repo), null, null);
        if (response.statusCode() == 501) {
            return null;
        }
        require(response, 200, "read the retention policy of " + repo);
        return JSON.readValue(response.body(), RetentionView.class);
    }

    /** Set a repository's retention policy (a blank duration clears that rule); {@code false} when retention is not
     *  installed (HTTP 501). The durations are ISO-8601 (e.g. {@code P30D}). */
    public boolean setRetention(String repo, int keepLast, String maxAge, String prereleaseExpiry,
                                String notDownloadedFor) throws IOException, InterruptedException {
        String query = "&keepLast=" + keepLast
                + "&maxAge=" + enc(blankIfNull(maxAge))
                + "&prereleaseExpiry=" + enc(blankIfNull(prereleaseExpiry))
                + "&notDownloadedFor=" + enc(blankIfNull(notDownloadedFor));
        HttpResponse<String> response = send("PUT", "/api/repository/retention?repo=" + enc(repo) + query, null, null);
        if (response.statusCode() == 501) {
            return false;
        }
        require(response, 200, "set the retention policy of " + repo);
        return true;
    }

    /** A repository's pinned coordinates ({@code ecosystem:coordinate:version}), which the sweep never reclaims. */
    public List<String> pins(String repo) throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/repository/pins?repo=" + enc(repo), null, null);
        require(response, 200, "read the pins of " + repo);
        return JSON.readValue(response.body(), PinsView.class).pinned();
    }

    public void pin(String repo, String ecosystem, String coordinate, String version)
            throws IOException, InterruptedException {
        require(send("POST", "/api/repository/pin?repo=" + enc(repo) + "&ecosystem=" + enc(ecosystem)
                + "&coordinate=" + enc(coordinate) + "&version=" + enc(version), null, null), 200, "pin " + coordinate);
    }

    public void unpin(String repo, String ecosystem, String coordinate, String version)
            throws IOException, InterruptedException {
        require(send("DELETE", "/api/repository/pin?repo=" + enc(repo) + "&ecosystem=" + enc(ecosystem)
                + "&coordinate=" + enc(coordinate) + "&version=" + enc(version), null, null), 200, "unpin " + coordinate);
    }

    /** Start an asynchronous migration into a repository from an incumbent manager, returning the HTTP status (202
     *  accepted, 405 read-only target, 501 no upstream fetcher, 400 no such source) and, when accepted, the job id to
     *  poll. Only {@code source}, {@code url} and {@code sourceRepository} are required; the rest are optional. */
    public ImportResult startImport(String repo, String source, String url, String sourceRepository, String format,
                                    String username, String password, String resume)
            throws IOException, InterruptedException {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("source", source);
        fields.put("url", url);
        fields.put("repository", sourceRepository);
        if (format != null) {
            fields.put("format", format);
        }
        if (username != null) {
            fields.put("username", username);
        }
        if (password != null) {
            fields.put("password", password);
        }
        if (resume != null) {
            fields.put("resume", resume);
        }
        HttpResponse<String> response = send("POST", "/api/repository/import?repo=" + enc(repo),
                body(fields), "application/json");
        if (response.statusCode() == 202) {
            return new ImportResult(202, JSON.readValue(response.body(), ImportJob.class).job());
        }
        return new ImportResult(response.statusCode(), null);
    }

    /** The state and counts of an import job, or {@code null} when no such job exists (HTTP 404). */
    public ImportStatus importStatus(String repo, String job) throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET",
                "/api/repository/import/" + enc(job) + "?repo=" + enc(repo), null, null);
        if (response.statusCode() == 404) {
            return null;
        }
        require(response, 200, "read import job " + job);
        return JSON.readValue(response.body(), ImportStatus.class);
    }

    /** Start publishing every version {@code repo} holds to the repository at {@code url} - the URL the format's own
     *  client would be pointed at - with a token, or a user name and password; {@code resume} continues a stopped
     *  job. */
    public ExportResult startExport(String repo, String url, String token, String username, String password,
                                    String resume) throws IOException, InterruptedException {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("url", url);
        if (token != null) {
            fields.put("token", token);
        }
        if (username != null) {
            fields.put("username", username);
        }
        if (password != null) {
            fields.put("password", password);
        }
        if (resume != null) {
            fields.put("resume", resume);
        }
        HttpResponse<String> response = send("POST", "/api/repository/export?repo=" + enc(repo),
                body(fields), "application/json");
        if (response.statusCode() == 202) {
            return new ExportResult(202, JSON.readValue(response.body(), ImportJob.class).job(), null);
        }
        return new ExportResult(response.statusCode(), null, response.body());
    }

    /** The state and counts of an export job, or {@code null} when no such job exists (HTTP 404). */
    public ExportStatus exportStatus(String repo, String job) throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET",
                "/api/repository/export/" + enc(job) + "?repo=" + enc(repo), null, null);
        if (response.statusCode() == 404) {
            return null;
        }
        require(response, 200, "read export job " + job);
        return JSON.readValue(response.body(), ExportStatus.class);
    }

    /** The retroactive-license-enforcement dry-run plan for a repository - what enabling enforcement would newly hold
     *  under the current policy - or {@code null} when license policy is not installed (HTTP 501). With {@code unknown}
     *  it additionally previews holding the coordinates whose license could not be identified. */
    public RetroPlan retroPlan(String repo, boolean unknown) throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET",
                "/api/licenses/retro/plan?repo=" + enc(repo) + "&unknown=" + unknown, null, null);
        if (response.statusCode() == 501) {
            return null;
        }
        require(response, 200, "plan retroactive license enforcement for " + repo);
        return JSON.readValue(response.body(), RetroPlan.class);
    }

    /** One page of a repository's published-asset enumeration - the {@code GET /api/assets} walk, the outbound
     *  mirror of the import connectors so getting your data out is never the paid feature. {@code cursor} is the opaque
     *  token that fetches the next page (pass it back as {@code after}); it is {@code null} once the walk is exhausted.
     *  {@code limit} caps the page (the server clamps it to its own maximum); a {@code null} limit takes the default. */
    public AssetPage assets(String repo, String after, Integer limit) throws IOException, InterruptedException {
        StringBuilder path = new StringBuilder("/api/assets?repo=").append(enc(repo));
        if (after != null && !after.isBlank()) {
            path.append("&cursor=").append(enc(after));
        }
        if (limit != null) {
            path.append("&limit=").append(limit);
        }
        HttpResponse<String> response = send("GET", path.toString(), null, null);
        require(response, 200, "list the assets of " + repo);
        return JSON.readValue(response.body(), AssetPage.class);
    }

    private static void appendParam(StringBuilder query, String name, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        query.append(query.isEmpty() ? '?' : '&').append(name).append('=').append(enc(value));
    }

    private static String blankIfNull(String value) {
        return value == null ? "" : value;
    }

    /** The repositories defined at runtime: each name with its routing specification. */
    public List<NamedValue> repositories() throws IOException, InterruptedException {
        return named("/api/repositories");
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
        Map<String, String> request = description == null
                ? Map.of("value", format) : Map.of("value", format, "description", description);
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

    public void setRepository(String name, String specification) throws IOException, InterruptedException {
        require(send("PUT", "/api/repositories/" + name, body(Map.of("value", specification)), "application/json"),
                200, "set repository " + name);
    }

    public void removeRepository(String name) throws IOException, InterruptedException {
        require(send("DELETE", "/api/repositories/" + name, null, null), 200, "remove repository " + name);
    }

    /** The per-format proxy upstreams set at runtime: each format with its upstream URL. */
    public List<NamedValue> upstreams() throws IOException, InterruptedException {
        return named("/api/upstreams");
    }

    public void setUpstream(String format, String url) throws IOException, InterruptedException {
        require(send("PUT", "/api/upstreams/" + format, body(Map.of("value", url)), "application/json"),
                200, "set upstream " + format);
    }

    public void removeUpstream(String format) throws IOException, InterruptedException {
        require(send("DELETE", "/api/upstreams/" + format, null, null), 200, "remove upstream " + format);
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

    // ---------------------------------------------------------------------------------------------------------
    // Operations, compliance and lifecycle reads the console had and this client did not. Each is the same shape as
    // everything above it: one request, the body handed back as text or parsed into a record. They are grouped here
    // rather than interleaved because they arrived together, closing the surface gap the parity census measured.
    // ---------------------------------------------------------------------------------------------------------

    /** The read caches of the node this client is pointed at. */
    public String caches() throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/admin/caches", null, null);
        require(response, 200, "read the node's caches");
        return response.body();
    }

    /** Drop every entry of every read cache on the node this client is pointed at, and every node's authorization
     *  cache; answers what went where. The listings are node-local because dropping them everywhere would be a
     *  fan-out; the grants are not, because the reason to call this is usually a credential a peer is still
     *  honouring - and that half travels as one bumped document each node reads on its own schedule. */
    public String cachesClear() throws IOException, InterruptedException {
        HttpResponse<String> response = send("POST", "/api/admin/caches/clear", null, null);
        require(response, 200, "clear the node's caches");
        return response.body();
    }

    /** The standing requests for work - a walk of the store among them. */
    public String walks() throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/admin/walks", null, null);
        require(response, 200, "read the standing requests");
        return response.body();
    }

    /** Ask for a walk of the store now; answers the standing requests. */
    public String walksRun() throws IOException, InterruptedException {
        HttpResponse<String> response = send("POST", "/api/admin/walks/run", null, null);
        require(response, 200, "request a walk of the store");
        return response.body();
    }

    /** The security-posture report: every advisor's verdict on this deployment, deployment-wide or for one tenant. */
    public String posture(String tenant) throws IOException, InterruptedException {
        String path = "/api/admin/posture" + (tenant == null ? "" : "?tenant=" + enc(tenant));
        HttpResponse<String> response = send("GET", path, null, null);
        require(response, 200, "read the security posture");
        return response.body();
    }

    /**
     * The build-cache projects on this deployment's volume.
     *
     * <p>These reach the same {@code CacheService} the console's project screens do, which is what makes them one
     * capability rather than two implementations - the surface-parity rule measures exactly that, and reported the
     * console's project and eviction routes as sharing no implementation with any API route until the API twin
     * existed for them to share one with.
     */
    /**
     * The deployment's issued login keys.
     *
     * <p>A login key is a deployment-wide credential, so these are super-admin calls; the controller enforces that
     * itself rather than through the shared chain. Its javadoc has always said the surface is reachable "through
     * the console, CLI or a headless agent", and until these methods existed the middle one was not true.
     */
    public String keyLogins() throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/keylogin", null, null);
        require(response, 200, "list the login keys");
        return response.body();
    }

    /** Issue a login key, binding {@code principal} into {@code tenant} at {@code role}; the key is returned once. */
    public String issueKeyLogin(String principal, String login, String tenant, String role)
            throws IOException, InterruptedException {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("principal", principal);
        fields.put("tenant", tenant);
        if (login != null) {
            fields.put("login", login);
        }
        if (role != null) {
            fields.put("role", role);
        }
        HttpResponse<String> response = send("POST", "/api/keylogin", body(fields), "application/json");
        require(response, 200, "issue the login key");
        return response.body();
    }

    /**
     * Revoke one issued login key.
     *
     * <p>Answered {@code 204}, so there is no body to return - which is the whole answer: the key is gone.
     * Revoking mattered most of the three, because a credential a script can mint and only a browser can withdraw
     * is one that outlives the incident that should have ended it.
     */
    public void revokeKeyLogin(String id) throws IOException, InterruptedException {
        require(send("POST", "/api/keylogin/" + enc(id) + "/delete",
                HttpRequest.BodyPublishers.noBody(), null), 204, "revoke the login key");
    }

    /**
     * Mint this tenant's SCIM bearer token and return the server's answer, which carries the secret once - the
     * store keeps only its hash, so there is no reading it back afterwards.
     */
    public String mintScimToken() throws IOException, InterruptedException {
        HttpResponse<String> response = send("POST", "/api/scim/token",
                HttpRequest.BodyPublishers.noBody(), null);
        require(response, 201, "mint the SCIM token");
        return response.body();
    }

    public String clearScimToken() throws IOException, InterruptedException {
        HttpResponse<String> response = send("POST", "/api/scim/token/clear",
                HttpRequest.BodyPublishers.noBody(), null);
        require(response, 200, "clear the SCIM token");
        return response.body();
    }

    public String cacheProjects() throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/cache/projects", null, null);
        require(response, 200, "list the build-cache projects");
        return response.body();
    }

    public String cacheProject(String name) throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/cache/projects/" + enc(name), null, null);
        require(response, 200, "read the build-cache project");
        return response.body();
    }

    public String createCacheProject(String name) throws IOException, InterruptedException {
        HttpResponse<String> response = send("POST", "/api/cache/projects?name=" + enc(name), HttpRequest.BodyPublishers.noBody(), null);
        require(response, 201, "create the build-cache project");
        return response.body();
    }

    /** The well-known cache values; an omitted one is cleared rather than left at its previous value. */
    public String saveCacheConfig(String name, String size, String lru, String ttl)
            throws IOException, InterruptedException {
        StringBuilder query = new StringBuilder();
        if (size != null) {
            query.append(query.isEmpty() ? "?" : "&").append("size=").append(enc(size));
        }
        if (lru != null) {
            query.append(query.isEmpty() ? "?" : "&").append("lru=").append(enc(lru));
        }
        if (ttl != null) {
            query.append(query.isEmpty() ? "?" : "&").append("ttl=").append(enc(ttl));
        }
        HttpResponse<String> response = send("POST",
                "/api/cache/projects/" + enc(name) + "/cache" + query,
                HttpRequest.BodyPublishers.noBody(), null);
        require(response, 200, "save the build-cache settings");
        return response.body();
    }

    /**
     * Start one of the project's passes - {@code size}, {@code ttl} or {@code clear} - and answer what the server
     * said. The pass sweeps off the request path, so this returns as soon as it has started, and {@code started}
     * being false means one was already running rather than that anything failed.
     */
    public String evictCache(String name, String pass) throws IOException, InterruptedException {
        // Each pass's path written whole rather than assembled from the pass's name, so the route it reaches is a
        // constant this client carries - which is what anything reading the compiled client can see it send.
        String route = switch (pass) {
            case "size" -> "/evict/size";
            case "ttl" -> "/evict/ttl";
            case "clear" -> "/evict/clear";
            default -> throw new IllegalArgumentException("Unknown pass '" + pass + "': size, ttl or clear");
        };
        HttpResponse<String> response = send("POST", "/api/cache/projects/" + enc(name) + route,
                HttpRequest.BodyPublishers.noBody(), null);
        require(response, 200, "start the build-cache eviction pass");
        return response.body();
    }

    public String recountCache(String name) throws IOException, InterruptedException {
        HttpResponse<String> response = send("POST",
                "/api/cache/projects/" + enc(name) + "/recount",
                HttpRequest.BodyPublishers.noBody(), null);
        require(response, 200, "start the build-cache recount");
        return response.body();
    }

    /** Per-node fingerprints and any divergence between the nodes of a cluster. */
    public String consistency() throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/admin/consistency", null, null);
        require(response, 200, "read the node consistency report");
        return response.body();
    }

    /** The tail of the instance's in-memory log buffer. */
    public String logs(String level, Integer limit) throws IOException, InterruptedException {
        StringBuilder path = new StringBuilder("/api/admin/logs");
        String separator = "?";
        if (level != null) {
            path.append(separator).append("level=").append(enc(level));
            separator = "&";
        }
        if (limit != null) {
            path.append(separator).append("limit=").append(limit);
        }
        HttpResponse<String> response = send("GET", path.toString(), null, null);
        require(response, 200, "read the recent logs");
        return response.body();
    }

    /** The generated observability reference: the meters and traces this build exposes. */
    public String observability() throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/admin/observability", null, null);
        require(response, 200, "read the observability reference");
        return response.body();
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

    /** The TXT record to publish so a coordinate redirects to {@code url}. */
    public String redirectRecord(String coordinate, String url, String formats, String scope, Long ttl)
            throws IOException, InterruptedException {
        StringBuilder path = new StringBuilder("/api/admin/redirect-dns/record?coordinate=").append(enc(coordinate))
                .append("&url=").append(enc(url));
        if (formats != null) {
            path.append("&formats=").append(enc(formats));
        }
        if (scope != null) {
            path.append("&scope=").append(enc(scope));
        }
        if (ttl != null) {
            path.append("&ttl=").append(ttl);
        }
        HttpResponse<String> response = send("GET", path.toString(), null, null);
        require(response, 200, "compose the redirect record for " + coordinate);
        return response.body();
    }

    /** Resolve a coordinate's published redirect record and report what it says. */
    public String redirectCheck(String coordinate, String expect) throws IOException, InterruptedException {
        String path = "/api/admin/redirect-dns/check?coordinate=" + enc(coordinate)
                + (expect == null ? "" : "&expect=" + enc(expect));
        HttpResponse<String> response = send("GET", path, null, null);
        require(response, 200, "check the redirect record for " + coordinate);
        return response.body();
    }

    /** Where each path under a prefix was served from - an upstream, or a local publish. */
    public String origin(String repo, String path) throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET",
                "/api/origin?repo=" + enc(repo) + "&path=" + enc(path), null, null);
        require(response, 200, "read the origin of " + repo + path);
        return response.body();
    }

    /** The assembled third-party attribution document for what is published. */
    public String attribution(String repo, String coordinate, String format)
            throws IOException, InterruptedException {
        StringBuilder path = new StringBuilder("/api/attribution?repo=").append(enc(repo));
        if (coordinate != null) {
            path.append("&coordinate=").append(enc(coordinate));
        }
        if (format != null) {
            path.append("&format=").append(enc(format));
        }
        HttpResponse<String> response = send("GET", path.toString(), null, null);
        require(response, 200, "read the attribution document for " + repo);
        return response.body();
    }

    /** What proxy hardening would do with the bytes at a path. */
    public String hardeningVerdict(String repo, String path) throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/hardening/verdict?repo=" + enc(repo)
                + (path == null ? "" : "&path=" + enc(path)), null, null);
        require(response, 200, "read the hardening verdict for " + repo);
        return response.body();
    }

    /** The immediate child folders under a path - the paged folder probe, not a listing of every entry. */
    public String browseChildren(String repo, String prefix) throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET",
                "/api/browse/children?repo=" + enc(repo) + "&prefix=" + enc(prefix), null, null);
        require(response, 200, "browse the children of " + repo + "/" + prefix);
        return response.body();
    }

    /** Recent outbound webhook deliveries and their state. */
    public String webhooks(String repo) throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/webhook?repo=" + enc(repo), null, null);
        require(response, 200, "read the webhook deliveries for " + repo);
        return response.body();
    }

    /** Redeliver one failed webhook. */
    public void retryWebhook(String repo, String id) throws IOException, InterruptedException {
        HttpResponse<String> response = send("POST",
                "/api/webhook/retry?repo=" + enc(repo) + "&id=" + enc(id), HttpRequest.BodyPublishers.noBody(), null);
        require(response, 200, "retry webhook " + id);
    }

    /** The recorded VEX statements, deployment-wide or for one repository. */
    public String vexStatements(String repo) throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET",
                "/api/vex" + (repo == null ? "" : "?repo=" + enc(repo)), null, null);
        require(response, 200, "read the VEX statements");
        return response.body();
    }

    /** One VEX statement. */
    public String vexStatement(String id) throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/vex/" + enc(id), null, null);
        require(response, 200, "read VEX statement " + id);
        return response.body();
    }

    /** Record a VEX statement from an OpenVEX or CSAF document. */
    public String addVex(String document) throws IOException, InterruptedException {
        HttpResponse<String> response = send("POST", "/api/vex",
                HttpRequest.BodyPublishers.ofString(document), "application/json");
        require(response, 201, "record the VEX statement");
        return response.body();
    }

    /** Withdraw a VEX statement. */
    public void removeVex(String id) throws IOException, InterruptedException {
        HttpResponse<String> response = send("DELETE", "/api/vex/" + enc(id), null, null);
        require(response, 200, "withdraw VEX statement " + id);
    }

    /** Every VEX statement as one OpenVEX document. */
    public String exportVex() throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/vex/export", null, null);
        require(response, 200, "export the VEX statements");
        return response.body();
    }

    /** The ingested third-party scan runs. */
    public String scans(String repo) throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET",
                "/api/scans" + (repo == null ? "" : "?repo=" + enc(repo)), null, null);
        require(response, 200, "read the scan runs");
        return response.body();
    }

    /** One scan run. */
    public String scan(String id) throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/scans/" + enc(id), null, null);
        require(response, 200, "read scan run " + id);
        return response.body();
    }

    /** The findings of one scan run. */
    public String scanReport(String id) throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/scans/" + enc(id) + "/report", null, null);
        require(response, 200, "read the report of scan run " + id);
        return response.body();
    }

    /** Ingest a build scan - the per-run record of what a build ran and what the cache saved it. */
    public String ingestScan(String document, String repo)
            throws IOException, InterruptedException {
        StringBuilder path = new StringBuilder("/api/scans");
        if (repo != null) {
            path.append("?repo=").append(enc(repo));
        }
        HttpResponse<String> response = send("POST", path.toString(),
                HttpRequest.BodyPublishers.ofString(document), "application/json");
        require(response, 201, "ingest the build scan");
        return response.body();
    }

    /** The aggregate view across scan runs. */
    public String scanAnalytics(boolean asReport) throws IOException, InterruptedException {
        // Both paths written out whole rather than assembled from a common stem: a path built by concatenation
        // exists nowhere in the class file, so nothing that reads the compiled artifact - the surface-parity
        // census included - can see that this endpoint is reached at all.
        HttpResponse<String> response = send("GET",
                asReport ? "/api/scans/analytics/report" : "/api/scans/analytics", null, null);
        require(response, 200, "read the scan analytics");
        return response.body();
    }

    /** Ingest a test run. */
    public String ingestTestRun(String document, String repo) throws IOException, InterruptedException {
        HttpResponse<String> response = send("POST",
                "/api/tests" + (repo == null ? "" : "?repo=" + enc(repo)),
                HttpRequest.BodyPublishers.ofString(document), "application/json");
        require(response, 201, "ingest the test run");
        return response.body();
    }

    /** One ingested test run. */
    public String testRun(String id) throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/tests/" + enc(id), null, null);
        require(response, 200, "read test run " + id);
        return response.body();
    }

    /** The tests seen to flake. */
    public String flakyTests(String repo) throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET",
                "/api/tests/flaky" + (repo == null ? "" : "?repo=" + enc(repo)), null, null);
        require(response, 200, "read the flaky tests");
        return response.body();
    }

    /** The tests worth running for a change. */
    public String selectTests(String repo, String changed) throws IOException, InterruptedException {
        StringBuilder path = new StringBuilder("/api/tests/select");
        String separator = "?";
        if (repo != null) {
            path.append(separator).append("repo=").append(enc(repo));
            separator = "&";
        }
        if (changed != null) {
            path.append(separator).append("changed=").append(enc(changed));
        }
        HttpResponse<String> response = send("GET", path.toString(), null, null);
        require(response, 200, "select the tests for the change");
        return response.body();
    }

    /** One page of the coordinates carrying a deprecation or end-of-life mark. A mark exists per marked version, so
     *  the whole-repository form is paged: {@code after} is the {@code next} cursor of the previous answer and is
     *  {@code null} to start, {@code limit} caps the page and the server clamps it to its own maximum. A page whose
     *  {@code next} is set has more behind it, whether or not it came back short. */
    public String lifecycleMarks(String repository, String after, Integer limit)
            throws IOException, InterruptedException {
        StringBuilder path = new StringBuilder("/api/lifecycle?repository=").append(enc(repository));
        if (after != null && !after.isBlank()) {
            path.append("&after=").append(enc(after));
        }
        if (limit != null) {
            path.append("&limit=").append(limit);
        }
        HttpResponse<String> response = send("GET", path.toString(), null, null);
        require(response, 200, "read the lifecycle marks for " + repository);
        return response.body();
    }

    /** Mark a coordinate deprecated or end-of-life. */
    public void markLifecycle(String repository, String coordinate, String version, String state, String message)
            throws IOException, InterruptedException {
        // Query parameters, and a version: a mark names one version, which is what the endpoint binds and what the
        // stored flag carries. This sent a JSON body of its own shape until 2026-09, and was refused every time.
        HttpResponse<String> response = send("POST", "/api/lifecycle?repository=" + enc(repository)
                + "&coordinate=" + enc(coordinate)
                + "&version=" + enc(version)
                + "&state=" + enc(state)
                + (message == null ? "" : "&message=" + enc(message)), null, null);
        require(response, 200, "mark " + coordinate + "@" + version + " " + state);
    }

    /** Remove a coordinate's lifecycle mark. */
    public void clearLifecycle(String repository, String coordinate, String version)
            throws IOException, InterruptedException {
        HttpResponse<String> response = send("DELETE",
                "/api/lifecycle?repository=" + enc(repository) + "&coordinate=" + enc(coordinate)
                        + "&version=" + enc(version), null, null);
        require(response, 200, "clear the lifecycle mark on " + coordinate + "@" + version);
    }

    /** Record a human verdict on one finding. */
    public void reviewFinding(String repo, String coordinate, String id, String verdict, String note)
            throws IOException, InterruptedException {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("coordinate", coordinate);
        fields.put("id", id);
        fields.put("verdict", verdict);
        if (note != null) {
            fields.put("note", note);
        }
        HttpResponse<String> response = send("POST", "/api/findings/review?repo=" + enc(repo),
                body(fields), "application/json");
        require(response, 200, "review finding " + id);
    }

    /** Waive a finding, with a reason and an optional expiry. */
    public void waiveFinding(String repo, String coordinate, String id, String reason, String until)
            throws IOException, InterruptedException {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("coordinate", coordinate);
        fields.put("id", id);
        if (reason != null) {
            fields.put("reason", reason);
        }
        if (until != null) {
            fields.put("until", until);
        }
        HttpResponse<String> response = send("POST", "/api/findings/waiver?repo=" + enc(repo),
                body(fields), "application/json");
        require(response, 200, "waive finding " + id);
    }

    /** Post a scanner's report about one stored version, the request document read from {@code file} as it is.
     *  Returns {@code null} when the findings module is not installed on this deployment (HTTP 501). */
    public ReportAnswer reportFindings(String repo, Path file) throws IOException, InterruptedException {
        HttpResponse<String> response = send("POST", "/api/findings/report?repo=" + enc(repo),
                HttpRequest.BodyPublishers.ofFile(file), "application/json");
        if (response.statusCode() == 501) {
            return null;
        }
        require(response, 200, "report findings from " + file.getFileName() + " into " + repo);
        return JSON.readValue(response.body(), ReportAnswer.class);
    }

    /** What a report did: findings recorded, the gate's verdict, whether the version is withheld, and why. */
    public record ReportAnswer(int recorded, String verdict, boolean held, List<String> reasons) {
    }

    /** Revoke a finding's waiver. */
    public void revokeWaiver(String repo, String coordinate, String id) throws IOException, InterruptedException {
        HttpResponse<String> response = send("POST", "/api/findings/waiver/revoke?repo=" + enc(repo),
                body(Map.of("coordinate", coordinate, "id", id)), "application/json");
        require(response, 200, "revoke the waiver on finding " + id);
    }

    /** Forget every record of one ecosystem in a repository. */
    public void forgetEcosystem(String repo, String ecosystem) throws IOException, InterruptedException {
        HttpResponse<String> response = send("POST",
                "/api/repository/forget-ecosystem?repo=" + enc(repo) + "&ecosystem=" + enc(ecosystem),
                HttpRequest.BodyPublishers.noBody(), null);
        require(response, 200, "forget the " + ecosystem + " records in " + repo);
    }

    private List<NamedValue> named(String path) throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", path, null, null);
        require(response, 200, "read " + path);
        return List.of(JSON.readValue(response.body(), NamedValue[].class));
    }

    private static HttpRequest.BodyPublisher body(Map<String, ?> fields) {
        return HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(fields));
    }

    /**
     * The URL path of a repository the CLI names: {@code <tenant>/<name>} is that tenant's repository, and a bare
     * {@code <name>} one of this client's {@link #tenant}.
     */
    private String repository(String name) {
        return "/repository/" + (name.contains("/") ? name : tenant() + "/" + name);
    }

    /**
     * The tenant a bare repository name addresses: the one this client was given, else the one its key belongs to - a
     * key reads {@code jenk_<tenant>.<secret>} - else {@link Scopes#DEFAULT_TENANT}, the tenant a deployment
     * configuring none serves.
     */
    public String tenant() {
        if (tenant != null) {
            return tenant;
        }
        if (key != null && key.startsWith("jenk_") && key.indexOf('.') > "jenk_".length()) {
            return key.substring("jenk_".length(), key.indexOf('.'));
        }
        return Scopes.DEFAULT_TENANT;
    }

    private HttpResponse<String> send(String method, String path, HttpRequest.BodyPublisher body, String contentType)
            throws IOException, InterruptedException {
        return send(method, path, body, contentType, Map.of());
    }

    private HttpResponse<String> send(String method, String path, HttpRequest.BodyPublisher body, String contentType,
                                      Map<String, String> headers) throws IOException, InterruptedException {
        String root = base.toString();
        if (root.endsWith("/")) {
            root = root.substring(0, root.length() - 1);
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(root + path))
                .timeout(Duration.ofSeconds(60))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : body);
        if (key != null && !key.isBlank()) {
            request.header("Jenesis-Repository-Key", key);
        }
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }
        headers.forEach(request::header);
        HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        // In --json mode the server's own answer is the output, so it is captured here rather than reconstructed
        // from whatever the calling command happened to parse out of it.
        if (Output.isJson() && response.statusCode() >= 200 && response.statusCode() < 300) {
            Output.record(response.headers().firstValue("Content-Type").orElse(null), response.body());
        }
        return response;
    }

    /**
     * The server did not answer an endpoint at all.
     *
     * <p>Distinguished from every other refusal because it is the one whose cause is usually not the request: this
     * product is assembled from modules a deployment may leave out, and an absent module serves nothing. The
     * dispatcher catches this and asks {@code /api/capabilities} whether the feature behind the command is
     * installed, so the caller is told which of the two happened instead of being handed a bare 404.
     */
    public static final class EndpointMissing extends IOException {

        private static final long serialVersionUID = 1L;

        EndpointMissing(String action, int status) {
            super("Could not " + action + " (HTTP " + status + "): the server serves nothing at that endpoint.");
        }
    }

    private static void require(HttpResponse<String> response, int expected, String action) throws IOException {
        if (response.statusCode() == expected) {
            return;
        }
        if (response.statusCode() == 404 || response.statusCode() == 501) {
            throw new EndpointMissing(action, response.statusCode());
        }
        throw new IOException("Could not " + action + " (HTTP " + response.statusCode() + ")");
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, UTF_8);
    }

    /** One runtime setting as the API returns it. {@code kind} is the value kind ({@code SECRET}, {@code CHOICE}, …)
     *  so the CLI masks a secret; a SECRET's {@code value} and {@code defaultValue} come back {@code null} - the server
     *  never reads a secret back - while {@code overridden}/{@code pinned} still say whether it is set. */
    public record Setting(String key, String kind, String value, String defaultValue, boolean overridden,
                          boolean appliesImmediately, boolean pinned, String pinnedBy,
                          String group, String label, String description) {
    }

    /** The first-run setup guide the server serves at {@code /api/setup}. */
    public record Setup(List<SetupStep> steps) {
    }

    /** One step of it: its id, title, the sentence on why it is asked, and the settings it is about. */
    public record SetupStep(String id, String title, String why, List<Setting> settings) {
    }

    public record Credential(String id, String label, String expires, long useCount) {
    }

    /** One group as the API reports it: its name, its label and the rights it grants per scope. */
    public record Group(String name, String label, Map<String, String> grants) {
    }

    /** One person as the API reports them. {@code grants} is what they hold DIRECTLY; what they hold through a
     *  group is the group's, and is listed there. */
    public record Principal(String id, String label, Map<String, String> grants) {
    }

    public record Minted(String id, String key, String expires) {
    }

    public record NamedValue(String name, String value) {
    }

    /** {@code signals} lists the report columns the server's installed signal modules contribute; {@code null} when
     *  an older server answers without them. {@code nextCursor} is the server's paging cursor - the client follows it
     *  to accumulate every worst-first page, so the report a caller receives is the whole set even though the server
     *  serves it a bounded page at a time; it is {@code null} on a fully-accumulated report. */
    public record VulnerabilityReport(boolean scanned, List<Signal> signals, List<VulnerableArtifact> vulnerable,
                                      String nextCursor, List<String> feedWarnings) {
    }

    public record VulnerableArtifact(String coordinate, List<Advisory> advisories) {
    }

    /** {@code signals} carries one evaluated cell per report column - the known-exploited flag and the EPSS
     *  probability among them, read back through {@link #knownExploited()} and {@link #epss()}. {@code reachability}
     *  is the call-graph verdict the server's reachability sweep labelled onto the stored finding ({@code reachable} /
     *  {@code not-reachable} / {@code unknown}); empty when the coordinate was never analyzed, {@code null} from an
     *  older server without the engine. {@code applicability} is the AI applicability pass's opinion on whether the
     *  advisory applies in this artifact's context ({@code applies} / {@code not-applicable} / {@code unknown});
     *  empty when never judged, {@code null} from an older server. */
    public record Advisory(String id, String severity, boolean malicious,
                           String fixed, String reachability, String applicability, List<Cell> signals) {

        /** Whether the CISA known-exploited (KEV) catalogue flags this advisory, read from the {@code known-exploited}
         *  signal cell (its rank is positive when the catalogue lists a CVE alias); false when the signal is absent. */
        public boolean knownExploited() {
            return signalRank("known-exploited") > 0;
        }

        /** This advisory's EPSS exploitation probability, read from the {@code epss} signal cell's rank; 0 when the
         *  signal is absent. */
        public double epss() {
            return signalRank("epss");
        }

        private double signalRank(String name) {
            if (signals != null) {
                for (Cell cell : signals) {
                    if (cell.name().equals(name)) {
                        return cell.rank();
                    }
                }
            }
            return 0.0;
        }
    }

    public record Signal(String name, String label) {
    }

    public record Cell(String name, String label, String value, double rank) {
    }

    /** The served {@code /api/capabilities} document. The optional modules' feature flags sit at the <b>top level</b>
     *  ({@code scan}, {@code provenance}, {@code audit}, {@code dependents}, {@code search}, {@code walk},
     *  {@code gc}), because each is contributed by the module that owns it rather than lifted into a fixed view by
     *  the server; {@link Features} carries only the postures the server itself resolves. A flag a deployment does
     *  not carry is simply absent and reads as {@code false} - the SPI's no-op-by-absence contract. */
    public record Capabilities(int version, List<Format> formats, List<ImportSource> importSources,
                               List<Signal> signals, List<Module> modules, Features features,
                               boolean scan, boolean provenance, boolean audit, boolean dependents,
                               boolean search, boolean walk, boolean gc) {
    }

    public record Format(String name, String ecosystem) {
    }

    public record ImportSource(String name, String label, boolean requiresFormat) {
    }

    /** One discovered module's state (the earlier module list): its JPMS module name, whether it is {@code installed} on
     *  this deployment's module path, the key of its enablement gate ({@code null} for an always-on module), whether
     *  that gate resolves to {@code enabled}, and whether toggling it applies {@code live} or only on the next
     *  restart. A module named only by a leftover stored settings document reports {@code installed == false}. */
    public record Module(String module, boolean installed, String enableKey, boolean enabled, boolean live) {
    }

    /** The deployment postures the server resolves from its own beans. The module-contributed flags are not here -
     *  they are top-level entries of {@link Capabilities}. */
    public record Features(boolean advisories, boolean advisoriesEnabled, boolean staging, boolean retention,
                           boolean provenanceEnabled, boolean upstream, boolean tokenExchange, boolean rateLimit) {
    }

    /** The findings ledger's answer: every persisted finding matching the query, each fully attributed. */
    public record FindingsReport(boolean available, List<FindingRow> findings) {
    }

    /** One persisted finding: its coordinate, identity, attribution, categorization, the persisted description and
     *  references, kind-specific attributes (e.g. {@code fixed}), sighting instants, the supersession mark
     *  ({@code null} while it stands) and any attached labels. */
    public record FindingRow(String ecosystem, String coordinate, String version, String id, String source,
                             String kind, String category, String severity, double confidence, String description,
                             List<String> references, String provenance, Map<String, String> attributes,
                             String firstSeen, String lastSeen, String supersededBy, List<FindingLabel> labels) {
    }

    public record FindingLabel(String source, String name, String value, double confidence, String when) {
    }

    /** A reverse-dependency answer: with a {@code coordinate}, the {@code dependents} pulling it in; without one,
     *  the {@code coordinates} the index holds; with a package, the versions that {@code declared} a dependency on
     *  it and the {@code nextDeclaredCursor} past them. The unanswered parts are {@code null}. */
    public record DependentsReport(String coordinate, List<String> dependents, List<String> coordinates,
                                   List<Declaration> declared, String nextDeclaredCursor) {
    }

    /** One version declaring a dependency, with the requirement its manifest states - empty where it states none. */
    public record Declaration(String ecosystem, String coordinate, String version, String requirement) {
    }

    /** The license inventory facets: {@code indexed} says whether the search index answered, then the per-category and
     *  per-SPDX-id counts. */
    public record LicensesView(boolean indexed, List<LicenseCount> categories, List<LicenseCount> licenses) {
    }

    public record LicenseCount(String value, long count) {
    }

    /** One quarantine hold: when it was recorded, the path within the repository and coordinate, the gate verdict and the reasons. */
    public record QuarantineEvent(String when, String path, String coordinate, String verdict, List<String> reasons) {
    }

    /** One queued forward: the published path and ecosystem, the attempt count, whether it is parked, its status text,
     *  how many targets already took it, and the last error if any. */
    public record ForwardingEntry(String path, String ecosystem, int attempts, boolean parked, String status,
                                  int delivered, String error) {
    }

    /** One staging id with its lifecycle state and how many items it holds. */
    public record StagingEntry(String id, String state, int items) {
    }

    /** The published-index chain descriptor: the current generation and watermark, then the immutable chunks. */
    public record IndexDescriptor(long generation, String watermark, String rebased, List<IndexChunk> chunks) {
    }

    public record IndexChunk(String id, long uncompressedSize, long compressedSize, long records, String minPublished,
                             String maxPublished) {
    }

    private record QuarantineView(List<QuarantineEvent> events) {
    }

    private record ForwardingView(List<ForwardingEntry> entries) {
    }

    /** The staging list's odd wire field name ({@code repositories}) is hidden behind {@link #staging}; the server
     *  answers a window of the first two hundred and says whether more exist. */
    private record StagingList(List<StagingEntry> repositories, boolean more) {
    }

    /** The tenant's credential-lifetime policy: the default stamped on a blank-expiry mint and the optional ceiling
     *  beyond which no key may live ({@code null} when nothing caps it), both ISO-8601 durations. */
    public record PolicyView(String defaultLifetime, String maxLifetime) {
    }

    /** The tenant's storage quota: the byte ceiling ({@code 0} when unlimited) and the bytes currently stored. */
    public record QuotaView(long maxBytes, long usedBytes) {
    }

    /** The tenant's request-rate ceiling in permits per minute ({@code 0} falls back to the deployment default). */
    public record RateLimitView(long permitsPerMinute) {
    }

    /** One OIDC trust: it exchanges a matching id-token for a short-lived credential at {@code /api/token}. */
    public record TrustView(String name, String issuer, String audience, String subject, String scope, String rights,
                            String ttl) {
    }

    /** One audit event, newest first: when it happened, who did it, the action and the target. */
    public record AuditEvent(String at, String actor, String action, String target) {
    }

    /** The result of a cleanup sweep or its dry-run plan: how many content-addressed blobs were reclaimed (0 for a
     *  plan) and the {@code coordinate:version - reason} lines the sweep evicted or would evict.
     *  {@code evicted} names the first evictions of the sweep; {@code evictedCount} counts them all. */
    public record CleanupReport(int blobsReclaimed, List<String> evicted, int evictedCount) {
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

    /** A repository's retention policy: how many latest versions to keep, and the ISO-8601 age / prerelease-expiry /
     *  not-downloaded-for windows (each empty when the rule is off). */
    public record RetentionView(int keepLast, String maxAge, String prereleaseExpiry, String notDownloadedFor) {
    }

    /** The acknowledgement of a submitted import: the HTTP {@code status} (202 accepted, 405 read-only, 501 no
     *  upstream, 400 no such source) and, when accepted, the {@code job} id to poll. */
    public record ImportResult(int status, String job) {
    }

    /** The acknowledgement of a submitted export: the HTTP {@code status}, the {@code job} id to poll when it was
     *  accepted, and the server's reason when it was not - an export URL the deployment refuses says why. */
    public record ExportResult(int status, String job, String reason) {
    }

    /** An export job's state and counts: versions published, already present and withheld, where it has reached, and
     *  the error that stopped it if any. */
    public record ExportStatus(String state, String target, int published, int present, int withheld, String cursor,
                               String reached, String error) {
    }

    /** An import job's state and counts: what has been imported and skipped, which formats had no importer, the walk's
     *  continuation cursor, the most recently reached source asset and the last error if any. */
    public record ImportStatus(String state, int imported, int skipped, List<String> skippedFormats, String cursor,
                               String asset, String error, Map<String, Integer> dropped) {

        /** Rows the source offered that no connector would carry, by reason. Null on a server that predates the
         *  field, which is why every reader guards it - a missing count must not print as zero refusals. */
        public int droppedTotal() {
            return dropped == null ? 0 : dropped.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    /** The retroactive-license dry-run plan for one repository: the mode previewed, how many releases enabling
     *  enforcement would newly hold, and the per-coordinate reasons. */
    public record RetroPlan(String mode, int count, List<RetroHeld> held) {
    }

    /** One release the enforcement sweep would hold, with the human-readable reasons. */
    public record RetroHeld(String ecosystem, String coordinate, String version, List<String> reasons) {
    }

    /** One page of the asset enumeration: the repository walked, its assets, and the cursor to resume after the last
     *  one ({@code null} once the walk is exhausted). */
    public record AssetPage(String repository, List<AssetEntry> assets, String cursor) {
    }

    /** One enumerated asset: its serving request path, stored size and SHA-256 straight from the publication pointer,
     *  its owning format name, and - when the format exposes a coordinate layout - the neutral ecosystem/coordinate/
     *  version and prerelease flag ({@code null}/{@code false} for a coordinate-less format such as raw). */
    public record AssetEntry(String path, long size, String sha256, String format, String ecosystem,
                             String coordinate, String version, boolean prerelease) {
    }

    /** An attestation with its signing-time verification material; {@code certificateChain} and {@code transparencyLog}
     *  are {@code null} where the configured signer has none. */
    public record ProvenanceMaterial(String envelope, String certificateChain, TransparencyLog transparencyLog) {
    }

    /** The transparency-log entry recording an envelope, in the log's own shapes, so a consumer verifies offline. */
    public record TransparencyLog(String uuid, String logId, long logIndex, long integratedTime,
                                  String signedEntryTimestamp, String canonicalizedBody, InclusionProof inclusionProof) {
    }

    /** The Merkle audit path from the entry's leaf hash to the signed root the checkpoint publishes. */
    public record InclusionProof(long logIndex, long treeSize, String rootHash, List<String> hashes, String checkpoint) {
    }

    /** The result of a batch explode: the HTTP {@code status} and, on a batch response, the per-entry {@code manifest}
     *  ({@code null} when the archive was stored verbatim because batch upload is off). */
    public record ExplodeResult(int status, ExplodeManifest manifest) {
    }

    /** The per-entry manifest of a batch explode: what each archive member became, whether the walk was capped at the
     *  entry ceiling, and an {@code error} marker for a malformed archive. */
    public record ExplodeManifest(String explode, List<ExplodeEntry> entries, boolean capped, String error) {
    }

    /** One exploded archive member: its synthesized publish path, what it became ({@code stored | quarantined |
     *  rejected | unclaimed}) and, for a rejected entry, the reason. */
    public record ExplodeEntry(String path, String status, String reason) {
    }

    private record ImportJob(String job, String state) {
    }

    private record PinsView(List<String> pinned) {
    }

    private record Listing(List<String> entries) {
    }

    /** One page of {@code /api/search}: the rows, whether matches remain past it, and the cursor to resume after
     *  ({@code null} when nothing remains, and also on the no-index degrade, which truncates without a resume point). */
    private record Search(List<String> results, boolean truncated, String nextCursor) {
    }
}
