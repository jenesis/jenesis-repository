package build.jenesis.repository.ui;

import module java.base;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

/**
 * This console's half of the development sign-in: the URL space it guards, the authorization matrix it applies and
 * the accounts it offers - {@code admin}/{@code admin} (an admin) and {@code viewer}/{@code viewer} (a reader), so
 * both tiers can be exercised without an identity provider.
 *
 * <p>The chain itself is {@link DevConsoleSecurity}, shared with every other console. That is the whole of what is
 * here: this used to declare a chain of its own, and what it had drifted into was a form login pointing at a page
 * with no credential form on it - so nobody could sign in to this console under the dev profile at all.
 */
@Configuration
@Profile("dev")
public class DevSecurityConfig {

    /** Scoped exactly as the production chain is, or dev would prove a topology nothing ships. */
    @Bean
    public DevConsolePolicy devConsolePolicy() {
        return new DevConsolePolicy() {

            @Override
            public List<String> space() {
                return ConsoleUrlSpace.space();
            }

            @Override
            public void rules(org.springframework.security.config.annotation.web.configurers
                                      .AuthorizeHttpRequestsConfigurer<org.springframework.security.config.annotation
                                      .web.builders.HttpSecurity>.AuthorizationManagerRequestMatcherRegistry auth) {
                auth
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/login", "/login/**",
                                "/error", "/favicon.ico").permitAll()
                        .requestMatchers("/css/**", "/js/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/logout").permitAll()
                        .requestMatchers(HttpMethod.POST, "/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/**").hasRole("ADMIN")
                        .anyRequest().authenticated();
            }
        };
    }

    @Bean
    public UserDetailsService devUsers() {
        return new InMemoryUserDetailsManager(
                User.withUsername("admin").password("{noop}admin").roles("USER", "ADMIN").build(),
                User.withUsername("viewer").password("{noop}viewer").roles("USER").build());
    }
}
