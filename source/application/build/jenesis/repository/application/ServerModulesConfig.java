package build.jenesis.repository.application;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Pulls the installed server feature modules into the context through {@link ServerModuleImports}; with none
 * installed the server serves only its built-in surface.
 */
@Configuration(proxyBeanMethods = false)
@Import(ServerModuleImports.class)
public class ServerModulesConfig {
}
