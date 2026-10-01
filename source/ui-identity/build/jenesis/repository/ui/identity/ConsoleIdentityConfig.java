package build.jenesis.repository.ui.identity;

import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.ui.ConsoleAdministrators;
import build.jenesis.repository.ui.CurrentTenant;
import build.jenesis.repository.ui.KnownPrincipals;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The identity layer's beans, declared once for the console that imports this class: the per-tenant membership
 * directory over the session's tenant, the super-admin set, the provider-independent login decision and the console's
 * properties. The classes carry no Spring annotation and lie outside the console's component scan, so a composition
 * names this configuration and a test constructs them.
 */
@Configuration
@EnableConfigurationProperties(UiProperties.class)
public class ConsoleIdentityConfig {

    @Bean
    public Superadmins superadmins(ConsoleAdministrators administrators) {
        return new Superadmins(administrators);
    }

    @Bean
    public LoginAuthorization loginAuthorization(Superadmins superadmins, KnownPrincipals known) {
        return new LoginAuthorization(superadmins, known);
    }

    @Bean
    public UserDirectory userDirectory(Authorization authorization, CurrentTenant current) {
        return new UserDirectory(authorization, current);
    }
}
