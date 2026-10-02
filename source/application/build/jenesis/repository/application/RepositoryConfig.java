package build.jenesis.repository.application;

import build.jenesis.repository.server.RepositoryProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The shell that carries the context-level annotations and imports the wiring, grouped by concern. No {@code @Bean}
 * method calls another: every collaborator is injected by parameter, so Spring supplies one singleton across the
 * configuration classes.
 *
 * <ul>
 *   <li>{@link StoreConfig} - the store's layers, authorization, settings, token exchange, audit trail, the
 *       pinned-settings probe, the {@link build.jenesis.repository.server.kernel.Repositories} tenant kernel, storage
 *       namespaces and tenants.</li>
 *   <li>{@link SignalsConfig} - advisory feeds, health source, report signals, provenance signer and the live
 *       {@link build.jenesis.repository.server.kernel.LiveConfig} gate.</li>
 *   <li>{@link ServingConfig} - upstream credentials and fetcher, the router and routed serving, tenancy routing,
 *       format dispatcher, batch ingestion, the {@code repositoryController} serving bean and its deploy hooks.</li>
 *   <li>{@link WorkersConfig} - download and key-usage trackers, the maintenance scheduler and the settings
 *       refresh.</li>
 *   <li>{@link BootAdviceConfig} - the boot-time configuration advice.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RepositoryProperties.class)
@EnableScheduling
@Import({StoreConfig.class, SignalsConfig.class, ServingConfig.class, WorkersConfig.class, BootAdviceConfig.class})
public class RepositoryConfig {
}
