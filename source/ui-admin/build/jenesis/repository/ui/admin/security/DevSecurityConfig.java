package build.jenesis.repository.ui.admin.security;

import module java.base;
import build.jenesis.repository.ui.identity.UserDirectory;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.ui.ConsoleAccess;
import build.jenesis.repository.ui.DevConsolePolicy;
import build.jenesis.repository.ui.store.TenantService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

/**
 * This console's half of the development sign-in: the URL space it guards, the authorization matrix it applies, the
 * accounts it offers - {@code root}/{@code root} (env super-admin: all tenants, tenant lifecycle) and
 * {@code admin}/{@code admin}, {@code editor}/{@code editor}, {@code viewer}/{@code viewer} (members of a seeded
 * {@code default} tenant at the matching role) - and the tenant that gives them somewhere to be members of.
 *
 * <p>The chain itself, the credential form and the loopback guard are {@link build.jenesis.repository.ui
 * .DevConsoleSecurity}, shared with every other console. This used to declare all three, and what it had drifted
 * into was Spring Security's <em>generated</em> login page: a second sign-in page nobody designed, that no console
 * styling reaches, and that made this console sign in a different way from the other one.
 */
@Configuration
@Profile("dev")
public class DevSecurityConfig {

    /** Scoped exactly as the production chain is, or dev would prove a topology nothing ships. */
    @Bean
    public DevConsolePolicy devConsolePolicy(TenantAuthorization tenants, ConsoleAccess access) {
        return new DevConsolePolicy() {

            @Override
            public List<String> space() {
                return AdminUrlSpace.PATTERNS;
            }

            /** The same matrix the production chain applies, from the one place it is declared - not a copy kept in
             *  step by a comment. A dev chain that has quietly relaxed a rule proves a topology nothing ships. */
            @Override
            public void rules(AuthorizeHttpRequestsConfigurer<HttpSecurity>
                                      .AuthorizationManagerRequestMatcherRegistry auth) {
                ConsoleAuthorization.rules(auth, tenants, access);
            }
        };
    }

    @Bean
    public UserDetailsService devUsers() {
        return new InMemoryUserDetailsManager(
                User.withUsername("root").password("{noop}root").roles("USER", "SUPERADMIN").build(),
                User.withUsername("admin").password("{noop}admin").roles("USER").build(),
                User.withUsername("editor").password("{noop}editor").roles("USER").build(),
                User.withUsername("viewer").password("{noop}viewer").roles("USER").build());
    }

    /** Seed a {@code default} tenant whose members are the dev admin/editor/viewer accounts (keyed by username). */
    @Bean
    public ApplicationRunner devTenantSeed(Authorization authorization, TenantService tenants) {
        return _ -> {
            if (!tenants.exists("default")) {
                tenants.create("default");
            }
            UserDirectory directory = new UserDirectory(authorization, "default");
            directory.put("admin", UserDirectory.Role.ADMIN, "admin");
            directory.put("editor", UserDirectory.Role.EDITOR, "editor");
            directory.put("viewer", UserDirectory.Role.VIEWER, "viewer");
        };
    }
}
