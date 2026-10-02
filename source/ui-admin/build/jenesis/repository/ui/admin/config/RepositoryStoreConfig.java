package build.jenesis.repository.ui.admin.config;

import java.io.IOException;

import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.audit.AuditTrailProvider;
import build.jenesis.repository.store.ReadOnlyArtifactStore;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import build.jenesis.repository.server.kernel.PinnedSettings;
import build.jenesis.repository.server.kernel.SettingsEditor;
import build.jenesis.repository.store.Features;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;

/**
 * The console's standalone store wiring: the multi-tenant {@link ArtifactStore} root chosen by {@code jenrepo.store},
 * which {@code RepositoryAdmin} scopes per tenant and repository, and the enforcing {@link Authorization} over the same
 * store's credentials, which every surface authorizes against. Its {@link Authorization} is unconditional, so beside
 * the cache's conditional one it wins and is shared.
 */
@Configuration
public class RepositoryStoreConfig {

    /** The backend {@code jenrepo.store} names when it is not set. */
    public static final String DEFAULT_BACKEND = "filesystem";

    @Bean
    public ArtifactStore repositoryStore(Environment environment) {
        String backend = environment.getProperty("jenrepo.store", DEFAULT_BACKEND);
        ArtifactStore store = ArtifactStoreProvider.resolve(backend, environment::getProperty);
        // Read-only mode refuses the console's own writes at the server's choke point.
        return environment.getProperty("jenrepo.read-only", Boolean.class, false)
                ? new ReadOnlyArtifactStore(store)
                : store;
    }

    @Bean
    public Authorization authorization(ArtifactStore repositoryStore) {
        return Authorization.enforcing(repositoryStore);
    }

    /**
     * The settings editor of a standalone console, over the store it opens, pinning what its launch configuration
     * fixes. Composed, the console uses the repository's editor.
     */
    @Bean
    public SettingsEditor settingsEditor(ArtifactStore repositoryStore, AuditTrail auditTrail,
                                         ConfigurableEnvironment environment) throws IOException {
        PinnedSettings pins = new PinnedSettings(environment);
        return SettingsEditor.over(repositoryStore, pins::pinned, auditTrail,
                Features.namespaced(environment::getProperty));
    }

    @Bean
    public AuditTrail auditTrail(ArtifactStore repositoryStore, Environment environment) {
        // The server's discovered trail, never pruning (the server's retention prunes), and off when read-only, since it
        // writes.
        boolean readOnly = environment.getProperty("jenrepo.read-only", Boolean.class, false);
        return AuditTrailProvider.resolve(repositoryStore,
                key -> "audit".equals(key) && readOnly ? "false" : "audit-retention".equals(key) ? "" : null);
    }
}
