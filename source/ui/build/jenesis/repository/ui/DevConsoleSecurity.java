package build.jenesis.repository.ui;

import module java.base;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The development profile's sign-in, one chain for every console: a credential form at {@link #PATH}, HTTP basic
 * beside it, scoped and authorized by the console's own {@link DevConsolePolicy}, and a guard refusing a routable bind,
 * since the profile enables in-memory accounts with known passwords. Active only under {@code dev}; the production
 * chains are {@code @Profile("!dev")}. The form is a sign-in mechanism with its own page and {@link LoginOptions}
 * entry, like any other.
 */
@Configuration
@Profile("dev")
public class DevConsoleSecurity {

    /** Where the dev form is served and where it posts - the same URL, as Spring Security's form login expects. */
    public static final String PATH = "/ui/login/dev";

    /** The dev chain, scoped and authorized by the edition's policy, refusing as the production chain refuses, and
     *  identical in every other respect. It names its own accounts: a sign-in mechanism that contributes an
     *  {@code AuthenticationProvider} bean - key sign-in does - becomes the global manager's only provider, and a
     *  form leaning on the global manager would then refuse every one of them. */
    @Bean
    @Order(2)
    public SecurityFilterChain devSecurityFilterChain(HttpSecurity http, DevConsolePolicy policy, ConsoleAccess access,
                                                      UserDetailsService accounts) throws Exception {
        return ConsoleHeaders.apply(http)
                .securityMatcher(policy.space().toArray(String[]::new))
                .userDetailsService(accounts)
                .authorizeHttpRequests(policy::rules)
                .exceptionHandling(exceptions -> exceptions.accessDeniedHandler(new NoAccessRedirect(access)))
                .formLogin(form -> form
                        .loginPage(PATH)
                        .loginProcessingUrl(PATH)
                        .defaultSuccessUrl("/ui/", true)
                        .failureUrl(PATH + "?error")
                        .permitAll())
                .httpBasic(Customizer.withDefaults())
                .logout(logout -> logout.logoutUrl("/ui/logout").logoutSuccessUrl("/ui/login?logout").permitAll())
                .build();
    }

    @Bean
    public DevLoginController devLoginController() {
        return new DevLoginController();
    }

    /** The "a username and password" entry on the shared sign-in page. */
    @Bean
    public LoginOptions devLoginOptions() {
        return () -> List.of(new LoginOptions.LoginOption("dev", "a username and password", PATH,
                Optional.empty()));
    }

    /** Marker for the {@link #devLoopbackGuard} bean; its construction validating the bind is the guard. */
    public record DevLoopbackGuard() {
    }

    /**
     * Fails the boot when the dev profile is asked to bind a non-loopback {@code server.address}; empty keeps the
     * loopback default. A non-lazy singleton, so it runs during context refresh, before the web server binds.
     */
    @Bean
    public DevLoopbackGuard devLoopbackGuard(Environment environment) throws UnknownHostException {
        verifyLoopback(environment.getProperty("server.address", ""));
        return new DevLoopbackGuard();
    }

    /** Throw if {@code address} names a non-loopback interface. Public for a direct unit test of the check. */
    public static void verifyLoopback(String address) throws UnknownHostException {
        String trimmed = address == null ? "" : address.trim();
        if (!trimmed.isEmpty() && !InetAddress.getByName(trimmed).isLoopbackAddress()) {
            throw new IllegalStateException("The 'dev' profile enables in-memory accounts with published passwords "
                    + "and must not bind a non-loopback address (server.address=" + trimmed + "); bind a loopback "
                    + "address (127.0.0.1) for local dev, or run the production (non-dev) profile.");
        }
    }
}
