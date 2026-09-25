package build.jenesis.repository.export;

import module java.base;
import module java.net.http;
import build.jenesis.repository.format.ExportTarget;

/**
 * An {@link ExportTarget} over HTTP: the URL a format's client is pointed at, and the credential it was given. Every
 * request resolves a relative path against that URL and is refused if it would leave it; no redirect is followed,
 * since a redirect is how a target that passed the address screen would send the bytes and the credential somewhere
 * that did not. The credential goes as {@code Basic} with a user name, else as {@code Bearer} - which is what this
 * product accepts - unless the exporter sets {@code Authorization} or the header its format's client uses itself.
 */
public final class HttpExportTarget implements ExportTarget {

    /** How much of a response body is kept: enough for the message a registry refuses with, never an artifact. */
    public static final int RESPONSE_CAP = 16 * 1024;

    private final URI base;
    private final Optional<Credential> credential;
    private final HttpClient client;

    public HttpExportTarget(URI url, Optional<Credential> credential) {
        String text = url.toString();
        this.base = URI.create(text.endsWith("/") ? text : text + "/");
        this.credential = credential;
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Override
    public Response send(Request request) throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(resolve(request.path()))
                .timeout(Duration.ofMinutes(30));
        request.headers().forEach(builder::header);
        authorize(builder, request.headers());
        HttpRequest.BodyPublisher body = request.body() == Body.NONE && !request.method().equals("POST")
                && !request.method().equals("PUT")
                ? HttpRequest.BodyPublishers.noBody()
                : publisher(request.body());
        builder.method(request.method(), body);
        try {
            HttpResponse<InputStream> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                return new Response(response.statusCode(),
                        new String(in.readNBytes(RESPONSE_CAP), StandardCharsets.UTF_8));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("the export was interrupted");
        }
    }

    @Override
    public Optional<String> sha256(String path) throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(resolve(path)).timeout(Duration.ofMinutes(30)).GET();
        authorize(builder, Map.of());
        try {
            HttpResponse<InputStream> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
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
            }
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("the export was interrupted");
        }
    }

    @Override
    public Optional<Credential> credential() {
        return credential;
    }

    /** The absolute URI of {@code path}, which must be relative and stay under the target's URL. */
    URI resolve(String path) {
        if (path.contains("://") || path.startsWith("/") || path.contains("\\")
                || Arrays.asList(path.split("[/?]", -1)).contains("..")) {
            throw new IllegalArgumentException("an export may only address paths under its target: " + path);
        }
        URI resolved = base.resolve(path);
        if (!resolved.toString().startsWith(base.toString())) {
            throw new IllegalArgumentException("an export may only address paths under its target: " + path);
        }
        return resolved;
    }

    private void authorize(HttpRequest.Builder builder, Map<String, String> headers) {
        if (credential.isEmpty() || headers.keySet().stream().anyMatch(name -> name.equalsIgnoreCase("Authorization")
                || name.equalsIgnoreCase("X-NuGet-ApiKey"))) {
            return;
        }
        Credential given = credential.get();
        builder.header("Authorization", given.username()
                .map(user -> "Basic " + Base64.getEncoder().encodeToString(
                        (user + ":" + given.secret()).getBytes(StandardCharsets.UTF_8)))
                .orElse("Bearer " + given.secret()));
    }

    private static HttpRequest.BodyPublisher publisher(Body body) {
        Supplier<InputStream> open = () -> {
            try {
                return body.open();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
        HttpRequest.BodyPublisher streamed = HttpRequest.BodyPublishers.ofInputStream(open);
        return body.length() >= 0 ? HttpRequest.BodyPublishers.fromPublisher(streamed, body.length()) : streamed;
    }
}
