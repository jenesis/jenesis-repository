package build.jenesis.repository.ui.admin.security;

import module java.base;

import build.jenesis.repository.ui.ConsoleAccess;
import build.jenesis.repository.ui.NoAccessRedirect;
import build.jenesis.repository.ui.LoginContributor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;

/**
 * Server-side security for the admin: deny-by-default authorization and Spring Security's CSRF protection (left
 * enabled), with the login <em>mechanism</em> kept out of this chain. This config owns the authorization rules, the
 * entry point and logout, and applies every {@link LoginContributor} bean on the shared {@code HttpSecurity} - so a
 * mechanism (OAuth2/OIDC via {@code OAuth2ClientConfig}, SAML, key-login) plugs its own login in rather than being
 * wired here. Tenant lifecycle ({@code /instances/create}, {@code /delete}, {@code /reclaim}) needs an env
 * super-admin; the per-tenant admin area ({@code /admin/**}) needs admin in the selected tenant; other mutations need
 * editor; the tenant-scoped console reads (the repository and project pages) need viewer in the selected tenant, so an
 * offboarded member with a live session loses read access on the next request rather than keeping it until the session
 * expires; the tenant-agnostic reads (the instance picker, login) need only authentication. The per-tenant decisions
 * are made by {@link TenantAuthorization}
 * against the tenant the session has selected. With no contributor present, login is disabled - the app still starts
 * and shows a "not configured" notice on {@code /login}.
 */
@Configuration
public class AdminSecurityConfig {

    /**
     * The console's chain, scoped to the console's own paths.
     *
     * <p>Named, ordered and matched, which is what {@code scimSecurityFilterChain} already does over
     * {@code /scim/**} and the cache's does over {@code /build/**}. It used to be an unnamed catch-all, and that had
     * two consequences worth stating because neither announced itself. It suppressed the repository chain by
     * type, so on a node carrying both - which this one is, since {@code ui.admin} requires the repository server
     * module and auto-configuration registers its controller here - an artifact request to {@code /repository/**}
     * was governed by a browser-session chain and bounced to {@code /login} rather than authenticated by its key.
     * And two unordered catch-alls in one context have no defined precedence between them, so which governed what
     * was an accident of registration order.
     *
     * <p>The matcher is {@link AdminUrlSpace}, which is checked against the routes this console actually maps -
     * a screen added outside it would otherwise fall to the repository's chain and answer a browser with a keyless
     * {@code 401} and no redirect. The order sits after SCIM's, whose space is narrower, and before the
     * repository's, which carries no matcher because its space cannot be enumerated.
     */
    @Bean
    @Order(2)
    @Profile("!dev")
    public SecurityFilterChain consoleSecurityFilterChain(HttpSecurity http,
                                                   List<LoginContributor> loginContributors,
                                                   TenantAuthorization tenants,
                                                   ConsoleAccess access) throws Exception {
        http
                .securityMatcher(AdminUrlSpace.PATTERNS.toArray(String[]::new))
                .authorizeHttpRequests(auth -> {
                    // The federated login callbacks this chain serves, permitted ahead of the shared matrix since
                    // the first matching rule wins. The dev chain signs in through a form and permits none of them,
                    // which is the whole of the difference between the two matrices.
                    auth.requestMatchers("/oauth2/**", "/saml2/**", "/login/**", "/ui/login/**").permitAll();
                    ConsoleAuthorization.rules(auth, tenants, access);
                })
                .exceptionHandling(e -> e.authenticationEntryPoint(new LoginUrlAuthenticationEntryPoint("/ui/login"))
                        // A refusal for a principal that holds nothing anywhere is not the same event as a refusal
                        // at one screen, and answering both with a 403 would tell a new colleague the deployment is
                        // broken. See NoAccessRedirect for the distinction.
                        .accessDeniedHandler(new NoAccessRedirect(access)))
                .logout(logout -> logout.logoutUrl("/ui/logout").logoutSuccessUrl("/ui/login?logout").permitAll());

        for (LoginContributor contributor : loginContributors) {
            contributor.configure(http);
        }
        // No contributor means nobody can sign in - never that nobody has to. What makes that true is above: every
        // request this chain matches needs authentication, and nothing here enables a credential path, so with no
        // mechanism installed there is simply none to use. These two disables are belt-and-braces rather than the
        // load-bearing part (a manually declared chain does not get form login or basic by default, so removing them
        // changes no behaviour today) - they are kept so that a later edit enabling either one outside a contributor
        // cannot quietly open a way in on a deployment that installed no mechanism. Authentication is relaxed only by
        // an explicit choice - the dev profile, a setting an operator sets - never by a mechanism being absent.
        if (loginContributors.isEmpty()) {
            http.formLogin(AbstractHttpConfigurer::disable);
            http.httpBasic(AbstractHttpConfigurer::disable);
        }
        return http.build();
    }
}
