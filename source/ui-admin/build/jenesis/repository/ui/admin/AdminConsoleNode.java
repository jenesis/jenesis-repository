package build.jenesis.repository.ui.admin;

import module java.base;
import build.jenesis.repository.ui.identity.ConsoleIdentityConfig;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.ui.ConsoleAdministrators;
import build.jenesis.repository.ui.KnownPrincipals;
import build.jenesis.repository.ui.ConsoleModulesConfig;
import build.jenesis.repository.ui.identity.UiProperties;
import build.jenesis.repository.ui.ConsoleScreensConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;

/**
 * The console, as one thing an application either carries or does not; the shell module is a library with no wiring
 * of its own. {@link #GATE} is read as the context starts, never from the store, since it decides whether the
 * console's controllers and security chain exist: with it off, the repository's chain answers alone rather than a
 * console chain redirecting to a sign-in page nothing serves.
 */
@Configuration(proxyBeanMethods = false)
// GATE is the unprefixed settings key; Spring wants the property name.
@ConditionalOnProperty(name = "jenrepo." + AdminConsoleNode.GATE, havingValue = "true", matchIfMissing = true)
@ConfigurationPropertiesScan(basePackages = "build.jenesis.repository.ui.admin.config")
@ComponentScan(basePackages = "build.jenesis.repository.ui.admin",
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
                // The console's standalone store wiring; composed, the repository's StoreConfig is authoritative, so
                // the node reads the one store the repository writes.
                pattern = {"build\\.jenesis\\.repository\\.ui\\.admin\\.config\\.RepositoryStoreConfig",
                        "build\\.jenesis\\.repository\\.ui\\.admin\\.Application"}))
@Import({ConsoleScreensConfig.class, ConsoleModulesConfig.class, ConsoleIdentityConfig.class})
public class AdminConsoleNode {

    /**
     * Whether this deployment serves the console at all: the unprefixed settings key, read before the context starts, so
     * it applies on restart.
     */
    public static final String GATE = "console";

    /**
     * Who administers this deployment, seeded from this console's {@code jenrepo.ui.admins}. Declared on the node so it
     * exists in both compositions ({@code RepositoryStoreConfig} is standalone-only), over the {@link Authorization}
     * bean so the grants have one cache.
     */
    @Bean
    public ConsoleAdministrators consoleAdministrators(Authorization authorization, UiProperties properties) {
        return new ConsoleAdministrators(authorization, properties.getAdmins());
    }

    /**
     * The people this deployment has seen sign in, offered on the members screen; declared on the node as
     * {@code consoleAdministrators} is.
     */
    @Bean
    public KnownPrincipals knownPrincipals(Authorization authorization) {
        return new KnownPrincipals(authorization);
    }

}
