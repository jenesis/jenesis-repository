package build.jenesis.repository.application;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Pulls the installed server feature modules into the context (see {@link ServerModuleImports}); with none installed
 * it imports nothing - the server then serves only its built-in surface and each absent feature's endpoints answer
 * that it is not installed. Component-scanned as part of the {@code build.jenesis.repository.application} package, so
 * the {@link RepositoryApplication} names no feature module. Mirrors {@code ConsoleModulesConfig} on the console side.
 */
@Configuration(proxyBeanMethods = false)
@Import(ServerModuleImports.class)
public class ServerModulesConfig {
}
