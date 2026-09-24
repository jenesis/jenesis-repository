package build.jenesis.repository.auth.ldap;

import module java.base;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.server.spi.RateLimiterProvider;
import build.jenesis.repository.ui.ConsoleTemplates;
import build.jenesis.repository.ui.LoginAuthorities;
import build.jenesis.repository.ui.LoginContributor;
import build.jenesis.repository.ui.LoginOptions;
import build.jenesis.repository.ui.PrincipalNameResolver;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;

/**
 * Directory sign-in, wired only when {@code jenreg.ui.ldap.url} is set.
 *
 * <p>It authenticates through a filter and an authentication manager of its own on {@code POST /login/ldap} rather
 * than the chain's shared form login, because another mechanism may already own that: a second form login would
 * take over the first's processing URL, and a shared manager would try a directory password as a login key and send
 * a login key to the directory. The context is saved to the session, without which a filter of its own signs a
 * person in and forgets them on the next request.
 */
@Configuration(proxyBeanMethods = false)
@Conditional(LdapLoginConfig.Configured.class)
@EnableConfigurationProperties(LdapProperties.class)
public class LdapLoginConfig {

    /** Whether a directory URL is configured. */
    static final class Configured implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String url = context.getEnvironment().getProperty("jenreg.ui.ldap.url");
            return url != null && !url.isBlank();
        }
    }

    @Bean
    public Directory ldapDirectory(LdapProperties properties) {
        properties.validate();
        return new SpringLdapDirectory(properties);
    }

    @Bean
    public LoginContributor ldapLoginContributor(Directory directory, LdapProperties properties,
                                                 Authorization authorization, LoginAuthorities authorities,
                                                 AuditTrail audit, Environment environment) {
        ProviderManager manager = new ProviderManager(new LdapSignIn(directory, properties, authorization,
                authorities, RateLimiterProvider.resolve(environment::getProperty), audit));
        return http -> {
            UsernamePasswordAuthenticationFilter filter = new UsernamePasswordAuthenticationFilter(manager);
            filter.setRequiresAuthenticationRequestMatcher(
                    PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/login/ldap"));
            filter.setUsernameParameter("username");
            filter.setPasswordParameter("password");
            filter.setSecurityContextRepository(new HttpSessionSecurityContextRepository());
            SavedRequestAwareAuthenticationSuccessHandler success = new SavedRequestAwareAuthenticationSuccessHandler();
            success.setDefaultTargetUrl("/console");
            success.setAlwaysUseDefaultTargetUrl(true);
            filter.setAuthenticationSuccessHandler(success);
            filter.setAuthenticationFailureHandler(new SimpleUrlAuthenticationFailureHandler("/login/ldap?error"));
            http.addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class);
        };
    }

    /** The "Sign in with your directory account" option on the login page. */
    @Bean
    public LoginOptions ldapLoginOptions(LdapProperties properties) {
        return () -> List.of(new LoginOptions.LoginOption(LdapLoginMechanism.NAME, properties.getName().trim(),
                "/login/ldap", Optional.empty()));
    }

    @Bean
    public LdapLoginPageController ldapLoginPageController(LdapProperties properties) {
        return new LdapLoginPageController(properties);
    }

    /** This module's own page, resolved from its jar and answering only the {@code ldap/*} names. */
    @Bean
    public SpringResourceTemplateResolver ldapTemplateResolver(ApplicationContext context) {
        return ConsoleTemplates.resolver(context, LdapLoginMechanism.NAME);
    }

    /** The name the layout greets a directory principal by: the one they signed in with. */
    @Bean
    public PrincipalNameResolver ldapPrincipalNameResolver() {
        String prefix = LdapLoginMechanism.NAME + "/";
        return authentication -> authentication.getName() != null && authentication.getName().startsWith(prefix)
                ? Optional.of(authentication.getName().substring(prefix.length()))
                : Optional.empty();
    }
}
