package build.jenesis.repository.management.web;

import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.server.CredentialContext;
import build.jenesis.repository.server.RepositoryAutoConfiguration;
import build.jenesis.repository.server.kernel.Repositories;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;

/**
 * This distribution's {@link CredentialContext}, contributed <em>before</em> the core decides it needs a default.
 *
 * <p>The credential routes live once, in the core's {@code CredentialsController}. Only two things vary here and
 * neither is logic - the tenant is resolved through the deployment's own tenancy rather than read off the key, and
 * every mutation is written to the audit ledger - so they are supplied through this seam instead of by restating
 * the routes.
 *
 * <p>An auto-configuration ordered {@code before} the core's, rather than a plain {@code @Configuration} bean, so
 * the core's {@code @ConditionalOnMissingBean} sees this one already present and steps aside. Exactly ONE
 * {@code CredentialContext} then exists. Marking a second one {@code @Primary} would also resolve the injection,
 * but it leaves the losing bean in the context - which is the "two implementations of one thing" shape this whole
 * change exists to remove.
 *
 * <p><b>Conditional on what it needs, because not every composition this product ships is a repository.</b> The
 * build cache is a server with no repositories and no audit ledger, and an unconditional {@code @Bean} asking for
 * both made its image fail its context refresh on every boot - for nine days, because the only suite that boots
 * that image is a soak excluded from every lane. A distribution that has no repositories to administer credentials
 * for contributes no credential context, which is the honest answer rather than a bean that cannot be built.
 */
@AutoConfiguration(before = RepositoryAutoConfiguration.class)
public class CredentialContextAutoConfiguration {

    @Bean
    @ConditionalOnBean({Repositories.class, AuditTrail.class})
    public CredentialContext credentialContext(Repositories repositories, AuditTrail audit) {
        return new AuditedCredentialContext(repositories, audit);
    }
}
