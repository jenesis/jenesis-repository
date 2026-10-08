package build.jenesis.repository.cli;

import module java.base;
import module java.net.http;
import module tools.jackson.databind;

import static java.nio.charset.StandardCharsets.UTF_8;
import build.jenesis.repository.net.http.BoundedBody;
import build.jenesis.repository.scope.Scopes;

/**
 * What every client of the API is written in: the repository's base URL, the key sent on each request as the {@code
 * Jenesis-Repository-Key} header, the tenant a bare repository name addresses, and the request, the check of its
 * answer and the encodings each family's calls share. {@link RepositoryClient} and every family it hands out extend
 * this, so a call reads the same wherever it lives and there is one way to send a request.
 */
abstract class ClientCalls {

    static final JsonMapper JSON = JsonMapper.builder()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .build();

    /** The most of one answer read. Every answer is read whole, since {@code --json} prints it as one value; the API
     *  pages its documents, so the answers that can grow are the exports taken over a range - the audit trail's CSV
     *  above all - and one past this is taken a narrower range at a time rather than held in the CLI's heap. */
    private static final int LARGEST_ANSWER = 256 * 1024 * 1024;

    private final URI base;
    private final String key;
    private final String tenant;
    private final HttpClient client;

    ClientCalls(URI base, String key, String tenant, HttpClient client) {
        if (base == null) {
            throw new IllegalArgumentException("A repository URL is required");
        }
        this.base = base;
        this.key = key;
        this.tenant = tenant;
        this.client = client;
    }

    /** A family addressing the same deployment, with the same key and tenant, as {@code calls}. */
    ClientCalls(ClientCalls calls) {
        this(calls.base, calls.key, calls.tenant, calls.client);
    }

    static HttpRequest.BodyPublisher body(Map<String, ?> fields) {
        return HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(fields));
    }

    /**
     * The URL path of a repository the CLI names: {@code <tenant>/<name>} is that tenant's repository, and a bare
     * {@code <name>} one of this client's {@link #tenant}.
     */
    String repository(String name) {
        return "/repository/" + (name.contains("/") ? name : tenant() + "/" + name);
    }

    /**
     * The tenant a bare repository name addresses: the one this client was given, else the one its key belongs to - a
     * key reads {@code jenk_<tenant>.<secret>} - else {@link Scopes#DEFAULT_TENANT}, the tenant a deployment
     * configuring none serves.
     */
    String tenant() {
        if (tenant != null) {
            return tenant;
        }
        if (key != null && key.startsWith("jenk_") && key.indexOf('.') > "jenk_".length()) {
            return key.substring("jenk_".length(), key.indexOf('.'));
        }
        return Scopes.DEFAULT_TENANT;
    }

    HttpResponse<String> send(String method, String path, HttpRequest.BodyPublisher body, String contentType)
            throws IOException, InterruptedException {
        return send(method, path, body, contentType, Map.of());
    }

    HttpResponse<String> send(String method, String path, HttpRequest.BodyPublisher body, String contentType,
                              Map<String, String> headers) throws IOException, InterruptedException {
        URI uri = uri(path);
        HttpRequest.Builder request = request(method, path, body, contentType, Duration.ofSeconds(60));
        headers.forEach(request::header);
        HttpResponse<String> response = client.send(request.build(), BoundedBody.ofString(uri, LARGEST_ANSWER));
        // A 501 is the server saying it carries no module that would answer: what exit code 3 means, so it is that
        // wherever the call is made, rather than an absence each command reports in its own words and exit code.
        if (response.statusCode() == 501) {
            throw RepositoryClient.NotInstalled.unimplemented(method + " " + path);
        }
        // In --json mode the server's own answer is the output, so it is captured here rather than reconstructed
        // from whatever the calling command happened to parse out of it.
        if (Output.isJson() && response.statusCode() >= 200 && response.statusCode() < 300) {
            Output.record(response.headers().firstValue("Content-Type").orElse(null), response.body());
        }
        return response;
    }

    /** How long a transfer of a file sized like an artifact may take: the answer or the upload is the file itself, so
     *  {@link #send}'s minute bounds a request that is not. */
    private static final Duration TRANSFER = Duration.ofHours(2);

    /**
     * Stream the answer to {@code GET path} into {@code file}, written beside it and moved into place once complete, so
     * an interrupted transfer leaves no partial file under the name; answers the bytes written.
     */
    long download(String path, Path file, String action) throws IOException, InterruptedException {
        Path target = file.toAbsolutePath();
        Path partial = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".partial");
        try {
            HttpResponse<Path> response = client.send(request("GET", path, null, null, TRANSFER).build(),
                    HttpResponse.BodyHandlers.ofFile(partial));
            if (response.statusCode() == 501) {
                throw RepositoryClient.NotInstalled.unimplemented("GET " + path);
            }
            if (response.statusCode() == 404
                    && response.headers().firstValue("Jenesis-Installed").filter("false"::equals).isPresent()) {
                throw RepositoryClient.NotInstalled.unrouted(action);
            }
            if (response.statusCode() != 200) {
                throw new IOException("Could not " + action + " (HTTP " + response.statusCode() + ")");
            }
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return Files.size(target);
        } finally {
            Files.deleteIfExists(partial);
        }
    }

    /** {@link #send} with {@code file} as the body, given as long as a file sized like an artifact takes to send. */
    HttpResponse<String> upload(String method, String path, Path file, String contentType)
            throws IOException, InterruptedException {
        URI uri = uri(path);
        HttpResponse<String> response = client.send(
                request(method, path, HttpRequest.BodyPublishers.ofFile(file), contentType, TRANSFER).build(),
                BoundedBody.ofString(uri, LARGEST_ANSWER));
        if (response.statusCode() == 501) {
            throw RepositoryClient.NotInstalled.unimplemented(method + " " + path);
        }
        if (Output.isJson() && response.statusCode() >= 200 && response.statusCode() < 300) {
            Output.record(response.headers().firstValue("Content-Type").orElse(null), response.body());
        }
        return response;
    }

    private URI uri(String path) {
        String root = base.toString();
        if (root.endsWith("/")) {
            root = root.substring(0, root.length() - 1);
        }
        return URI.create(root + path);
    }

    private HttpRequest.Builder request(String method, String path, HttpRequest.BodyPublisher body,
                                        String contentType, Duration timeout) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .timeout(timeout)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : body);
        if (key != null && !key.isBlank()) {
            request.header("Jenesis-Repository-Key", key);
        }
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }
        return request;
    }

    static void require(HttpResponse<String> response, int expected, String action) throws IOException {
        if (response.statusCode() == expected) {
            return;
        }
        if (response.statusCode() == 404
                && response.headers().firstValue("Jenesis-Installed").filter("false"::equals).isPresent()) {
            throw RepositoryClient.NotInstalled.unrouted(action);
        }
        throw new IOException("Could not " + action + " (HTTP " + response.statusCode() + ")" + referenced(response));
    }

    /** What the server said of a failure it did not mean, as its problem document names it - the sentence and the
     *  reference that finds the failure in its log - so the message carries what to quote; empty for any other
     *  answer, and for a body that is not such a document. */
    private static String referenced(HttpResponse<String> response) {
        if (response.statusCode() < 500 || response.body() == null || response.body().isBlank()) {
            return "";
        }
        try {
            JsonNode problem = JSON.readTree(response.body());
            JsonNode reference = problem.get("reference");
            if (reference != null && reference.isString()) {
                return ": " + problem.path("title").asString("Something went wrong on the server.") + " Reference: "
                        + reference.asString();
            }
        } catch (RuntimeException unreadable) {
            // Not a problem document - a proxy's page, a truncated body - so there is no reference to name.
        }
        return "";
    }

    static String enc(String value) {
        return URLEncoder.encode(value, UTF_8);
    }

    /** The acknowledgement of a started import or export: the job id to poll, and its state. */
    record ImportJob(String job, String state) {
    }
}
