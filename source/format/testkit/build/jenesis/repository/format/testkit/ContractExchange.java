package build.jenesis.repository.format.testkit;

import module java.base;
import build.jenesis.repository.format.FormatExchange;

/**
 * The in-memory {@link FormatExchange} the contract drives a format through, plus the two things a plain capture
 * cannot tell you.
 *
 * <p><b>Which overload answered.</b> {@link FormatExchange} has two response shapes, and the difference is the whole
 * of the revalidation clause: a body handed over whole ({@code respond(status, byte[])}) can be hashed into an
 * {@code ETag} by the dispatcher, while a body streamed against a length ({@code respond(status, long)}) cannot. A
 * format that streams its generated index is therefore un-revalidatable no matter what it renders, and nothing in a
 * captured status or body would show it - so {@link #buffered()} records it.
 *
 * <p><b>Conditional revalidation.</b> The production validator is not a format's business: it lives once in the
 * servlet dispatcher, which hashes any buffered {@code 200} into an {@code ETag} and answers {@code 304} to a matching
 * {@code If-None-Match}. This exchange mirrors exactly those two rules, so the kit can drive a real conditional
 * re-fetch through a format end to end. It is a stand-in for that dispatcher, not a second implementation of it: what
 * the contract actually pins on the format is that its generated document arrives buffered and is a pure function of
 * the stored state (identical bytes twice over unchanged state, different bytes once the state changes) - the two
 * properties a content-derived validator rests on and that a format can get wrong.
 */
public final class ContractExchange implements FormatExchange {

    private final String method;
    private final String path;
    private final Map<String, String> query;
    private final Map<String, String> requestHeaders;
    private final UnaryOperator<String> settings;
    private final Supplier<InputStream> requestBody;

    /** How much of a response body is kept for inspection. A served artifact may be gigabytes - the streaming leg
     *  really does serve one - so the body is digested and counted in full but only its prefix is retained: an
     *  exchange that accumulated everything would make the test the thing that buffers the artifact. */
    private static final int RETAINED = 1 << 20;

    private final Map<String, String> responseHeaders = new LinkedHashMap<>();
    private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
    private final MessageDigest responseDigest = sha256();
    private long responseLength;
    private int status = -1;
    private boolean buffered;

    private ContractExchange(String method, String path, Map<String, String> query,
                             Map<String, String> requestHeaders, UnaryOperator<String> settings,
                             Supplier<InputStream> requestBody) {
        this.method = method;
        this.path = path;
        this.query = Map.copyOf(query);
        this.requestHeaders = Map.copyOf(requestHeaders);
        this.settings = settings;
        this.requestBody = requestBody;
    }

    /** A bodiless request ({@code GET}, {@code HEAD}, {@code DELETE}). */
    public static ContractExchange of(String method, String path) {
        return new ContractExchange(method, path, Map.of(), Map.of(), _ -> null, InputStream::nullInputStream);
    }

    /** A request carrying {@code body}. */
    public static ContractExchange of(String method, String path, byte[] body) {
        return new ContractExchange(method, path, Map.of(), Map.of(), _ -> null,
                () -> new ByteArrayInputStream(body));
    }

    /** A request whose body is generated as the format reads it - the streaming leg's upload counterpart. */
    public static ContractExchange streaming(String method, String path, GeneratedBody body) {
        return new ContractExchange(method, path, Map.of(), Map.of(), _ -> null, body::open);
    }

    /** The same exchange with a query parameter set. */
    public ContractExchange query(String name, String value) {
        Map<String, String> merged = new LinkedHashMap<>(query);
        merged.put(name, value);
        return new ContractExchange(method, path, merged, requestHeaders, settings, requestBody);
    }

    /** The same exchange with a request header set. */
    public ContractExchange header(String name, String value) {
        Map<String, String> merged = new LinkedHashMap<>(requestHeaders);
        merged.put(name, value);
        return new ContractExchange(method, path, query, merged, settings, requestBody);
    }

    /** The same exchange resolving {@link #setting} through {@code settings} - the seam a format reads a deployment
     *  toggle from (the Maven metadata computation, say) without binding to any settings layer. */
    public ContractExchange settings(UnaryOperator<String> settings) {
        return new ContractExchange(method, path, query, requestHeaders, settings, requestBody);
    }

    // --- what the format saw ---------------------------------------------------------------------------------------

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
    public String setting(String key) {
        return settings.apply(key);
    }

    @Override
    public InputStream requestStream() {
        return requestBody.get();
    }

    // --- what the format answered ----------------------------------------------------------------------------------

    @Override
    public void setResponseHeader(String name, String value) {
        responseHeaders.put(name, value);
    }

    @Override
    public OutputStream respond(int status, long contentLength) {
        this.status = status;
        return new OutputStream() {

            @Override
            public void write(int value) {
                write(new byte[]{(byte) value}, 0, 1);
            }

            @Override
            public void write(byte[] bytes, int offset, int length) {
                Objects.checkFromIndexSize(offset, length, bytes.length);
                responseDigest.update(bytes, offset, length);
                responseLength += length;
                int retain = (int) Math.min(length, Math.max(0, RETAINED - responseBody.size()));
                if (retain > 0) {
                    responseBody.write(bytes, offset, retain);
                }
            }
        };
    }

    /**
     * The buffered response overload, mirroring the servlet dispatcher: a non-empty {@code 200} carries an
     * {@code ETag} of its own bytes and a matching {@code If-None-Match} is answered {@code 304} with no body.
     */
    @Override
    public void respond(int status, byte[] content) throws IOException {
        buffered = true;
        if (status == 200 && content.length > 0) {
            String etag = validator(content);
            responseHeaders.put("ETag", etag);
            if (matches(requestHeaders.get("If-None-Match"), etag)) {
                this.status = 304;
                return;
            }
        }
        try (OutputStream out = respond(status, content.length == 0 ? -1 : content.length)) {
            out.write(content);
        }
    }

    /** The status the format answered, or {@code -1} when it answered nothing at all. */
    public int status() {
        return status;
    }

    /** The response body the format wrote, truncated past the retained prefix - use {@link #responseLength()} and
     *  {@link #responseSha256()} for a body that may be artifact-sized. */
    public byte[] responseBytes() {
        return responseBody.toByteArray();
    }

    /** How many bytes the format wrote, whatever their size. */
    public long responseLength() {
        return responseLength;
    }

    /** The SHA-256 of everything the format wrote, in lower-case hex - exact for a body of any size. */
    public String responseSha256() {
        try {
            return HexFormat.of().formatHex(((MessageDigest) responseDigest.clone()).digest());
        } catch (CloneNotSupportedException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The response body as UTF-8 text. */
    public String responseText() {
        return responseBody.toString(StandardCharsets.UTF_8);
    }

    /** A response header the format set. */
    public String responseHeader(String name) {
        return responseHeaders.get(name);
    }

    /** Whether the format answered through the buffered {@code respond(status, byte[])} overload - the only shape a
     *  dispatcher can attach a content-derived validator to. */
    public boolean buffered() {
        return buffered;
    }

    /** The validator a dispatcher derives from a buffered body - the same content hash the servlet exchange uses. */
    public static String validator(byte[] content) {
        return '"' + HexFormat.of().formatHex(sha256().digest(content)) + '"';
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Whether an {@code If-None-Match} header covers this validator (strong or weak form, or {@code *}). */
    private static boolean matches(String header, String etag) {
        if (header == null) {
            return false;
        }
        if (header.trim().equals("*")) {
            return true;
        }
        for (String candidate : header.split(",")) {
            String tag = candidate.trim();
            if (tag.equals(etag) || tag.equals("W/" + etag)) {
                return true;
            }
        }
        return false;
    }
}
