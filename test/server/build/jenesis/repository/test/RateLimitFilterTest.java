package build.jenesis.repository.test;

import module org.junit.jupiter.api;
import module java.base;

import build.jenesis.repository.ratelimit.RateLimitSettingsContributor;
import build.jenesis.repository.ratelimit.TokenBucketRateLimiter;
import build.jenesis.repository.server.RateLimitFilter;
import build.jenesis.repository.server.RateLimitFilter.Ceilings;
import build.jenesis.repository.server.RateLimitFilter.Limit;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.server.spi.RateLimiter;
import build.jenesis.repository.settings.Setting;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RateLimitFilter}, driven through its {@code doFilter} entry point with Mockito servlet mocks and either a
 * {@link RateLimiter} double that records every bucket and ceiling a request meters against, or the real
 * {@link TokenBucketRateLimiter} on a frozen clock.
 *
 * <ul>
 *   <li>Only a client's traffic is limited: the repositories and the build cache, never the console, the management
 *       API or a probe.</li>
 *   <li>A request meters against its address, its credential where a tenant sets a ceiling for one, and its tenant;
 *       a shed request carries {@code Retry-After: 60} and a sentence naming the limit and its setting, never reaches
 *       the chain, and is counted against that limit.</li>
 *   <li>The address is the one the trusted proxies resolve, or behind an unlisted proxy on a private address the
 *       last forwarded hop; a public peer's forwarded header is never believed.</li>
 *   <li>A forged tenant past the bucket cap meters at the deployment's ceiling, with no credential limit.</li>
 *   <li>With nothing set, an address may make 60,000 requests a minute and a credential has no ceiling of its own -
 *       the catalogue declares both, and the filter applies them.</li>
 * </ul>
 */
public class RateLimitFilterTest {

    private static final String ARTIFACT = "/repository/default/maven/org/x/y/1/y-1.jar";

    private static final long DEFAULT_CEILING = 7L;

    private static final long TENANT_OVERRIDE = 100_000L;

    /** The filter's fixed distinct-tenant cap ({@code RateLimitFilter.MAX_TRACKED_TENANTS}). */
    private static final int CAP = 50_000;

    /** A {@link RateLimiter} double that admits every request and records each bucket and ceiling metered. */
    private static final class Capturing implements RateLimiter {
        private final Map<String, Double> metered = new LinkedHashMap<>();

        @Override
        public synchronized boolean allow(String key, double permitsPerMinute) {
            metered.put(key, permitsPerMinute);
            return true;
        }

        synchronized Map<String, Double> metered() {
            return new LinkedHashMap<>(metered);
        }

        synchronized void clear() {
            metered.clear();
        }
    }

    /** One request's arrangement: its path, its TCP peer, any forwarded header and key. */
    private static HttpServletRequest request(String path, String peer, String forwarded, String key) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn(path);
        when(request.getDispatcherType()).thenReturn(DispatcherType.REQUEST);
        when(request.getRemoteAddr()).thenReturn(peer);
        when(request.getHeader("X-Forwarded-For")).thenReturn(forwarded);
        when(request.getHeader("Jenesis-Repository-Key")).thenReturn(key);
        return request;
    }

    /** What a response was answered with. */
    private static final class Answer {
        private int status = -1;
        private final Map<String, String> headers = new HashMap<>();
        private final StringWriter body = new StringWriter();
        private final HttpServletResponse response = mock(HttpServletResponse.class);

        Answer() throws IOException {
            doAnswer(invocation -> status = invocation.getArgument(0)).when(response).setStatus(anyInt());
            doAnswer(invocation -> headers.put(invocation.getArgument(0), invocation.getArgument(1)))
                    .when(response).setHeader(anyString(), anyString());
            when(response.getWriter()).thenReturn(new PrintWriter(body));
        }
    }

    private static Ceilings ceilings(ToLongFunction<String> tenant, ToLongFunction<String> account, long address) {
        return new Ceilings(tenant, account, () -> address);
    }

    private static RateLimitFilter filter(RateLimiter limiter, Ceilings ceilings, String... trustedProxies) {
        return new RateLimitFilter(limiter, ceilings, List.of(trustedProxies));
    }

    @Test
    void only_a_clients_traffic_is_limited_never_the_console_the_management_api_or_a_probe() throws Exception {
        RateLimitFilter filter = filter((_, _) -> false, ceilings(_ -> 1, _ -> 1, 1));

        for (String path : List.of("/ui/repositories", "/api/admin/caches", "/api/settings", "/actuator/health",
                "/login", "/oauth2/authorization/github")) {
            HttpServletRequest request = request(path, "203.0.113.9", null, null);
            Answer answer = new Answer();
            FilterChain chain = mock(FilterChain.class);
            filter.doFilter(request, answer.response, chain);
            verify(chain).doFilter(request, answer.response);
            assertThat(answer.status).as("%s is never limited", path).isEqualTo(-1);
        }
        assertThat(RateLimitFilter.limited(ARTIFACT)).isTrue();
        assertThat(RateLimitFilter.limited("/v2/default/app/manifests/1")).isTrue();
        assertThat(RateLimitFilter.limited("/v2")).as("the registry's version probe").isTrue();
        assertThat(RateLimitFilter.limited("/staging/default/releases/1/a.jar")).isTrue();
        assertThat(RateLimitFilter.limited("/build/default/ab/cd")).as("the build cache").isTrue();
        assertThat(filter.rejected()).isZero();
    }

    @Test
    void a_shed_request_says_which_limit_it_hit_carries_retry_after_and_never_reaches_the_chain() throws Exception {
        RateLimitFilter filter = filter((key, _) -> !key.startsWith("address:"), ceilings(_ -> 1, _ -> 0, 25));
        HttpServletRequest request = request(ARTIFACT, "203.0.113.9", null, null);
        Answer answer = new Answer();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, answer.response, chain);

        assertThat(answer.status).isEqualTo(429);
        assertThat(answer.headers).containsEntry("Retry-After", "60");
        assertThat(answer.body.toString()).contains("this client address", "25 a minute", "rate-limit-address");
        verify(chain, never()).doFilter(request, answer.response);
        assertThat(filter.rejected(Limit.ADDRESS)).isEqualTo(1);
        assertThat(filter.rejected()).isEqualTo(1);
        assertThat(filter.metrics()).anySatisfy(metric -> {
            assertThat(metric.name()).isEqualTo("jenrepo.ratelimit.rejected.address");
            assertThat(metric.value()).isEqualTo(1.0);
        });
    }

    @Test
    void a_keyed_request_meters_against_its_address_its_credential_and_its_tenant() throws Exception {
        Capturing limiter = new Capturing();
        String key = Authorization.mint("acme");
        RateLimitFilter filter = filter(limiter, ceilings(_ -> 600, tenant -> "acme".equals(tenant) ? 60 : 0, 6000));

        filter.doFilter(request(ARTIFACT, "203.0.113.9", null, key), new Answer().response, mock(FilterChain.class));

        assertThat(limiter.metered()).containsExactly(
                Map.entry("address:203.0.113.9", 6000.0),
                Map.entry("account:" + Authorization.hash(key), 60.0),
                Map.entry("acme", 600.0));
    }

    @Test
    void a_credential_has_no_bucket_of_its_own_until_its_tenant_sets_a_ceiling_for_one() throws Exception {
        Capturing limiter = new Capturing();
        RateLimitFilter filter = filter(limiter, ceilings(_ -> 600, _ -> 0, 6000));

        filter.doFilter(request(ARTIFACT, "203.0.113.9", null, Authorization.mint("acme")), new Answer().response,
                mock(FilterChain.class));

        assertThat(limiter.metered().keySet()).containsExactly("address:203.0.113.9", "acme");
    }

    @Test
    void behind_an_unlisted_proxy_on_a_private_address_each_client_is_its_own_address() throws Exception {
        Capturing limiter = new Capturing();
        RateLimitFilter filter = filter(limiter, ceilings(_ -> 0, _ -> 0, 6000));

        filter.doFilter(request(ARTIFACT, "10.0.3.7", "198.51.100.1, 203.0.113.9", null), new Answer().response,
                mock(FilterChain.class));

        assertThat(limiter.metered()).as("the ingress saw the client at the last hop, and that is who is limited")
                .containsKey("address:203.0.113.9");
    }

    @Test
    void a_public_peer_is_its_own_address_whatever_it_forwards() throws Exception {
        Capturing limiter = new Capturing();
        RateLimitFilter filter = filter(limiter, ceilings(_ -> 0, _ -> 0, 6000));

        filter.doFilter(request(ARTIFACT, "203.0.113.9", "192.0.2.44", null), new Answer().response,
                mock(FilterChain.class));

        assertThat(limiter.metered()).as("a client cannot pick its bucket by forging a header")
                .containsKey("address:203.0.113.9").doesNotContainKey("address:192.0.2.44");
    }

    @Test
    void a_listed_proxy_is_believed_through_its_chain() throws Exception {
        Capturing limiter = new Capturing();
        RateLimitFilter filter = filter(limiter, ceilings(_ -> 0, _ -> 0, 6000), "198.51.100.0/24");

        filter.doFilter(request(ARTIFACT, "198.51.100.7", "203.0.113.9, 198.51.100.8", null), new Answer().response,
                mock(FilterChain.class));

        assertThat(limiter.metered()).containsKey("address:203.0.113.9");
    }

    @Test
    void an_overflowed_forged_tenant_meters_at_the_deployment_ceiling_with_no_credential_limit() throws Exception {
        Capturing limiter = new Capturing();
        RateLimitFilter filter = filter(limiter, ceilings(
                tenant -> "evil-corp".equals(tenant) ? TENANT_OVERRIDE : DEFAULT_CEILING, _ -> 5, 0));
        String[] key = new String[1];
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn(ARTIFACT);
        when(request.getDispatcherType()).thenReturn(DispatcherType.REQUEST);
        when(request.getHeader("Jenesis-Repository-Key")).thenAnswer(invocation -> key[0]);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        for (int tenant = 0; tenant < CAP; tenant++) {
            key[0] = Authorization.mint("filler-" + tenant);
            filter.doFilter(request, response, chain);
            if (tenant % 1000 == 0) {
                clearInvocations(request, response, chain);
                limiter.clear();
            }
        }
        limiter.clear();
        key[0] = Authorization.mint("evil-corp");
        filter.doFilter(request, response, chain);

        assertThat(limiter.metered()).as("the shared bucket at the deployment's ceiling, and no credential bucket "
                        + "an attacker could mint one of per fabricated key")
                .containsExactly(Map.entry("anonymous", (double) DEFAULT_CEILING));
    }

    @Test
    void the_same_tenant_meters_at_its_own_override_while_it_holds_a_bucket() throws Exception {
        Capturing limiter = new Capturing();
        RateLimitFilter filter = filter(limiter, ceilings(
                tenant -> "evil-corp".equals(tenant) ? TENANT_OVERRIDE : DEFAULT_CEILING, _ -> 0, 0));

        filter.doFilter(request(ARTIFACT, null, null, Authorization.mint("evil-corp")), new Answer().response,
                mock(FilterChain.class));

        assertThat(limiter.metered()).containsEntry("evil-corp", (double) TENANT_OVERRIDE);
    }

    @Test
    void the_live_ceilings_resolve_the_settings_and_fall_back_to_their_defaults() {
        Map<String, String> deployment = Map.of("jenrepo.rate-limit", "120", "jenrepo.rate-limit-address", "900");
        Map<String, String> acme = Map.of("jenrepo.rate-limit", "30", "jenrepo.rate-limit-account", "10");
        Ceilings live = Ceilings.live(tenant -> "acme".equals(tenant) ? acme::get : deployment::get, DEFAULT_CEILING);

        assertThat(live.tenant().applyAsLong("acme")).as("a tenant's own value is its ceiling").isEqualTo(30);
        assertThat(live.tenant().applyAsLong("globex")).as("else the deployment's").isEqualTo(120);
        assertThat(live.account().applyAsLong("acme")).isEqualTo(10);
        assertThat(live.address().getAsLong()).isEqualTo(900);

        Ceilings unset = Ceilings.live(_ -> Map.<String, String>of()::get, DEFAULT_CEILING);
        assertThat(unset.tenant().applyAsLong(null)).as("nothing written leaves the boot property in force")
                .isEqualTo(DEFAULT_CEILING);
        Ceilings garbled = Ceilings.live(_ -> Map.of("jenrepo.rate-limit", "plenty",
                "jenrepo.rate-limit-address", "-4")::get, DEFAULT_CEILING);
        assertThat(garbled.tenant().applyAsLong(null)).as("a value that is not a number never turns every request "
                + "into an error").isEqualTo(DEFAULT_CEILING);
        assertThat(garbled.address().getAsLong()).isEqualTo(60_000);
    }

    @Test
    void with_nothing_set_an_address_makes_sixty_thousand_requests_a_minute_and_a_credential_has_no_ceiling()
            throws Exception {
        // What the catalogue declares - the settings screen and the reference render it.
        Map<String, String> declared = new HashMap<>();
        for (Setting setting : new RateLimitSettingsContributor().settings()) {
            declared.put(setting.key(), setting.defaultValue());
        }
        assertThat(declared).containsEntry("rate-limit-address", "60000").containsEntry("rate-limit-account", "0");

        // What the filter does with nothing set: one address is admitted for a minute's worth and refused after it,
        // on a clock that does not move, while a second address is untouched.
        RateLimitFilter filter = new RateLimitFilter(new TokenBucketRateLimiter().withClock(() -> 0L),
                Ceilings.live(_ -> Map.<String, String>of()::get, 0), List.of());
        HttpServletRequest request = request(ARTIFACT, "203.0.113.9", null, Authorization.mint("acme"));
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        for (int sent = 0; sent < 60_000; sent++) {
            filter.doFilter(request, response, chain);
            if (sent % 1000 == 0) {
                clearInvocations(request, response, chain);
            }
        }
        assertThat(filter.rejected()).as("a minute's worth from one address is admitted").isZero();

        Answer refused = new Answer();
        filter.doFilter(request, refused.response, chain);
        assertThat(refused.status).isEqualTo(429);
        assertThat(filter.rejected(Limit.ADDRESS)).isEqualTo(1);
        assertThat(filter.rejected(Limit.ACCOUNT)).as("no credential ceiling bit").isZero();

        Answer other = new Answer();
        filter.doFilter(request(ARTIFACT, "203.0.113.10", null, null), other.response, chain);
        assertThat(other.status).as("another address has its own allowance").isEqualTo(-1);
    }
}
