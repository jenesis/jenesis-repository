package build.jenesis.repository.server;
import module java.base;

import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.server.spi.RateLimiter;
import build.jenesis.repository.store.Features;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Sheds excess load before the request reaches the repository: each request is metered against its tenant's rate
 * ceiling (the {@code rate-limit} setting as that tenant resolves it - its own value, else the deployment's), and
 * one that exhausts the tenant's {@link RateLimiter} bucket is answered {@code 429 Too Many Requests} with a {@code
 * Retry-After}. The tenant is read from the presented key ({@link PresentedKey}) when it is
 * {@link Authorization#wellFormed well-formed}, but that check is only a CRC32 typo guard, not a signature: this
 * pre-auth filter cannot afford the store lookup that would tell a genuine key from a fabricated one, so the tenant
 * here is effectively attacker-controlled. To keep a flood of distinct fabricated tenant names from minting an
 * unbounded number of per-tenant buckets (a memory-exhaustion vector - it would also grow the ceiling cache and the
 * per-tenant reject counters), the distinct-tenant cardinality is capped by {@link BoundedTenantBuckets}: at most
 * {@link #MAX_TRACKED_TENANTS} tenants get their own bucket and the rest, along with every keyless request, share one
 * {@code anonymous} bucket. A real deployment stays far below the cap, so a seen tenant keeps its own bucket; only an
 * adversarial excess spills to the shared one. The Actuator endpoints are never limited, so liveness and scrape
 * probes are unaffected. The effective ceiling is cached briefly per bucket so the limiter, not a store read, is on
 * the hot path. A ceiling of zero (nothing configured) is unlimited - the filter is then a no-op.
 *
 * <p>The ceiling is read live, through the lookup the runtime settings resolve against for the tenant
 * ({@link #liveCeiling}), so an operator who lowers, raises or zeroes {@code rate-limit} - deployment-wide or for one
 * tenant - through the settings API sees it take effect within the cache's ten seconds of the lookup seeing it rather
 * than at the next boot. The boot property stays the fallback for a deployment that never set it at runtime.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final long CACHE_TTL_NANOS = 10_000_000_000L;

    /** The most distinct tenants that ever get a dedicated bucket; the rest share {@code anonymous}. Far above any
     *  real tenant count, so it bounds only an adversarial flood of fabricated tenant names, never a real deployment. */
    static final int MAX_TRACKED_TENANTS = 50_000;

    private final RateLimiter limiter;
    private final ToLongFunction<String> ceilingOf;
    private final BoundedTenantBuckets buckets = new BoundedTenantBuckets(MAX_TRACKED_TENANTS);
    private final ConcurrentHashMap<String, long[]> ceilings = new ConcurrentHashMap<>();
    private final AtomicLong rejected = new AtomicLong();
    private final ConcurrentHashMap<String, AtomicLong> rejectedByTenant = new ConcurrentHashMap<>();

    /** One fixed ceiling for every tenant. */
    public RateLimitFilter(RateLimiter limiter, long permitsPerMinute) {
        this(limiter, _ -> permitsPerMinute);
    }

    /** {@code ceilingOf} answers a tenant's ceiling - {@code null} for a keyless request, or a tenant past the
     *  tracked cap, which meter against the deployment's. */
    public RateLimitFilter(RateLimiter limiter, ToLongFunction<String> ceilingOf) {
        this.limiter = limiter;
        this.ceilingOf = ceilingOf;
    }

    /**
     * The ceiling as the runtime settings currently resolve it for a tenant: whatever {@code lookup} - given the
     * tenant, {@code null} for the deployment's - answers for {@code jenrepo.rate-limit}, which is
     * {@link Features#lookup()} on the shell and the store-backed chain (pin over the tenant's stored value over the
     * deployment's over the environment) on a shell that has one - otherwise {@code fallback}, the boot property's
     * value. A value that does not parse as a non-negative number is ignored in favour of the fallback rather than
     * turning every request into an error: the settings API validates the setting on write, so this only guards a
     * hand-edited store.
     */
    public static ToLongFunction<String> liveCeiling(Function<String, UnaryOperator<String>> lookup, long fallback) {
        return tenant -> {
            String configured = lookup.apply(tenant).apply("jenrepo.rate-limit");
            if (configured == null || configured.isBlank()) {
                return fallback;
            }
            try {
                long ceiling = Long.parseLong(configured.trim());
                return ceiling < 0 ? fallback : ceiling;
            } catch (NumberFormatException notANumber) {
                return fallback;
            }
        };
    }

    /** The number of requests shed with {@code 429} since startup - a back-pressure signal a metrics layer can scrape. */
    public long rejected() {
        return rejected.get();
    }

    /** Requests shed with {@code 429} since startup, broken down by the bucket they metered against ({@code anonymous}
     *  for a keyless request, else the tenant), so a metrics layer can tag {@code jenrepo.ratelimit.rejected} by
     *  tenant. A snapshot view; a tenant that has never been rate-limited is absent rather than zero. */
    public Map<String, Long> rejectedByTenant() {
        return rejectedByTenant.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, e -> e.getValue().get()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getRequestURI().startsWith("/actuator")) {
            chain.doFilter(request, response);
            return;
        }
        String presented = PresentedKey.from(request);
        String tenant = Authorization.wellFormed(presented) ? Authorization.tenantOf(presented) : null;
        String bucket = buckets.bucket(tenant);
        // A tenant that overflowed the cap meters against the shared bucket on the default ceiling - never its own
        // per-tenant ceiling, which would re-introduce an unbounded per-tenant cache entry.
        String effectiveTenant = bucket.equals(BoundedTenantBuckets.ANONYMOUS) ? null : tenant;
        if (!limiter.allow(bucket, ceiling(bucket, effectiveTenant))) {
            rejected.incrementAndGet();
            rejectedByTenant.computeIfAbsent(bucket, key -> new AtomicLong()).incrementAndGet();
            response.setStatus(429);
            response.setHeader("Retry-After", "60");
            return;
        }
        chain.doFilter(request, response);
    }

    private long ceiling(String bucket, String tenant) {
        long now = System.nanoTime();
        long[] cached = ceilings.get(bucket);
        if (cached != null && cached[1] > now) {
            return cached[0];
        }
        long ceiling = ceilingOf.applyAsLong(tenant);
        ceilings.put(bucket, new long[]{ceiling, now + CACHE_TTL_NANOS});
        return ceiling;
    }
}
