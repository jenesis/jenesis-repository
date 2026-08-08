package build.jenesis.repository.feed;

import module java.base;

/**
 * One HTTP answer a {@link FeedTransport} hands back: the status, the response headers and the body as a
 * <em>stream</em>. The body is never a {@code String} or a {@code byte[]}, because a catalogue feed answers with a
 * multi-megabyte document a reader must parse incrementally (&sect;1) - {@link FeedClient} additionally wraps the
 * stream in the policy's byte cap before a reader ever sees it, so a feed cannot spend unbounded heap by answering
 * with an unbounded body.
 *
 * <p>The response owns its stream: {@link FeedClient} closes each response before it draws the next page, so a
 * reader must consume what it needs within its callback rather than retaining the stream.
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

    /** A response with no headers - the shape a recorded-response test double answers with. */
    public static FeedResponse of(int status, String body) {
        return new FeedResponse(status, Map.of(),
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }

    /** The first value of a header, matched case-insensitively; empty when the response carries none. */
    public Optional<String> header(String name) {
        List<String> values = headers.get(name);
        return values == null || values.isEmpty() ? Optional.empty() : Optional.ofNullable(values.getFirst());
    }

    /**
     * The delay a {@code Retry-After} header asks for, when the vendor sent one in its delta-seconds form. A vendor
     * that says how long its rate limit lasts is obeyed in preference to the client's own backoff schedule (bounded
     * by the policy's maximum), because guessing shorter is what exhausts a quota. The HTTP-date form and any
     * unparseable or negative value are ignored rather than guessed at.
     */
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

    /** This response with its body replaced - how the client interposes its byte cap without copying anything. */
    public FeedResponse over(InputStream replacement) {
        return new FeedResponse(status, headers, replacement);
    }

    @Override
    public void close() throws IOException {
        body.close();
    }
}
