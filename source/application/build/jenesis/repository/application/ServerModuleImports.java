package build.jenesis.repository.application;

import module java.base;

import build.jenesis.repository.server.kernel.ServerModuleProvider;
import build.jenesis.repository.store.Features;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.DeferredImportSelector;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotationMetadata;

/**
 * Bridges {@link ServiceLoader} discovery into the Spring context: every installed {@link ServerModuleProvider}'s
 * configuration class is imported as a deferred configuration, as Boot treats its auto-configurations, so the server
 * names no module. A module switched off by name ({@code jenrepo.<name>=false}, the {@link Features} convention) is
 * not imported, exactly as if it were absent.
 */
public class ServerModuleImports implements DeferredImportSelector, EnvironmentAware {

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public String[] selectImports(AnnotationMetadata metadata) {
        // Without an environment (a non-Spring caller exercising the seam) nothing is configured off.
        UnaryOperator<String> config = environment == null
                ? key -> null
                : Features.namespaced(environment::getProperty);
        // The SPI home owns the enablement, the order and the refusal of a duplicate name.
        return ServerModuleProvider.enabled(config).stream()
                .map(provider -> provider.configuration().getName())
                .toArray(String[]::new);
    }
}
