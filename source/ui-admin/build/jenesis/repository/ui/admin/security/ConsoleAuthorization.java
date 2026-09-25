package build.jenesis.repository.ui.admin.security;

import build.jenesis.repository.ui.identity.UserDirectory;
import build.jenesis.repository.ui.ConsoleAccess;
import build.jenesis.repository.ui.ConsoleAccessRule;
import build.jenesis.repository.ui.ConsoleUrlSpace;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;

/**
 * The console's authorization matrix: who may reach what, declared once for every chain that serves the console.
 *
 * <p>It used to be declared twice - the production chain and the dev-profile chain each spelled out all sixteen
 * rules - with three comments instructing the reader to "keep the dev chain's matrix identical", and those comments
 * citing a class that had since been renamed. That is the shape a security rule set must not have: a copy kept in
 * step by prose is a copy that will drift, and the direction it drifts is whichever one somebody forgets to update.
 * The dev chain exists to run the same topology a deployment runs, so a dev chain that has quietly relaxed a rule is
 * worse than no dev chain at all - it proves a permission model nothing ships.
 *
 * <p>The floor at the bottom is {@link ConsoleAccess}, not {@code authenticated()}. Sign-in succeeds for anyone the
 * identity provider authenticates, so being signed in says nothing about what may be seen; the membership check that
 * used to refuse the sign-in itself is what that floor asks, once per request rather than once per session, so a
 * revoked membership closes the console on the next click.
 *
 * <p>What a caller still decides is what to permit <em>before</em> these rules, since the first matching rule wins:
 * the production chain permits the federated login callbacks it serves ({@code /oauth2/**}, {@code /saml2/**},
 * {@code /login/**}) and the dev chain, which signs in through a form, does not. Everything after that is here.
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
                // The destination of the floor at the bottom of this matrix, so it cannot itself be behind it.
                .requestMatchers("/ui/no-access").authenticated()
                .requestMatchers("/ui/instances/create", "/ui/instances/delete", "/ui/instances/reclaim").hasRole("SUPERADMIN")
                // Tenant-agnostic - the picker is how a tenant gets selected, so it cannot require one to be
                // selected already - but NOT merely authenticated. It is the console's front door, and a principal
                // that is a member nowhere must meet the floor here as it does everywhere else; a rule ahead of
                // anyRequest() overrides it, so the floor has to be spelled out rather than inherited.
                .requestMatchers("/ui/instances", "/ui/instances/select")
                        .access(ConsoleAccessRule.holdsSomething(access))
                .requestMatchers("/ui/setup", "/ui/setup/**").hasRole("SUPERADMIN")
                .requestMatchers("/ui/settings", "/ui/settings/**").hasRole("SUPERADMIN")
                // The walks document is deployment-wide, like the settings it is one of, and a walk it schedules
                // reads every tenant's store: the same floor, the form included, so it never reads as available
                // to someone who may not submit it.
                .requestMatchers("/ui/walks", "/ui/walks/**").hasRole("SUPERADMIN")
                // Screens that moved to the base module keep the floor they had under /settings/**: a shared screen
                // must not become easier to reach by moving, and the route no longer carries the prefix that gated
                // it. Named one by one so a move is a deliberate decision about access, not a side effect.
                .requestMatchers("/ui/observability", "/ui/catalog", "/ui/posture").hasRole("SUPERADMIN")
                .requestMatchers("/ui/admin/**").access(tenants.require(UserDirectory.Role.ADMIN))
                // Credential administration (minting keys, OIDC trusts, the lifetime policy) and the
                // deployment/tenant quota + rate-limit levers are admin-grade, matching the API's manage:write
                // scope: an EDITOR must not mint an admin-scoped key or add a trust that exchanges to admin, nor
                // relax a quota/rate-limit, so these sit above the generic editor mutation gate.
                .requestMatchers("/ui/credentials/**").access(tenants.require(UserDirectory.Role.ADMIN))
                // Publishing from the console writes into the tenant's repositories through the same ingress edge
                // a build tool uses, so it sits with the admin-grade acts rather than under the generic editor
                // mutation gate below - and the GET is gated too, because a form nobody may submit is a control
                // that reads as available. The module is off unless an operator switches it on; this is what the
                // route means once it exists.
                .requestMatchers("/ui/deploy").access(tenants.require(UserDirectory.Role.ADMIN))
                .requestMatchers(HttpMethod.POST, "/ui/repositories/quota", "/ui/repositories/rate-limit")
                        .access(tenants.require(UserDirectory.Role.ADMIN))
                // Deleting a repository removes everything it holds, for everyone - admin-grade, like the limits.
                .requestMatchers(HttpMethod.POST, "/ui/repositories/*/delete")
                        .access(tenants.require(UserDirectory.Role.ADMIN))
                // Cache eviction (enforce the size cap, expire stale entries, or clear a project entirely) is
                // admin-grade: clearing a project wipes its cache for everyone, which an EDITOR must not do.
                .requestMatchers(HttpMethod.POST, "/ui/projects/*/evict/**")
                        .access(tenants.require(UserDirectory.Role.ADMIN))
                .requestMatchers(HttpMethod.POST, "/**").access(tenants.require(UserDirectory.Role.EDITOR))
                .requestMatchers(HttpMethod.PUT, "/**").access(tenants.require(UserDirectory.Role.EDITOR))
                .requestMatchers(HttpMethod.DELETE, "/**").access(tenants.require(UserDirectory.Role.EDITOR))
                // Tenant-scoped console reads expose the selected tenant's data (the repository browse/artifact/
                // vulnerabilities/findings/licenses panels and the project cache pages), so they need membership in
                // that tenant, not merely a live session - otherwise an offboarded user keeps read access to the
                // selected tenant until the session expires. Gating them on VIEWER routes the decision through
                // TenantAuthorization, so the request-scoped MembershipCache sees a revoked grant on the next
                // request and fails closed. The tenant-agnostic reads matched above (the instance picker, login)
                // stay on plain authentication.
                .requestMatchers(HttpMethod.GET, "/ui/repositories", "/ui/repositories/**", "/ui/projects", "/ui/projects/**")
                        .access(tenants.require(UserDirectory.Role.VIEWER))
                // The floor, and it is not merely "signed in". Sign-in succeeds for anyone the identity provider
                // authenticates - that is deliberate, and it is why the membership check that used to refuse a
                // sign-in lives here now. A principal that is a member nowhere reaches nothing and is sent to
                // /no-access; the tenant-agnostic reads above (the instance picker) sit on this floor too, so an
                // empty instance list is a screen a member sees, never the whole console a stranger gets.
                .anyRequest().access(ConsoleAccessRule.holdsSomething(access));
    }
}
