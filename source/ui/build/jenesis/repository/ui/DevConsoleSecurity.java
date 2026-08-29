package build.jenesis.repository.ui;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

import module java.base;

/**
 * The development profile's sign-in, for every console: a credential form at {@code /login/dev}, HTTP basic beside
 * it, and a chain scoped and authorized by the edition's own {@link DevConsolePolicy}. Active only under the
 * {@code dev} profile; the production chains are {@code @Profile("!dev")} and are untouched by this.
 *
 * <h2>One chain, because two had drifted</h2>
 *
 * <p>Each console used to declare its own. They agreed on nothing that mattered and nobody could see it, because
 * the differences were all in the dev profile: form login pointed at a page with no form on it in one and at
 * Spring Security's generated page in the other, and the loopback guard was on one of them. The guard is the sharp
 * one - a dev profile enables in-memory accounts with known passwords, and both do, so binding either to a routable
 * interface is the same mistake.
 *
 * <h2>The form is a mechanism, not a special case</h2>
 *
 * <p>The shared sign-in page lists what the installed mechanisms offer and links into the URL space each owns. So
 * the dev credential form gets its own page and its own {@link LoginOptions} entry, exactly as key login does,
 * rather than a form spliced into a page whose job is to be neutral between mechanisms.
 */
@Configuration
@Profile("dev")
public class DevConsoleSecurity {

    /** Where the dev form is served and where it posts - the same URL, as Spring Security's form login expects. */
    public static final String PATH = "/login/dev";

    /** The dev chain, scoped and authorized by the edition's policy and identical in every other respect. */
    @Bean
    @Order(2)
    public SecurityFilterChain devSecurityFilterChain(HttpSecurity http, DevConsolePolicy policy) throws Exception {
        return http
                .securityMatcher(policy.space().toArray(String[]::new))
                .authorizeHttpRequests(policy::rules)
                .formLogin(form -> form
                        .loginPage(PATH)
                        .loginProcessingUrl(PATH)
                        .defaultSuccessUrl("/console", true)
                        .failureUrl(PATH + "?error")
                        .permitAll())
                .httpBasic(Customizer.withDefaults())
                .logout(logout -> logout.logoutSuccessUrl("/login?logout").permitAll())
                .build();
    }

    @Bean
    public DevLoginController devLoginController() {
        return new DevLoginController();
    }

    /** The "a username and password" entry on the shared sign-in page. */
    @Bean
    public LoginOptions devLoginOptions() {
        return () -> List.of(new LoginOptions.LoginOption("dev", "a username and password", PATH));
    }

    /** Marker for the {@link #devLoopbackGuard} bean; its construction validating the bind is the guard. */
    public record DevLoopbackGuard() {
    }

    /**
     * Fail the boot fast if the dev profile is asked to bind a non-loopback address. This chain enables in-memory
     * accounts with published passwords, so it must never be network-reachable. A dev boot defaults
     * {@code server.address} to loopback; this refuses to start if that default is overridden to a routable
     * interface - the operator ran the dev profile where only the production chain belongs. The bean is a non-lazy
     * singleton, so it is constructed during context refresh before the embedded web server binds its port, and the
     * throw aborts the boot pre-bind. An empty {@code server.address} is left to the loopback default.
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
