package build.jenesis.repository.ui;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.core.annotation.Order;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;

import module java.base;

/**
 * Server-side security for the console: deny-by-default authorization and Spring Security's CSRF protection (left
 * enabled), with the login <em>mechanism</em> kept out of this chain. This config owns the authorization rules, the
 * entry point and logout, and applies every {@link LoginContributor} bean to the shared {@link HttpSecurity} - so a
 * mechanism (OAuth2/OIDC today via {@link OAuth2ClientConfig}, others later) plugs its own login in rather than being
 * wired here. Any write ({@code POST}/{@code PUT}/{@code DELETE}) needs the {@code ADMIN} role; reads need any
 * authenticated user. With no contributor present, login is disabled - the app still starts and shows a "not
 * configured" notice on {@code /login}. The chain is {@code @Profile("!dev")}; the {@code dev} profile's
 * {@link DevSecurityConfig} replaces it with local form login, and a downstream deployment can replace it by
 * contributing its own {@link SecurityFilterChain}.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * The console's chain, scoped to the console's own paths.
     *
     * <p>Named, ordered and matched so it sits BESIDE the repository's rather than replacing it. The repository
     * chain backs off for a bean named {@code securityFilterChain}, so an unnamed catch-all here would take its
     * place - and in a bundle running both, an artifact request would then be governed by a browser-session chain
     * and answered with a redirect to {@code /login} instead of being authenticated by its key. Two unordered
     * catch-alls in one context also have no defined precedence between them, so which governed what would be an
     * accident of registration order.
     *
     * <p>The matcher is {@link ConsoleUrlSpace}, checked against the routes this console actually maps.
     */
    @Bean
    @Order(2)
    @Profile("!dev")
    public SecurityFilterChain consoleSecurityFilterChain(HttpSecurity http,
                                                   ObjectProvider<LoginContributor> loginContributors) throws Exception {
        http
                .securityMatcher(ConsoleUrlSpace.PATTERNS.toArray(String[]::new))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/login", "/error", "/favicon.ico").permitAll()
                        .requestMatchers("/css/**", "/js/**").permitAll()
                        .requestMatchers("/oauth2/**", "/login/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/logout").permitAll()
                        .requestMatchers(HttpMethod.POST, "/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new LoginUrlAuthenticationEntryPoint("/login")))
                .logout(logout -> logout.logoutSuccessUrl("/login?logout").permitAll());

        List<LoginContributor> contributors = loginContributors.orderedStream().toList();
        for (LoginContributor contributor : contributors) {
            contributor.configure(http);
        }
        if (contributors.isEmpty()) {
            http.formLogin(AbstractHttpConfigurer::disable);
            http.httpBasic(AbstractHttpConfigurer::disable);
        }
        return http.build();
    }
}
