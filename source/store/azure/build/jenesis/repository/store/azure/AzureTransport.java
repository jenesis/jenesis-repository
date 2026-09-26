package build.jenesis.repository.store.azure;

import module java.base;
import build.jenesis.repository.net.http.ScreenedHttpClient;
import com.azure.core.http.HttpClient;
import com.azure.core.http.HttpHeader;
import com.azure.core.http.HttpHeaders;
import com.azure.core.http.HttpRequest;
import com.azure.core.http.HttpResponse;
import com.azure.core.util.BinaryData;
import com.azure.core.util.Context;
import com.azure.core.util.FluxUtil;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * The blob client's HTTP over the product's own client, where the SDK's Netty client used to be: the store's
 * requests leave the way every other outbound call does, and carry the product's {@code User-Agent} rather than the
 * SDK's, which names the Java version and the operating system. Shared-key signing covers neither the
 * {@code User-Agent} nor anything this changes, so every signature stands as the SDK computed it.
 *
 * <p>The store uses the SDK's synchronous clients, so {@link #sendSync} is the path every request takes, and it
 * blocks the calling thread as those clients expect; {@link #send} runs the same exchange on the SDK's
 * bounded-elastic scheduler for any caller that asks asynchronously. A request body streams from the SDK's
 * {@link BinaryData} with its length - the body's own, or the {@code Content-Length} the SDK declared for a body
 * that knows none, since the service refuses a chunked one. It is public for the store's tests, whose own blob
 * clients speak through it too. A response body streams back undecoded.
 */
public final class AzureTransport implements HttpClient {

    /** Headers the product's client sets itself, from the request's URI, body and flags, or that name the runtime. */
    private static final Set<String> NOT_SENT = Set.of("host", "content-length", "connection", "expect", "upgrade",
            "user-agent");

    /** How long a request waits for the response's headers - a response already streaming is not cut short. */
    private static final Duration RESPONSE_TIMEOUT = Duration.ofMinutes(1);

    private final java.net.http.HttpClient client = ScreenedHttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override
    public Mono<HttpResponse> send(HttpRequest request) {
        return Mono.fromCallable(() -> exchange(request)).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public HttpResponse sendSync(HttpRequest request, Context context) {
        try {
            return exchange(request);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private HttpResponse exchange(HttpRequest request) throws IOException {
        java.net.http.HttpRequest.Builder builder;
        try {
            builder = java.net.http.HttpRequest.newBuilder(request.getUrl().toURI()).timeout(RESPONSE_TIMEOUT);
        } catch (URISyntaxException malformed) {
            throw new IOException("not a request URI: " + request.getUrl(), malformed);
        }
        boolean expect = false;
        Long declared = null;
        for (HttpHeader header : request.getHeaders()) {
            String name = header.getName().toLowerCase(Locale.ROOT);
            if (name.equals("content-length") && !header.getValuesList().isEmpty()) {
                declared = Long.parseLong(header.getValuesList().getFirst().strip());
            } else if (name.equals("expect")) {
                expect = header.getValuesList().stream().anyMatch("100-continue"::equalsIgnoreCase);
            } else if (!NOT_SENT.contains(name)) {
                header.getValuesList().forEach(value -> builder.header(header.getName(), value));
            }
        }
        builder.method(request.getHttpMethod().name(), body(request.getBodyAsBinaryData(), declared))
                .expectContinue(expect);
        java.net.http.HttpResponse<InputStream> response;
        try {
            response = client.send(builder.build(), java.net.http.HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("the storage request was interrupted");
        }
        return new Received(request, response);
    }

    /** The request's body with its length: the one the body knows, else the {@code Content-Length} the SDK set - a
     *  staged block streams from a publisher that knows none, and the service refuses a body sent without one. */
    private static java.net.http.HttpRequest.BodyPublisher body(BinaryData body, Long declared) {
        Long length = body == null ? Long.valueOf(0) : body.getLength() != null ? body.getLength() : declared;
        if (body == null || length != null && length == 0) {
            return java.net.http.HttpRequest.BodyPublishers.noBody();
        }
        java.net.http.HttpRequest.BodyPublisher streamed = java.net.http.HttpRequest.BodyPublishers.ofInputStream(
                body::toStream);
        return length != null ? java.net.http.HttpRequest.BodyPublishers.fromPublisher(streamed, length) : streamed;
    }

    /** The SDK's view of a response whose body is still streaming. */
    private static final class Received extends HttpResponse {

        private final java.net.http.HttpResponse<InputStream> response;
        private final HttpHeaders headers = new HttpHeaders();

        Received(HttpRequest request, java.net.http.HttpResponse<InputStream> response) {
            super(request);
            this.response = response;
            response.headers().map().forEach((name, values) -> headers.set(name, values));
        }

        @Override
        public int getStatusCode() {
            return response.statusCode();
        }

        @Override
        public String getHeaderValue(String name) {
            return headers.getValue(name);
        }

        @Override
        public HttpHeaders getHeaders() {
            return headers;
        }

        @Override
        public Flux<ByteBuffer> getBody() {
            return FluxUtil.toFluxByteBuffer(response.body());
        }

        @Override
        public Mono<byte[]> getBodyAsByteArray() {
            return Mono.fromCallable(() -> {
                try (InputStream in = response.body()) {
                    return in.readAllBytes();
                }
            });
        }

        @Override
        public Mono<String> getBodyAsString() {
            return getBodyAsString(StandardCharsets.UTF_8);
        }

        @Override
        public Mono<String> getBodyAsString(Charset charset) {
            return getBodyAsByteArray().map(bytes -> new String(bytes, charset));
        }

        @Override
        public InputStream getBodyAsInputStreamSync() {
            return response.body();
        }

        @Override
        public void close() {
            try {
                response.body().close();
            } catch (IOException _) {
                // a closed body is discarded either way
            }
        }
    }
}
