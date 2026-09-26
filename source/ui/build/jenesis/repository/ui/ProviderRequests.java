package build.jenesis.repository.ui;

import module java.base;
import build.jenesis.repository.net.http.ScreenedHttpClient;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

/**
 * The HTTP a sign-in makes to its identity provider - the token exchange, the user-info read and the fetch of the
 * key set an id token is checked against - over the product's own client. Spring Security makes each of these with a
 * client of its own choosing when nobody names one, and each of those announces the runtime it runs on
 * ({@code Java/<version>} from a URL connection, {@code Jetty/<version>} from Jetty's client); these carry the
 * product's {@code User-Agent} and nothing else. Each is configured exactly as Spring Security configures its own
 * default, bar the request factory.
 */
final class ProviderRequests {

    private ProviderRequests() {
    }

    /** Requests through the product's client, answered within half a minute. */
    static ClientHttpRequestFactory factory() {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                ScreenedHttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(Duration.ofSeconds(30));
        return factory;
    }

    /** What the user-info read and the key-set fetch go through: an OAuth2 error answer becomes the exception. */
    static RestTemplate rest() {
        RestTemplate rest = new RestTemplate(factory());
        rest.setErrorHandler(new OAuth2ErrorResponseErrorHandler());
        return rest;
    }

    /** What the token exchange goes through: a form out, a token response back. */
    static RestClient tokens() {
        return RestClient.builder()
                .requestFactory(factory())
                .configureMessageConverters(converters -> converters.disableDefaults()
                        .addCustomConverter(new FormHttpMessageConverter())
                        .addCustomConverter(new OAuth2AccessTokenResponseHttpMessageConverter()))
                .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
                .build();
    }
}
