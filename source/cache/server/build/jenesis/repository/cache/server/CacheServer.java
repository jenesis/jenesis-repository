package build.jenesis.repository.cache.server;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;


/**
 * The build-cache server's composition: wire protocol, tenancy and eviction live in {@link Cache} and
 * {@link CacheController}; persistence is a {@link build.jenesis.repository.cache.storage.CacheStorage} delegating into
 * a segment of the repository's store, configured by {@code JENREPO_STORE} ({@link CacheConfig}). Configuration loads
 * from {@code cache.properties} ({@code spring.config.name=cache}), so the credential store module's own
 * {@code application.properties} cannot shadow it.
 *
 * <p>Spring Security's servlet auto-configuration is excluded, since the cache authorizes its own requests and a
 * default login chain would lock the protocol and Actuator down; the combined deployment keeps its own chain.
 *
 * <p><b>Nothing ships this.</b> Running only the cache is a configuration of the image, which imports
 * {@code CacheNode}, so there is no {@code @jenesis.main}: {@link #start(int)} serves a test and
 * {@link #boot(String...)} the harness, through a launcher of its own.
 */
@SpringBootApplication(excludeName = {
        "org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration",
        "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration",
        "org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration",
        "org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration",
        "org.springframework.boot.security.autoconfigure.actuate.web.servlet.ManagementWebSecurityAutoConfiguration",
        "build.jenesis.repository.server.RepositoryAutoConfiguration",
        "build.jenesis.repository.server.RepositorySecurityAutoConfiguration"})
public class CacheServer {

    /**
     * The management surface of a cache running alone: the probe paths and nothing else. Without this chain
     * {@code /actuator/prometheus}, its counters labelled by tenant and project, would be matched by no chain and
     * answer anyone. There is no deployment-wide authorization here to gate it with, and no need: a deployment wanting
     * only the cache runs the ordinary node with every format off, where the scrape is gated. So a composition that is
     * not a deployment closes its management surface.
     *
     * <p>It is declared on the launcher, which {@code CacheNode} excludes from a composing scan, so it reaches only the
     * standalone composition; in the shared security config it would deny the merged node's actuator surface. The probe
     * paths stay open, since a kubelet carries no credential.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain cacheActuatorFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/actuator/**")
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health",
                                "/actuator/health/liveness",
                                "/actuator/health/readiness").permitAll()
                        .anyRequest().denyAll())
                .csrf(AbstractHttpConfigurer::disable);
        return http.build();
    }

    /** Runs the cache alone, for a process that must measure it without the rest of the product, where booting the
     *  bundle would measure the bundle's footprint. Not a {@code main}: the harness starts a node by module and main
     *  class, and a class with a {@code main} reads as a product. The entry point is a test-owned launcher, which makes
     *  the boot-time decisions a harness-booted node owes; this method only boots. */
    public static void boot(String... arguments) {
        new SpringApplicationBuilder(CacheServer.class)
                .properties("spring.config.name=cache")
                .run(arguments);
    }

    /** Boot the server on {@code port} ({@code 0} picks an ephemeral one) and return a handle exposing the bound port
     *  and closing the context, without leaking Spring types. */
    public static Running start(int port) {
        // The port rides as a run argument: a properties() default is the lowest-precedence layer, so with a higher one
        // set two suites asking for an ephemeral port would race for a fixed one.
        ConfigurableApplicationContext context = new SpringApplicationBuilder(CacheServer.class)
                .properties("spring.config.name=cache")
                .run("--server.port=" + port);
        int bound = Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
        return new Running(bound, context);
    }

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
