package build.jenesis.repository.application;

import build.jenesis.repository.server.kernel.PinnedSettings;
import build.jenesis.repository.server.RepositoryProperties;
import build.jenesis.repository.server.kernel.RequestBodyLimitFilter;
import build.jenesis.repository.server.kernel.Settings;
import build.jenesis.repository.server.spi.KeyUsageTracker;
import build.jenesis.repository.server.RateLimitFilter;
import build.jenesis.repository.server.RepositoryAuthorizationManager;
import build.jenesis.repository.server.spi.RateLimiter;
import build.jenesis.repository.server.SecurityChainCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Contributes to the server's own deny-by-default security chain through {@link SecurityChainCustomizer} rather
 * than replacing it.
 *
 * <p>The authorization manager is not declared here: the server's own is the one manager, and a richer policy
 * arrives through {@code AuthorizationManagerProvider}, so it applies in every composition carrying it. Overriding the
 * bean by name would couple two modules by a string that fails silently when it stops matching.
 *
 * <p>The {@link RateLimitFilter} is declared here so its ceiling resolves through the pin-aware runtime-settings
 * chain; the chain's own filter backs off in its favour.
 *
 * <p>The customizer opens the routes that authenticate by something other than a management key - the provenance
 * verification key and the token exchange (an id-token) - and caps the exchange's unauthenticated body with
 * {@link RequestBodyLimitFilter}. A module that opens a route of its own contributes its own customizer. Everything else falls to the chain's deny-by-default rule.
 */
@Configuration
public class RepositorySecurityConfig {

    @Bean
    public RateLimitFilter rateLimitFilter(RateLimiter rateLimiter, RepositoryProperties properties,
                                           Settings settings, Environment environment,
                                           PinnedSettings pinnedSettings) {
        // Each tenant's ceiling is read live through the settings chain, so a change applies within the filter's
        // ceiling cache; the boot property is the fallback.
        return new RateLimitFilter(rateLimiter, RateLimitFilter.Ceilings.live(
                tenant -> pinnedSettings.effectiveProperty(settings, environment, tenant), properties.getRateLimit()),
                RepositoryAuthorizationManager.parseTrustedProxies(properties.getTrustedProxies()));
    }

    @Bean
    public SecurityChainCustomizer repositorySecurityChainCustomizer() {
        return http -> http
                .authorizeHttpRequests(authorize -> authorize
                        // No console route: this node serves no console, and a permit reads as a surface.
                        .requestMatchers(HttpMethod.GET, "/api/provenance/key").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/token").permitAll())
                .addFilterBefore(new RequestBodyLimitFilter(1L << 20, "/api/token"), UsernamePasswordAuthenticationFilter.class);
    }

    /**
     * Places the {@link CacheControlHeaderFilter} after the {@link AuthorizationFilter}, so a denied request never
     * reaches it and keeps the default {@code no-store}.
     */
    @Bean
    public SecurityChainCustomizer cacheControlSecurityChainCustomizer() {
        return http -> http.addFilterAfter(new CacheControlHeaderFilter(), AuthorizationFilter.class);
    }
}
