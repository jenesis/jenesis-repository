package build.jenesis.repository.server;

import module java.base;

import build.jenesis.repository.server.spi.KeyUsageTracker;
import build.jenesis.repository.server.spi.AccessDenial;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.server.spi.RateLimiter;
import build.jenesis.repository.store.Features;
import jakarta.servlet.DispatcherType;
import org.springframework.core.env.Environment;

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
import org.springframework.security.web.firewall.HttpFirewall;
import org.springframework.security.web.firewall.StrictHttpFirewall;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Server-side security for the repository as auto-configuration: stateless, deny-by-default authorization
 * delegated to the {@link RepositoryAuthorizationManager} (a pass-through when the deployment is anonymous), with the
 * Actuator health endpoint left open for liveness/readiness probes. The {@link KeyAuthenticationFilter} runs first to
 * lift the presented key ({@link PresentedKey}) into the security context. CSRF, HTTP Basic and form login are
 * disabled - this is a machine-to-machine artifact API keyed by a header, not a browser session - and a write a
 * browser sends from another site is refused by the {@link CrossSiteWriteFilter}, since a key presented as a Basic
 * password is one a browser attaches for itself. Both the
 * authentication entry point and the access-denied handler are the {@link RepositoryAuthorizationEntryPoint}, so a
 * denied request answers the status the credential model intends whichever Spring Security failure path it takes:
 * {@code 401} for a caller with no usable credential, and the deployment's {@link AccessDenial} for one refused.
 *
 * <p>The chain is a <em>composition seam</em>, not a fixed chain. The authorization manager, the
 * {@link RateLimitFilter} and the chain itself are {@link ConditionalOnMissingBean conditional}, and every discovered
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

    /**
     * The manager every request is decided by: an installed {@link AuthorizationManagerProvider}'s, or this
     * server's own deny-by-default one when no module provides a richer policy.
     *
     * <p>The provider is resolved <em>here</em>, inside the declaration, rather than contributed as a competing
     * bean. A competing bean works only from whichever module happens to be the composition root - a discovered
     * configuration is a deferred import and is evaluated after this conditional has already been decided - and
     * it couples two modules through a bean name that nothing checks. Resolving here reaches every composition
     * that carries the provider, including the ones a test harness assembles.
     *
     * <p>The declared type is the interface because that is what the chain consumes; the bean NAME still matters
     * and is still the method name, since a deployment may also replace this bean outright.
     */
    @Bean
    @ConditionalOnMissingBean(name = "repositoryAuthorizationManager")
    public AuthorizationManager<RequestAuthorizationContext> repositoryAuthorizationManager(
            Authorization authorization, RepositoryRouting routing, KeyUsageTracker keyUsageTracker,
            Environment environment, RepositoryProperties properties) {
        return AuthorizationManagerProvider
                .resolve(authorization, keyUsageTracker, routing, Features.namespaced(environment::getProperty))
                .orElseGet(() -> new RepositoryAuthorizationManager(authorization, keyUsageTracker, properties));
    }

    /**
     * The request firewall, admitting an encoded slash ({@code %2F}) and nothing else the strict default refuses. npm
     * addresses a scoped package that way - {@code PUT} and {@code GET /@scope%2Fname} - so refusing it would refuse
     * every scoped package the npm client publishes or installs. It is safe because nothing decides on the encoded
     * form: {@link RepositoryAuthorizationManager} authorizes the decoded path and refuses one that decodes into an
     * empty or dot segment, and the routing takes the tenant and repository from segments that cannot carry a
     * {@code %}.
     */
    @Bean
    @ConditionalOnMissingBean(HttpFirewall.class)
    public HttpFirewall repositoryHttpFirewall() {
        StrictHttpFirewall firewall = new StrictHttpFirewall();
        firewall.setAllowUrlEncodedSlash(true);
        return firewall;
    }

    @Bean
    @ConditionalOnMissingBean
    public AuthFailures authFailures() {
        // A registry-free accessor seam: the key entry point (and the console's OIDC/SAML login failure handlers)
        // record denials here, and a metrics layer scrapes them into jenrepo.auth.failures.
        return new AuthFailures();
    }

    @Bean
    @ConditionalOnMissingBean
    public RateLimitFilter rateLimitFilter(RateLimiter rateLimiter, RepositoryProperties properties) {
        // A bean (not an inline filter) so a metrics layer can scrape the same instance the chain sheds load with.
        // The ceiling is read live, so the rate-limit setting an operator writes at runtime is honoured; a shell
        // without the stored settings has no tenant's value to read, so every tenant meters at the deployment's.
        return new RateLimitFilter(rateLimiter,
                RateLimitFilter.Ceilings.live(_ -> Features.lookup(), properties.getRateLimit()),
                RepositoryAuthorizationManager.parseTrustedProxies(properties.getTrustedProxies()));
    }

    /**
     * The repository's chain, backed off only by a bean of THIS NAME.
     *
     * <p>By name, not by type, and the distinction is the whole point. A bare {@code @ConditionalOnMissingBean}
     * matches the method's return type, so ANY {@link SecurityFilterChain} in the context would suppress this one -
     * including the ordered, path-matched chains that plainly exist to sit BESIDE it, such as a {@code @Order(1)}
     * chain over {@code /scim/**} or the cache's over {@code /build/**}, neither of which replaces the artifact chain.
     *
     * <p>The admin console requires this module, so auto-configuration registers the repository's controller into its
     * context; with this chain suppressed by the console's own, an artifact request there would be bounced to
     * {@code /login} instead of being authenticated by its key.
     *
     * <p>Replacing this chain outright is still available: define a bean named {@code securityFilterChain}.
     * Contributing to it - extra open routes, an extra filter, a richer authorization manager - needs no replacement
     * at all and rides the {@link SecurityChainCustomizer} seam, which is what the class comment above recommends.
     */
    @Bean
    @ConditionalOnMissingBean(name = "securityFilterChain")
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   @Qualifier("repositoryAuthorizationManager")
                                                   AuthorizationManager<RequestAuthorizationContext> authorizationManager,
                                                   RateLimitFilter rateLimitFilter,
                                                   AuthFailures authFailures,
                                                   ObjectProvider<SecurityChainCustomizer> customizers,
                                                   ObjectProvider<FormatDispatcher> dispatcher)
            throws Exception {
        // A value the catalogue would refuse can still arrive from the environment: refuse it here, at the start,
        // rather than answer every later refusal with a 500.
        AccessDenial.configured();
        RepositoryAuthorizationEntryPoint entryPoint = new RepositoryAuthorizationEntryPoint(authFailures,
                () -> challenges(dispatcher.getIfAvailable()), AccessDenial::configured);
        http
                .csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(entryPoint))
                .authorizeHttpRequests(authorize -> authorize
                        // An error page renders the answer a request was already given - its status decided by the
                        // manager, a filter or a controller - so its dispatch is not decided again: deciding it would
                        // replace that status with the manager's verdict on the error page, which would turn the
                        // cross-site filter's 403 into the refusal a credential without a wildcard read gets.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        // The three paths a container platform probes, and nothing else. They answer a
                        // summarized state - UP or DOWN - and are open because a kubelet has no credential to
                        // present and a probe that needs one is a probe that fails the pod.
                        //
                        // Not /actuator/health/** , which would also open every PER-COMPONENT path
                        // (/actuator/health/db, /actuator/health/diskSpace). Those report which component is
                        // unhealthy and why, which is a map of the deployment's internals to anyone who can
                        // reach the port. They fall through to the authorization manager, which binds the
                        // /actuator subtree to a wildcard grant, so an operator still reads them with a key.
                        //
                        // The narrow list is the guarantee. show-details is a second line - and a weaker one,
                        // because it is a property a deployment can set back.
                        .requestMatchers("/actuator/health",
                                "/actuator/health/liveness",
                                "/actuator/health/readiness").permitAll())
                .addFilterBefore(new CrossSiteWriteFilter(CrossSiteWriteFilter.live(Features.lookup())),
                        UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new UploadLimitFilter(UploadLimitFilter.live(Features.lookup())),
                        UsernamePasswordAuthenticationFilter.class)
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

    /** Every scheme an installed format declares for a {@code 401}, in discovery order and each once - none when
     *  this composition dispatches no formats. */
    private static List<String> challenges(FormatDispatcher dispatcher) {
        if (dispatcher == null) {
            return List.of();
        }
        return dispatcher.formats().stream().flatMap(format -> format.challenges().stream()).distinct().toList();
    }
}
