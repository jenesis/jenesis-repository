package build.jenesis.repository.format;

import module java.base;

import build.jenesis.repository.store.ArtifactStore;

/**
 * A {@link FormatExchange} that wraps another and forwards every method to it, the defaulted ones included, so a
 * decorator overrides only what it changes. A decorator written against the interface inherits a default wherever it
 * forgets to forward: a range answered from byte 0, a format's audit line that records nothing, an index whose
 * conditional revalidation is lost, a caller's rights read as none. Here the defaults are the wrapped exchange's.
 *
 * <p>A decorator that holds the response back rather than streaming it to the wrapped exchange overrides
 * {@link #from} to answer {@code 0}, since the wrapped exchange slices a range from what it is finally handed, and
 * overrides every response method it intercepts: {@link #respond(int, long)}, {@link #respond(int, byte[])} and
 * {@link #setResponseHeader}.
 */
public abstract class ForwardingExchange implements FormatExchange {

    /** The exchange this one wraps. */
    protected final FormatExchange delegate;

    protected ForwardingExchange(FormatExchange delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
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
    public String requestedPath() {
        return delegate.requestedPath();
    }

    @Override
    public String requestUri() {
        return delegate.requestUri();
    }

    @Override
    public String external(String formatPath) {
        return delegate.external(formatPath);
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
        delegate.setResponseHeader(name, value);
    }

    @Override
    public OutputStream respond(int status, long contentLength) throws IOException {
        return delegate.respond(status, contentLength);
    }

    @Override
    public long from(long contentLength) {
        return delegate.from(contentLength);
    }

    @Override
    public void respond(int status, byte[] content) throws IOException {
        delegate.respond(status, content);
    }

    @Override
    public void audit(String action, String target) {
        delegate.audit(action, target);
    }

    @Override
    public Optional<ArtifactStore> readable(String path) {
        return delegate.readable(path);
    }

    @Override
    public boolean readsHeld() {
        return delegate.readsHeld();
    }

    @Override
    public boolean administers() {
        return delegate.administers();
    }
}
