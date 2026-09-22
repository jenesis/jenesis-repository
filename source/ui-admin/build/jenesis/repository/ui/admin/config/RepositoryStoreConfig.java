package build.jenesis.repository.ui.admin.config;

import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.audit.AuditTrailProvider;
import build.jenesis.repository.store.ReadOnlyArtifactStore;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * The artifact-repository store the console manages, alongside the cache's CacheStorage. The console reads and
 * writes the repository through this multi-tenant {@link ArtifactStore} root (a backend chosen by
 * {@code jenreg.store}); {@code RepositoryAdmin} scopes it to the signed-in user's tenant and the
 * selected repository, so the same console fronts both products.
 *
 * <p>The same store also holds the access credentials under {@code auth/<tenant>/<hash>/}, managed through an
 * enforcing {@link Authorization}: {@code CredentialService} mints and grants over it, and the cache and the
 * artifact repository authorize requests against it, so one credential store serves every surface. The
 * {@link Authorization} is the unconditional definition, so when this configuration is combined with the cache
 * (whose own store and authorization are conditional), this one wins and is shared.
 */
@Configuration
public class RepositoryStoreConfig {

    @Bean
    public ArtifactStore repositoryStore(Environment environment) {
        String backend = environment.getProperty("jenreg.store", "filesystem");
        ArtifactStore store = ArtifactStoreProvider.resolve(backend, environment::getProperty);
        // In read-only mode the console browses/downloads but refuses its own admin writes (credentials, settings,
        // tenants) at the same store choke point the repository server uses, so a mutating action cannot slip past
        // the mode by going through the console's store instead of the server's.
        return environment.getProperty("jenreg.read-only", Boolean.class, false)
                ? new ReadOnlyArtifactStore(store)
                : store;
    }

    @Bean
    public Authorization authorization(ArtifactStore repositoryStore) {
        return Authorization.enforcing(repositoryStore);
    }

    @Bean
    public AuditTrail auditTrail(ArtifactStore repositoryStore, Environment environment) {
        // The console records through the same discovered trail as the server; always enabled here (the console
        // only sees signed-in actors) and never pruning (the server's retention owns that). In read-only mode the
        // trail is off, since it is itself a store write and there is no mutation to record.
        boolean readOnly = environment.getProperty("jenreg.read-only", Boolean.class, false);
        return AuditTrailProvider.resolve(repositoryStore,
                key -> "audit".equals(key) && readOnly ? "false" : "audit-retention".equals(key) ? "" : null);
    }
}
