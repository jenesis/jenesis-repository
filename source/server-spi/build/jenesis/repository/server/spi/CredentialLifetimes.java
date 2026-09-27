package build.jenesis.repository.server.spi;

import module java.base;

import build.jenesis.repository.store.Durations;

/**
 * How long a credential lives: the deployment's default and ceiling, a tenant's stored policy narrowing them, the
 * expiry a mint is stamped with, and the one grammar an operator writes a lifetime or an expiry in on every surface.
 *
 * <p>The deployment-wide values are the authorization's own configuration ({@link Authorization#withLifetimes});
 * a tenant's policy is a document in the credential space, and the effective policy layers it over them - the
 * ceiling being the stricter of the two, and the default never exceeding it. So a credential expires by default and
 * never outlives the policy.
 */
public final class CredentialLifetimes {

    /** The default lifetime of a credential minted without an explicit expiry, unless the deployment says
     *  otherwise. */
    static final Duration DEFAULT_LIFETIME = Duration.ofDays(90);

    /** How long a rotated credential keeps working beside its successor when the caller names no overlap. */
    static final Duration DEFAULT_OVERLAP = Duration.ofDays(7);

    private final CredentialSpace space;
    private final Duration defaultLifetime;
    private final Duration maxLifetime;

    CredentialLifetimes(CredentialSpace space, Duration defaultLifetime, Duration maxLifetime) {
        this.space = space;
        this.defaultLifetime = defaultLifetime;
        this.maxLifetime = maxLifetime;
    }

    /** A tenant's effective credential-lifetime policy: the default to stamp on a blank-expiry mint and the optional
     *  ceiling beyond which no key may live. {@code maxLifetime} is {@code null} when nothing caps the lifetime. */
    public record Policy(Duration defaultLifetime, Duration maxLifetime) {
    }

    /**
     * A credential lifetime, a rotation overlap or a trust's token ttl as an operator writes it on any surface:
     * blank is none, so the caller's default applies; otherwise a duration in the deployment's one grammar
     * ({@code P90D}, {@code 90d}, {@code PT12H}). The API, the console and the management surface all parse through
     * this and {@link #expiry}, so none of them can accept a spelling the others refuse.
     */
    public static Duration lifetime(String value) {
        return value == null || value.isBlank() ? null : Durations.parse(value);
    }

    /**
     * A credential expiry as an operator writes it: blank clears it; an absolute ISO-8601 instant
     * ({@code 2027-01-01T00:00:00Z}) is taken as written; anything else is a duration from now ({@code P30D},
     * {@code 30d}). The instant is the one form that carries a colon, which is what tells the two apart - a suffixed
     * duration never does, and an ISO duration starts with a {@code P}.
     */
    public static Instant expiry(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.indexOf(':') >= 0 && !trimmed.regionMatches(true, 0, "P", 0, 1)
                ? Instant.parse(trimmed)
                : Instant.now().plus(Durations.parse(trimmed));
    }

    /** A deployment dial's duration in the deployment's one grammar, or a refusal naming the key and what a
     *  well-formed value looks like. */
    static Duration configured(String value, String key) {
        try {
            return Durations.parse(value);
        } catch (IllegalArgumentException malformed) {
            throw new IllegalArgumentException("jenreg." + key + " is not a duration: '" + value
                    + "'. Write it as P30D or 30d (thirty days), PT12H or 12h (twelve hours) or P1DT6H.", malformed);
        }
    }

    /** Refuse a lifetime that is not a positive duration - or, when {@code required}, one that is absent -
     *  naming which of the two lifetimes ({@code default} or {@code maximum}) it was. */
    static void requirePositive(Duration value, String which, boolean required) {
        if (value == null ? required : value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("A " + which + " credential lifetime must be a positive duration");
        }
    }

    /** The deployment-wide default lifetime for a credential minted without an explicit expiry; a tenant policy may
     *  narrow it further (see {@link #policy}). */
    public Duration defaultLifetime() {
        return defaultLifetime;
    }

    /** The deployment-wide ceiling on a credential's lifetime, or {@code null} when none is set; no credential of
     *  any tenant may outlive it, and a tenant policy can only cap further, never beyond it. */
    public Duration maxLifetime() {
        return maxLifetime;
    }

    /** The effective policy for {@code tenant}: a stored per-tenant default/ceiling layered over the deployment-wide
     *  values, the ceiling being the stricter (shorter) of the two and the default never exceeding it. */
    public Policy policy(String tenant) throws IOException {
        Properties stored = space.enforcing() ? space.read(path(tenant)) : null;
        Duration tenantDefault = duration(stored, "default-lifetime");
        Duration ceiling = shorter(duration(stored, "max-lifetime"), maxLifetime);
        Duration effectiveDefault = tenantDefault != null ? tenantDefault : defaultLifetime;
        if (ceiling != null && effectiveDefault.compareTo(ceiling) > 0) {
            effectiveDefault = ceiling;
        }
        return new Policy(effectiveDefault, ceiling);
    }

    /** Set or clear ({@code null}) a tenant's per-tenant default and maximum lifetimes; a value must be positive. */
    public void setPolicy(String tenant, Duration defaultLifetime, Duration maxLifetime) throws IOException {
        space.require();
        requirePositive(defaultLifetime, "default", false);
        requirePositive(maxLifetime, "maximum", false);
        Properties properties = new Properties();
        if (defaultLifetime != null) {
            properties.setProperty("default-lifetime", defaultLifetime.toString());
        }
        if (maxLifetime != null) {
            properties.setProperty("max-lifetime", maxLifetime.toString());
        }
        space.write(path(tenant), properties);
    }

    /** The expiry to stamp on a newly minted credential for {@code tenant}. A non-null {@code requested} instant is
     *  honoured, a null request yields the tenant default from now, and {@code nonExpiring} asks for an unbounded key
     *  - granted only when no ceiling applies, otherwise pulled back to the ceiling. So a credential expires by
     *  default and never outlives the policy. */
    public Instant mintExpiry(String tenant, Instant requested, boolean nonExpiring) throws IOException {
        Policy policy = policy(tenant);
        Instant base = nonExpiring
                ? null
                : requested != null ? requested : Instant.now().plus(policy.defaultLifetime());
        return cap(base, policy.maxLifetime());
    }

    /** {@code expires} pulled back to the tenant's ceiling: a {@code null} (never) or too-distant expiry becomes the
     *  ceiling from now, and with no ceiling it is left as it is. */
    Instant capped(String tenant, Instant expires) throws IOException {
        return cap(expires, policy(tenant).maxLifetime());
    }

    private static Instant cap(Instant expires, Duration maxLifetime) {
        if (maxLifetime == null) {
            return expires;
        }
        Instant ceiling = Instant.now().plus(maxLifetime);
        return expires == null || expires.isAfter(ceiling) ? ceiling : expires;
    }

    private static Duration shorter(Duration left, Duration right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.compareTo(right) <= 0 ? left : right;
    }

    private static Duration duration(Properties properties, String key) {
        if (properties == null) {
            return null;
        }
        String value = properties.getProperty(key);
        return value == null ? null : Duration.parse(value);
    }

    private static String path(String tenant) {
        return CredentialSpace.tenantDocument(tenant, "policy");
    }
}
