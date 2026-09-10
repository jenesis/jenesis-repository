package build.jenesis.repository.ui;

import module java.base;

import build.jenesis.repository.store.TenantsProvider;
import build.jenesis.repository.store.Tenants;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import build.jenesis.repository.server.spi.Authorization;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

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

    /**
     * The store this console reads when it runs alone - {@code @ConditionalOnMissingBean}, so wherever it is
     * composed with the repository (which is every shipped image) the repository's own bean wins and this is
     * never built.
     *
     * <p>It reads {@code jenreg.store}, the deployment's one store key, rather than a {@code jenreg.ui.store} of
     * its own. That property existed and could not carry a meaningful second value: a console browses,
     * administers and reclaims the artifacts the repository serves, so pointing it elsewhere administers a store
     * nobody serves - and it selected only the backend NAME while both read the same {@code jenreg.<backend>.*}
     * location keys. Its own javadoc named {@code JENREG_STORE} as its source and the public reference said the
     * two "point at one store"; the downstream console had already stopped consulting it. Reading the real key
     * also means {@code JENREG_STORE} reaches this bean by relaxed binding, which is what a second key could not
     * do without a file declaring it.
     */
    @Bean
    @ConditionalOnMissingBean
    public ArtifactStore artifactStore(Environment environment) {
        return ArtifactStoreProvider.resolve(environment.getProperty("jenreg.store", "filesystem"),
                environment::getProperty);
    }

    /**
     * The grants this console reads its authority model out of.
     *
     * <p>Deliberately not a bean. Where the console is composed with the repository, the repository's own
     * {@code Authorization} carries a deployment's anonymous rights and credential lifetimes, and it declares
     * itself {@code @ConditionalOnMissingBean} - so a bean here would not merely duplicate it, it would REPLACE
     * it, and a console configuration would silently decide a repository's security posture. Taken through an
     * {@link ObjectProvider} instead: the composition's own if there is one, and a plain enforcing view of the
     * store when this console runs alone. Either way both readers below share one, because two caches over one
     * set of grants disagree for as long as the shorter of their windows.
     */
    private static Authorization grants(ObjectProvider<Authorization> authorization, ArtifactStore store) {
        return authorization.getIfAvailable(() -> Authorization.enforcing(store));
    }

    /** Who administers this deployment: one reader over grants, seeded from this console's own admins setting.
     *  A composing console that binds the prefix with its own configuration type declares its own. */
    @Bean
    @ConditionalOnMissingBean
    public ConsoleAdministrators consoleAdministrators(ObjectProvider<Authorization> authorization,
                                                      ArtifactStore store, UiProperties properties) {
        return new ConsoleAdministrators(grants(authorization, store), properties.getAdmins());
    }

    /**
     * Who may see this console at all - the single-tenant answer, which a multi-tenant console replaces.
     *
     * <p>It is a bean rather than a constant because it is the one decision that differs between a console serving
     * one tenant and a console serving many, and the difference is only in what "somewhere" means.
     */
    @Bean
    @ConditionalOnMissingBean
    public ConsoleAccess consoleAccess(ObjectProvider<Authorization> authorization, ArtifactStore store,
                                       ConsoleAdministrators administrators, CurrentTenant currentTenant) {
        return new GrantedConsoleAccess(grants(authorization, store), administrators, currentTenant);
    }

    /** The people this deployment has seen sign in, so an administrator can grant from a list rather than from a
     *  provider subject somebody had to be told out of band. */
    @Bean
    @ConditionalOnMissingBean
    public KnownPrincipals knownPrincipals(ObjectProvider<Authorization> authorization, ArtifactStore store) {
        return new KnownPrincipals(grants(authorization, store));
    }

    @Bean
    @ConditionalOnMissingBean
    public Principals principals(ConsoleAdministrators administrators, KnownPrincipals known) {
        return new Principals(administrators, known);
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
