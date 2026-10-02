package build.jenesis.repository.ui.admin.security;

import build.jenesis.repository.ui.identity.UserDirectory;
import build.jenesis.repository.ui.ConsoleAccess;
import build.jenesis.repository.ui.ConsoleAccessRule;
import build.jenesis.repository.ui.ConsoleUrlSpace;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;

/**
 * The console's authorization matrix, declared once for the production and the dev-profile chain, so the dev chain
 * runs the permission model a deployment ships. The floor is {@link ConsoleAccess}, asked per request, so a revoked
 * membership closes the console on the next click. A caller decides only what it permits ahead of these rules: the
 * production chain's federated login callbacks.
 */
final class ConsoleAuthorization {

    private ConsoleAuthorization() {
    }

    /**
     * Apply the matrix to {@code auth}. {@code tenants} decides the per-tenant roles, so a revoked grant is seen on
     * the next request rather than at the end of a session.
     */
    static void rules(AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry auth,
                      TenantAuthorization tenants, ConsoleAccess access) {
        auth
                .requestMatchers(ConsoleUrlSpace.ANONYMOUS.toArray(String[]::new)).permitAll()
                .requestMatchers(HttpMethod.POST, "/ui/logout").permitAll()
                // Where the floor sends a refusal, so it cannot be behind the floor.
                .requestMatchers("/ui/no-access").authenticated()
                .requestMatchers("/ui/tenants/create", "/ui/tenants/delete").hasRole("SUPERADMIN")
                // The reclaim sweeps every tenant's projects.
                .requestMatchers("/ui/projects/cache-volume").hasRole("SUPERADMIN")
                .requestMatchers(HttpMethod.POST, "/ui/projects/volume-reclaim").hasRole("SUPERADMIN")
                // The picker needs no selected tenant but does need the floor, which a rule ahead of anyRequest() does
                // not inherit.
                .requestMatchers("/ui/tenants", "/ui/tenants/select")
                        .access(ConsoleAccessRule.holdsSomething(access))
                .requestMatchers("/ui/setup", "/ui/setup/**").hasRole("SUPERADMIN")
                .requestMatchers("/ui/settings", "/ui/settings/**").hasRole("SUPERADMIN")
                // Walks are deployment-wide settings reading every tenant's store; the form is gated with them.
                .requestMatchers("/ui/walks", "/ui/walks/**").hasRole("SUPERADMIN")
                // Deployment-wide screens outside /settings/**, named one by one.
                .requestMatchers("/ui/metrics", "/ui/posture").hasRole("SUPERADMIN")
                // The caches belong to no tenant.
                .requestMatchers("/ui/caches", "/ui/caches/**").hasRole("SUPERADMIN")
                .requestMatchers("/ui/admin/**").access(tenants.require(UserDirectory.Role.ADMIN))
                // Credential administration and the limits are admin-grade, as the API's manage:write: an editor
                // must not mint an admin key, add a trust exchanging to admin, or relax a limit.
                .requestMatchers("/ui/credentials/**").access(tenants.require(UserDirectory.Role.ADMIN))
                // Publishing from the console is admin-grade, the form included.
                .requestMatchers("/ui/repositories/*/deploy").access(tenants.require(UserDirectory.Role.ADMIN))
                .requestMatchers(HttpMethod.POST, "/ui/limits/**")
                        .access(tenants.require(UserDirectory.Role.ADMIN))
                // Deleting a repository removes everything it holds.
                .requestMatchers(HttpMethod.POST, "/ui/repositories/*/delete")
                        .access(tenants.require(UserDirectory.Role.ADMIN))
                // Eviction can clear a project's whole cache.
                .requestMatchers(HttpMethod.POST, "/ui/projects/*/evict/**")
                        .access(tenants.require(UserDirectory.Role.ADMIN))
                // Deleting a project removes its entries and settings.
                .requestMatchers(HttpMethod.POST, "/ui/projects/*/delete")
                        .access(tenants.require(UserDirectory.Role.ADMIN))
                // A wizard creates, so even its first page is an editor's.
                .requestMatchers("/ui/new/repository", "/ui/new/project")
                        .access(tenants.require(UserDirectory.Role.EDITOR))
                .requestMatchers(HttpMethod.POST, "/**").access(tenants.require(UserDirectory.Role.EDITOR))
                .requestMatchers(HttpMethod.PUT, "/**").access(tenants.require(UserDirectory.Role.EDITOR))
                .requestMatchers(HttpMethod.DELETE, "/**").access(tenants.require(UserDirectory.Role.EDITOR))
                // Tenant-scoped reads need membership of the selected tenant, decided per request, so an offboarded
                // user loses access on the next request.
                .requestMatchers(HttpMethod.GET, "/ui/repositories", "/ui/repositories/**", "/ui/limits",
                        "/ui/projects", "/ui/projects/**")
                        .access(tenants.require(UserDirectory.Role.VIEWER))
                // The floor: a principal holding nothing anywhere reaches nothing and is sent to /no-access.
                .anyRequest().access(ConsoleAccessRule.holdsSomething(access));
    }
}
