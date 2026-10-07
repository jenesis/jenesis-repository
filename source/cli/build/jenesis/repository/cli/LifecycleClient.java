package build.jenesis.repository.cli;

import module java.base;
import module java.net.http;
import module tools.jackson.databind;

/**
 * How long a repository keeps what it holds and where it sends it: retention and the cleanup sweep, pins,
 * deprecation marks, the publish-through outbox and the webhook deliveries, the export to another repository and
 * forgetting one ecosystem's records.
 *
 * <p>Reached through {@link RepositoryClient#lifecycle()}.
 */
public final class LifecycleClient extends ClientCalls {

    LifecycleClient(ClientCalls calls) {
        super(calls);
    }

    /** The publish-through forwarding outbox of a repository: what is still queued, how many attempts each has taken,
     *  whether it is parked after a terminal failure and the last error - one page resumed after {@code cursor}. */
    public RepositoryClient.Page<ForwardingEntry> forwarding(String repo, String cursor)
            throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/forwarding?repo=" + enc(repo)
                + (cursor == null ? "" : "&after=" + enc(cursor)), null, null);
        require(response, 200, "read the forwarding outbox of " + repo);
        ForwardingView view = JSON.readValue(response.body(), ForwardingView.class);
        return new RepositoryClient.Page<>(view.entries(), view.next());
    }

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

    /** Unpark a parked forward for another delivery attempt; {@code false} when nothing parked is queued at the path
     *  (HTTP 404, nothing to retry). */
    public boolean retryForwarding(String repo, String path) throws IOException, InterruptedException {
        HttpResponse<String> response = send("POST", "/api/forwarding/retry?repo=" + enc(repo),
                body(Map.of("path", path)), "application/json");
        if (response.statusCode() == 404) {
            return false;
        }
        require(response, 200, "retry forwarding " + path);
        return true;
    }

    /** Start the retention sweep over a repository, and the collection behind it, off the request: whether this
     *  request started it or found one running, and the sweep as it stood. */
    public CleanupStart cleanup(String repo) throws IOException, InterruptedException {
        return started(send("POST", "/api/repository/cleanup?repo=" + enc(repo), null, null), "run cleanup on " + repo);
    }

    /** The last cleanup sweep of a repository, or the one running now. */
    public CleanupReport cleanupStatus(String repo) throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/repository/cleanup?repo=" + enc(repo), null, null);
        require(response, 200, "read the cleanup of " + repo);
        return JSON.readValue(response.body(), CleanupReport.class);
    }

    /** Start the dry run of a sweep off the request: what it would evict and reclaim, deleting nothing. */
    public CleanupStart startCleanupPlan(String repo) throws IOException, InterruptedException {
        return started(send("GET", "/api/repository/cleanup/plan?repo=" + enc(repo) + "&refresh=true", null, null),
                "plan cleanup on " + repo);
    }

    /** The last dry run of a sweep, or the one running now. */
    public CleanupReport cleanupPlan(String repo) throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/repository/cleanup/plan?repo=" + enc(repo), null, null);
        require(response, 200, "plan cleanup on " + repo);
        return JSON.readValue(response.body(), CleanupReport.class);
    }

    private static CleanupStart started(HttpResponse<String> response, String action) throws IOException {
        require(response, 200, action);
        return new CleanupStart(!"running".equals(response.headers().firstValue("Jenesis-Refresh").orElse("")),
                JSON.readValue(response.body(), CleanupReport.class));
    }

    /** What starting a sweep or a dry run answered: whether this request started it, and the run as it stood. */
    public record CleanupStart(boolean started, CleanupReport report) {
    }

    /** A repository's retention policy. */
    public RetentionView retention(String repo) throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/repository/retention?repo=" + enc(repo), null, null);
        require(response, 200, "read the retention policy of " + repo);
        return JSON.readValue(response.body(), RetentionView.class);
    }

    /** Set the given rules of a repository's retention - each a repository setting: a value sets it, a blank one
     *  clears it so the repository inherits, {@code none} switches a duration rule off, and a {@code null} one is left
     *  as it is. */
    public void setRetention(String repo, String keepLast, String maxAge, String prereleaseExpiry,
                                String notDownloadedFor) throws IOException, InterruptedException {
        String query = given("keepLast", keepLast) + given("maxAge", maxAge)
                + given("prereleaseExpiry", prereleaseExpiry) + given("notDownloadedFor", notDownloadedFor);
        HttpResponse<String> response = send("PUT", "/api/repository/retention?repo=" + enc(repo) + query, null, null);
        require(response, 200, "set the retention policy of " + repo);
    }

    private static String given(String parameter, String value) {
        return value == null ? "" : "&" + parameter + "=" + enc(value);
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

    /** Recent outbound webhook deliveries and their state. */
    public String webhooks(String repo, String cursor) throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/webhook?repo=" + enc(repo)
                + (cursor == null ? "" : "&after=" + enc(cursor)), null, null);
        require(response, 200, "read the webhook deliveries for " + repo);
        return response.body();
    }

    /** Redeliver one failed webhook; the endpoint reads the delivery's id from the body. */
    public void retryWebhook(String repo, String id) throws IOException, InterruptedException {
        HttpResponse<String> response = send("POST", "/api/webhook/retry?repo=" + enc(repo),
                body(Map.of("id", id)), "application/json");
        require(response, 200, "retry webhook " + id);
    }

    /** One page of the coordinates carrying a deprecation or end-of-life mark. A mark exists per marked version, so
     *  the whole-repository form is paged: {@code after} is the {@code next} cursor of the previous answer and is
     *  {@code null} to start, {@code limit} caps the page and the server clamps it to its own maximum. A page whose
     *  {@code next} is set has more behind it, whether or not it came back short. */
    public String lifecycleMarks(String repository, String after, Integer limit)
            throws IOException, InterruptedException {
        StringBuilder path = new StringBuilder("/api/lifecycle?repo=").append(enc(repository));
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
        // stored flag carries; the endpoint refuses a JSON body.
        HttpResponse<String> response = send("POST", "/api/lifecycle?repo=" + enc(repository)
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
                "/api/lifecycle?repo=" + enc(repository) + "&coordinate=" + enc(coordinate)
                        + "&version=" + enc(version), null, null);
        require(response, 200, "clear the lifecycle mark on " + coordinate + "@" + version);
    }

    /** Forget every record of one ecosystem in a repository. */
    public void forgetEcosystem(String repo, String ecosystem) throws IOException, InterruptedException {
        HttpResponse<String> response = send("POST",
                "/api/repository/forget-ecosystem?repo=" + enc(repo) + "&ecosystem=" + enc(ecosystem),
                HttpRequest.BodyPublishers.noBody(), null);
        require(response, 200, "forget the " + ecosystem + " records in " + repo);
    }

    /** One queued forward: the published path and ecosystem, the attempt count, whether it is parked, its status text,
     *  how many targets already took it, and the last error if any. */
    public record ForwardingEntry(String path, String ecosystem, int attempts, boolean parked, String status,
                                  int delivered, String error) {
    }

    private record ForwardingView(List<ForwardingEntry> entries, String next) {
    }

    /** A cleanup sweep or its dry run as the last run left it: {@code state} ({@code not-run}, {@code running},
     *  {@code done} or {@code failed}), when the latest began and the last finished, the {@code coordinate:version -
     *  reason} lines it evicted or would evict - the first of {@code evictedCount} - its collection leg, and why the
     *  latest run stopped. */
    public record CleanupReport(boolean plan, String state, String startedAt, String finishedAt, int evictedCount,
                                List<String> evicted, CleanupGc gc, String failure) {
    }

    /** A sweep's collection leg: whether a collector is installed, whether its judgment is complete, what it
     *  condemned, spared and collected, and why it declined, empty in the ordinary case. */
    public record CleanupGc(boolean installed, boolean complete, long condemned, long spared, long collected,
                             String refusal) {
    }

    /** A repository's retention policy: how many latest versions to keep, and the ISO-8601 age / prerelease-expiry /
     *  not-downloaded-for windows (each empty when the rule is off). */
    public record RetentionView(int keepLast, String maxAge, String prereleaseExpiry, String notDownloadedFor) {
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

    private record PinsView(List<String> pinned) {
    }
}
