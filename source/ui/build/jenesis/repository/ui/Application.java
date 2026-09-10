package build.jenesis.repository.ui;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The Spring Boot composition for the repository web console. It replaces the former hand-wired JDK-httpserver skeleton
 * with a mainstream Spring stack (Spring Boot on embedded Jetty, Thymeleaf views, Spring
 * Security with OAuth2/OIDC login), so a downstream distribution extends this shell rather than forking it. The console is
 * an open shell: sections are contributed as {@link ConsoleCard} plugins discovered with {@code ServiceLoader} and bridged
 * into Spring by {@link UiConfig}, and login mechanisms are contributed as {@link LoginContributor} beans, so neither
 * requires a fork of the console. The console is store-agnostic - it reads whatever {@code ArtifactStore} backend is on
 * the module path (filesystem, S3, Azure), selected by {@code jenreg.ui.store} - so a deployment or a test supplies
 * the backend, the shell names none.
 *
 * <p><b>It is not a launcher.</b> The console ships inside the all-in-one bundle rather than as a node of its own,
 * so what is left here is {@link #start(int)} - the seam an embedder or a test boots the real console through - and
 * the composition the bundle imports.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class Application {

    /**
     * Boot the console on the given port ({@code 0} picks an ephemeral one) and return a handle exposing the bound port
     * and closing the context, so an embedder or a test can drive the real console over HTTP without the Spring types
     * leaking into its module. A backend {@code ArtifactStore} provider must be on the module path.
     */
    public static Running start(int port) {
        ConfigurableApplicationContext context = new SpringApplicationBuilder(Application.class)
                .run("--server.port=" + port);
        int bound = Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
        return new Running(bound, context);
    }

    /**
     * A handle on a started console: its actually bound port (so a caller can start on an ephemeral port 0 and discover
     * it) and an orderly shutdown. Keeps the Spring {@link ConfigurableApplicationContext} private so a caller need not
     * require any Spring module to drive the console over HTTP.
     */
    public static final class Running implements AutoCloseable {

        private final int port;
        private final ConfigurableApplicationContext context;

        private Running(int port, ConfigurableApplicationContext context) {
            this.port = port;
            this.context = context;
        }

        public int port() {
            return port;
        }

        @Override
        public void close() {
            context.close();
        }
    }
}
