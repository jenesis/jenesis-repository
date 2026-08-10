package build.jenesis.repository.importer.testkit;

import module java.base;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.testkit.GeneratedBody;

/**
 * The incumbent, scripted: a {@link ProxyFormat.Fetcher} that answers each URL from a canned table and records every
 * request it was asked to make, so a contract check can assert what a walk fetched, how it authenticated, and - as
 * importantly - what it never fetched at all.
 *
 * <p>Five connector test modules each hand-rolled a {@code FakeFetcher} of this shape. This one is shared because the
 * contract checks that drive it must be identical across connectors: a per-connector double is how "the walk sends its
 * credentials" comes to mean five subtly different things.
 *
 * <p>Two behaviours are deliberately more than a stub:
 * <ul>
 *   <li>{@link #generating} registers a {@link GeneratedBody} that never exists as an array. It is served
 *       <em>only</em> through {@link #download}; a walk that reaches for the buffered {@link #fetch} overload instead
 *       trips an {@link AssertionError} naming the URL, because materialising an artifact to hand it on is exactly the
 *       failure the streaming clause forbids and it would otherwise pass every downstream assertion.</li>
 *   <li>An unmapped URL is a <em>transport failure</em> ({@link Optional#empty()}), not a 404 - the same distinction
 *       the real fetcher draws, and the one a connector's "no response from" path depends on.</li>
 * </ul>
 */
public final class ScriptedUpstream implements ProxyFormat.Fetcher {

    /** The request headers that carry a credential in this product: HTTP basic/bearer, and the jenesis API key. A
     *  check asserts their absence on an anonymous walk and their presence on an authenticated one, so it never has to
     *  know which of the two a given connector speaks. */
    public static final List<String> CREDENTIAL_HEADERS = List.of("Authorization", "Jenesis-Repository-Key");

    /** One scripted response: either canned bytes or a generated body, never both. */
    private record Response(int status, byte[] body, GeneratedBody generated, Map<String, String> headers) {
    }

    /** One request the walk made: the URL and the headers it carried. */
    public record Request(String url, Map<String, String> headers) {

        public Request {
            Objects.requireNonNull(url, "url");
            headers = Map.copyOf(headers);
        }

        /** The first value of a request header, case-insensitively, or {@code null}. */
        public String header(String name) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name)) {
                    return entry.getValue();
                }
            }
            return null;
        }

        /** Whether this request carried any of the {@link #CREDENTIAL_HEADERS}. */
        public boolean authenticated() {
            return CREDENTIAL_HEADERS.stream().anyMatch(name -> header(name) != null);
        }
    }

    private final Map<String, Response> responses = new LinkedHashMap<>();
    private final List<Request> requests = new CopyOnWriteArrayList<>();

    private ScriptedUpstream() {
    }

    /** An incumbent that answers nothing - every URL is a transport failure. */
    public static ScriptedUpstream incumbent() {
        return new ScriptedUpstream();
    }

    /** Answer {@code url} with {@code status} and {@code body}. */
    public ScriptedUpstream answering(String url, int status, byte[] body) {
        responses.put(url, new Response(status, body.clone(), null, Map.of()));
        return this;
    }

    /** Answer {@code url} with {@code status} and {@code body} as UTF-8 - the shape of every listing document. */
    public ScriptedUpstream answering(String url, int status, String body) {
        return answering(url, status, body.getBytes(StandardCharsets.UTF_8));
    }

    /** Answer {@code url} with {@code status} and no body - a refusal. */
    public ScriptedUpstream refusing(String url, int status) {
        return answering(url, status, new byte[0]);
    }

    /**
     * Answer {@code url} with {@code body}, streamed. The body is generated as it is read and counts what it has handed
     * out, so a check can ask how much of it a connector had already pulled when it passed the stream on. Reaching for
     * this URL through the buffered {@link #fetch} overload fails the check outright.
     */
    public ScriptedUpstream generating(String url, GeneratedBody body) {
        responses.put(url, new Response(200, null, Objects.requireNonNull(body, "body"), Map.of()));
        return this;
    }

    /** Every request the walk made, in order. */
    public List<Request> requests() {
        return List.copyOf(requests);
    }

    /** Every URL the walk asked for, in order. */
    public List<String> urls() {
        return requests.stream().map(Request::url).toList();
    }

    @Override
    public Optional<ProxyFormat.Fetched> fetch(URI url, Map<String, String> requestHeaders) {
        Response response = record(url, requestHeaders);
        if (response == null) {
            return Optional.empty();
        }
        if (response.generated() != null) {
            throw new AssertionError("The walk read the artifact at '" + url + "' through the buffered fetch() "
                    + "overload, which materialises the whole body as a byte[]. An asset copies from the incumbent to "
                    + "storage through download() so a multi-gigabyte artifact never sits in heap (§1); fetch() is for "
                    + "the small listing and index documents a connector must parse.");
        }
        return Optional.of(new ProxyFormat.Fetched(response.status(), response.body().clone(), response.headers()));
    }

    @Override
    public Optional<ProxyFormat.Download> download(URI url, Map<String, String> requestHeaders) {
        Response response = record(url, requestHeaders);
        if (response == null) {
            return Optional.empty();
        }
        InputStream body = response.generated() != null
                ? response.generated().open()
                : new ByteArrayInputStream(response.body());
        return Optional.of(new ProxyFormat.Download(response.status(), body, response.headers()));
    }

    @Override
    public Optional<ProxyFormat.Head> head(URI url, Map<String, String> requestHeaders) {
        // Answered from the canned table, never by opening the download: a scripted upstream that derived its HEAD
        // from its own body would serve a GeneratedBody's stream to answer a question about metadata, and a connector
        // whose real transport did the same would sail through every check here.
        Response response = record(url, requestHeaders);
        return response == null
                ? Optional.empty()
                : Optional.of(new ProxyFormat.Head(response.status(), response.headers()));
    }

    private Response record(URI url, Map<String, String> requestHeaders) {
        requests.add(new Request(url.toString(), requestHeaders));
        return responses.get(url.toString());
    }
}
