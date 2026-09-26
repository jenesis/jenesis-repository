package build.jenesis.repository.blobs;

import module java.base;
import build.jenesis.repository.format.FormatExchange;

/**
 * A one-shot {@link FormatExchange} that replays a single stored body into a {@code RepositoryFormat} through its own
 * {@code handle}, so an {@code importArtifact} lays an asset out by driving the format's publish path rather than
 * re-implementing it. The source stream is passed straight through (never buffered whole), so the format spools a large
 * package into the CAS unbuffered; the format's response is discarded, since a replay cares that the version
 * materialises, not what the publish would have answered. Carries no query parameters, and request headers only when
 * built with them ({@link #put(String, InputStream, Map)}) - the language importers each declared a private, byte-identical copy
 * of this stub before it was hoisted here, the one module every format module already requires.
 *
 * <p>The method defaults to {@code PUT} (the raw-push shape every pool/flat-container/dist format uses); a format whose
 * publish endpoint is a {@code POST} (the RubyGems gem push) builds one with {@link #post}.
 */
public final class ReplayExchange implements FormatExchange {

    private final String method;
    private final String path;
    private final InputStream body;
    private final Map<String, String> headers;

    /** A {@code PUT} replay of {@code body} at {@code path} - the raw-push shape the pool/flat-container/dist formats use. */
    public ReplayExchange(String path, InputStream body) {
        this("PUT", path, body, Map.of());
    }

    private ReplayExchange(String method, String path, InputStream body, Map<String, String> headers) {
        this.method = method;
        this.path = path;
        this.body = body;
        this.headers = headers;
    }

    /** A {@code PUT} replay that carries request headers - the shape a format whose publish reads one (a multipart
     *  boundary in {@code Content-Type}) drives its {@code handle} with. Header names match case-insensitively. */
    public static ReplayExchange put(String path, InputStream body, Map<String, String> headers) {
        Map<String, String> named = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        named.putAll(headers);
        return new ReplayExchange("PUT", path, body, Collections.unmodifiableMap(named));
    }

    /** A {@code POST} replay of {@code body} at {@code path} - the shape a format whose publish endpoint is a POST (a
     *  RubyGems gem push) drives its {@code handle} with. */
    public static ReplayExchange post(String path, InputStream body) {
        return new ReplayExchange("POST", path, body, Map.of());
    }

    @Override
    public String method() {
        return method;
    }

    @Override
    public String path() {
        return path;
    }

    @Override
    public String queryParameter(String name) {
        return null;
    }

    @Override
    public String requestHeader(String name) {
        return headers.get(name);
    }

    @Override
    public InputStream requestStream() {
        return body;
    }

    @Override
    public void setResponseHeader(String name, String value) {
        // the replay discards the publish response; the import outcome is the laid-out asset
    }

    @Override
    public OutputStream respond(int status, long contentLength) {
        return OutputStream.nullOutputStream();
    }
}
