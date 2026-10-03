package build.jenesis.repository.server;
import module java.base;

import build.jenesis.repository.observation.Metric;
import build.jenesis.repository.observation.ObservabilitySource;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.server.spi.ClientAddresses;
import build.jenesis.repository.server.spi.RateLimiter;
import build.jenesis.repository.settings.CoreDefaults;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Sheds excess load on the surfaces clients use - a repository ({@code /repository/}, {@code /v2/},
 * {@code /staging/}) and the build cache ({@code /build/}) - before the request reaches them. The console, the
 * management API and the actuator are never limited: an operator must be able to reach the deployment that is being
 * flooded, and nothing there is a client's traffic.
 *
 * <p>A request is metered against three limits, each a {@link RateLimiter} bucket at a ceiling of requests a minute,
 * and one that exhausts any of them is answered {@code 429 Too Many Requests} with a {@code Retry-After} and a sentence
 * naming the limit and its setting:
 * <ol>
 * <li><b>The client address</b> ({@code rate-limit-address}, deployment-wide, on by default at a thousand a second):
 *     what caps one runaway machine, keyed or not. The address is the one the deployment's {@code trusted-proxies}
 *     resolve. A request arriving from a private address that is not listed - an ingress or a load balancer inside
 *     the deployment's network, which is how a chart runs - is keyed by the last {@code X-Forwarded-For} hop instead,
 *     the address that proxy saw: otherwise every client behind it would share one bucket and the limit would be a
 *     ceiling on the whole deployment. A client inside that network can set its own header and so pick its bucket,
 *     which loosens only this limit; the other two still hold it, and a source-IP allowlist never reads this
 *     address.</li>
 * <li><b>The credential</b> ({@code rate-limit-account}, per tenant, off by default): one key's requests, so one
 *     tenant's runaway job cannot spend the tenant's whole ceiling.</li>
 * <li><b>The tenant</b> ({@code rate-limit}, per tenant): every credential of a tenant together; every keyless
 *     request shares one {@code anonymous} bucket at the deployment's value.</li>
 * </ol>
 *
 * <p>The tenant and the credential are read from the presented key ({@link PresentedKey}) when it is
 * {@link Authorization#wellFormed well-formed} - a CRC32 typo guard, not a signature, so both are attacker-controlled
 * here: this pre-auth filter cannot afford the store lookup that would tell a genuine key from a fabricated one. A
 * flood of fabricated tenant names is bounded by {@link BoundedTenantBuckets} (at most {@link #MAX_TRACKED_TENANTS}
 * tenants get their own bucket and ceiling; the rest meter as {@code anonymous}, with no credential limit), and the
 * limiter bounds the address and credential buckets it holds. A ceiling is cached briefly per tenant so the limiter,
 * not a store read, is on the hot path, and is read live through the settings, so a change applies within its ten
 * seconds. A ceiling of zero is no limit.
 *
 * <p>It reports how many requests each limit shed since the node started ({@code jenrepo.ratelimit.rejected} and one
 * counter per limit) through {@link ObservabilitySource}, so the console's metrics, the API and the CLI show them.
 */
public class RateLimitFilter extends OncePerRequestFilter implements ObservabilitySource {

    private static final long CACHE_TTL_NANOS = 10_000_000_000L;

    /** The most distinct tenants that ever get a dedicated bucket; the rest share {@code anonymous}. Far above any
     *  real tenant count, so it bounds only an adversarial flood of fabricated tenant names, never a real deployment. */
    static final int MAX_TRACKED_TENANTS = 50_000;

    /** The surfaces a client's traffic reaches, and so the only ones limited. */
    private static final List<String> LIMITED = List.of("/repository/", "/v2/", "/staging/", "/build/");

    /** What a request is metered against, in the order it is asked. */
    public enum Limit {

        ADDRESS("this client address", "rate-limit-address"),
        ACCOUNT("this credential", "rate-limit-account"),
        TENANT("this tenant", "rate-limit");

        private final String whose;
        private final String setting;

        Limit(String whose, String setting) {
            this.whose = whose;
            this.setting = setting;
        }

        /** The setting that sets this limit's ceiling. */
        public String setting() {
            return setting;
        }
    }

    /**
     * The ceilings, in requests a minute, each read when its cache lapses: a tenant's ({@code null} for the
     * deployment's, which keyless requests meter at), a tenant's per-credential one, and the deployment's
     * per-address one.
     */
    public record Ceilings(ToLongFunction<String> tenant, ToLongFunction<String> account, LongSupplier address) {

        public Ceilings {
            Objects.requireNonNull(tenant, "tenant");
            Objects.requireNonNull(account, "account");
            Objects.requireNonNull(address, "address");
        }

        /**
         * The ceilings as the runtime settings resolve them: whatever {@code lookup} - given the tenant, {@code null}
         * for the deployment's - answers for {@code jenrepo.rate-limit}, {@code jenrepo.rate-limit-account} and
         * {@code jenrepo.rate-limit-address}, which is {@code Features.lookup()} on a shell and the store-backed chain
         * (pin over the tenant's stored value over the deployment's over the environment) on one that has it. A
         * value that is unset, or does not parse as a non-negative number, gives way to the default - for the
         * tenant's ceiling {@code tenantFallback}, the boot property - rather than turning every request into an
         * error: the settings API validates on write, so this only guards a hand-edited store.
         */
        public static Ceilings live(Function<String, UnaryOperator<String>> lookup, long tenantFallback) {
            return new Ceilings(
                    tenant -> read(lookup.apply(tenant), "jenrepo.rate-limit", tenantFallback),
                    tenant -> read(lookup.apply(tenant), "jenrepo.rate-limit-account",
                            Long.parseLong(CoreDefaults.RATE_LIMIT_ACCOUNT)),
                    () -> read(lookup.apply(null), "jenrepo.rate-limit-address",
                            Long.parseLong(CoreDefaults.RATE_LIMIT_ADDRESS)));
        }

        private static long read(UnaryOperator<String> settings, String key, long fallback) {
            String configured = settings.apply(key);
            if (configured == null || configured.isBlank()) {
                return fallback;
            }
            try {
                long ceiling = Long.parseLong(configured.trim());
                return ceiling < 0 ? fallback : ceiling;
            } catch (NumberFormatException notANumber) {
                return fallback;
            }
        }
    }

    private final RateLimiter limiter;
    private final Ceilings ceilingsOf;
    private final List<String> trustedProxies;
    private final BoundedTenantBuckets buckets = new BoundedTenantBuckets(MAX_TRACKED_TENANTS);
    private final ConcurrentHashMap<String, long[]> ceilings = new ConcurrentHashMap<>();
    private final Map<Limit, AtomicLong> rejected = new EnumMap<>(Limit.class);

    /**
     * @param limiter        the buckets.
     * @param ceilings       each limit's ceiling.
     * @param trustedProxies the reverse proxies whose {@code X-Forwarded-For} is believed, as the deployment lists
     *                       them in {@code trusted-proxies}.
     */
    public RateLimitFilter(RateLimiter limiter, Ceilings ceilings, List<String> trustedProxies) {
        this.limiter = limiter;
        this.ceilingsOf = ceilings;
        this.trustedProxies = List.copyOf(trustedProxies);
        for (Limit limit : Limit.values()) {
            rejected.put(limit, new AtomicLong());
        }
    }

    /** Whether a request to {@code path} is a client's traffic, which is limited, rather than the console's, the
     *  management API's or a probe's, which is not. */
    public static boolean limited(String path) {
        if (path.equals("/v2")) {
            return true;
        }
        for (String root : LIMITED) {
            if (path.startsWith(root)) {
                return true;
            }
        }
        return false;
    }

    /** The requests shed with {@code 429} since startup, by every limit. */
    public long rejected() {
        return rejected.values().stream().mapToLong(AtomicLong::get).sum();
    }

    /** The requests {@code limit} shed with {@code 429} since startup. */
    public long rejected(Limit limit) {
        return rejected.get(limit).get();
    }

    @Override
    public List<Metric> metrics() {
        List<Metric> metrics = new ArrayList<>();
        metrics.add(Metric.counter("jenrepo.ratelimit.rejected", "Requests to a repository or the build cache "
                + "answered 429 Too Many Requests since this node started, by any rate limit.", rejected(), "requests"));
        for (Limit limit : Limit.values()) {
            metrics.add(Metric.counter("jenrepo.ratelimit.rejected." + limit.name().toLowerCase(Locale.ROOT),
                    "Requests answered 429 because " + limit.whose + " was over its ceiling (" + limit.setting
                            + ") since this node started.", rejected(limit), "requests"));
        }
        return metrics;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!limited(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }
        String address = address(request);
        long addressCeiling = ceiling("address", ceilingsOf.address()::getAsLong);
        if (address != null && !limiter.allow("address:" + address, addressCeiling)) {
            refuse(response, Limit.ADDRESS, addressCeiling);
            return;
        }
        String presented = PresentedKey.fromAnyClient(request);
        String tenant = Authorization.wellFormed(presented) ? Authorization.tenantOf(presented) : null;
        String bucket = buckets.bucket(tenant);
        // A tenant past the cap meters in the shared bucket at the deployment's ceiling, with no credential limit of
        // its own - a per-tenant ceiling for it would re-introduce an unbounded cache entry per fabricated name.
        String admitted = bucket.equals(BoundedTenantBuckets.ANONYMOUS) ? null : tenant;
        if (admitted != null) {
            long accountCeiling = ceiling("account:" + admitted, () -> ceilingsOf.account().applyAsLong(admitted));
            if (accountCeiling > 0 && !limiter.allow("account:" + Authorization.hash(presented), accountCeiling)) {
                refuse(response, Limit.ACCOUNT, accountCeiling);
                return;
            }
        }
        long tenantCeiling = ceiling("tenant:" + bucket, () -> ceilingsOf.tenant().applyAsLong(admitted));
        if (!limiter.allow(bucket, tenantCeiling)) {
            refuse(response, Limit.TENANT, tenantCeiling);
            return;
        }
        chain.doFilter(request, response);
    }

    /** The address a request is limited by: see the class's first limit. */
    private String address(HttpServletRequest request) {
        String peer = request.getRemoteAddr();
        String forwarded = request.getHeader("X-Forwarded-For");
        String resolved = ClientAddresses.resolve(peer, forwarded, trustedProxies);
        if (!Objects.equals(resolved, peer) || forwarded == null || forwarded.isBlank() || !internal(peer)) {
            return resolved;
        }
        String[] hops = forwarded.split(",");
        String nearest = hops[hops.length - 1].trim();
        return nearest.isEmpty() ? peer : nearest;
    }

    /** Whether {@code address} is a loopback, link-local or private one - where a proxy in front of the node sits. */
    private static boolean internal(String address) {
        try {
            InetAddress parsed = InetAddress.ofLiteral(address);
            return parsed.isLoopbackAddress() || parsed.isSiteLocalAddress() || parsed.isLinkLocalAddress()
                    || parsed instanceof Inet6Address && (parsed.getAddress()[0] & 0xFE) == 0xFC;
        } catch (IllegalArgumentException | NullPointerException notAnAddress) {
            return false;
        }
    }

    private void refuse(HttpServletResponse response, Limit limit, long ceiling) throws IOException {
        rejected.get(limit).incrementAndGet();
        response.setStatus(429);
        response.setHeader("Retry-After", "60");
        response.setContentType("text/plain;charset=UTF-8");
        response.getWriter().write("Too many requests from " + limit.whose + ": its limit is " + ceiling
                + " a minute (the " + limit.setting + " setting). Retry after a minute.\n");
    }

    private long ceiling(String key, LongSupplier read) {
        long now = System.nanoTime();
        long[] cached = ceilings.get(key);
        if (cached != null && cached[1] > now) {
            return cached[0];
        }
        long ceiling = read.getAsLong();
        ceilings.put(key, new long[]{ceiling, now + CACHE_TTL_NANOS});
        return ceiling;
    }
}
