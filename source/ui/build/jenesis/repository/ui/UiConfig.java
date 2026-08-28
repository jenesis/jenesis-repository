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
 * Bridges the framework-neutral console primitives into Spring: the {@link Panel} plugins (discovered with
 * {@code ServiceLoader}, exactly as the repository server discovers its formats), the {@link ArtifactStore} the panels
 * read (the same backend the server writes, selected by name through {@code ArtifactStoreProvider}), and the
 * {@link Principals} authority model. Each bean is {@link ConditionalOnMissingBean conditional}, so a deployment
 * that contributes its own store, panel set or authority model overrides the default and this backs off.
 */
@Configuration(proxyBeanMethods = false)
public class UiConfig {

    @Bean
    @ConditionalOnMissingBean(name = "panels")
    public List<Panel> panels(Environment environment) {
        List<Panel> panels = new ArrayList<>();
        ServiceLoader.load(Panel.class).forEach(panels::add);
        // The Security-posture panel is config-aware, so it is contributed here with the deployment
        // configuration lookup (the Spring Environment) rather than ServiceLoader-discovered no-arg, so its body reads
        // the same effective configuration the header badge (ConsoleAdvice) counts.
        return panels;
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
}
