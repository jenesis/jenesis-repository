package build.jenesis.repository.ui;

import module java.base;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenValidator;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.converter.ClaimTypeConverter;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.user.OAuth2User;

/**
 * The OAuth2/OIDC sign-in: GitHub while a client id is configured - read when a sign-in starts, from the console's
 * settings where it provides {@link GithubCredentials}, else from {@code jenrepo.ui.github.*} - and any OpenID Connect
 * provider, discovered once at startup from {@code jenrepo.ui.oidc.issuer-uri} when that issuer and a client id are
 * set. The login is installed whenever this module is, so a provider configured later signs in without a restart; the
 * sign-in page lists only the providers configured at the moment it renders, and says there is none when that is so.
 * Spring Boot's property auto-configuration, which rejects a blank client id, is avoided for the same reason. The login
 * is a {@link LoginContributor} mapping the user to authorities through {@link LoginAuthorities}, the seam that carries
 * a deployment's authority model, after redeeming any {@link AdministratorClaim} the first-run guide made.
 *
 * <p>A console that wants the mechanism optional imports this through its module seam; one that always carries it
 * component-scans it.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({GithubProperties.class, OidcProperties.class})
public class OAuth2ClientConfig {

    @Bean
    public OAuth2PrincipalService oauth2PrincipalService(LoginAuthorities authorities,
                                                         ObjectProvider<ConsoleAdministrators> administrators) {
        return new OAuth2PrincipalService(authorities, administrators.getIfAvailable());
    }

    @Bean
    public OidcPrincipalService oidcPrincipalService(LoginAuthorities authorities,
                                                     ObjectProvider<ConsoleAdministrators> administrators) {
        return new OidcPrincipalService(authorities, administrators.getIfAvailable());
    }

    /** The code-for-token exchange with the provider, over the product's own client ({@link ProviderRequests}). */
    @Bean
    public OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> accessTokenResponseClient() {
        RestClientAuthorizationCodeTokenResponseClient client = new RestClientAuthorizationCodeTokenResponseClient();
        client.setRestClient(ProviderRequests.tokens());
        return client;
    }

    /**
     * The decoder an OIDC id token is checked with, per registration: Spring Security's own - the id-token validators
     * and claim conversions its default factory applies - with the provider's key set fetched over the product's
     * client, which the default factory has no way to be told.
     */
    @Bean
    public JwtDecoderFactory<ClientRegistration> idTokenDecoderFactory() {
        Map<String, JwtDecoder> decoders = new ConcurrentHashMap<>();
        return registration -> decoders.computeIfAbsent(registration.getRegistrationId(), _ -> {
            NimbusJwtDecoder decoder = NimbusJwtDecoder
                    .withJwkSetUri(registration.getProviderDetails().getJwkSetUri())
                    .restOperations(ProviderRequests.rest())
                    .build();
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(),
                    new OidcIdTokenValidator(registration)));
            decoder.setClaimSetConverter(
                    new ClaimTypeConverter(OidcIdTokenDecoderFactory.createDefaultClaimTypeConverters()));
            return decoder;
        });
    }

    /** The OIDC/GitHub login, contributed to the core chain when a provider is configured. */
    @Bean
    public LoginContributor oauth2LoginContributor(OAuth2PrincipalService oauth2Users, OidcPrincipalService oidcUsers,
                                                   OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest>
                                                           tokens) {
        return http -> http.oauth2Login(oauth -> oauth
                .loginPage("/ui/login")
                .tokenEndpoint(token -> token.accessTokenResponseClient(tokens))
                .userInfoEndpoint(userInfo -> userInfo
                        .userService(oauth2Users)
                        .oidcUserService(oidcUsers))
                .defaultSuccessUrl("/ui/", true));
    }

    @Bean
    public ClientRegistrationRepository clientRegistrationRepository(GithubProperties github, OidcProperties oidc,
                                                                     ObjectProvider<GithubCredentials> credentials) {
        // openid selects the id-token flow and the qualified oidc/<sub> principal and is required before UserInfo
        // answers; profile and email give the display name and member list.
        return new LiveClientRegistrations(credentials.getIfAvailable(() -> new GithubCredentials() {
            @Override
            public String clientId() {
                return github.getClientId();
            }

            @Override
            public String clientSecret() {
                return github.getClientSecret();
            }
        }), ConsoleClientRegistrations.oidc(oidc.getIssuerUri(), oidc.getClientId(), oidc.getClientSecret(),
                oidc.getName(), List.of("openid", "profile", "email")));
    }

    /** The sign-in buttons for the login page, one per configured registration. */
    @Bean
    public LoginOptions oauth2LoginOptions(ClientRegistrationRepository registrations) {
        return () -> {
            List<LoginOptions.LoginOption> options = new ArrayList<>();
            if (registrations instanceof Iterable<?> available) {
                for (Object entry : available) {
                    ClientRegistration registration = (ClientRegistration) entry;
                    // No mark: the registration id is operator-chosen, so the figure is generated from it.
                    options.add(new LoginOptions.LoginOption(registration.getRegistrationId(),
                            registration.getClientName(),
                            "/oauth2/authorization/" + registration.getRegistrationId(),
                            Optional.empty()));
                }
            }
            return options;
        };
    }

    /** The display name of an OAuth2/OIDC principal, so a layout needs no OAuth2 type. Unconditional, since it answers
     *  empty for any other principal. */
    @Bean
    public PrincipalNameResolver oauth2PrincipalNameResolver() {
        return authentication -> authentication.getPrincipal() instanceof OAuth2User user
                ? Optional.of(ProviderPrincipal.displayName(user.getAttributes())).filter(name -> !name.isBlank())
                : Optional.empty();
    }
}
