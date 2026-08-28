package build.jenesis.repository.ui;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Pulls the installed console modules into the context (see {@link ConsoleModuleImports}); with none installed it
 * imports nothing - the console then runs with sign-in disabled and without the modules' screens and endpoints.
 */
@Configuration(proxyBeanMethods = false)
@Import(ConsoleModuleImports.class)
public class ConsoleModulesConfig {
}
