package build.jenesis.repository.ui.admin.security;

import module java.base;

import build.jenesis.repository.ui.ConsoleAccess;
import build.jenesis.repository.ui.NoAccessRedirect;
import build.jenesis.repository.ui.LoginContributor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import build.jenesis.repository.ui.ConsoleHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;

/**
 * The console's security chain: deny-by-default authorization ({@link ConsoleAuthorization}, per-tenant decisions by
 * {@link TenantAuthorization}) and CSRF protection, owning the rules, the entry point and logout, and applying every
 * {@link LoginContributor} so a mechanism plugs its own login in. With no contributor, nobody can sign in and
 * {@code /login} says so.
 */
@Configuration
public class AdminSecurityConfig {

    /**
     * The console's chain, named, ordered and matched to {@link AdminUrlSpace}, so the repository's unmatched chain
     * still authenticates artifact requests by key and precedence is defined. Ordered after SCIM's narrower space and
     * before the repository's.
     */
    @Bean
    @Order(2)
    @Profile("!dev")
    public SecurityFilterChain consoleSecurityFilterChain(HttpSecurity http,
                                                   List<LoginContributor> loginContributors,
                                                   TenantAuthorization tenants,
                                                   ConsoleAccess access) throws Exception {
        ConsoleHeaders.apply(http)
                .securityMatcher(AdminUrlSpace.PATTERNS.toArray(String[]::new))
                .authorizeHttpRequests(auth -> {
                    // The federated login callbacks, ahead of the shared matrix since the first match wins.
                    auth.requestMatchers("/oauth2/**", "/saml2/**", "/login/**", "/ui/login/**").permitAll();
                    ConsoleAuthorization.rules(auth, tenants, access);
                })
                .exceptionHandling(e -> e.authenticationEntryPoint(new LoginUrlAuthenticationEntryPoint("/ui/login"))
                        .accessDeniedHandler(new NoAccessRedirect(access)))
                .logout(logout -> logout.logoutUrl("/ui/logout").logoutSuccessUrl("/ui/login?logout").permitAll());

        for (LoginContributor contributor : loginContributors) {
            contributor.configure(http);
        }
        // With no mechanism there is no credential path; these disables keep a later edit from opening one.
        if (loginContributors.isEmpty()) {
            http.formLogin(AbstractHttpConfigurer::disable);
            http.httpBasic(AbstractHttpConfigurer::disable);
        }
        return http.build();
    }
}
