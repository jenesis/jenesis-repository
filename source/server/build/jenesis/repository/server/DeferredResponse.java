package build.jenesis.repository.server;

import module java.base;

import build.jenesis.repository.format.FormatExchange;

/**
 * A {@link FormatExchange} that holds the format's response back until the edge lets it go.
 *
 * <p>The response to an accepted write is written by the format inside the edge's layout callback, and the servlet
 * exchange commits it the moment the format closes its stream - before {@code Publication.commit} has fired the
 * after-commit observers. The client was therefore acknowledged while the publish's consequences were still running,
 * and its next request raced them. Measured 2026-09-12 by the soak, with {@code signature-missing} at QUARANTINE so
 * that a Maven {@code .asc} releases the artifact it completes: the release ran behind the sidecar's {@code 201}, a
 * jar completed a moment earlier answered {@code 404} to a reader for the length of its own release (twenty-five
 * times in a quarter of an hour), and one jar publish answered {@code 500} because its hold record was written into a
 * directory that the POM's release - still running behind the POM signature's {@code 201} - had just pruned
 * ({@code NoSuchFileException} under {@code holds/signature/}). Everything an accepted publish causes now happens
 * before the client hears that it was accepted.
 *
 * <p>Only the response side is held; the request side is the wrapped exchange's. A write's response is a status,
 * a few headers and at most a small document, so keeping it costs nothing measurable, and a streamed read never
 * passes through here - the edge screens single-body writes and nothing else.
 */
public final class DeferredResponse implements FormatExchange {

    private final FormatExchange delegate;
    private final Map<String, String> headers = new LinkedHashMap<>();
    private final ByteArrayOutputStream body = new ByteArrayOutputStream();
    private int status = -1;
    private long contentLength = -1L;

    public DeferredResponse(FormatExchange delegate) {
        this.delegate = delegate;
    }

    @Override
    public String method() {
        return delegate.method();
    }

    @Override
    public String path() {
        return delegate.path();
    }

    @Override
    public String requestUri() {
        return delegate.requestUri();
    }

    @Override
    public String scheme() {
        return delegate.scheme();
    }

    @Override
    public String remoteAddress() {
        return delegate.remoteAddress();
    }

    @Override
    public String queryParameter(String name) {
        return delegate.queryParameter(name);
    }

    @Override
    public String requestHeader(String name) {
        return delegate.requestHeader(name);
    }

    @Override
    public String setting(String key) {
        return delegate.setting(key);
    }

    @Override
    public InputStream requestStream() throws IOException {
        return delegate.requestStream();
    }

    @Override
    public void setResponseHeader(String name, String value) {
        headers.put(name, value);
    }

    @Override
    public OutputStream respond(int status, long contentLength) {
        this.status = status;
        this.contentLength = contentLength;
        body.reset();
        return new FilterOutputStream(body) {

            @Override
            public void write(byte[] bytes, int offset, int length) {
                body.write(bytes, offset, length);
            }

            @Override
            public void close() {
                // The client's stream is closed when the response is released, not when the format is done with it.
            }
        };
    }

    /** Whether the format answered at all; a format that laid out and said nothing leaves the edge to answer. */
    public boolean answered() {
        return status >= 0;
    }

    /** Write the held response to the client, headers first, exactly as the format wrote it. */
    public void release() throws IOException {
        if (!answered()) {
            return;
        }
        headers.forEach(delegate::setResponseHeader);
        long length = contentLength >= 0 ? contentLength : body.size() > 0 ? body.size() : -1L;
        try (OutputStream out = delegate.respond(status, length)) {
            body.writeTo(out);
        }
    }
}
