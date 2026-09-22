package build.jenesis.repository.ui.identity;

import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.store.Documents;
import build.jenesis.repository.ui.ConsoleAdministrators;
import build.jenesis.repository.ui.CurrentTenant;
import build.jenesis.repository.ui.KnownPrincipals;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The identity layer's beans, declared once for the console that imports this class: the per-tenant membership
 * directory over the current session's tenant, the deployment's super-admin set, the provider-independent login
 * decision, and the console's own properties. The classes carry no Spring annotation of their own - they used to
 * be found by the console's component scan of its own package, which a module of their own is outside of - so a
 * composition that wants them names this configuration, and a test constructs them.
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
    public UserDirectory userDirectory(Authorization authorization, CurrentTenant current,
                                       @Qualifier("rootStorage") Documents rootStorage) {
        return new UserDirectory(authorization, current, rootStorage);
    }
}
