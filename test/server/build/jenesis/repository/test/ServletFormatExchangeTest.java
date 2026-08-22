package build.jenesis.repository.test;

import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.server.ServletFormatExchange;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import module org.junit.jupiter.api;

import module java.base;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The servlet exchange answers a format's questions about the request from the request: the scheme it arrived on
 * among them, so an index a format generates sends a client back over TLS when that is what the server terminated -
 * never a cleartext URL for a deployment that serves https.
 */
class ServletFormatExchangeTest {

    @Test
    void the_scheme_is_the_one_the_request_arrived_on() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getScheme()).thenReturn("https");
        FormatExchange exchange = new ServletFormatExchange(request, mock(HttpServletResponse.class), "/npm/x");

        assertThat(exchange.scheme()).isEqualTo("https");
    }

    @Test
    void an_exchange_with_no_connection_defaults_to_http() {
        FormatExchange headless = new FormatExchange() {
            @Override
            public String method() {
                return "GET";
            }

            @Override
            public String path() {
                return "/npm/x";
            }

            @Override
            public String queryParameter(String name) {
                return null;
            }

            @Override
            public String requestHeader(String name) {
                return null;
            }

            @Override
            public InputStream requestStream() {
                return InputStream.nullInputStream();
            }

            @Override
            public void setResponseHeader(String name, String value) {
            }

            @Override
            public OutputStream respond(int status, long contentLength) {
                return OutputStream.nullOutputStream();
            }
        };

        assertThat(headless.scheme()).isEqualTo("http");
    }
}
