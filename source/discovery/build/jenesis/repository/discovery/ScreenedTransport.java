package build.jenesis.repository.discovery;

import module java.base;
import module java.net.http;
import build.jenesis.repository.net.http.BoundedBody;
import build.jenesis.repository.net.http.ScreenedHttpClient;

/**
 * The network a {@link RepositoryDiscovery} reads through: the product's screened HTTP client, a file read with its
 * redirects followed within {@code http} and {@code https} and a latest link's {@code HEAD} with none followed, each
 * bounded by {@link #TIMEOUT}. A host that does not exist, refuses or does not answer is no answer; a certificate that
 * does not verify is a {@link DiscoveryException}, never an absent file.
 */
public final class ScreenedTransport implements RepositoryDiscovery.Transport {

    /** How long a request waits for its answer. */
    public static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient following = ScreenedHttpClient.newBuilder().connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private final HttpClient staying = ScreenedHttpClient.newBuilder().connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER).build();

    @Override
    public Optional<String> read(URI url, int most) {
        HttpRequest request = HttpRequest.newBuilder(url).timeout(TIMEOUT).GET().build();
        try {
            HttpResponse<String> response = following.send(request, BoundedBody.ofString(url, most));
            return response.statusCode() == 200 ? Optional.of(response.body()) : Optional.empty();
        } catch (BoundedBody.TooLarge large) {
            throw new DiscoveryException(url + " is larger than the " + most + " bytes a discovery read takes");
        } catch (SSLException untrusted) {
            throw new DiscoveryException("The certificate of " + url.getHost() + " does not verify: "
                    + untrusted.getMessage(), untrusted);
        } catch (IOException unreachable) {
            return Optional.empty();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    @Override
    public Optional<RepositoryDiscovery.Head> head(URI url) {
        HttpRequest request = HttpRequest.newBuilder(url).timeout(TIMEOUT)
                .method("HEAD", HttpRequest.BodyPublishers.noBody()).build();
        try {
            HttpResponse<Void> response = staying.send(request, HttpResponse.BodyHandlers.discarding());
            Map<String, String> headers = new LinkedHashMap<>();
            response.headers().map().forEach((name, values) -> {
                if (!values.isEmpty()) {
                    headers.put(name, values.getFirst());
                }
            });
            return Optional.of(new RepositoryDiscovery.Head(response.statusCode(), headers));
        } catch (SSLException untrusted) {
            throw new DiscoveryException("The certificate of " + url.getHost() + " does not verify: "
                    + untrusted.getMessage(), untrusted);
        } catch (IOException unreachable) {
            return Optional.empty();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }
}
