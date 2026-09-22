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
 * The Jenesis build-cache server's composition: a Spring Boot configuration whose wire protocol, multi-tenant
 * auth and eviction live in {@link Cache} (HTTP-framework-independent) and {@link CacheController}.
 * Persistence is a {@link build.jenesis.repository.cache.storage.CacheStorage} that delegates into a
 * segment of the repository's own store, so it is configured by {@code JENREG_STORE} and that store's
 * keys rather than by a selection of its own; see {@link CacheConfig} for the environment configuration. Configuration is loaded from {@code cache.properties}
 * ({@code spring.config.name=cache}) rather than {@code application.properties}, so the credential
 * store's artifact-repository module - which ships its own {@code application.properties} - cannot
 * shadow it on the module path.
 *
 * <p>The credential-store module pulls Spring Security onto the module path (the key-usage tracker lives there).
 * The cache does its own per-request key authorization in {@link Cache}, so the servlet-security auto-configuration
 * is excluded to keep the wire protocol and Actuator open exactly as before, rather than have a default login chain
 * lock them down. The combined deployment keeps its own security chain and is unaffected.
 *
 * <p><b>Nothing ships this.</b> The cache stopped being an image of its own when the nodes became one - running
 * just the cache is a configuration of the image, which imports {@code CacheNode} - so the module declares no
 * {@code @jenesis.main} and no launcher is built from it. What is left is {@link #start(int)} for a test and
 * {@link #boot(String...)} for the harness, which reaches it through a launcher of its own rather than through a
 * {@code main} here.
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
     * The management surface of a cache running alone: the probe paths and nothing else.
     *
     * <p>This composition used to serve {@code /actuator/prometheus} to anyone, and its own suite asserted so -
     * with {@code jenreg_cache_requests_total} labelled by {@code tenant} and {@code project}, that hands every
     * tenant and project name to whoever can reach the port. It happened because the only chain here claims
     * {@code /cache/**} and this launcher excludes the repository's security auto-configuration by name, so
     * everything under {@code /actuator} was matched by no chain at all and passed with no security filters. A
     * request nothing matches is not a request nothing governs; it is a request governed by nothing.
     *
     * <p>The fix is not to authenticate it. There is no deployment-wide authorization on this node to authenticate
     * against - the repository's authorization manager arrives with beans this launcher deliberately excludes -
     * and there does not need to be, because <b>this is not a deployment</b>. There is no cache-only image: a
     * deployment that wants only the build cache runs the ordinary node with every format switched off, and there
     * the scrape is authorization-gated like every other {@code /actuator} path. So the honest posture for a
     * composition that is not a deployment is a closed management surface.
     *
     * <p><b>It lives on the launcher rather than in {@code CacheSecurityConfig}, and that is load-bearing.</b>
     * {@code CacheNode} excludes this class from the scan that pulls the cache into a composing launcher, so a
     * bean declared here reaches the standalone composition and only that one. Declared in the shared security
     * config instead, it reached the merged node too - and an ordered chain over a narrower space wins over the
     * repository's unmatched-scope chain, so it denied the actuator surface of the node an operator actually runs.
     * Measured, not reasoned: {@code ServerToggleE2ETest} failed with a {@code 403} on the merged node's scrape.
     *
     * <p>What stays open is what a container platform probes, for the reason it is open everywhere else: a kubelet
     * carries no credential, and a probe that needs one is a probe that fails the pod.
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

    /**
     * Runs the cache <em>alone</em>, for a process that must measure it without the rest of the product around it -
     * which today means the cache soak, where a 48 MiB entry against a 512 MiB bound makes buffering fatal rather
     * than merely visible. Booting the whole bundle into that bound would measure the bundle's footprint instead.
     *
     * <p><b>This is not a {@code main} and the distinction is the point.</b> The harness starts a node by naming a
     * module and a main class, which used to force the entry point to be a product class; a class with a
     * {@code main} reads as a product whatever its javadoc says, and the cache is a segment of the bundle, imported
     * as {@code CacheNode}. The entry point is a test-owned launcher now - the deliberate exception to
     * {@code ServerRuntime.isHarness} - and it is what makes the boot-time decisions a harness-booted node owes,
     * the licence report among them. Nothing here decides anything: this method boots and returns.
     */
    public static void boot(String... arguments) {
        new SpringApplicationBuilder(CacheServer.class)
                .properties("spring.config.name=cache")
                .run(arguments);
    }

    /**
     * Boot the server on the given port ({@code 0} picks an ephemeral one) and return a handle that
     * exposes the bound port and closes the context. Lets an embedder or test drive the real server,
     * controller and servlet filters over HTTP without leaking the Spring types into its module.
     */
    public static Running start(int port) {
        // The port rides as a run argument rather than a default property: a .properties() default is Spring's
        // lowest-precedence layer, so anything above it wins and two suites asking for an ephemeral port would race
        // for one fixed port. No file pins server.port any more - 8080 is Spring's own default - so the argument is
        // what makes 0 mean 0.
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
