package build.jenesis.repository.server;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.server.spi.RateLimiter;
import build.jenesis.repository.store.Features;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Server-side security for the repository as auto-configuration: stateless, deny-by-default authorization
 * delegated to the {@link RepositoryAuthorizationManager} (a pass-through when the deployment is anonymous), with the
 * Actuator health endpoint left open for liveness/readiness probes. The {@link KeyAuthenticationFilter} runs first to
 * lift the presented key ({@link PresentedKey}) into the security context. CSRF, HTTP Basic and form login are
 * disabled - this is a machine-to-machine artifact API keyed by a header, not a browser session. Both the
 * authentication entry point and the access-denied handler are the {@link RepositoryAuthorizationEntryPoint}, so a
 * denied request answers the status the credential model intends ({@code 401} unauthorized, {@code 403} forbidden)
 * whichever Spring Security failure path it takes.
 *
 * <p>The chain is a <em>composition seam</em>, not a fixed chain. The authorization manager, the {@link RateLimitFilter}
 * and the chain itself are {@link ConditionalOnMissingBean conditional}, and every discovered
 * {@link SecurityChainCustomizer} is applied over the baseline before the {@code anyRequest} catch-all is registered.
 * So a deployment that needs a richer authorization manager (multi-tenant scoping, an operator-tenant check, usage
 * recording), extra open routes (a console page, a self-authenticating webhook, an OIDC token endpoint) or an extra
 * filter (a request-body cap) contributes them as beans and rides <em>this</em> chain - reusing the shared
 * {@link KeyAuthenticationFilter}, {@link RateLimitFilter} and {@link Authorization} credential model - rather than
 * excluding this auto-configuration and forking the whole chain.
 */
@AutoConfiguration
@EnableWebSecurity
public class RepositorySecurityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "repositoryAuthorizationManager")
    public RepositoryAuthorizationManager repositoryAuthorizationManager(Authorization authorization,
                                                                         RepositoryRouting routing) {
        return new RepositoryAuthorizationManager(authorization, routing);
    }

    @Bean
    @ConditionalOnMissingBean
    public AuthFailures authFailures() {
        // A registry-free accessor seam: the key entry point (and the console's OIDC/SAML login failure handlers)
        // record denials here, and a metrics layer scrapes them into jenreg.auth.failures.
        return new AuthFailures();
    }

    @Bean
    @ConditionalOnMissingBean
    public RateLimitFilter rateLimitFilter(RateLimiter rateLimiter, Authorization authorization,
                                           RepositoryProperties properties) {
        // A bean (not an inline filter) so a metrics layer can scrape the same instance the chain sheds load with.
        // The default ceiling is read live, so the rate-limit setting an operator writes at runtime is honoured.
        return new RateLimitFilter(rateLimiter, authorization, RateLimitFilter.liveDefault(Features.lookup(), properties.getRateLimit()));
    }

    /**
     * The repository's chain, backed off only by a bean of THIS NAME.
     *
     * <p>By name, not by type, and the distinction is the whole point. A bare {@code @ConditionalOnMissingBean}
     * matches the method's return type, so ANY {@link SecurityFilterChain} in the context suppressed this one -
     * including the ordered, path-matched chains that plainly exist to sit BESIDE it. SCIM's
     * {@code scimSecurityFilterChain} is {@code @Order(1)} over {@code /scim/**} and the cache's is the same shape
     * over {@code /cache/**}: neither is a replacement for the artifact chain, and both silently were one.
     *
     * <p>What that cost is a node that maps {@code /repository/**} and answers it from a browser-session chain.
     * The admin console requires this module, so auto-configuration registers the repository's controller into its
     * context - and with this chain suppressed by the console's own, an artifact request there was bounced to
     * {@code /login} instead of being authenticated by its key.
     *
     * <p>Replacing this chain outright is still available, and is still how a fork-free deployment does it: define
     * a bean named {@code securityFilterChain}. Contributing to it - extra open routes, an extra filter, a richer
     * authorization manager - needs no replacement at all and rides the {@link SecurityChainCustomizer} seam, which
     * is what the class comment above recommends and what enterprise actually does.
     */
    @Bean
    @ConditionalOnMissingBean(name = "securityFilterChain")
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   @Qualifier("repositoryAuthorizationManager")
                                                   AuthorizationManager<RequestAuthorizationContext> authorizationManager,
                                                   RateLimitFilter rateLimitFilter,
                                                   AuthFailures authFailures,
                                                   ObjectProvider<SecurityChainCustomizer> customizers)
            throws Exception {
        RepositoryAuthorizationEntryPoint entryPoint = new RepositoryAuthorizationEntryPoint(authFailures);
        http
                .csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(entryPoint))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll())
                .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new KeyAuthenticationFilter(), UsernamePasswordAuthenticationFilter.class);
        // The composition seam: contributed customizers layer their open routes and filters over the baseline while
        // anyRequest is still unset, so their permit rules keep precedence over the deny-by-default catch-all below.
        customizers.orderedStream().forEach(customizer -> {
            try {
                customizer.customize(http);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to apply a repository security-chain customizer", e);
            }
        });
        http.authorizeHttpRequests(authorize -> authorize.anyRequest().access(authorizationManager));
        return http.build();
    }
}
