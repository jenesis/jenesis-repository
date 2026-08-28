package build.jenesis.repository.ui;

import module java.base;
import build.jenesis.repository.store.Features;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.DeferredImportSelector;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotationMetadata;

/**
 * Bridges {@link ServiceLoader} discovery into the Spring context: every installed
 * {@link ConsoleModuleProvider}'s configuration class is imported as a deferred configuration - the same treatment
 * Boot gives its auto-configurations - so a module's {@code @Bean} methods, conditions and properties work exactly
 * as if the class were part of the console, while the console names no module. A module configured off by its
 * provider name ({@code jenreg.<name>=false}, the {@link Features} convention) is not imported, so its
 * screens degrade exactly as if the module were absent from the image.
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
        // One validated discovery in the SPI home (ConsoleModuleProvider.enabled), not a loop here: the enablement
        // convention, the name-sorted order and the refusal of two modules answering to one name all live beside the
        // contract, so this selector and the shell's capability/nav discovery can never disagree about what is
        // installed.
        return ConsoleModuleProvider.enabled(config).stream()
                .map(provider -> provider.configuration().getName())
                .toArray(String[]::new);
    }
}
