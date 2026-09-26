package build.jenesis.repository.net.http.aws;

import module java.base;
import module java.net.http;
import build.jenesis.repository.net.http.ScreenedHttpClient;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.http.ExecutableHttpRequest;
import software.amazon.awssdk.http.HttpExecuteRequest;
import software.amazon.awssdk.http.HttpExecuteResponse;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.SdkHttpResponse;

/**
 * An AWS SDK client's HTTP over the product's own client, where the SDK's {@code UrlConnectionHttpClient} used to be:
 * the S3 store's requests and the upstream credential exchange leave the way every other outbound call does, and
 * carry the product's {@code User-Agent} rather than the SDK's, which names the JVM, its version and the operating
 * system. Signature Version 4 does not sign the {@code User-Agent}, so replacing it leaves every signature as the SDK
 * computed it.
 *
 * <p>The headers the client itself owns - {@code Host}, {@code Content-Length}, {@code Connection}, {@code Expect}
 * and {@code Upgrade} - are carried as what they mean rather than copied: the length becomes the body's declared
 * length, and {@code Expect: 100-continue} the request's own flag. A body streams from the SDK's provider; a
 * response body streams to the SDK, and aborting it closes it.
 */
public final class AwsHttpClient implements SdkHttpClient {

    /** Headers the product's client sets itself, from the request's URI, body and flags. */
    private static final Set<String> CARRIED = Set.of("host", "content-length", "connection", "expect", "upgrade",
            "user-agent");

    /** How long a request waits for the response's headers - a response already streaming is not cut short. */
    private static final Duration RESPONSE_TIMEOUT = Duration.ofMinutes(1);

    private final HttpClient client = ScreenedHttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Override
    public ExecutableHttpRequest prepareRequest(HttpExecuteRequest request) {
        return new ExecutableHttpRequest() {

            private volatile InputStream body;

            @Override
            public HttpExecuteResponse call() throws IOException {
                SdkHttpRequest sdk = request.httpRequest();
                HttpRequest.Builder builder = HttpRequest.newBuilder(sdk.getUri()).timeout(RESPONSE_TIMEOUT);
                long length = -1;
                boolean expect = false;
                for (Map.Entry<String, List<String>> header : sdk.headers().entrySet()) {
                    String name = header.getKey().toLowerCase(Locale.ROOT);
                    if (name.equals("content-length") && !header.getValue().isEmpty()) {
                        length = Long.parseLong(header.getValue().getFirst().strip());
                    } else if (name.equals("expect")) {
                        expect = header.getValue().stream().anyMatch("100-continue"::equalsIgnoreCase);
                    } else if (!CARRIED.contains(name)) {
                        header.getValue().forEach(value -> builder.header(header.getKey(), value));
                    }
                }
                long declared = length;
                HttpRequest.BodyPublisher publisher = request.contentStreamProvider()
                        .filter(_ -> declared != 0)
                        .map(provider -> {
                            HttpRequest.BodyPublisher streamed = HttpRequest.BodyPublishers.ofInputStream(
                                    provider::newStream);
                            return declared > 0 ? HttpRequest.BodyPublishers.fromPublisher(streamed, declared)
                                    : streamed;
                        })
                        .orElse(HttpRequest.BodyPublishers.noBody());
                builder.method(sdk.method().name(), publisher).expectContinue(expect);
                HttpResponse<InputStream> response;
                try {
                    response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("the AWS request was interrupted");
                }
                body = response.body();
                return HttpExecuteResponse.builder()
                        .response(SdkHttpResponse.builder()
                                .statusCode(response.statusCode())
                                .headers(response.headers().map())
                                .build())
                        .responseBody(AbortableInputStream.create(body, this::abort))
                        .build();
            }

            @Override
            public void abort() {
                InputStream open = body;
                if (open != null) {
                    try {
                        open.close();
                    } catch (IOException _) {
                        // an aborted body is discarded either way
                    }
                }
            }
        };
    }

    @Override
    public String clientName() {
        return "Jenesis";
    }

    @Override
    public void close() {
        // The product's client is shared and lives as long as the JVM.
    }
}
