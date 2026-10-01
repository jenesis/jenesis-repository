package build.jenesis.repository.config.web;

import java.io.IOException;

import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.server.RepositoryRouting;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.server.RepositoryProperties;
import build.jenesis.repository.server.kernel.Repositories;
import build.jenesis.repository.server.kernel.SettingsEditor;
import build.jenesis.repository.upstream.UpstreamCredentialSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the deployment-config web adapter into the server: {@link ConfigController} over {@link Repositories}, the one
 * {@link SettingsEditor}, the discovered {@link UpstreamCredentialSource} and the discovered {@link AuditTrail}.
 * Imported through {@code ServerModuleProvider} discovery ({@link ConfigWebModule}); without this module the server
 * carries none of these endpoints and the console hides the panels.
 */
@Configuration(proxyBeanMethods = false)
public class ConfigWebConfig {

    @Bean
    public ConfigController configController(Repositories repositories, SettingsEditor editor,
                                             UpstreamCredentialSource upstreamCredentials, AuditTrail audit,
                                             RepositoryRouting routing, Authorization authorization,
                                             RepositoryProperties properties) {
        String operatorTenant = properties.operatorTenantOrDefault();
        return new ConfigController(repositories, editor, upstreamCredentials, audit,
                routing, key -> operator(authorization, operatorTenant, key));
    }

    /** Whether {@code key} is the deployment operator's: always, on a deployment enforcing no authorization; otherwise
     *  a key of the operator tenant holding the manage right over every repository. */
    static boolean operator(Authorization authorization, String operatorTenant, String key) {
        if (!authorization.enforced()) {
            return true;
        }
        if (key == null || !operatorTenant.equals(Authorization.tenantOf(key))) {
            return false;
        }
        try {
            return authorization.authorize(key, "*", Authorization.MANAGE_WRITE) == Authorization.Decision.ALLOWED;
        } catch (IOException unreadable) {
            return false;
        }
    }
}
