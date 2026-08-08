package build.jenesis.repository.feed;

import module java.base;
import module java.net.http;

/**
 * The one network operation a feed makes, isolated so that everything above it - request shaping, caps, backoff,
 * pagination, fail-mode policy, snapshot persistence - is exercised without a socket. A contract suite hands in a
 * transport answering from recorded responses, or one that throws on any call at all to prove a read path performs
 * no I/O (&sect;10); production hands in {@link #jdk(Duration)}.
 *
 * <p>A transport is a pure "send this, give me the answer" seam: it never retries, never follows a redirect into
 * another origin, never inspects the status. Those are {@link FeedClient}'s, so every feed gets them identically.
 */
@FunctionalInterface
public interface FeedTransport {

    /**
     * Send one request and answer with its response, whose body stream the caller closes.
     *
     * @param request the request to send.
     * @param timeout the per-request budget {@link FeedClient} allows this call - already the smaller of the policy's
     *                request timeout and what is left of the whole fetch's deadline.
     * @throws IOException when the request cannot be sent or the response headers cannot be read.
     */
    FeedResponse send(FeedRequest request, Duration timeout) throws IOException;

    /**
     * A transport over the JDK HTTP client the caller owns and closes (clause 10) - the form a deployment uses when
     * it pools one client across several feeds, or must configure a proxy, an SSL context or an executor.
     *
     * <p>Redirects are deliberately <em>not</em> followed: a redirect is a vendor-controlled hop that would carry the
     * request's credential header to whatever host the answer names, which is exactly the cross-origin exfiltration
     * {@link FeedClient} refuses on the pagination path. A feed that legitimately moved answers a 3xx, which the
     * client reports as the named non-200 failure rather than chasing.
     */
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

    /**
     * A transport over a JDK HTTP client this method builds with {@code connectTimeout} and no redirect following -
     * the convenience form. The client lives for as long as the transport is referenced; a deployment that must
     * close it explicitly builds its own and uses {@link #jdk(HttpClient)}.
     *
     * <p>A bounded connect timeout is not optional: a black-holed feed host (a firewall dropping the SYN with no
     * RST) would otherwise park the refresh forever, and a refresh is single-flight.
     */
    static FeedTransport jdk(Duration connectTimeout) {
        return jdk(HttpClient.newBuilder()
                .connectTimeout(Objects.requireNonNull(connectTimeout, "connectTimeout"))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }
}
