package build.jenesis.repository.server;

import module java.base;

import build.jenesis.repository.format.FormatExchange;

/**
 * A {@link FormatExchange} synthesized for a request that has no socket behind it: a {@code PUT} of one body at one
 * path, or a {@link #read GET} of one path, with the format's status captured and whatever it writes discarded.
 *
 * <p>Two callers publish this way and share this one exchange. {@link BatchIngestion} explodes an archive
 * and publishes every entry, and the admin console's deploy screen publishes an operator's upload - both name a path
 * and hand over a stream, and neither has a socket to answer on. Sharing one implementation is what keeps them
 * answering the same way: the batch manifest and the console's verdict message are both reading a status that came
 * out of the same edge, through the same screen chain, with the same absent headers.
 *
 * <p><b>It carries no request headers, deliberately.</b> A format publishes the body plainly, and a header that
 * would change how a write is interpreted - the batch explode header above all - cannot ride in and recurse.
 */
public final class CapturingExchange implements FormatExchange {

    private final String method;
    private final String path;
    private final InputStream body;
    private int status;

    /** A {@code PUT} of {@code body} at {@code path}. */
    public CapturingExchange(String path, InputStream body) {
        this("PUT", path, body);
    }

    private CapturingExchange(String method, String path, InputStream body) {
        this.method = method;
        this.path = path;
        this.body = body;
    }

    /**
     * A {@code GET} of {@code path} whose body is discarded as it is written: what a read in process learns is the
     * status, and what the read leaves behind - a proxy's pull cached in the store - is the point of making it.
     */
    public static CapturingExchange read(String path) {
        return new CapturingExchange("GET", path, InputStream.nullInputStream());
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
        return null;
    }

    @Override
    public InputStream requestStream() {
        return body;
    }

    @Override
    public void setResponseHeader(String name, String value) {
        // Nothing carries a response back to a client here: a batch entry's headers are irrelevant beside the
        // manifest, and the console renders a verdict rather than proxying a response.
    }

    @Override
    public OutputStream respond(int status, long contentLength) {
        this.status = status;
        return OutputStream.nullOutputStream();
    }

    /** The status the format answered, which is the whole of what an in-process publish or read learns. */
    public int status() {
        return status;
    }
}
