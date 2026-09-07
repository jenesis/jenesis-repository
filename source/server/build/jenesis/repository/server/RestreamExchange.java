package build.jenesis.repository.server;

import module java.base;

import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.store.Publication;

/**
 * A {@link FormatExchange} that wraps a real request/response exchange but restreams its request body from an
 * already-stored source instead of the socket, so an ingress edge can hand an accepted blob back to the claiming
 * {@link build.jenesis.repository.format.RepositoryFormat} for pure layout. Everything except the body - the method,
 * path, query, headers, settings, and the whole response side (status, headers, streamed body, range/conditional
 * handling) - delegates to the wrapped exchange, so the format writes its response straight to the original client
 * exactly as it would on a direct dispatch; only {@link #requestStream()} is redirected to the stored blob.
 *
 * <p>This is the core, edition-neutral restream exchange the edge screening choreography builds on: after
 * {@link build.jenesis.repository.store.Publication#screen} accepts a body, the edge hands the format a
 * {@link Publication.Stored} stream over the acceptance. The body is a restream, never a buffered copy - each
 * {@link #requestStream()} is a fresh stream that opens {@code blobs/<hash>} lazily on the first read - so a large
 * artifact goes from storage to the format's layout write without being materialised in memory, and a layout that
 * only stores what it was given stores nothing: {@link Publication#storeBlob} recognises the stream and answers the
 * hash. Measured before that: every screened publish wrote its blob twice and read it once more to do so.
 */
public final class RestreamExchange implements FormatExchange {

    private final FormatExchange delegate;
    private final Publication.Acceptance accepted;

    public RestreamExchange(FormatExchange delegate, Publication.Acceptance accepted) {
        this.delegate = delegate;
        this.accepted = accepted;
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
        return new Publication.Stored(accepted);
    }

    @Override
    public void setResponseHeader(String name, String value) {
        delegate.setResponseHeader(name, value);
    }

    @Override
    public OutputStream respond(int status, long contentLength) throws IOException {
        return delegate.respond(status, contentLength);
    }
}
