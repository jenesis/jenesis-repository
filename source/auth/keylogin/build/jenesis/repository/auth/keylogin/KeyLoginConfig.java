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

/**
 * Wires key-based console sign-in unless {@code jenreg.key-login=false}, and nothing at all when it is, so switching
 * it off leaves the other sign-in chains untouched. It contributes a form-login leg to the shared security chain (a
 * {@link LoginContributor}) backed by {@link KeyLoginAuthenticationProvider}, a "Sign in with a key" option to the
 * login page (a {@link LoginOptions}), the key-entry page, the operator admin API and the {@link FirstRunKey} a
 * deployment nobody can sign in to yet announces when it has started ({@link FirstRunWelcome}). A full-access admin key
 * in the environment is still announced with a WARN. Registering the provider as a bean also stops Boot from
 * auto-creating a default in-memory user.
 */
@Configuration(proxyBeanMethods = false)
@Conditional(KeyLoginConfig.KeyLoginEnabled.class)
public class KeyLoginConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(KeyLoginConfig.class);

    /** True unless {@code jenreg.key-login=false} ({@link KeyLoginMechanism#onByDefault()}): switched off, this
     *  configuration and every bean it declares is absent, so the other sign-in chains are byte-for-byte untouched and
     *  no key is ever accepted. */
    public static class KeyLoginEnabled implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return context.getEnvironment().getProperty(Features.key(KeyLoginMechanism.NAME),
                    Boolean.class, KeyLoginMechanism.onByDefault());
        }
    }

    public KeyLoginConfig(UiProperties properties) {
        LOGGER.info("Key-based console sign-in is on (jenreg.key-login). Switch it off with jenreg.key-login=false "
                + "once single sign-on (OIDC) or a directory (LDAP) signs people in.");
        if (!properties.getAdminKey().isBlank()) {
            LOGGER.warn("SECURITY: a full-access bootstrap admin key is set (JENREG_UI_ADMIN_KEY) - it grants "
                    + "super-admin over every tenant. Use it only to bootstrap, then issue scoped login keys and "
                    + "rotate or remove it.");
        }
    }

    @Bean
    public KeyLoginKeys keyLoginKeys(@Qualifier("rootStorage") Documents rootStorage) {
        return new KeyLoginKeys(rootStorage);
    }

    /**
     * The first-run key, over the same documents the issued keys live in. "Administered" is one bounded page read
     * of the deployment scope's principals and groups - whoever holds a grant there administers the deployment,
     * however it was made - asked at start and at each first-run sign-in, never on a request path.
     */
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
        double permits = environment.getProperty("jenreg.key-login.rate-limit", Double.class, 30.0);
        String tenant = environment.getProperty("jenreg.default-tenant", "default");
        return new KeyLoginAuthenticationProvider(keys, firstRunKey, properties.getAdminKey().trim(), limiter, permits,
                audit, tenant, superadmins::is);
    }

    /** Contribute a form-login leg to the shared chain: the key posts to {@code /login/key}, authenticated by the
     *  key provider. Registering the provider here keeps this mechanism's authentication local to the chain. */
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

    /**
     * Resolves this module's own page templates from the jar it ships in: {@code keylogin/form.html} lives under the
     * module's {@code META-INF/templates/} - a location the module system derives no package from, so requiring the
     * module splits nothing and the shell never needs to know the template. The template travels with the module (the
     * GUI-contract template leg), mirroring {@code SharedShellConfig}'s free-shell resolver in the other direction.
     * The resolver answers ONLY the {@code keylogin/*} namespace (resolvable patterns, not ordering, with
     * {@code checkExistence}) so it can never hijack another module's view and every other name falls through to the
     * shell's own resolvers - exactly the collision-proofing {@code SharedShellConfig} documents.
     */
    @Bean
    public SpringResourceTemplateResolver keyLoginTemplateResolver(ApplicationContext context) {
        return ConsoleTemplates.resolver(context, KeyLoginMechanism.QUALIFIER);
    }

    @Bean
    public KeyLoginController keyLoginController(KeyLoginKeys keys,
                                                @Qualifier("rootStorage") Documents rootStorage,
                                                Authorization authorization, AuditTrail audit) {
        return new KeyLoginController(keys, rootStorage, authorization, audit);
    }
}
