package build.jenesis.repository.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.server.CapturingExchange;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A write or a read made in process answers the request URI of the client request it stands in for, so what reads the
 * addressed repository off the request - the deploy observation's repository tag - names the repository rather than
 * the first segment of the format's path.
 */
class CapturingExchangeTest {

    @Test
    void an_in_process_publish_and_read_answer_the_request_uri_a_client_would_have_sent() {
        CapturingExchange put = new CapturingExchange("acme", "libs", "/maven/org/acme/lib/1.0/lib-1.0.jar",
                InputStream.nullInputStream());
        assertThat(put.requestUri()).isEqualTo("/repository/acme/libs/maven/org/acme/lib/1.0/lib-1.0.jar");
        assertThat(put.path()).isEqualTo("/maven/org/acme/lib/1.0/lib-1.0.jar");
        assertThat(put.method()).isEqualTo("PUT");

        CapturingExchange get = CapturingExchange.read("acme", "npm-proxy", "/npm/lodash/-/lodash-4.17.11.tgz");
        assertThat(get.requestUri()).isEqualTo("/repository/acme/npm-proxy/npm/lodash/-/lodash-4.17.11.tgz");
        assertThat(get.method()).isEqualTo("GET");
    }
}
