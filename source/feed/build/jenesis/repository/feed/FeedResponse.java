package build.jenesis.repository.feed;

import module java.base;

/**
 * One HTTP answer a {@link FeedTransport} hands back: status, headers and the body as a stream, never whole, since a
 * catalogue is a multi-megabyte document; {@link FeedClient} wraps it in the policy's byte cap before a reader sees it.
 *
 * <p>The response owns its stream and {@link FeedClient} closes it before the next page, so a reader consumes what it
 * needs within its callback.
 */
public record FeedResponse(int status, Map<String, List<String>> headers, InputStream body) implements Closeable {

    public FeedResponse {
        Objects.requireNonNull(headers, "headers");
        Objects.requireNonNull(body, "body");
        SortedMap<String, List<String>> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.forEach((name, values) -> copy.put(Objects.requireNonNull(name, "header name"),
                List.copyOf(Objects.requireNonNull(values, "The values of header " + name))));
        headers = Collections.unmodifiableSortedMap(copy);
    }

    /** A response with no headers, as a recorded-response test double answers. */
    public static FeedResponse of(int status, String body) {
        return new FeedResponse(status, Map.of(),
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }

    /** The first value of a header, matched case-insensitively; empty when the response carries none. */
    public Optional<String> header(String name) {
        List<String> values = headers.get(name);
        return values == null || values.isEmpty() ? Optional.empty() : Optional.ofNullable(values.getFirst());
    }

    /** The delay a {@code Retry-After} header asks for in its delta-seconds form, obeyed in preference to the client's
     *  own backoff (bounded by the policy's maximum). The HTTP-date form and an unparseable or negative value are
     *  ignored. */
    public Optional<Duration> retryAfter() {
        return header("Retry-After").flatMap(value -> {
            try {
                long seconds = Long.parseLong(value.strip());
                return seconds < 0 ? Optional.empty() : Optional.of(Duration.ofSeconds(seconds));
            } catch (NumberFormatException _) {
                return Optional.empty();
            }
        });
    }

    /** This response with its body replaced, how the client interposes its byte cap. */
    public FeedResponse over(InputStream replacement) {
        return new FeedResponse(status, headers, replacement);
    }

    @Override
    public void close() throws IOException {
        body.close();
    }
}
