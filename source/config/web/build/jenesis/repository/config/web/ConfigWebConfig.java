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
 * Wires the deployment-config management web adapter into the repository server: the {@link ConfigController} over the
 * framework-free {@link Repositories} resolver, the one {@link SettingsEditor} every settings change goes through,
 * the discovered {@link UpstreamCredentialSource} and the discovered {@link AuditTrail}. Imported through {@code ServerModuleProvider} discovery (see {@link ConfigWebModule}), never
 * named by the server - so with this module absent the server carries no settings, repository-definition,
 * format-upstream or upstream-credential endpoints and the console hides the panels. The bean mirrors the constructor
 * injection the monolith performed, so the resolved dependencies are the same ones the server already exposes.
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

    /** Whether {@code key} is the deployment operator's: every caller is on a deployment that enforces no
     *  authorization, and otherwise a key of the operator tenant holding the manage right over every repository -
     *  the one a route reading or writing the whole deployment takes. */
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
