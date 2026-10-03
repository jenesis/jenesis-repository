package build.jenesis.repository.cache.server;

import build.jenesis.repository.server.RateLimitFilter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/**
 * The cache's own security chain, in the cache's module, so every node carrying the cache - the bundle included - has
 * {@code /build/**} governed by it rather than by a chain that would redirect a cache client to a login page.
 *
 * <p>It permits {@code /build/**} with CSRF disabled, because {@link CacheController} authenticates each request
 * itself, and sheds load through the repository's {@link RateLimitFilter} where the node carries one, and is ordered ahead of a console's chain and the repository's unmatched fall-through. It governs nothing
 * else: a cache running alone has its management surface closed by {@code CacheServer}, since a chain here would also
 * reach the merged node and deny its actuator surface.
 */
@Configuration
@EnableWebSecurity
public class CacheSecurityConfig {

    @Bean
    @Order(1)
    public SecurityFilterChain cacheSecurityFilterChain(HttpSecurity http, ObjectProvider<RateLimitFilter> rateLimit)
            throws Exception {
        http
                .securityMatcher("/build/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable);
        rateLimit.ifAvailable(filter -> http.addFilterBefore(filter, AuthorizationFilter.class));
        return http.build();
    }
}
