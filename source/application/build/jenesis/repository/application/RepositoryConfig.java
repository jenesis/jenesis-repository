package build.jenesis.repository.application;

import build.jenesis.repository.server.RepositoryProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Wires the repository from {@link RepositoryProperties}: the artifact store (a backend chosen by name
 * through {@code ArtifactStoreProvider}), the {@link build.jenesis.repository.server.spi.Authorization} (enforcing or
 * anonymous), the {@code ComplianceGate} (a license allow-list and a CVSS threshold, optionally over a live OSV
 * source), the gated publish path, the store-backed staging and inventory, and the retention policy. Every bean is
 * plain domain code reused as-is; Spring only assembles them.
 *
 * <p>split the former monolith into five focused, same-package {@code @Configuration} classes,
 * grouped by concern - this class is now the thin shell that carries the context-level annotations
 * ({@link EnableConfigurationProperties}, {@link EnableScheduling}) and {@link Import}s the groups. The split is a
 * pure mechanical, behaviour-preserving extraction (mirror): every bean keeps the same name, type,
 * {@code initMethod}/{@code destroyMethod} lifecycles and {@code proxyBeanMethods = false} semantics. The singleton
 * graph is preserved by construction: the monolith contained no direct inter-{@code @Bean} method calls (which
 * {@code proxyBeanMethods = false} already forbade from returning shared instances) - every collaborator is
 * method-parameter injected, so Spring supplies the one singleton across the new config-class boundaries exactly as
 * it did within the one class, and no bean is defined twice ({@code @Import} and the same-package component scan
 * dedupe configuration classes by class name). In particular the metering / read-only artifact-store wrap order
 * (§2.4) is applied where the store is declared, which this composition layers into, and the demo seed still depends
 * on every {@link build.jenesis.repository.store.PublishPathWiring} bean by parameter so the publish path is armed
 * before the seed publishes.
 *
 * <ul>
 *   <li>{@link StoreConfig} - artifact store (metering/read-only wrap), authorization, settings, token exchange,
 *       audit trail, pinned-settings probe, the {@link build.jenesis.repository.server.kernel.Repositories} tenant
 *       kernel, storage namespaces, tenants.</li>
 *   <li>{@link SignalsConfig} - advisory feeds/source, health source, report signals, provenance signer, the live
 *       {@link build.jenesis.repository.server.kernel.LiveConfig} gate (VEX overlay + definition sweep) and the
 *       publish-path
 *       wiring beans.</li>
 *   <li>{@link ServingConfig} - the upstream credentials/fetcher, the router
 *       and routed serving, tenancy routing, format dispatcher, batch ingestion, the {@code repositoryController}
 *       serving bean, the deploy edge hooks and the publish-tenant filter.</li>
 *   <li>{@link WorkersConfig} - download/key-usage trackers, the maintenance scheduler and the settings-refresh
 *       convergence pass.</li>
 *   <li>{@link DemoConfig} - demo seeding and the first-run hardening advice.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RepositoryProperties.class)
@EnableScheduling
@Import({StoreConfig.class, SignalsConfig.class, ServingConfig.class, WorkersConfig.class, DemoConfig.class})
public class RepositoryConfig {
}
