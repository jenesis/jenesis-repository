package build.jenesis.repository.export.http;

import module java.base;
import build.jenesis.repository.format.ExportTarget;

/**
 * An {@link ExportTarget} over HTTP: the URL a format's client is pointed at, and its credential. Every request
 * resolves a relative path against that URL and is refused if it would leave it, and goes through {@link HttpPush}.
 * The credential goes as {@code Basic} with a user name, else {@code Bearer}, unless the exporter sets
 * {@code Authorization} or its client's own header itself.
 */
public final class HttpExportTarget implements ExportTarget {

    private final URI base;
    private final Optional<Credential> credential;
    private final HttpPush push = new HttpPush(Duration.ofMinutes(30));

    public HttpExportTarget(URI url, Optional<Credential> credential) {
        this.base = url;
        this.credential = credential;
    }

    @Override
    public Response send(Request request) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>(request.headers());
        authorize(headers);
        return push.send(HttpPush.under(base, request.path()), request.method(), headers, request.body());
    }

    @Override
    public Optional<String> sha256(String path) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        authorize(headers);
        return push.sha256(HttpPush.under(base, path), headers);
    }

    @Override
    public Optional<Credential> credential() {
        return credential;
    }

    private void authorize(Map<String, String> headers) {
        if (credential.isEmpty() || headers.keySet().stream().anyMatch(name -> name.equalsIgnoreCase("Authorization")
                || name.equalsIgnoreCase("X-NuGet-ApiKey"))) {
            return;
        }
        Credential given = credential.get();
        headers.put("Authorization", given.username()
                .map(user -> "Basic " + Base64.getEncoder().encodeToString(
                        (user + ":" + given.secret()).getBytes(StandardCharsets.UTF_8)))
                .orElse("Bearer " + given.secret()));
    }
}
