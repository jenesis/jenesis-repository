package build.jenesis.repository.feed;

import module java.base;
import build.jenesis.repository.net.Origins;

/**
 * One HTTP request a feed makes: where, how, with which headers and, for a POST query, which body - the vendor-specific
 * half of a fetch, built by the feed module and handed to {@link FeedClient}.
 *
 * <p>Immutable: every mutator returns a fresh request, so a first request can be retried verbatim.
 *
 * <p><strong>Credentials never reach a log or an exception message.</strong> {@link #toString()} masks every header
 * whose name carries a credential ({@code Authorization}, {@code Cookie}, {@code *-api-key}, {@code *-token}, ...),
 * since a {@link FeedException} names the request it failed on.
 */
public record FeedRequest(URI uri, String method, Map<String, String> headers, String body) {

    /** The header names whose value is a credential and is masked in every rendering of a request. */
    private static final Set<String> SECRET = Set.of("authorization", "proxy-authorization", "cookie", "set-cookie");

    /** Header-name fragments that mark a vendor-specific credential header ({@code X-Api-Key}, {@code X-Auth-Token}). */
    private static final List<String> SECRET_FRAGMENTS = List.of("api-key", "apikey", "token", "secret", "password");

    public FeedRequest {
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(headers, "headers");
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("A feed request needs an absolute URI with a host: " + uri);
        }
        if (method.isBlank()) {
            throw new IllegalArgumentException("A feed request needs an HTTP method");
        }
        SortedMap<String, String> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.forEach((name, value) -> {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("A feed request header needs a name");
            }
            copy.put(name, Objects.requireNonNull(value, "The value of header " + name));
        });
        headers = Collections.unmodifiableSortedMap(copy);
        method = method.strip().toUpperCase(Locale.ROOT);
    }

    /** A bodyless {@code GET} - the shape of every catalogue download and most vendor query APIs. */
    public static FeedRequest get(URI uri) {
        return new FeedRequest(uri, "GET", Map.of(), null);
    }

    /** A {@code POST} carrying {@code body} (sent as UTF-8) with the given {@code Content-Type}. */
    public static FeedRequest post(URI uri, String body, String contentType) {
        return new FeedRequest(uri, "POST", Map.of("Content-Type", Objects.requireNonNull(contentType, "contentType")),
                Objects.requireNonNull(body, "body"));
    }

    /** This request with one header set (replacing a same-named one, case-insensitively). */
    public FeedRequest header(String name, String value) {
        SortedMap<String, String> merged = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        merged.putAll(headers);
        merged.put(name, value);
        return new FeedRequest(uri, method, merged, body);
    }

    /** This request with an {@code Authorization: Bearer <token>} header - the scheme most vendor APIs use. */
    public FeedRequest bearer(String token) {
        return header("Authorization", "Bearer " + Objects.requireNonNull(token, "token"));
    }

    /** This request with an HTTP Basic {@code Authorization} header. */
    public FeedRequest basic(String user, String password) {
        return header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                (Objects.requireNonNull(user, "user") + ':' + Objects.requireNonNull(password, "password"))
                        .getBytes(StandardCharsets.UTF_8)));
    }

    /** This request re-pointed at another URI, keeping method, headers and body - how a cursor page is drawn. */
    public FeedRequest to(URI next) {
        return new FeedRequest(next, method, headers, body);
    }

    /** This request re-pointed at another URI with a fresh body - how a token-echoing POST draws its next page. */
    public FeedRequest to(URI next, String nextBody) {
        return new FeedRequest(next, method, headers, nextBody);
    }

    /** The value of a header, matched case-insensitively; empty when the request carries none. */
    public Optional<String> header(String name) {
        return Optional.ofNullable(headers.get(name));
    }

    /** Whether {@code other} is on this request's origin. A cursor elsewhere would both steer the fetch (an SSRF, when
     *  the target is internal) and carry the request's credential to another host. */
    public boolean sameOrigin(URI other) {
        return Origins.same(uri, other);
    }

    @Override
    public String toString() {
        StringJoiner rendered = new StringJoiner(", ", "FeedRequest[", "]");
        rendered.add(method + " " + uri);
        headers.forEach((name, value) -> rendered.add(name + "=" + (secret(name) ? "<redacted>" : value)));
        if (body != null) {
            rendered.add("body=" + body.length() + " chars");
        }
        return rendered.toString();
    }

    /** Whether a header name carries a credential and must never be rendered. */
    private static boolean secret(String name) {
        String lowered = name.toLowerCase(Locale.ROOT);
        if (SECRET.contains(lowered)) {
            return true;
        }
        for (String fragment : SECRET_FRAGMENTS) {
            if (lowered.contains(fragment)) {
                return true;
            }
        }
        return false;
    }
}
