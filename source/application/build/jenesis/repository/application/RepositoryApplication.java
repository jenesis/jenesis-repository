package build.jenesis.repository.application;

import module java.base;
import build.jenesis.repository.server.Launched;
import build.jenesis.repository.server.kernel.MaintenanceScheduler;
import build.jenesis.repository.store.ArtifactStore;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The repository server's composition: {@link RepositoryConfig} wires the framework-independent
 * {@code build.jenesis.repository.*} modules, and the discovered feature modules contribute their controllers.
 * Writes ride the free {@code RepositoryController} serving bean, with the deploy concerns plugged in through the
 * {@link build.jenesis.repository.server.kernel.PublishTenantFilter} and the
 * {@link build.jenesis.repository.gateway.DeployEdgeHooks} bean. The storage backend is selected by
 * {@code jenrepo.store}.
 *
 * <p>No free auto-configuration is excluded. The security chain runs and this composition contributes to it through
 * {@code SecurityChainCustomizer} ({@link RepositorySecurityConfig}); every {@code RepositoryAutoConfiguration} bean is
 * {@code @ConditionalOnMissingBean} and backs off behind its richer replacement here.
 *
 * <p>Nothing ships this: the bundle imports it, and the module declares no {@code @jenesis.main}. What remains is
 * {@link #start(int)} for an embedder or a test and the {@link #main} below.
 */
@SpringBootApplication
public class RepositoryApplication {

    /**
     * Runs this node alone, for the containerised harness, which boots a product module and main class and keeps its
     * own modules off the module path it mounts. A debt rather than a design: the way out is a test-owned launcher
     * module the harness may mount.
     *
     * <p>No shipped artifact runs this method, so nothing a deployment depends on may be decided here - a default set
     * here would be off in the image while the settings screen said it was on. A report every entry point makes may.
     */
    public static void main(String[] args) {
        // The licence report fires from the licence module's configuration, once per JVM.
        new SpringApplicationBuilder(RepositoryApplication.class)
                .properties("spring.config.name=repository")
                .run(args);
    }

    /**
     * Boot the server on the given port ({@code 0} picks an ephemeral one) and return a handle exposing the bound
     * port and closing the context, so an embedder or test can drive the real server over HTTP without the Spring
     * types leaking into its module.
     */
    public static Running start(int port) {
        // A run argument, not a .properties() default: that is Spring's lowest layer, which any configured port
        // would outrank.
        ConfigurableApplicationContext context = new SpringApplicationBuilder(RepositoryApplication.class)
                .properties("spring.config.name=repository")
                .run("--server.port=" + port);
        int bound = Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
        return new Running(bound, context);
    }

    /**
     * The routes a booted context mapped, as {@link Running#routes()} reports them; shared with the launchers that
     * compose this application.
     */
    public static Set<String> routes(ConfigurableApplicationContext context) {
        // By name: actuator contributes a second RequestMappingHandlerMapping.
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

    public static final class Running extends Launched {

        private Running(int port, ConfigurableApplicationContext context) {
            super(port, context);
        }

        /**
         * The {@code (method, path-pattern)} routes Spring actually mapped, each rendered as {@code "METHOD /pattern"}
         * (a method-agnostic mapping as {@code "* /pattern"}), without exposing the Spring context.
         */
        public Set<String> routes() {
            return RepositoryApplication.routes(context);
        }

        /**
         * Runs every enabled maintenance task once, synchronously ({@link MaintenanceScheduler#runNow(Instant)}); an
         * exclusive pass still takes its lease, so it coordinates with other nodes. A no-op without a scheduler.
         */
        public void runMaintenance(Instant now) {
            context.getBeanProvider(MaintenanceScheduler.class).ifAvailable(scheduler -> scheduler.runNow(now));
        }

        /**
         * The deployment's root store, carrying its bindings, so a test reads and publishes as this node does.
         */
        public ArtifactStore store() {
            return context.getBean(ArtifactStore.class);
        }
    }
}
