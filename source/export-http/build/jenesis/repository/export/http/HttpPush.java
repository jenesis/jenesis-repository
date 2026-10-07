package build.jenesis.repository.export.http;

import module java.base;
import module java.net.http;
import build.jenesis.repository.format.ExportTarget;
import build.jenesis.repository.net.http.ScreenedHttpClient;

/**
 * Requests to another repository over HTTP. No redirect is followed, since a redirect could send the bytes and the
 * credential somewhere the address screen never saw; a body streams from where it is held, of its length where that
 * is known; and an answer is read back only as far as {@value #RESPONSE_CAP} bytes - enough for the message a registry
 * refuses with, never an artifact - so a refusal reaches whoever reads the failure in the registry's own words.
 */
public final class HttpPush {

    /** How much of a response body is kept. */
    public static final int RESPONSE_CAP = 16 * 1024;

    private final HttpClient client;

    private final Duration timeout;

    /** A push whose every request is given {@code timeout} to complete. */
    public HttpPush(Duration timeout) {
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.client = ScreenedHttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    /**
     * The absolute URI of {@code path} under {@code base}, refused unless it stays there: a path that is absolute,
     * names a scheme, carries a backslash or a {@code ..} segment, or resolves outside {@code base} throws
     * {@link IllegalArgumentException} before anything is sent.
     */
    public static URI under(URI base, String path) {
        String root = base.toString().endsWith("/") ? base.toString() : base + "/";
        if (path.contains("://") || path.startsWith("/") || path.contains("\\")
                || Arrays.asList(path.split("[/?]", -1)).contains("..")) {
            throw new IllegalArgumentException("a push may only address paths under its target: " + path);
        }
        URI resolved = URI.create(root).resolve(path);
        if (!resolved.toString().startsWith(root)) {
            throw new IllegalArgumentException("a push may only address paths under its target: " + path);
        }
        return resolved;
    }

    /** Send one request and answer its status, the first {@value #RESPONSE_CAP} bytes of what came back, and the
     *  {@code Location} it named, resolved against {@code url} - absolute here, since only a caller that knows the
     *  target's URL can say what is under it. */
    public ExportTarget.Response send(URI url, String method, Map<String, String> headers, ExportTarget.Body body)
            throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(url).timeout(timeout);
        headers.forEach(builder::header);
        builder.method(method, publisher(body));
        HttpResponse<InputStream> response = call(builder.build());
        try (InputStream in = response.body()) {
            return new ExportTarget.Response(response.statusCode(),
                    new String(in.readNBytes(RESPONSE_CAP), StandardCharsets.UTF_8),
                    response.headers().firstValue("Location").map(location -> url.resolve(location).toString()));
        }
    }

    /** The SHA-256 of what {@code url} serves, or empty when it answers anything but a {@code 2xx}. */
    public Optional<String> sha256(URI url, Map<String, String> headers) throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(url).timeout(timeout).GET();
        headers.forEach(builder::header);
        HttpResponse<InputStream> response = call(builder.build());
        try (InputStream in = response.body()) {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return Optional.empty();
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            for (int read; (read = in.read(buffer)) >= 0; ) {
                digest.update(buffer, 0, read);
            }
            return Optional.of(HexFormat.of().formatHex(digest.digest()));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private HttpResponse<InputStream> call(HttpRequest request) throws IOException {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted sending to " + request.uri());
        }
    }

    /** A streamed body, of its length where it is known. An empty one goes as the empty body, since the JDK refuses a
     *  streamed publisher of length zero. */
    private static HttpRequest.BodyPublisher publisher(ExportTarget.Body body) {
        if (body.length() == 0) {
            return HttpRequest.BodyPublishers.noBody();
        }
        HttpRequest.BodyPublisher streamed = HttpRequest.BodyPublishers.ofInputStream(() -> {
            try {
                return body.open();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        return body.length() > 0 ? HttpRequest.BodyPublishers.fromPublisher(streamed, body.length()) : streamed;
    }
}
