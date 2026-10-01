package build.jenesis.repository.auth.keylogin;

import module java.base;
import module org.slf4j;

import build.jenesis.repository.store.Features;
import build.jenesis.repository.store.Documents;
import build.jenesis.repository.audit.AuditTrail;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.server.spi.Authorization.Kind;
import build.jenesis.repository.server.spi.RateLimiter;
import build.jenesis.repository.server.spi.RateLimiterProvider;
import build.jenesis.repository.ui.LoginOptions;
import build.jenesis.repository.ui.ConsoleTemplates;
import build.jenesis.repository.ui.LoginContributor;
import build.jenesis.repository.ui.identity.UiProperties;
import build.jenesis.repository.ui.identity.Superadmins;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;
import build.jenesis.repository.scope.Scopes;

/**
 * Wires key-based console sign-in unless {@code jenrepo.key-login=false}, and nothing at all when it is, leaving the
 * other sign-in chains untouched. It contributes a form-login leg to the shared chain ({@link LoginContributor}) backed
 * by {@link KeyLoginAuthenticationProvider}, a "Sign in with a key" option ({@link LoginOptions}), the key-entry page,
 * the issued keys' API and screen over {@link KeyLogins}, and the {@link FirstRunKey} announced by
 * {@link FirstRunWelcome}. An admin key in the environment is announced with a WARN. Registering the provider as a bean
 * also stops Boot from creating a default in-memory user.
 */
@Configuration(proxyBeanMethods = false)
@Conditional(KeyLoginConfig.KeyLoginEnabled.class)
public class KeyLoginConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(KeyLoginConfig.class);

    /** True unless {@code jenrepo.key-login=false} ({@link KeyLoginMechanism#onByDefault()}); off, no bean here exists
     *  and no key is ever accepted. */
    public static class KeyLoginEnabled implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return context.getEnvironment().getProperty(Features.key(KeyLoginMechanism.NAME),
                    Boolean.class, KeyLoginMechanism.onByDefault());
        }
    }

    public KeyLoginConfig(UiProperties properties) {
        LOGGER.info("Key-based console sign-in is on (jenrepo.key-login). Switch it off with jenrepo.key-login=false "
                + "once single sign-on (OIDC) or a directory (LDAP) signs people in.");
        if (!properties.getAdminKey().isBlank()) {
            LOGGER.warn("SECURITY: a full-access bootstrap admin key is set (JENREPO_UI_ADMIN_KEY) - it grants "
                    + "super-admin over every tenant. Use it only to bootstrap, then issue scoped login keys and "
                    + "rotate or remove it.");
        }
    }

    @Bean
    public KeyLoginKeys keyLoginKeys(@Qualifier("rootStorage") Documents rootStorage) {
        return new KeyLoginKeys(rootStorage);
    }

    /** The first-run key, over the issued keys' documents. "Administered" is one bounded page read of the deployment
     *  scope's principals and groups, asked at start and at each first-run sign-in, never on a request path. */
    @Bean
    public FirstRunKey firstRunKey(@Qualifier("rootStorage") Documents rootStorage, KeyLoginKeys keys,
                                   UiProperties properties, Authorization authorization) {
        BooleanSupplier administered = () ->
                !authorization.subjects(Authorization.DEPLOYMENT, Kind.PRINCIPAL, null, 1).ids().isEmpty()
                        || !authorization.subjects(Authorization.DEPLOYMENT, Kind.GROUP, null, 1).ids().isEmpty();
        return new FirstRunKey(rootStorage, keys, !properties.getAdminKey().isBlank(), administered,
                Clock.systemUTC());
    }

    @Bean
    public FirstRunWelcome firstRunWelcome(FirstRunKey firstRunKey, Environment environment) {
        return new FirstRunWelcome(firstRunKey, environment);
    }

    @Bean
    public KeyLoginAuthenticationProvider keyLoginAuthenticationProvider(KeyLoginKeys keys, FirstRunKey firstRunKey,
                                                                         Environment environment,
                                                                         UiProperties properties, AuditTrail audit,
                                                                         Superadmins superadmins) {
        RateLimiter limiter = RateLimiterProvider.resolve(environment::getProperty);
        double permits = environment.getProperty("jenrepo.key-login.rate-limit", Double.class, 30.0);
        String tenant = environment.getProperty("jenrepo.default-tenant", Scopes.DEFAULT_TENANT);
        return new KeyLoginAuthenticationProvider(keys, firstRunKey, properties.getAdminKey().trim(), limiter, permits,
                audit, tenant, superadmins::is);
    }

    /** A form-login leg on the shared chain: the key posts to {@code /login/key}, authenticated by the key provider. */
    @Bean
    public LoginContributor keyLoginContributor(KeyLoginAuthenticationProvider provider) {
        return http -> {
            http.authenticationProvider(provider);
            http.formLogin(form -> form
                    .loginPage("/ui/login")
                    .loginProcessingUrl("/ui/login/key")
                    .usernameParameter("principal")
                    .passwordParameter("key")
                    .defaultSuccessUrl("/ui/", true)
                    .failureUrl("/ui/login?error"));
        };
    }

    /** The "Sign in with a key" option added to the login page, linking to the key-entry form. */
    @Bean
    public LoginOptions keyLoginOptions() {
        return () -> List.of(new LoginOptions.LoginOption(KeyLoginMechanism.QUALIFIER, "a key", "/ui/login/key",
                Optional.empty()));
    }

    @Bean
    public KeyLoginPageController keyLoginPageController() {
        return new KeyLoginPageController();
    }

    /** Resolves this module's own templates from its jar: {@code keylogin/form.html} lives under
     *  {@code META-INF/templates/}, from which the module system derives no package, so the shell never needs to know
     *  it. It answers only the {@code keylogin/*} namespace, with {@code checkExistence}, so it cannot hijack another
     *  module's view - the collision-proofing {@code SharedShellConfig} documents. */
    @Bean
    public SpringResourceTemplateResolver keyLoginTemplateResolver(ApplicationContext context) {
        return ConsoleTemplates.resolver(context, KeyLoginMechanism.QUALIFIER);
    }

    /** The one implementation of listing, issuing and revoking login keys, which the API and the screen both call. */
    @Bean
    public KeyLogins keyLogins(KeyLoginKeys keys, @Qualifier("rootStorage") Documents rootStorage,
                               Authorization authorization, AuditTrail audit) {
        return new KeyLogins(keys, rootStorage, authorization, audit);
    }

    @Bean
    public KeyLoginController keyLoginController(KeyLogins keyLogins) {
        return new KeyLoginController(keyLogins);
    }

    @Bean
    public KeyLoginScreenController keyLoginScreenController(KeyLogins keyLogins) {
        return new KeyLoginScreenController(keyLogins);
    }
}
