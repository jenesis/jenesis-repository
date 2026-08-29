package build.jenesis.repository.server;

import build.jenesis.repository.format.FormatExchange;

import module java.base;

/**
 * A {@link FormatExchange} synthesized for a publish that has no request behind it: a {@code PUT} of one body at one
 * path, with the format's status captured and whatever it writes discarded.
 *
 * <p>Two callers publish this way and they used to have an exchange each. {@link BatchIngestion} explodes an archive
 * and publishes every entry, and the admin console's deploy screen publishes an operator's upload - both name a path
 * and hand over a stream, and neither has a socket to answer on. Sharing one implementation is what keeps them
 * answering the same way: the batch manifest and the console's verdict message are both reading a status that came
 * out of the same edge, through the same screen chain, with the same absent headers.
 *
 * <p><b>It carries no request headers, deliberately.</b> A format publishes the body plainly, and a header that
 * would change how a write is interpreted - the batch explode header above all - cannot ride in and recurse.
 */
public final class CapturingExchange implements FormatExchange {

    private final String path;
    private final InputStream body;
    private int status;

    public CapturingExchange(String path, InputStream body) {
        this.path = path;
        this.body = body;
    }

    @Override
    public String method() {
        return "PUT";
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

    /** The status the format answered, which is the whole of what an in-process publish learns. */
    public int status() {
        return status;
    }
}
