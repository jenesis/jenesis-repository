package build.jenesis.repository.feed;

import module java.base;

import build.jenesis.repository.net.http.ScreenedHttpClient;
import module java.net.http;

/**
 * The one network operation a feed makes, isolated so everything above it is exercised without a socket: a test hands
 * in recorded responses, or a transport that throws to prove a read path performs no I/O; production uses
 * {@link #jdk(Duration)}. It never retries, follows a redirect or inspects the status; those are {@link FeedClient}'s.
 */
@FunctionalInterface
public interface FeedTransport {

    /**
     * Send one request and answer with its response, whose body the caller closes.
     *
     * @param request the request to send
     * @param timeout the per-request budget: the smaller of the policy's request timeout and what is left of the
     *     deadline
     * @throws IOException when the request cannot be sent or the response headers cannot be read
     */
    FeedResponse send(FeedRequest request, Duration timeout) throws IOException;

    /** What a test's stand-in for an endpoint answers a request with: the body of a {@code 200}. */
    @FunctionalInterface
    interface Exchange {
        String answer(FeedRequest request) throws IOException;
    }

    /** A transport answering every request through {@code exchange}, as a {@code 200} the client bounds and pages as
     *  it does a live response. */
    static FeedTransport exchanging(Exchange exchange) {
        Objects.requireNonNull(exchange, "exchange");
        return (request, timeout) -> FeedResponse.of(200, exchange.answer(request));
    }

    /** A transport over a JDK HTTP client the caller owns and closes (clause 10), for a deployment pooling one client
     *  or configuring a proxy, SSL context or executor. Redirects are not followed: a redirect would carry the
     *  credential to a host the vendor names, so a 3xx is reported as the named non-200 failure. */
    static FeedTransport jdk(HttpClient client) {
        Objects.requireNonNull(client, "client");
        return (request, timeout) -> {
            HttpRequest.Builder built = HttpRequest.newBuilder(request.uri()).timeout(timeout);
            request.headers().forEach(built::header);
            built.method(request.method(), request.body() == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(request.body(), StandardCharsets.UTF_8));
            try {
                HttpResponse<InputStream> response = client.send(built.build(),
                        HttpResponse.BodyHandlers.ofInputStream());
                return new FeedResponse(response.statusCode(), response.headers().map(), response.body());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Interrupted while requesting " + request.uri());
            }
        };
    }

    /** A transport over a JDK HTTP client built here with {@code connectTimeout} and no redirects; a deployment that
     *  must close it builds its own ({@link #jdk(HttpClient)}). The connect timeout is mandatory, since a host dropping
     *  the SYN would otherwise park the single-flight refresh forever. */
    static FeedTransport jdk(Duration connectTimeout) {
        return jdk(ScreenedHttpClient.newBuilder()
                .connectTimeout(Objects.requireNonNull(connectTimeout, "connectTimeout"))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }
}
