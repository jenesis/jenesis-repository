package build.jenesis.repository.application;

import module java.base;
import build.jenesis.repository.server.kernel.MaintenanceScheduler;
import build.jenesis.repository.server.kernel.SettingsEnvironmentLayer;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The repository server's composition: a Spring Boot configuration whose dual-layout serving, compliance gate,
 * staging and cleanup live in the framework-independent {@code build.jenesis.repository.*} modules and are wired by
 * {@link RepositoryConfig} and exposed by the focused core controllers (
 * {@code ImportController}, {@code BrowseController}, {@code DependentsController}, {@code FormatIconController},
 * {@code DeploymentInfoController}) plus the discovered per-feature {@code web} adapters. Artifact writes ride the free
 * {@code RepositoryController} serving bean, with the deploy concerns (tenant binding, release immutability,
 * quarantine-dispatch record, deploy observation) plugged in through the
 * {@link build.jenesis.repository.server.kernel.PublishTenantFilter} and the
 * {@link build.jenesis.repository.gateway.DeployEdgeHooks} {@code EdgeHooks} bean (retired the forked
 * {@code DeployController}).
 * The storage backend is selected by
 * {@code jenreg.store} through {@code ArtifactStoreProvider} (ServiceLoader, filesystem fallback).
 *
 * <p>The {@code RepositorySecurityAutoConfiguration} is no longer excluded: it now runs and this distribution
 * <em>composes over</em> its chain rather than forking it. A contributed authorization manager (a
 * {@code @ConditionalOnMissingBean} the chain picks up), the open routes and the request-body cap ride the free
 * security chain through the {@code SecurityChainCustomizer} seam (see {@link RepositorySecurityConfig}); the rate limiter
 * and filter reuse the classes, re-declared there with the pin-aware live ceiling.
 *
 * <p>No free auto-configuration is excluded. The {@code RepositoryAutoConfiguration} now runs alongside
 * the security one: every one of its beans is {@code @ConditionalOnMissingBean}, so each backs off behind this
 * module's richer replacement (the serving controller is registered here under the bean name
 * {@code repositoryController} so the one backs off too). The former exclusion existed only because both
 * distributions bound the same {@code jenreg.repository} prefix with an <em>incompatible schema</em>: the free
 * {@code proxy} is a {@code Map<String,String>} of format upstreams and its {@code repository} a {@code String}, where
 * this distribution once bound a boolean {@code proxy} switch and a {@code repository} map. Those two collisions are
 * gone - this distribution's switch is {@code jenreg.proxy-enabled} and its definitions
 * {@code jenreg.repositories.<name>}, so it <em>extends</em> the schema over one prefix rather than
 * redefining it, and {@link LegacyPropertyProbe} fails a boot fast that still carries an old key.
 *
 * <p><b>Nothing ships this.</b> It was an image of its own once; the shipped artifact is the bundle,
 * which imports this composition. The module declares no {@code @jenesis.main}, so no launcher is built from it -
 * what is left is {@link #start(int)} for an embedder or a test, and a {@link #main} the containerised harness
 * starts when a suite needs this node without the console and the cache around it.
 */
@SpringBootApplication
public class RepositoryApplication {

    /**
     * An entry point for a process that must run <em>this node alone</em>, which today means the containerised e2e
     * harness: it boots the product in a container by naming a module and a main class, and its own modules are
     * deliberately excluded from the module path it mounts ({@code ServerRuntime.isHarness}), so that entry point
     * has to be a product one - the format suites want this node without the console and the cache around it.
     * Nothing ships from here: the module declares no {@code @jenesis.main}, so no launcher is built from it.
     *
     * <p><b>It is a debt, not a design.</b> A class with a {@code main} reads as a product whatever its javadoc
     * says. The way out is to let the harness mount one test-owned launcher module - a deliberate exception to
     * that filter rather than a loosened rule - and delete this.
     *
     * <p>What may live here is a report every entry point makes, not a decision a deployment depends on. The
     * licence report qualifies and is made here; a defaults map did not, and was removed.
     *
     * <p><b>Nothing a deployment depends on may be done in this method.</b> That is not a style note. A defaults
     * map lived here and floored the public advisory feeds on; no shipped artifact ever ran it, so the feeds were
     * off in the image while the settings screen said they were on. A boot-time decision belongs in the artifact
     * that ships.
     */
    public static void main(String[] args) {
        // The licence report fires from the licence feature module's configuration, which every composition that
        // imports the kernel's feature modules constructs - so this node reports without naming the licence, and
        // the bundle still reports from its own main; the guard keeps it to one line per JVM either way.
        new SpringApplicationBuilder(RepositoryApplication.class)
                .listeners(new SettingsEnvironmentLayer(), new LegacyPropertyProbe())
                .properties("spring.config.name=repository")
                .run(args);
    }

    /**
     * Boot the server on the given port ({@code 0} picks an ephemeral one) and return a handle exposing the bound
     * port and closing the context, so an embedder or test can drive the real server over HTTP without the Spring
     * types leaking into its module.
     */
    public static Running start(int port) {
        // The port rides as a run argument rather than a default property: a .properties() default is Spring's
        // lowest-precedence layer, so anything above it wins and two suites asking for an ephemeral port would race
        // for one fixed port. No file pins server.port any more - 8080 is Spring's own default - so the argument is
        // what makes 0 mean 0.
        ConfigurableApplicationContext context = new SpringApplicationBuilder(RepositoryApplication.class)
                .listeners(new SettingsEnvironmentLayer(), new LegacyPropertyProbe())
                .properties("spring.config.name=repository")
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

        /**
         * The {@code (method, path-pattern)} routes Spring actually mapped, each rendered as {@code "METHOD /pattern"}
         * (a method-agnostic mapping as {@code "* /pattern"}), enumerated from the {@link RequestMappingHandlerMapping}.
         * The endpoint auth-matrix guard reads this to fail the build if a controller ships a route with no auth-matrix
         * entry or explicit public allowlist entry - the completeness "teeth" - without leaking the Spring context out
         * of the handle.
         */
        public Set<String> routes() {
            // Select the app controllers' mapping by name: actuator contributes a second RequestMappingHandlerMapping
            // (controllerEndpointHandlerMapping), so a by-type lookup is ambiguous.
            RequestMappingHandlerMapping mapping = context.getBean(
                    "requestMappingHandlerMapping", RequestMappingHandlerMapping.class);
            Set<String> routes = new TreeSet<>();
            mapping.getHandlerMethods().forEach((info, handler) -> {
                var patterns = info.getPathPatternsCondition();
                if (patterns == null) {
                    return;
                }
                Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
                for (String pattern : patterns.getPatternValues()) {
                    if (methods.isEmpty()) {
                        routes.add("* " + pattern);
                    } else {
                        for (RequestMethod method : methods) {
                            routes.add(method.name() + " " + pattern);
                        }
                    }
                }
            });
            return routes;
        }

        /**
         * Run every enabled maintenance task once, synchronously - the deterministic sweep an embedder or a test
         * triggers instead of waiting for the schedule (delegating to {@link MaintenanceScheduler#runNow(Instant)}),
         * still without leaking the Spring context out of the handle. An exclusive pass still takes (and promptly
         * releases) its single-writer {@link build.jenesis.repository.store.Lease} exactly as the scheduled loop
         * does, so a synchronous run coordinates with other nodes rather than double-sweeping. A no-op when this image
         * was built without the scheduler, so it degrades gracefully.
         */
        public void runMaintenance(Instant now) {
            context.getBeanProvider(MaintenanceScheduler.class).ifAvailable(scheduler -> scheduler.runNow(now));
        }

        @Override
        public void close() {
            context.close();
        }
    }
}
