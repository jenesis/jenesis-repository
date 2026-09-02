package build.jenesis.repository.ui;

import module java.base;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.user.OAuth2User;

/**
 * Builds the OAuth2/OIDC client registrations from configuration: the built-in GitHub provider when
 * {@code jenreg.ui.github.client-id} is set, and a generic OpenID Connect provider - its endpoints and JWK set
 * discovered from {@code jenreg.ui.oidc.issuer-uri} - when that issuer and a client id are set (so any OIDC identity
 * provider, e.g. Google, Keycloak, Okta, Azure AD, works). Every bean here exists only when at least one provider is
 * configured, so the app still starts with login disabled ({@link ConsoleSecurityConfig} shows a notice) rather than failing,
 * and Spring Boot's property auto-configuration, which rejects a blank client id, is avoided. Discovery makes a network
 * call to the issuer at startup. The login is contributed to the chain as a {@link LoginContributor}, mapping the
 * signed-in user to authorities through {@link LoginAuthorities} - which is the seam that lets one wiring serve
 * both consoles, since the policy is what differed between them and not the mechanism.
 *
 * <p>This existed twice, once here and once as an enterprise module, with the condition and the contributor
 * byte-identical and the rest differing only in which properties class bound {@code jenreg.ui.github.*} and
 * {@code jenreg.ui.oidc.*} - two bindings of one documented key, with identical fields and identical defaults.
 * A console that wants the mechanism optional imports this through its module seam; one that always carries it
 * component-scans it. Both get the same beans.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({GithubProperties.class, OidcProperties.class})
public class OAuth2ClientConfig {

    /** True when GitHub or OIDC is configured (a non-blank client id, and for OIDC an issuer too). */
    public static class AnyProviderConfigured implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return configured(context, "jenreg.ui.github.client-id")
                    || (configured(context, "jenreg.ui.oidc.issuer-uri")
                    && configured(context, "jenreg.ui.oidc.client-id"));
        }

        private static boolean configured(ConditionContext context, String key) {
            String value = context.getEnvironment().getProperty(key, "");
            return value != null && !value.isBlank();
        }
    }

    @Bean
    @Conditional(AnyProviderConfigured.class)
    public OAuth2PrincipalService oauth2PrincipalService(LoginAuthorities authorities) {
        return new OAuth2PrincipalService(authorities);
    }

    @Bean
    @Conditional(AnyProviderConfigured.class)
    public OidcPrincipalService oidcPrincipalService(LoginAuthorities authorities) {
        return new OidcPrincipalService(authorities);
    }

    /** The OIDC/GitHub login, contributed to the core chain when a provider is configured. */
    @Bean
    @Conditional(AnyProviderConfigured.class)
    public LoginContributor oauth2LoginContributor(OAuth2PrincipalService oauth2Users, OidcPrincipalService oidcUsers) {
        return http -> http.oauth2Login(oauth -> oauth
                .loginPage("/login")
                .userInfoEndpoint(userInfo -> userInfo
                        .userService(oauth2Users)
                        .oidcUserService(oidcUsers))
                .defaultSuccessUrl("/console", true));
    }

    @Bean
    @Conditional(AnyProviderConfigured.class)
    public ClientRegistrationRepository clientRegistrationRepository(GithubProperties github, OidcProperties oidc) {
        // The standard OpenID Connect scopes. openid marks this an OIDC (not plain OAuth2) login so the id-token
        // flow and the qualified oidc/<sub> principal are used, and it is what a spec-compliant provider (e.g.
        // Keycloak) requires before its UserInfo endpoint will answer; profile and email are what a console renders
        // a display name and a member list from. This console asked for openid alone while the admin console asked
        // for all three, on the argument that it rendered no name - which was true only because it read
        // Authentication.getName() and showed a qualified id where the other showed a person.
        return new InMemoryClientRegistrationRepository(Stream.of(
                        ConsoleClientRegistrations.github(github.getClientId(), github.getClientSecret()),
                        ConsoleClientRegistrations.oidc(oidc.getIssuerUri(), oidc.getClientId(),
                                oidc.getClientSecret(), oidc.getName(),
                                List.of("openid", "profile", "email")))
                .flatMap(Optional::stream)
                .toList());
    }

    /** The sign-in buttons for the login page, one per configured registration. */
    @Bean
    @Conditional(AnyProviderConfigured.class)
    public LoginOptions oauth2LoginOptions(ClientRegistrationRepository registrations) {
        return () -> {
            List<LoginOptions.LoginOption> options = new ArrayList<>();
            if (registrations instanceof Iterable<?> available) {
                for (Object entry : available) {
                    ClientRegistration registration = (ClientRegistration) entry;
                    // No mark: the registration id is chosen by whoever configured the client, so there is
                    // nothing here to look a drawing up by. The figure computed from that id is the honest answer.
                    options.add(new LoginOptions.LoginOption(registration.getRegistrationId(),
                            registration.getClientName(),
                            "/oauth2/authorization/" + registration.getRegistrationId(),
                            Optional.empty()));
                }
            }
            return options;
        };
    }

    /** The display name for an OAuth2/OIDC principal, so a layout greets the user without importing any OAuth2
     *  type. Unconditional: it answers empty for a principal of any other kind, which is what makes it safe to
     *  install beside a mechanism that is not configured. */
    @Bean
    public PrincipalNameResolver oauth2PrincipalNameResolver() {
        return authentication -> authentication.getPrincipal() instanceof OAuth2User user
                ? Optional.of(ProviderPrincipal.displayName(user.getAttributes())).filter(name -> !name.isBlank())
                : Optional.empty();
    }
}
