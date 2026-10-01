package build.jenesis.repository.ui.admin.security;

import module java.base;
import build.jenesis.repository.ui.identity.UserDirectory;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.ui.ConsoleAccess;
import build.jenesis.repository.ui.DevConsolePolicy;
import build.jenesis.repository.ui.store.TenantService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import build.jenesis.repository.scope.Scopes;

/**
 * This console's half of the development sign-in: the URL space it guards, the authorization matrix it applies, the
 * accounts it offers - {@code root}/{@code root} (env super-admin: all tenants, tenant lifecycle) and
 * {@code admin}/{@code admin}, {@code editor}/{@code editor}, {@code viewer}/{@code viewer} (members of a seeded
 * {@code default} tenant at the matching role) - and the tenant that gives them somewhere to be members of.
 *
 * <p>The chain, the credential form and the loopback guard are the shared
 * {@link build.jenesis.repository.ui.DevConsoleSecurity}.
 */
@Configuration
@Profile("dev")
public class DevSecurityConfig {

    /** Scoped and authorized exactly as the production chain. */
    @Bean
    public DevConsolePolicy devConsolePolicy(TenantAuthorization tenants, ConsoleAccess access) {
        return new DevConsolePolicy() {

            @Override
            public List<String> space() {
                return AdminUrlSpace.PATTERNS;
            }

            /** The production chain's matrix. */
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

    /** Seeds the tenant this deployment serves ({@code jenrepo.default-tenant}, else {@link Scopes#DEFAULT_TENANT})
     *  with the dev accounts as members, keyed by username. */
    @Bean
    public ApplicationRunner devTenantSeed(Authorization authorization, TenantService tenants,
            @Value("${jenrepo.default-tenant:" + Scopes.DEFAULT_TENANT + "}") String tenant) {
        return _ -> {
            if (!tenants.exists(tenant)) {
                tenants.create(tenant);
            }
            UserDirectory directory = new UserDirectory(authorization, tenant);
            directory.put("admin", UserDirectory.Role.ADMIN, "admin");
            directory.put("editor", UserDirectory.Role.EDITOR, "editor");
            directory.put("viewer", UserDirectory.Role.VIEWER, "viewer");
        };
    }
}
