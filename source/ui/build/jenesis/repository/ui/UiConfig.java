package build.jenesis.repository.ui;

import build.jenesis.repository.store.TenantsProvider;
import build.jenesis.repository.store.Tenants;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import module java.base;

/**
 * Bridges the framework-neutral console primitives into Spring: the {@link ConsoleCard} plugins (discovered with
 * {@code ServiceLoader}, exactly as the repository server discovers its formats), the {@link ArtifactStore} the cards
 * read (the same backend the server writes, selected by name through {@code ArtifactStoreProvider}), and the
 * {@link Principals} authority model. Each bean is {@link ConditionalOnMissingBean conditional}, so a deployment
 * that contributes its own store, card set or authority model overrides the default and this backs off.
 */
@Configuration(proxyBeanMethods = false)
public class UiConfig {

    /**
     * The overview's cards, in discovery order.
     *
     * <p>It takes no configuration, and that is the shape of the seam rather than an omission: a card that needs
     * deployment configuration or a collaborator is contributed as a bean and this backs off, which is what the
     * {@link ConditionalOnMissingBean} is for. The security posture used to be discovered here with the
     * {@code Environment} threaded in so that its card and the header badge counted one report; it is a screen of
     * its own now, over the {@link PostureSource} seam both of them read.
     */
    @Bean
    @ConditionalOnMissingBean(name = "cards")
    public List<ConsoleCard> cards() {
        List<ConsoleCard> cards = new ArrayList<>();
        ServiceLoader.load(ConsoleCard.class).forEach(cards::add);
        return cards;
    }

    @Bean
    @ConditionalOnMissingBean
    public ArtifactStore artifactStore(UiProperties properties, Environment environment) {
        return ArtifactStoreProvider.resolve(properties.getStore(), environment::getProperty);
    }

    @Bean
    @ConditionalOnMissingBean
    public Principals principals(UiProperties properties) {
        return new Principals(properties);
    }

    /**
     * The tenant this console acts in, answered by the tenancy SPI rather than by a second reading of a setting.
     *
     * <p>Tenancy is one SPI with two implementations, and which is installed is a deployment's choice: with no
     * tenants module on the graph {@link TenantsProvider#resolve} answers the fixed directory over the configured
     * tenant, whose {@link Tenants#list} is exactly that one tenant - and that is this console's answer on every
     * request. A deployment that installs a tenants module selects per session and contributes its own
     * {@link CurrentTenant}, which this steps aside for. Neither case is an edition: the same console serves both.
     *
     * <p>Deliberately not a fresh {@code jenreg.tenant} read of its own. That would be a second single-tenant
     * implementation beside the one the SPI already has, and the two would answer differently the day one of them
     * learned something the other did not.
     */
    @Bean
    @ConditionalOnMissingBean(CurrentTenant.class)
    public CurrentTenant currentTenant(ArtifactStore artifactStore, Environment environment) {
        Tenants tenants = TenantsProvider.resolve(artifactStore, environment::getProperty,
                environment.getProperty("jenreg.tenant", "default"));
        return () -> {
            try {
                // A directory is never empty - the fixed one answers its single tenant - so this is the deployment's
                // tenant, read from the thing that decides what the deployment's tenants are.
                return tenants.list().getFirst();
            } catch (IOException unreadable) {
                throw new IllegalStateException("The tenant directory could not be read, so this console cannot "
                        + "say which tenant it is acting in", unreadable);
            }
        };
    }

    /**
     * Where this console reads posture from when nothing else supplies it: the environment this process started
     * with. A deployment that keeps stored settings layers those over it and contributes its own source, which
     * this steps aside for - the report is the same report either way, discovered against a different view of the
     * effective configuration.
     */
    @Bean
    @ConditionalOnMissingBean(PostureSource.class)
    public PostureSource postureSource(Environment environment) {
        return PostureSource.ofEnvironment(environment::getProperty);
    }

    /**
     * The installed-providers screen's catalogue.
     *
     * <p>The default reports the module graph undecorated, because a deployment with no stored configuration has
     * nothing that could have switched an installed implementation off. One that reads stored settings contributes
     * its own and this backs off - which is what replaced the second, richer copy of this screen.
     */
    @Bean
    @ConditionalOnMissingBean(SpiCatalogSource.class)
    public SpiCatalogSource spiCatalogSource() {
        return SpiCatalogSource.ofModuleGraph();
    }
}
