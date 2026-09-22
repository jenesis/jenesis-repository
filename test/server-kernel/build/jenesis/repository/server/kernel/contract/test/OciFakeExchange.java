package build.jenesis.repository.server.kernel.contract.test;

import module java.base;

import build.jenesis.repository.format.FormatExchange;

/**
 * An in-memory {@link FormatExchange} for driving {@code OciFormat.handle} without an HTTP server - the
 * counterpart of the free OCI suite's fake exchange, so the OCI hold-lifecycle E2E exercises the real push/pull
 * choreography in process rather than booting the whole server (which would only add load to the shared test JVM). It
 * carries the request method, path, query parameters, headers and body, and captures the status, response headers and
 * response body the format writes; a {@link ByteArrayOutputStream} close is a no-op, so streamed and buffered responses
 * are both captured.
 */
final class OciFakeExchange implements FormatExchange {

    private final String method;
    private final String path;
    private final Map<String, String> query;
    private final Map<String, String> requestHeaders;
    private final byte[] requestBody;
    private final Map<String, String> responseHeaders = new LinkedHashMap<>();
    private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
    private int status = -1;

    OciFakeExchange(String method, String path) {
        this(method, path, new byte[0], Map.of(), Map.of());
    }

    OciFakeExchange(String method, String path, byte[] requestBody,
                    Map<String, String> query, Map<String, String> requestHeaders) {
        this.method = method;
        this.path = path;
        this.requestBody = requestBody;
        this.query = query;
        this.requestHeaders = requestHeaders;
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
        return query.get(name);
    }

    @Override
    public String requestHeader(String name) {
        return requestHeaders.get(name);
    }

    @Override
    public InputStream requestStream() {
        return new ByteArrayInputStream(requestBody);
    }

    @Override
    public void setResponseHeader(String name, String value) {
        responseHeaders.put(name, value);
    }

    @Override
    public OutputStream respond(int status, long contentLength) {
        this.status = status;
        return responseBody;
    }

    int status() {
        return status;
    }

    byte[] responseBytes() {
        return responseBody.toByteArray();
    }

    String responseHeader(String name) {
        return responseHeaders.get(name);
    }
}
