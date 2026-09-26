package build.jenesis.repository.store.gcs;

import module java.base;
import module java.net.http;
import build.jenesis.repository.net.http.ScreenedHttpClient;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.LowLevelHttpRequest;
import com.google.api.client.http.LowLevelHttpResponse;
import com.google.api.client.util.StreamingContent;

/**
 * The storage client's HTTP over the product's own client, where Google's {@code NetHttpTransport} - a URL
 * connection - used to be: the store's requests leave the way every other outbound call does.
 *
 * <p>Two headers of the client library's are not sent. Its {@code User-Agent} and its {@code x-goog-api-client}
 * name the Java version, the library's own versions and the operating system; the request carries the product's
 * {@code User-Agent} instead, and neither header is needed by the JSON API. The headers the product's client sets
 * itself - {@code Host}, {@code Content-Length}, {@code Connection}, {@code Expect}, {@code Upgrade} - are carried as
 * what they mean: the length is the content's declared length.
 *
 * <p>An upload streams: the library writes its content into a pipe the request reads from, on a thread of its own,
 * so an artifact of any size is never held in memory. A response body streams back the same way, undecoded, and the
 * library decodes it by the {@code Content-Encoding} this reports.
 */
final class GcsTransport extends HttpTransport {

    /** Headers not copied from the library's request: set by the product's client, or naming the runtime. */
    private static final Set<String> NOT_SENT = Set.of("host", "content-length", "connection", "expect", "upgrade",
            "user-agent", "x-goog-api-client");

    private final HttpClient client = ScreenedHttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

    @Override
    protected LowLevelHttpRequest buildRequest(String method, String url) {
        return new Request(method, URI.create(url));
    }

    @Override
    public boolean supportsMethod(String method) {
        return true;
    }

    private final class Request extends LowLevelHttpRequest {

        private final String method;
        private final URI url;
        private final List<String[]> headers = new ArrayList<>();
        private Duration readTimeout = Duration.ofMinutes(1);

        Request(String method, URI url) {
            this.method = method;
            this.url = url;
        }

        @Override
        public void addHeader(String name, String value) {
            headers.add(new String[] {name, value});
        }

        @Override
        public void setTimeout(int connectTimeout, int readTimeout) {
            if (readTimeout > 0) {
                this.readTimeout = Duration.ofMillis(readTimeout);
            }
        }

        @Override
        public LowLevelHttpResponse execute() throws IOException {
            HttpRequest.Builder builder = HttpRequest.newBuilder(url).timeout(readTimeout);
            for (String[] header : headers) {
                if (!NOT_SENT.contains(header[0].toLowerCase(Locale.ROOT))) {
                    builder.header(header[0], header[1]);
                }
            }
            if (getContentType() != null) {
                builder.setHeader("Content-Type", getContentType());
            }
            if (getContentEncoding() != null) {
                builder.setHeader("Content-Encoding", getContentEncoding());
            }
            builder.method(method, body(getStreamingContent(), getContentLength()));
            HttpResponse<InputStream> response;
            try {
                response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("the storage request was interrupted");
            }
            return new Response(response);
        }

        /** The library's content as a body the request reads while the library writes it, on a thread of its own. */
        private HttpRequest.BodyPublisher body(StreamingContent content, long length) {
            if (content == null || length == 0) {
                return HttpRequest.BodyPublishers.noBody();
            }
            HttpRequest.BodyPublisher streamed = HttpRequest.BodyPublishers.ofInputStream(() -> {
                PipedInputStream in = new PipedInputStream(64 * 1024);
                PipedOutputStream out;
                try {
                    out = new PipedOutputStream(in);
                } catch (IOException impossible) {
                    throw new UncheckedIOException(impossible);
                }
                Thread.ofVirtual().name("jenesis-gcs-upload").start(() -> {
                    try (out) {
                        content.writeTo(out);
                    } catch (IOException _) {
                        // the reading side sees the pipe break and fails the request with it
                    }
                });
                return in;
            });
            return length > 0 ? HttpRequest.BodyPublishers.fromPublisher(streamed, length) : streamed;
        }
    }

    private static final class Response extends LowLevelHttpResponse {

        private final HttpResponse<InputStream> response;
        private final List<Map.Entry<String, String>> headers = new ArrayList<>();

        Response(HttpResponse<InputStream> response) {
            this.response = response;
            response.headers().map().forEach((name, values) ->
                    values.forEach(value -> headers.add(Map.entry(name, value))));
        }

        @Override
        public InputStream getContent() {
            return response.body();
        }

        @Override
        public String getContentEncoding() {
            return response.headers().firstValue("Content-Encoding").orElse(null);
        }

        @Override
        public long getContentLength() {
            return response.headers().firstValueAsLong("Content-Length").orElse(-1);
        }

        @Override
        public String getContentType() {
            return response.headers().firstValue("Content-Type").orElse(null);
        }

        @Override
        public String getStatusLine() {
            return "HTTP/1.1 " + response.statusCode();
        }

        @Override
        public int getStatusCode() {
            return response.statusCode();
        }

        @Override
        public String getReasonPhrase() {
            return null;
        }

        @Override
        public int getHeaderCount() {
            return headers.size();
        }

        @Override
        public String getHeaderName(int index) {
            return headers.get(index).getKey();
        }

        @Override
        public String getHeaderValue(int index) {
            return headers.get(index).getValue();
        }

        @Override
        public void disconnect() throws IOException {
            response.body().close();
        }
    }
}
