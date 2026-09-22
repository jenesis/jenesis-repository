package build.jenesis.repository.config.web;

import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.server.RepositoryRouting;
import build.jenesis.repository.server.kernel.LiveConfig;
import build.jenesis.repository.server.kernel.PinnedSettings;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.server.kernel.Settings;
import build.jenesis.repository.upstream.UpstreamCredentialSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the deployment-config management web adapter into the repository server: the {@link ConfigController} over the
 * framework-free {@link Repositories} resolver, the store-backed {@link Settings} (with {@link LiveConfig} rebuilt
 * live where a setting allows it), the discovered {@link UpstreamCredentialSource} and the discovered
 * {@link AuditTrail}. Imported through {@code ServerModuleProvider} discovery (see {@link ConfigWebModule}), never
 * named by the server - so with this module absent the server carries no settings, repository-definition,
 * format-upstream or upstream-credential endpoints and the console hides the panels. The bean mirrors the constructor
 * injection the monolith performed, so the resolved dependencies are the same ones the server already exposes.
 */
@Configuration(proxyBeanMethods = false)
public class ConfigWebConfig {

    @Bean
    public ConfigController configController(Repositories repositories, Settings settings, LiveConfig live,
                                             PinnedSettings pinnedSettings,
                                             UpstreamCredentialSource upstreamCredentials, AuditTrail audit,
                                             RepositoryRouting routing) {
        return new ConfigController(repositories, settings, live, pinnedSettings, upstreamCredentials, audit,
                routing);
    }
}
