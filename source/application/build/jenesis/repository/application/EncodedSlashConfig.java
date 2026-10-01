package build.jenesis.repository.application;

import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.eclipse.jetty.http.UriCompliance;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.springframework.boot.jetty.servlet.JettyServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jetty admits an encoded slash ({@code %2F}) in a request path, which npm addresses a scoped package with -
 * {@code PUT} and {@code GET /@scope%2Fname}. Both settings are needed, each alone still answering {@code 400}: the
 * connector's URI compliance allows the ambiguous separator, and the servlet handler decodes such a URI. Spring
 * Security's firewall admits the same character; authorization reads the decoded path and refuses one that decodes
 * into an empty or dot segment.
 */
@Configuration(proxyBeanMethods = false)
class EncodedSlashConfig {

    @Bean
    WebServerFactoryCustomizer<JettyServletWebServerFactory> encodedSlash() {
        return factory -> factory.addServerCustomizers(server -> {
            for (Connector connector : server.getConnectors()) {
                HttpConnectionFactory http = connector.getConnectionFactory(HttpConnectionFactory.class);
                if (http != null) {
                    HttpConfiguration configuration = http.getHttpConfiguration();
                    configuration.setUriCompliance(configuration.getUriCompliance()
                            .with("encoded-slash", UriCompliance.Violation.AMBIGUOUS_PATH_SEPARATOR));
                }
            }
            ServletContextHandler context = server.getDescendant(ServletContextHandler.class);
            if (context != null) {
                context.getServletHandler().setDecodeAmbiguousURIs(true);
            }
        });
    }
}
