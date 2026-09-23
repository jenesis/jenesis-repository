package build.jenesis.repository.application;

import build.jenesis.repository.server.kernel.PinnedSettings;
import build.jenesis.repository.server.kernel.RepositoryProperties;
import build.jenesis.repository.server.kernel.RequestBodyLimitFilter;
import build.jenesis.repository.server.kernel.Settings;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.server.spi.KeyUsageTracker;
import build.jenesis.repository.server.RateLimitFilter;
import build.jenesis.repository.server.spi.RateLimiter;
import build.jenesis.repository.server.SecurityChainCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Composes the enterprise repository's security concerns <em>over</em> the free repository security chain rather than
 * replacing it: the free {@code RepositorySecurityAutoConfiguration} builds the stateless, deny-by-default chain
 * (key authentication, rate limiting and the deny-by-default authorization manager), and this contributes to it
 * through the {@link SecurityChainCustomizer} seam.
 *
 * <p><b>The authorization manager is no longer declared here.</b> It used to be, under a name the free chain's
 * {@code @ConditionalOnMissingBean(name = ...)} backs off from - a coupling that was a string matched in two
 * modules, where nothing failed when it stopped matching and what failed instead was that every access decision
 * was taken by the weaker manager. The tenancy module offers its manager through
 * {@code AuthorizationManagerProvider} now, which the free declaration resolves as it builds the bean, so the
 * replacement happens in every composition carrying that module rather than only in whichever one is the
 * composition root.
 *
 * <p>The {@link RateLimitFilter} is declared here rather than reused from the free
 * {@code RepositorySecurityAutoConfiguration}: this wiring resolves the default ceiling through the pin-aware
 * runtime-settings chain, which the free bean does not consult. The
 * filter is still the free class over the free {@code RateLimiterProvider} - shared mechanism reused, only its
 * wiring lives here - and the free chain's {@code @ConditionalOnMissingBean} rate-limit filter backs off in its
 * favour and shed-loads the wire through it.
 *
 * <p>The customizer opens the routes that authenticate by something other than a management key - the console
 * landing page and console shell ({@code GET /}, {@code GET /console}), the deployment-static format icons
 * ({@code GET /api/formats/<name>/icon}, a brand asset the console renders) and provenance verification key
 * ({@code GET /api/provenance/key}), the secret-scanning leak webhook ({@code POST /api/leaked}, authenticated by
 * signature) and the OIDC token-exchange endpoint ({@code POST /api/token}, authenticated by the presented
 * id-token) - and adds the {@link RequestBodyLimitFilter} that caps the unauthenticated write routes so an
 * anonymous caller cannot exhaust memory. Everything else falls through to the free chain's deny-by-default
 * {@code anyRequest} rule.
 */
@Configuration
public class RepositorySecurityConfig {

    @Bean
    public RateLimitFilter rateLimitFilter(RateLimiter rateLimiter, Authorization authorization,
                                           RepositoryProperties properties, Settings settings,
                                           Environment environment, PinnedSettings pinnedSettings) {
        // The default ceiling is read live through the whole runtime-settings chain - an operator's pin, else the
        // stored override, else the environment - so the rate-limit setting written through the settings API is
        // honoured within the filter's ten-second ceiling cache rather than at the next boot; the boot property stays
        // the fallback for a deployment that never set it at runtime.
        return new RateLimitFilter(rateLimiter, authorization, RateLimitFilter.liveDefault(
                pinnedSettings.effectiveProperty(settings, environment, null), properties.getRateLimit()));
    }

    @Bean
    public SecurityChainCustomizer enterpriseSecurityChainCustomizer() {
        return http -> http
                .authorizeHttpRequests(authorize -> authorize
                        // The console shell and its format-icon route used to be opened here. They are gone: the
                        // repository node served a single-page console that duplicated nine of the admin console's
                        // ten screens, and it was removed rather than gated. A permit for an unmapped route is
                        // inert, but it reads as a surface that exists, which is worse than nothing.
                        .requestMatchers(HttpMethod.GET, "/api/provenance/key").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/leaked").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/token").permitAll())
                .addFilterBefore(new RequestBodyLimitFilter(1L << 20), UsernamePasswordAuthenticationFilter.class);
    }

    /**
     * The CDN-cache precondition (EPIC 29, RD-3): insert the {@link CacheControlHeaderFilter} <em>after</em> Spring
     * Security's {@link AuthorizationFilter}, so it runs only for an authorized request (a denied request never reaches
     * it and keeps the default {@code no-store}) and wraps the response the format writes to. The filter stamps an
     * immutability-driven {@code Cache-Control} on artifact {@code GET}/{@code HEAD} reads only, overriding the default
     * {@code no-store} for exactly those responses while every other route (API, console, auth, actuator, admin) keeps
     * it - the scoping lives in the filter's own path/method/status guard (design §8.1), never a global disable of
     * Spring's cache-control writer. Contributed as a separate customizer bean so the two concerns stay independent.
     */
    @Bean
    public SecurityChainCustomizer cacheControlSecurityChainCustomizer() {
        return http -> http.addFilterAfter(new CacheControlHeaderFilter(), AuthorizationFilter.class);
    }
}
