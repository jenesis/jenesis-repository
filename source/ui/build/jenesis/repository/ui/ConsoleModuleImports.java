package build.jenesis.repository.ui;

import module java.base;
import build.jenesis.repository.store.Features;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.DeferredImportSelector;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotationMetadata;

/**
 * Bridges {@link ServiceLoader} discovery into the Spring context: every enabled {@link ConsoleModuleProvider}'s
 * configuration class is imported as a deferred configuration, as Boot imports auto-configurations, while the console
 * names no module. A module switched off ({@code jenrepo.<name>=false}, {@link Features}) is not imported.
 */
public class ConsoleModuleImports implements DeferredImportSelector, EnvironmentAware {

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
        // The SPI home's discovery, so this selector and the shell's nav agree on what is installed.
        return ConsoleModuleProvider.enabled(config).stream()
                .map(provider -> provider.configuration().getName())
                .toArray(String[]::new);
    }
}
