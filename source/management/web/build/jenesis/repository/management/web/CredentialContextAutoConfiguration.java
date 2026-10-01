package build.jenesis.repository.management.web;

import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.server.CredentialContext;
import build.jenesis.repository.server.RepositoryAutoConfiguration;
import build.jenesis.repository.server.RepositoryProperties;
import build.jenesis.repository.server.kernel.Repositories;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;

/**
 * This distribution's {@link CredentialContext}, contributed before the core decides it needs a default. The credential
 * routes live once, in the core's {@code CredentialsController}; only the audit write and the default-tenant fallback
 * vary, so they come through this seam.
 *
 * <p>An auto-configuration ordered before the core's, so the core's {@code @ConditionalOnMissingBean} steps aside and
 * exactly one {@code CredentialContext} exists; a {@code @Primary} second bean would leave the losing one in the
 * context.
 *
 * <p>Conditional on what it needs, since not every composition is a repository: the build cache has no repositories and
 * no audit ledger, and a bean demanding both would fail its context. A distribution with no repositories contributes no
 * credential context.
 */
@AutoConfiguration(before = RepositoryAutoConfiguration.class)
public class CredentialContextAutoConfiguration {

    @Bean
    @ConditionalOnBean({Repositories.class, AuditTrail.class})
    public CredentialContext credentialContext(RepositoryProperties properties, AuditTrail audit) {
        return new AuditedCredentialContext(properties.getDefaultTenant(), audit);
    }
}
