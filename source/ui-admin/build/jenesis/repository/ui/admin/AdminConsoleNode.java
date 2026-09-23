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
 * The console, as one thing an application either carries or does not.
 *
 * <p>There were two nodes once - this one and the shell's own, which imported the shared screens and supplied a
 * default for every seam this console overrides. Nothing ever imported it, so it was a second console that no
 * image served, and the {@link #GATE} an operator reads as "the console" was declared on it. The shell is a
 * library now: the layout, the url space, the extension seams and the screens both consoles rendered. What is
 * gone is its wiring, and a composition scanning both would have registered two
 * {@code SecurityConfig} classes under one bean name and fail the context outright.
 *
 * <p>The gate is read as the context starts, never from the settings store: it decides whether the console's
 * controllers and its deny-by-default security chain <em>exist</em>, and a stored setting is read by beans that
 * already do. With the console off, the repository's own chain is the whole of what the node answers - which is the
 * point, since leaving a console chain registered without its screens would claim every path the console owns and
 * answer them with a redirect to a sign-in page nothing serves.
 */
@Configuration(proxyBeanMethods = false)
// "jenreg." + GATE, not GATE: the constant is the SETTINGS key (unprefixed, as the catalogue
// carries it) and Spring wants the property name. They were one string until the settings
// reference needed a literal it could read, and a compile-time constant is inlined - so this
// call site went on compiling while asking for a property called "console" that nothing sets.
@ConditionalOnProperty(name = "jenreg." + AdminConsoleNode.GATE, havingValue = "true", matchIfMissing = true)
@ConfigurationPropertiesScan(basePackages = "build.jenesis.repository.ui.admin.config")
@ComponentScan(basePackages = "build.jenesis.repository.ui.admin",
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
                // The console's own store wiring, which exists so it can run as its own node. In a composed
                // application the repository's StoreConfig is authoritative: both declare an `authorization` and an
                // `auditTrail` bean, and more to the point the console's opens the store named by jenreg.ui.* while
                // the repository opens jenreg.store - one node must read one store, and it is the one the
                // repository writes.
                pattern = {"build\\.jenesis\\.repository\\.ui\\.admin\\.config\\.RepositoryStoreConfig",
                        "build\\.jenesis\\.repository\\.ui\\.admin\\.Application"}))
@Import({ConsoleScreensConfig.class, ConsoleModulesConfig.class, ConsoleIdentityConfig.class})
public class AdminConsoleNode {

    /**
     * Whether this deployment serves the console at all. Read before the context starts, so it applies on the next
     * restart rather than live; the settings catalogue carries it unprefixed, which is why the call sites above
     * compose {@code "jenreg." + GATE} rather than holding one string for both jobs.
     *
     * <p>It lived on the shell's own node until that node went. Nothing imported that node, so the gate it carried
     * was the gate of a console nobody booted, while the console that ships read the same constant across a module
     * boundary.
     */
    public static final String GATE = "console";

    /**
     * Who administers this deployment, seeded from this console's own {@code jenreg.ui.admins}.
     *
     * <p>Declared on the node rather than in {@code RepositoryStoreConfig}, which is the console's <em>standalone</em>
     * store wiring and is excluded from the scan wherever the console is composed with the repository - so a bean
     * declared there exists in one of the two compositions this console ships in. It is declared here rather than
     * inherited from the base console's default because the two bind {@code jenreg.ui.*} with different
     * configuration types - disjoint keys, deliberately not merged - so each supplies the value from its own; the
     * reader, the seeding and the grants they produce are shared.
     *
     * <p>Over the {@link Authorization} bean, which both compositions declare, rather than over the store: a second
     * instance would carry a second cache of the same grants.
     */
    @Bean
    public ConsoleAdministrators consoleAdministrators(Authorization authorization, UiProperties properties) {
        return new ConsoleAdministrators(authorization, properties.getAdmins());
    }

    /**
     * The people this deployment has seen sign in, so the members screen can offer them rather than asking an
     * administrator to type a provider subject nobody can learn. Declared here for the same reason as
     * {@code consoleAdministrators}: {@code RepositoryStoreConfig} is the standalone-console wiring and is excluded
     * wherever this console is composed with the repository.
     */
    @Bean
    public KnownPrincipals knownPrincipals(Authorization authorization) {
        return new KnownPrincipals(authorization);
    }

}
