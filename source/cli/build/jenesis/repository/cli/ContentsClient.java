package build.jenesis.repository.cli;

import module java.base;
import module java.net.http;
import module tools.jackson.databind;

/**
 * The repository's contents: what it holds - a directory walk, a search, the published-asset enumeration and the
 * published index - and the ways to put more in: a deploy, an import from another repository manager and the
 * staging repositories.
 *
 * <p>Reached through {@link RepositoryClient#contents()}.
 */
public final class ContentsClient extends ClientCalls {

    ContentsClient(ClientCalls calls) {
        super(calls);
    }

    /** The request header that turns a publish into a batch explode; only {@code zip} is understood. The server's
     *  {@code BatchIngestion.EXPLODE_HEADER}, spelled here because this module does not depend on the server. */
    private static final String EXPLODE_HEADER = "Jenesis-Explode";

    /** The staging ids of a repository with their state and item count. */
    public List<StagingEntry> staging(String repo) throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/repository/staging?repo=" + enc(repo), null, null);
        require(response, 200, "list the staging of " + repo);
        return JSON.readValue(response.body(), StagingList.class).repositories();
    }

    /** Promote a staged id into the release layout, returning the HTTP status (200 promoted, 409 already sealed). */
    public int promoteStaging(String repo, String id) throws IOException, InterruptedException {
        return send("POST", "/api/repository/staging/" + enc(id) + "/promote?repo=" + enc(repo), null, null)
                .statusCode();
    }

    /** Drop a staged id and its held blobs, returning the HTTP status (200 dropped, 409 already sealed). */
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
     * A repository's search: its published coordinates ({@code group:artifact:version}) - or the paths of artifacts
     * with no coordinate - matching {@code query}, and how the repository answered it. A repository answers by the
     * start of a name unless its full-text index is on. The endpoint answers one bounded page at a time, so this
     * follows its cursor to the end rather than returning the first page as if it were the whole match set - a
     * client may page where a request path may not.
     */
    public Found search(String repo, String query) throws IOException, InterruptedException {
        List<String> results = new ArrayList<>();
        String cursor = null;
        Search page;
        do {
            HttpResponse<String> response = send("GET", "/api/search?repo=" + enc(repo) + "&q=" + enc(query)
                    + (cursor == null ? "" : "&after=" + enc(cursor)), null, null);
            require(response, 200, "search " + repo);
            page = JSON.readValue(response.body(), Search.class);
            results.addAll(page.results());
            String next = page.next();
            // Strictly advancing, so this terminates; a repeated or blank cursor is a server that cannot page on.
            cursor = next == null || next.isBlank() || next.equals(cursor) ? null : next;
        } while (cursor != null);
        return new Found(page.mode(), page.indexed(), List.copyOf(results));
    }

    /** A whole search: the mode the repository answers in ({@code NAME} or {@code FULL_TEXT}), whether its full-text
     *  index answered, and every row. */
    public record Found(String mode, boolean indexed, List<String> results) {
    }

    /** What a deploy was answered: the HTTP status the gate's verdict maps to (201 published, 202 quarantined, 422
     *  rejected, 405 not writable) and what the server said with it - why a hold or a refusal happened - or empty. */
    public record Deployed(int status, String said) {
    }

    /** Deploy bytes to a repository at the given path within it (a Maven repository's {@code /maven/...}, an npm
     *  one's {@code /<package>/...}) through the compliance gate. The client assumes no layout. */
    public Deployed deploy(String repo, String path, byte[] bytes) throws IOException, InterruptedException {
        return deployed(send("PUT", repository(repo) + path,
                HttpRequest.BodyPublishers.ofByteArray(bytes), "application/octet-stream"));
    }

    /** {@link #deploy(String, String, byte[])}, streaming a file from disk rather than buffering it. */
    public Deployed deploy(String repo, String path, Path file) throws IOException, InterruptedException {
        return deployed(send("PUT", repository(repo) + path,
                HttpRequest.BodyPublishers.ofFile(file), "application/octet-stream"));
    }

    /** The answer's status, and its sentence: the plain-text body a deploy edge sends, or the message of the error
     *  document a format whose client reads one sends ({@code error}, or the first {@code errors} entry's
     *  {@code detail} or {@code message}). */
    private static Deployed deployed(HttpResponse<String> response) {
        String body = response.body() == null ? "" : response.body().strip();
        if (body.startsWith("{")) {
            try {
                JsonNode document = JSON.readTree(body);
                JsonNode first = document.path("errors").path(0);
                for (JsonNode said : List.of(document.path("error"), first.path("detail"), first.path("message"))) {
                    if (said.isString()) {
                        return new Deployed(response.statusCode(), said.asString().strip());
                    }
                }
            } catch (RuntimeException unreadable) {
                // Not an error document after all; what was sent is what is shown.
            }
        }
        return new Deployed(response.statusCode(), body);
    }

    /** Deploy an archive with the batch-explode header set, so the server publishes each entry through the gate on
     *  the entry's own format path; returns the HTTP status and,
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
                // A batch manifest always carries entries; an error body also parses, with none, and is not one.
                if (parsed != null && parsed.entries() != null) {
                    manifest = parsed;
                }
            } catch (RuntimeException _) {
                // a non-manifest body (a plain verbatim store, an error page) leaves the manifest null
            }
        }
        return new ExplodeResult(response.statusCode(), manifest);
    }

    /** Start an asynchronous migration into a repository from an incumbent manager, returning the HTTP status (202
     *  accepted, 405 read-only target, 400 no such source) and, when accepted, the job id to
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

    /** One page of a repository's published-asset enumeration - the {@code GET /api/assets} walk, the outbound
     *  mirror of the import connectors. {@code next} is the opaque
     *  token that fetches the next page (pass it back as {@code after}); it is {@code null} once the walk is exhausted.
     *  {@code limit} caps the page (the server clamps it to its own maximum); a {@code null} limit takes the default. */
    public AssetPage assets(String repo, String after, Integer limit) throws IOException, InterruptedException {
        StringBuilder path = new StringBuilder("/api/assets?repo=").append(enc(repo));
        if (after != null && !after.isBlank()) {
            path.append("&after=").append(enc(after));
        }
        if (limit != null) {
            path.append("&limit=").append(limit);
        }
        HttpResponse<String> response = send("GET", path.toString(), null, null);
        require(response, 200, "list the assets of " + repo);
        return JSON.readValue(response.body(), AssetPage.class);
    }

    /** One window of the immediate children under a path - the console's folder probe, each child marked folder or
     *  artifact with its size - resumed after the child named {@code after} ({@code null} starts at the beginning);
     *  the answer's {@code next} is the {@code after} of the window that follows. {@code limit} caps the window, and
     *  the server clamps it to its own maximum; {@code null} takes the default. */
    public String browseChildren(String repo, String prefix, String after, Integer limit)
            throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/api/browse/children?repo=" + enc(repo) + "&prefix=" + enc(prefix)
                + (after == null ? "" : "&after=" + enc(after)) + (limit == null ? "" : "&limit=" + limit), null, null);
        require(response, 200, "browse the children of " + repo + "/" + prefix);
        return response.body();
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

    /** The staging list's odd wire field name ({@code repositories}) is hidden behind {@link #staging}; the server
     *  answers a window of the first two hundred and says whether more exist. */
    private record StagingList(List<StagingEntry> repositories, boolean more) {
    }

    /** The acknowledgement of a submitted import: the HTTP {@code status} (202 accepted, 405 read-only, 400 no such
     *  source) and, when accepted, the {@code job} id to poll. */
    public record ImportResult(int status, String job) {
    }

    /** An import job's state and counts: what has been imported and skipped, which formats had no importer, the walk's
     *  continuation cursor, the most recently reached source asset and the last error if any. */
    public record ImportStatus(String state, int imported, int skipped, List<String> skippedFormats, String cursor,
                               String asset, String error, Map<String, Integer> dropped) {

        /** Rows the source offered that no connector would carry, by reason, or null when the answer carries
         *  none - which every reader guards, so a missing count never prints as zero refusals. */
        public int droppedTotal() {
            return dropped == null ? 0 : dropped.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    /** One page of the asset enumeration: the repository walked, its assets, and the cursor to resume after the last
     *  one ({@code null} once the walk is exhausted). */
    public record AssetPage(String repository, List<AssetEntry> assets, String next) {
    }

    /** One enumerated asset: its serving request path, stored size and SHA-256 straight from the publication pointer,
     *  its owning format name, and - when the format exposes a coordinate layout - the neutral ecosystem/coordinate/
     *  version and prerelease flag ({@code null}/{@code false} for a coordinate-less format such as raw). */
    public record AssetEntry(String path, long size, String sha256, String format, String ecosystem,
                             String coordinate, String version, boolean prerelease) {
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

    private record Listing(List<String> entries) {
    }

    /** One page of {@code /api/search}: how the repository answered, the rows, and the cursor to resume after -
     *  {@code null} when nothing remains. */
    private record Search(String mode, boolean indexed, List<String> results, String next) {
    }
}
