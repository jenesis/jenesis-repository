package build.jenesis.repository.cache.server;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The cache's own security chain, in the cache's own module.
 *
 * <p>It used to live in the module that composed the console and the cache into one app, which meant the cache
 * carried its security only when that particular composition assembled it. Any other node with the cache aboard -
 * the bundle - would have had {@code /build/**} governed by whatever chain happened to match, which for a
 * cache client is either a redirect to a login page or a demand for a repository key it does not have. A module
 * that authenticates its own requests should carry the chain that lets it, exactly as
 * {@code scimSecurityFilterChain} does for {@code /scim/**}.
 *
 * <p>The chain scopes {@code /build/**} to permit-all with CSRF disabled, because {@link CacheController} does its
 * own per-request key authentication and a cache client must never be bounced to {@code /login}. It is ordered
 * ahead of a console's chain and of the repository's unmatched fall-through, so the narrower space wins.
 *
 * <p>It governs {@code /build/**} and nothing else, deliberately. The management surface of a cache running ALONE
 * is closed by {@code CacheServer}, in the launcher rather than here, because a chain declared here would reach
 * the merged node too - and an ordered chain over a narrower space wins over the repository's unmatched-scope
 * one, so it would deny the actuator surface of the node an operator actually runs. Measured: it did, and
 * {@code ServerToggleE2ETest} caught it.
 */
/*
 * @EnableWebSecurity because this module now owns its chain and must therefore bring what builds one. Moving the
 * chain here revealed that the STANDALONE cache had no web-security infrastructure at all: it borrowed the
 * repository's @EnableWebSecurity whenever a composition happened to put them together, and had none when run
 * alone. A module cannot own its security only in the compositions somebody else assembled.
 */
@Configuration
@EnableWebSecurity
public class CacheSecurityConfig {

    @Bean
    @Order(1)
    public SecurityFilterChain cacheSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/build/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable);
        return http.build();
    }
}
