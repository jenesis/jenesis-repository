package build.jenesis.repository.server.spi;

import module java.base;

import build.jenesis.repository.scope.Scopes;
import build.jenesis.repository.store.Durations;
import build.jenesis.repository.store.Retries;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Epoch;
import build.jenesis.repository.store.Features;
import build.jenesis.repository.store.StoreCache;

/**
 * The credential model. A key is {@code jenk_<tenant>.<secret><checksum>} (see {@link #mint}): the {@code jenk_}
 * prefix and trailing checksum make a leaked key recognisable and offline-validatable by a secret scanner, the
 * tenant travels in the key so the deployment stays stateless and multi-tenant, and only the key's SHA-256 hash is
 * ever stored, never the secret. Under
 * {@code auth/<tenant>/<hash>/} sit two small objects: {@code grants} - a properties map of
 * {@code <scope> -> <rights>} where the scope is a named repository ({@code *} matching all) and the rights are
 * {@code <surface>:<verb>} tokens - and {@code metadata} (label, created, optional expiry, optional last-used).
 * The lookup tries the exact scope, then falls back to {@code *}; a re-read picks up a revoked grant at once because
 * the objects are read through the store on each check, and an expired key is rejected before its grants are
 * consulted.
 *
 * The rights a credential can carry are named here as {@code <surface>:<verb>} string constants rather than a closed
 * enum, so a deployment or a plugged-in surface can introduce a new surface (or a new verb on one) without changing
 * this type. Because a right names its surface, the same key (and the same grants object) can carry repository
 * rights, cache rights, management rights, or any mix, which is how one credential authorizes a combined deployment.
 * This class is the single store for all of them; each surface reads the same key against the same grants.
 */
public final class Authorization {

    /** {@code System.Logger} rather than SLF4J: this module is java.base plus the store, and the one thing it has
     *  to say is a best-effort repair that did not happen. */
    private static final System.Logger LOGGER = System.getLogger(Authorization.class.getName());

    /** The credential space, deployment-wide: {@code .system/auth/<tenant>/...}. */
    private static final String AUTH = Scopes.space(Scopes.AUTH);


    public static final String CACHE_READ = "cache:read";

    public static final String CACHE_WRITE = "cache:write";

    public static final String REPOSITORY_READ = "repository:read";

    public static final String REPOSITORY_WRITE = "repository:write";

    public static final String MANAGE_READ = "manage:read";

    public static final String MANAGE_WRITE = "manage:write";

    private final ArtifactStore store;

    /** The credential, quota and ceiling documents through the read-through, write-through cache - a request pays for
     *  a credential's two documents once per {@code jenreg.cache.ttl}, not three times per request. */
    private final StoreCache cache;
    private final Duration defaultLifetime;
    private final Duration maxLifetime;
    // The strictly-opt-in anonymous role (WANON.1): the rights a keyless caller is granted, as scope -> tokens, exactly
    // the shape a minted credential's grants object has. Empty (the default) means no anonymous access: a keyless
    // request is rejected byte-for-byte as an enforcing deployment rejects it today. Immutable instance state (never a
    // mutable static), parsed once from jenreg.anonymous-rights at bean creation.
    private final Map<String, List<String>> anonymousGrants;

    /** The key the deployment's auth epoch lives under - one token, bumped by every credential mutation. */
    static final String EPOCH = AUTH + "/epoch";

    /** How long a credential document is cached: {@code jenreg.auth.cache-ttl}, fifteen minutes by default. */
    public static final String CACHE_TTL_SETTING = "auth.cache-ttl";

    /** The default, deliberately longer than the deployment-wide {@code cache.ttl}: an authorization happens on
     *  every request, and the epoch below is what keeps a long ttl from also meaning a long revocation window. */
    public static final String DEFAULT_CACHE_TTL_TEXT = "PT15M";

    public static final Duration DEFAULT_CACHE_TTL = Duration.parse(DEFAULT_CACHE_TTL_TEXT);

    /** How long this node may believe its own reading of the epoch. Seconds, not minutes: this is the interval a
     *  revocation takes to reach another node, and one small read amortised over every request the node serves in
     *  that window is the whole of its cost. */
    static final Duration EPOCH_TTL = Duration.ofSeconds(5);

    private final Epoch epoch;

    /** The epoch token this node last read, and when - the pair that turns one document into a fleet-wide
     *  invalidation without a read per request. */
    private volatile String seenEpoch;
    private volatile long seenAt;

    private Authorization(ArtifactStore store) {
        this(store, Duration.ofDays(90), null, Map.of());
    }

    private Authorization(ArtifactStore store, Duration defaultLifetime, Duration maxLifetime,
                          Map<String, List<String>> anonymousGrants) {
        this.store = store;
        this.cache = store == null ? null : StoreCache.of("authorization", store, cacheTtl());
        this.epoch = store == null ? null : new Epoch(store, EPOCH);
        this.defaultLifetime = defaultLifetime;
        this.maxLifetime = maxLifetime;
        this.anonymousGrants = anonymousGrants;
    }

    /** An open deployment: every request is allowed, the explicit {@code jenreg.auth=false} opt-out for the free
     *  single-token deployment. */
    public static Authorization anonymous() {
        return new Authorization(null);
    }

    /** An enforcing deployment: every request is checked against the grants held in {@code store}. */
    public static Authorization enforcing(ArtifactStore store) {
        if (store == null) {
            throw new IllegalArgumentException("An enforcing authorization needs a store to read grants from");
        }
        return new Authorization(store);
    }

    /** The strictly-opt-in anonymous role (WANON.1): return a copy of this authorization that grants a keyless caller
     *  the rights in {@code rights} (a comma-list in the existing grant grammar - a bare {@code <surface>:<verb>} token
     *  granted on every repository, or a {@code <repository>=<token>} entry scoped to one named repository, or the
     *  all-privileges {@code *}). A blank value grants nothing, so a keyless request is rejected exactly as it is today.
     *  Only an enforcing authorization consults it; an anonymous (open) one already allows everything. */
    public Authorization withAnonymousRights(String rights) {
        return new Authorization(store, defaultLifetime, maxLifetime, parseAnonymousGrants(rights));
    }

    /** Parse an {@code anonymous-rights} value into an immutable {@code scope -> tokens} map, the shape a credential's
     *  grants object has (so {@link #authorize} can reuse the same {@link #covers}/{@link #grantedBy} matching). A bare
     *  token is granted on the {@code *} (every-repository) scope; a {@code <scope>=<token>} entry (the scope a
     *  repository name, or {@code <repository>:<prefix>} path scope) is granted on that scope. Blank/garbage entries are
     *  skipped. No new right vocabulary is introduced - a token that names nothing simply confers nothing at match time,
     *  exactly as an unknown token on a minted credential does. */
    static Map<String, List<String>> parseAnonymousGrants(String rights) {
        if (rights == null || rights.isBlank()) {
            return Map.of();
        }
        Map<String, List<String>> grants = new LinkedHashMap<>();
        for (String element : rights.split(",")) {
            String entry = element.strip();
            if (entry.isEmpty()) {
                continue;
            }
            int equals = entry.indexOf('=');
            String scope = equals < 0 ? "*" : entry.substring(0, equals).strip();
            String token = (equals < 0 ? entry : entry.substring(equals + 1)).strip();
            if (scope.isEmpty() || token.isEmpty()) {
                continue;
            }
            grants.computeIfAbsent(scope, unused -> new ArrayList<>()).add(token);
        }
        Map<String, List<String>> immutable = new LinkedHashMap<>();
        grants.forEach((scope, tokens) -> immutable.put(scope, List.copyOf(tokens)));
        return Map.copyOf(immutable);
    }

    /** Whether an {@code anonymous-rights} value would let a keyless caller write or administer - it grants the
     *  all-privileges {@code *}, any {@code <surface>:write} (or a {@code <surface>:*} wildcard covering write), or any
     *  {@code manage:<verb>} admin right. The loud-warning escalation (WANON.1 guardrail 2): anonymous read is a WARN,
     *  anonymous write/admin a governance-level CRITICAL. Mirrored by the posture seeder (which cannot depend on this
     *  module). */
    public static boolean grantsWriteOrAdmin(String rights) {
        if (rights == null || rights.isBlank()) {
            return false;
        }
        for (String element : rights.split(",")) {
            String entry = element.strip();
            int equals = entry.indexOf('=');
            String token = (equals < 0 ? entry : entry.substring(equals + 1)).strip();
            if (token.equals("*")) {
                return true;
            }
            int colon = token.indexOf(':');
            String surface = colon < 0 ? token : token.substring(0, colon);
            String verb = colon < 0 ? "" : token.substring(colon + 1).strip();
            if (surface.equals("manage")) {
                return true;
            }
            if (verb.equals("write") || verb.equals("*")) {
                return true;
            }
        }
        return false;
    }

    /** The deployment-wide default lifetime for a credential minted without an explicit expiry (90 days unless
     *  overridden); a tenant policy may narrow it further (see {@link #policy}). */
    public Authorization withDefaultLifetime(Duration defaultLifetime) {
        if (defaultLifetime == null || defaultLifetime.isZero() || defaultLifetime.isNegative()) {
            throw new IllegalArgumentException("A default credential lifetime must be a positive duration");
        }
        return new Authorization(store, defaultLifetime, maxLifetime, anonymousGrants);
    }

    /** The deployment-wide ceiling on a credential's lifetime (none unless set); no credential of any tenant may
     *  outlive it, and a tenant policy can only cap further, never beyond it. */
    public Authorization withMaxLifetime(Duration maxLifetime) {
        if (maxLifetime != null && (maxLifetime.isZero() || maxLifetime.isNegative())) {
            throw new IllegalArgumentException("A maximum credential lifetime must be a positive duration");
        }
        return new Authorization(store, defaultLifetime, maxLifetime, anonymousGrants);
    }

    /**
     * The deployment's two credential-lifetime dials applied from their raw configuration values, each blank one
     * leaving this authorization unchanged.
     *
     * <p>It takes the config strings rather than {@link Duration}s for the reason {@link #withAnonymousRights} does:
     * the value's grammar, and what a malformed one means, belong to the type that owns the concept rather than to
     * whichever wiring layer happens to read the property.
     *
     * <p>Both dials were honoured where they are read and reachable from no configuration at all - the withers were
     * public, the mint path consulted them, nothing outside a test called them - so every deployment ran the 90-day
     * default with no ceiling and no way to say otherwise, while a <em>tenant</em> policy could already narrow both.
     * That made the missing deployment-wide floor and ceiling the odd gap rather than a deliberate omission.
     *
     * <p>A blank value leaves the shipped posture untouched, deliberately: a ceiling appearing on upgrade would cap
     * every tenant's credentials at once, and an operator who never asked for one would find keys expiring early
     * with nothing in their configuration to explain it. A malformed duration throws rather than falling back - a
     * lifetime silently reverting to 90 days because someone wrote {@code 30d} for {@code P30D} is only noticed when
     * a key outlives what its operator believes it does.
     */
    public Authorization withLifetimes(String defaultLifetime, String maxLifetime) {
        Authorization configured = this;
        if (defaultLifetime != null && !defaultLifetime.isBlank()) {
            configured = configured.withDefaultLifetime(
                    lifetime(defaultLifetime.strip(), "credential-default-lifetime"));
        }
        if (maxLifetime != null && !maxLifetime.isBlank()) {
            configured = configured.withMaxLifetime(lifetime(maxLifetime.strip(), "credential-max-lifetime"));
        }
        return configured;
    }

    /** A duration in the deployment's one grammar, or a refusal naming the key and what a well-formed value looks
     *  like. */
    private static Duration lifetime(String value, String key) {
        try {
            return Durations.parse(value);
        } catch (IllegalArgumentException malformed) {
            throw new IllegalArgumentException("jenreg." + key + " is not a duration: '" + value
                    + "'. Write it as P30D or 30d (thirty days), PT12H or 12h (twelve hours) or P1DT6H.", malformed);
        }
    }

    /**
     * A credential lifetime, a rotation overlap or a trust's token ttl as an operator writes it on any surface:
     * blank is none, so the caller's default applies; otherwise a duration in the deployment's one grammar
     * ({@code P90D}, {@code 90d}, {@code PT12H}). The API, the console and the management surface used to carry
     * one copy each of this line and of {@link #expiry}, which is how one of them came to accept a spelling the
     * others refused.
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

    public Duration defaultLifetime() {
        return defaultLifetime;
    }

    public Duration maxLifetime() {
        return maxLifetime;
    }

    /** A tenant's effective credential-lifetime policy: the default to stamp on a blank-expiry mint and the optional
     *  ceiling beyond which no key may live. {@code maxLifetime} is {@code null} when nothing caps the lifetime. */
    public record Policy(Duration defaultLifetime, Duration maxLifetime) {
    }

    /** A named OIDC trust: an id-token from {@code issuer} (signed by its JWKS) whose {@code audience} and
     *  {@code subject} (a glob, blank for any) match is exchanged for a short-lived credential of {@code ttl} carrying
     *  {@code rights} on {@code scope}. The {@code audience} is required and must be explicit: a blank audience would
     *  match a token bearing <em>any</em> {@code aud} - including one minted for a foreign relying party - so it is
     *  rejected at construction (fail-fast) rather than silently trusting every audience. Every Trust, whether built
     *  in code or parsed from stored config ({@link #trusts}), flows through this canonical constructor, so the check
     *  covers every creation and parse path. */
    public record Trust(String name, String issuer, String audience, String subject, String scope, String rights,
                        Duration ttl) {
        public Trust {
            if (audience == null || audience.isBlank()) {
                throw new IllegalArgumentException("OIDC trust '" + name + "' (issuer " + issuer
                        + ") requires an explicit audience: set its audience to the value your tokens carry in the "
                        + "aud claim; a blank audience would accept a token minted for any relying party");
            }
        }
    }

    /** The effective policy for {@code tenant}: a stored per-tenant default/ceiling layered over the deployment-wide
     *  values, the ceiling being the stricter (shorter) of the two and the default never exceeding it. */
    public Policy policy(String tenant) throws IOException {
        Properties stored = store == null ? null : read(policyPath(tenant));
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
        require();
        if (defaultLifetime != null && (defaultLifetime.isZero() || defaultLifetime.isNegative())) {
            throw new IllegalArgumentException("A default credential lifetime must be a positive duration");
        }
        if (maxLifetime != null && (maxLifetime.isZero() || maxLifetime.isNegative())) {
            throw new IllegalArgumentException("A maximum credential lifetime must be a positive duration");
        }
        Properties properties = new Properties();
        if (defaultLifetime != null) {
            properties.setProperty("default-lifetime", defaultLifetime.toString());
        }
        if (maxLifetime != null) {
            properties.setProperty("max-lifetime", maxLifetime.toString());
        }
        write(policyPath(tenant), properties);
    }

    /** A tenant's stored-content quota in bytes, or {@code 0} when none is set (unlimited). */
    public long quota(String tenant) throws IOException {
        Properties stored = store == null ? null : read(quotaPath(tenant));
        String value = stored == null ? null : stored.getProperty("max-bytes");
        return value == null ? 0L : Long.parseLong(value);
    }

    /** Set ({@code > 0}) or clear ({@code 0}) a tenant's stored-content quota in bytes; a negative value is rejected. */
    public void setQuota(String tenant, long maxBytes) throws IOException {
        require();
        if (maxBytes < 0) {
            throw new IllegalArgumentException("A storage quota must not be negative");
        }
        if (maxBytes == 0) {
            if (cache.readVersioned(quotaPath(tenant)).isPresent()) {
                remove(quotaPath(tenant));
            }
            return;
        }
        Properties properties = new Properties();
        properties.setProperty("max-bytes", Long.toString(maxBytes));
        write(quotaPath(tenant), properties);
    }

    /** A tenant's request rate ceiling in permits per minute, or {@code 0} when none is set (the deployment default). */
    public long rateLimit(String tenant) throws IOException {
        Properties stored = store == null ? null : read(rateLimitPath(tenant));
        String value = stored == null ? null : stored.getProperty("permits-per-minute");
        return value == null ? 0L : Long.parseLong(value);
    }

    /** Set ({@code > 0}) or clear ({@code 0}) a tenant's request rate ceiling in permits per minute; a negative
     *  value is rejected. */
    public void setRateLimit(String tenant, long permitsPerMinute) throws IOException {
        require();
        if (permitsPerMinute < 0) {
            throw new IllegalArgumentException("A rate limit must not be negative");
        }
        if (permitsPerMinute == 0) {
            if (cache.readVersioned(rateLimitPath(tenant)).isPresent()) {
                remove(rateLimitPath(tenant));
            }
            return;
        }
        Properties properties = new Properties();
        properties.setProperty("permits-per-minute", Long.toString(permitsPerMinute));
        write(rateLimitPath(tenant), properties);
    }

    /** A tenant's OIDC trusts, by name. An id-token matching a trust is exchanged for a short-lived credential. */
    public List<Trust> trusts(String tenant) throws IOException {
        Properties stored = store == null ? null : read(oidcPath(tenant));
        if (stored == null) {
            return List.of();
        }
        Set<String> names = new TreeSet<>();
        for (String key : stored.stringPropertyNames()) {
            int dot = key.indexOf('.');
            if (dot > 0) {
                names.add(key.substring(0, dot));
            }
        }
        List<Trust> trusts = new ArrayList<>();
        for (String name : names) {
            String ttl = stored.getProperty(name + ".ttl");
            trusts.add(new Trust(name, stored.getProperty(name + ".issuer"),
                    stored.getProperty(name + ".audience"), stored.getProperty(name + ".subject"),
                    stored.getProperty(name + ".scope"), stored.getProperty(name + ".rights"),
                    ttl == null || ttl.isBlank() ? null : Duration.parse(ttl)));
        }
        return trusts;
    }

    /** Add or replace an OIDC trust by name; {@code issuer}, {@code scope} and {@code rights} are required here, and
     *  an explicit {@code audience} is enforced earlier at {@link Trust} construction. */
    public void setTrust(String tenant, Trust trust) throws IOException {
        require();
        if (trust.name() == null || trust.name().isBlank() || trust.issuer() == null || trust.issuer().isBlank()
                || trust.scope() == null || trust.scope().isBlank() || trust.rights() == null || trust.rights().isBlank()) {
            throw new IllegalArgumentException("An OIDC trust needs a name, an issuer, a scope and rights");
        }
        Properties stored = read(oidcPath(tenant));
        if (stored == null) {
            stored = new Properties();
        }
        String name = trust.name();
        stored.setProperty(name + ".issuer", trust.issuer());
        stored.setProperty(name + ".audience", trust.audience() == null ? "" : trust.audience());
        stored.setProperty(name + ".subject", trust.subject() == null ? "" : trust.subject());
        stored.setProperty(name + ".scope", trust.scope());
        stored.setProperty(name + ".rights", trust.rights());
        stored.setProperty(name + ".ttl", (trust.ttl() == null ? Duration.ofHours(1) : trust.ttl()).toString());
        write(oidcPath(tenant), stored);
    }

    /** Remove a tenant's OIDC trust by name. */
    public void removeTrust(String tenant, String name) throws IOException {
        require();
        Properties stored = read(oidcPath(tenant));
        if (stored == null) {
            return;
        }
        for (String key : new ArrayList<>(stored.stringPropertyNames())) {
            if (key.startsWith(name + ".")) {
                stored.remove(key);
            }
        }
        write(oidcPath(tenant), stored);
    }

    /** A tenant's named roles (a role bundles grant tokens), built-in defaults overlaid with stored custom roles. The
     *  defaults form a hierarchy - read-only reads, deploy adds writes, admin grants everything - so a console can
     *  offer a friendly role instead of raw {@code <surface>:<verb>} tokens. A custom role may override a default. */
    public Map<String, String> roles(String tenant) throws IOException {
        Map<String, String> roles = new LinkedHashMap<>();
        roles.put("read-only", "cache:read,repository:read");
        roles.put("deploy", "cache:read,cache:write,repository:read,repository:write");
        roles.put("admin", "*");
        Properties stored = store == null ? null : read(rolesPath(tenant));
        if (stored != null) {
            for (String name : stored.stringPropertyNames()) {
                roles.put(name, stored.getProperty(name));
            }
        }
        return roles;
    }

    /** Add or replace a custom role on a tenant; a built-in name can be overridden. */
    public void setRole(String tenant, String name, String tokens) throws IOException {
        require();
        if (name == null || name.isBlank() || tokens == null || tokens.isBlank()) {
            throw new IllegalArgumentException("A role needs a name and tokens");
        }
        Properties stored = read(rolesPath(tenant));
        if (stored == null) {
            stored = new Properties();
        }
        stored.setProperty(name.trim(), tokens.trim());
        write(rolesPath(tenant), stored);
    }

    /** Remove a stored custom role (a built-in default reappears unless it was overriding one). */
    public void removeRole(String tenant, String name) throws IOException {
        require();
        Properties stored = read(rolesPath(tenant));
        if (stored == null) {
            return;
        }
        stored.remove(name);
        write(rolesPath(tenant), stored);
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

    public boolean enforced() {
        return store != null;
    }

    /** The verdict for a request, mapped at the HTTP layer to 200/201, 401 and 403 respectively. */
    public enum Decision {
        ALLOWED,
        UNAUTHORIZED,
        FORBIDDEN
    }

    /** A credential as the management surface sees it: its hash, metadata and per-scope grants ({@code scope ->
     *  comma-separated tokens}). {@code label}, {@code expires}, {@code lastUsed}, {@code lastUsedAddress} and
     *  {@code allowedAddresses} (a comma-separated source-IP allowlist) may be {@code null}; {@code useCount} is the
     *  running number of authorized uses. */
    public record Credential(String hash, String label, Instant created, Instant expires, Instant lastUsed,
                             String lastUsedAddress, long useCount, String allowedAddresses,
                             Map<String, String> grants) {
    }

    /**
     * What a grant is held by. Rights are the noun and this is the variable: the same vocabulary
     * ({@code repository:read}, {@code cache:write}, {@code manage:write}, {@code *}) is held by a machine
     * credential, by a person, by a named group of people, and by the keyless caller - so anonymous access becomes
     * a holder with a small grant rather than a mechanism of its own, and a person's rights are written down the
     * way a key's are instead of inferred from the edition and the tenancy mode.
     *
     * <p><strong>What is true today is the key, and only the key.</strong> This declares the vocabulary and keys
     * the store by it; {@link #authorize} still resolves {@link Kind#CREDENTIAL} and nothing else, and a person is
     * still authorized by the console's own mechanism. The other three kinds are the shape the rest is built into,
     * and each becomes writable in the change that makes it enforceable - never before, because a stored grant
     * nothing consults reads as access granted.
     *
     * <p>A subject is one path segment pair, so a grant lookup stays the point read the cost model depends on.
     * What it deliberately does NOT do is resolve membership: the rights a person effectively holds are the union
     * over their identity, their groups and anonymous, and computing that union per request would be the
     * unbounded fan-out the bounded-read rule forbids. That union is a document maintained on the write path.
     */
    public enum Kind {

        /** A minted key, identified by its hash. The only kind that authenticates by presenting a secret. */
        CREDENTIAL,

        /** A person, identified as the sign-in mechanism names them (an {@code oidc/<sub>}, a SAML name id). */
        PRINCIPAL,

        /** A named collection of principals within a tenant, holding rights exactly as a person or key does. */
        GROUP,

        /** The keyless caller - today a setting rather than a row; see {@link Subject#ANONYMOUS}. */
        ANONYMOUS;

        /** The path segment this kind's subjects live under. Lower case, so the store key reads as prose. */
        public String segment() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * The tenant a deployment-wide grant is held under.
     *
     * <p>Some rights are not any tenant's: an operator who administers the deployment itself holds them
     * everywhere, and expressing that as a grant in each tenant would be a set that has to be maintained as
     * tenants come and go - one missed and the operator is locked out of the newest tenant, one stale and a
     * removed operator keeps a tenant nobody thought to check.
     *
     * <p>It is a tenant segment no tenant can occupy rather than a separate space, so every path, listing and
     * purge already handles it: a scope name is {@code [A-Za-z0-9_-]+} and cannot carry a dot, so nothing an
     * operator may create collides with it and no enumeration that derives tenants from store names offers it as
     * one. The same reason {@code .system} is safe from a tenant called {@code system}.
     */
    public static final String DEPLOYMENT = ".deployment";

    /** A grant's holder: a {@link Kind} and an id unique within that kind and tenant. */
    public record Subject(Kind kind, String id) {

        public Subject {
            Objects.requireNonNull(kind, "A grant is held by some kind of subject");
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("A " + kind.segment() + " subject needs an id");
            }
            // A slash is legitimate in a PRINCIPAL's id and in nothing else: a person is named as the sign-in
            // mechanism names them - github/octocat, oidc/<sub>, keylogin/<name> - and that qualification is what
            // makes two providers' identically-named users different people. The id is NOT a key: subjectPath
            // encodes it into one segment, so a slash cannot graft a subject into another's subtree. What is still
            // refused everywhere is a traversal segment, which encoding would carry through faithfully.
            if (kind != Kind.PRINCIPAL && id.indexOf('/') >= 0) {
                throw new IllegalArgumentException("A " + kind.segment() + " id may not contain '/': " + id);
            }
            for (String segment : id.split("/", -1)) {
                if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                    throw new IllegalArgumentException("A subject id may not carry an empty or traversal "
                            + "segment: " + id);
                }
            }
        }

        /** The subject a minted key authorizes as. */
        public static Subject credential(String hash) {
            return new Subject(Kind.CREDENTIAL, hash);
        }

        /** The subject a signed-in person authorizes as. */
        public static Subject principal(String id) {
            return new Subject(Kind.PRINCIPAL, id);
        }

        /** The subject every member of a named group holds through. */
        public static Subject group(String name) {
            return new Subject(Kind.GROUP, name);
        }

        /**
         * The keyless caller's subject: unnamed within its kind, so it takes a fixed id - {@code -} rather than a
         * word, because a word is a name a real subject could also be given.
         *
         * <p><strong>Not yet the source of truth.</strong> A keyless caller's rights come from the
         * {@code anonymous-rights} setting, held in memory per node and deployment-wide, and {@link #authorize}
         * still reads them from there. This subject is where they belong, and moving them is a decision rather
         * than a refactor: it settles whether a grant may be expressed in configuration at all, and a keyless
         * request names no tenant, so the row is deployment-wide until the request path carries one.
         */
        public static final Subject ANONYMOUS = new Subject(Kind.ANONYMOUS, "-");
    }

    /** Whether {@code key} carries {@code required} (a {@code <surface>:<verb>} token) for {@code scope} (a
     *  repository name, or {@code null} for the default); an expired key is {@code UNAUTHORIZED}. */
    public Decision authorize(String key, String scope, String required) throws IOException {
        return authorize(key, scope, null, required);
    }

    /** Whether {@code key} carries {@code required} for {@code scope} on the in-repository {@code path} ({@code null}
     *  when the surface has no path, as the cache). A grant scope is a repository name (or {@code *}), optionally
     *  narrowed to a path prefix as {@code <repo>:<prefix>} - a prefix grant covers the request only when {@code path}
     *  lies under it - so one credential can carry repository-wide and path-scoped rights at once. An expired key is
     *  {@code UNAUTHORIZED}, an unprovisioned one {@code FORBIDDEN}. */
    public Decision authorize(String key, String scope, String path, String required) throws IOException {
        if (store == null) {
            return Decision.ALLOWED;
        }
        freshen();   // another node's grant or revocation, at most EPOCH_TTL old - see freshen()
        // WANON.1 - the one choke-point: an enforcing deployment that sees a request with NO credential decides it
        // against the strictly-opt-in anonymous grant set, reusing the exact covers()/grantedBy() matching a minted
        // credential uses (no second code path). Default (empty grants) => UNAUTHORIZED, byte-for-byte today's keyless
        // rejection. A present-but-malformed key is NOT keyless: it stays a failed authentication attempt below.
        if (key == null || key.isBlank()) {
            return anonymousDecision(scope, path, required);
        }
        if (!wellFormed(key)) {
            return Decision.UNAUTHORIZED;
        }
        String tenant = tenantOf(key);
        String hash = hash(key);
        Instant expires = instant(read(metadataPath(tenant, hash)), "expires");
        if (expires != null && Instant.now().isAfter(expires)) {
            return Decision.UNAUTHORIZED;
        }
        Properties grants = read(grantsPath(tenant, hash));
        if (grants == null) {
            return Decision.FORBIDDEN;
        }
        String repository = scope == null || scope.isBlank() ? "*" : scope;
        for (String grantScope : grants.stringPropertyNames()) {
            if (!covers(grantScope, repository, path)) {
                continue;
            }
            for (String token : grants.getProperty(grantScope).split(",")) {
                if (grantedBy(token, required)) {
                    return Decision.ALLOWED;
                }
            }
        }
        return Decision.FORBIDDEN;
    }

    /** The verdict for a keyless (no-credential) request under an enforcing deployment (WANON.1): {@code ALLOWED} iff
     *  the strictly-opt-in anonymous grant set covers the required {@code <surface>:<verb>} for {@code repository} on
     *  {@code path}, reusing the same {@link #covers}/{@link #grantedBy} logic a minted credential is matched by; else
     *  {@code UNAUTHORIZED} - exactly the {@code 401} a keyless request takes today. Empty grants (the default) cover
     *  nothing, so a keyless request is rejected byte-for-byte as it is today. */
    private Decision anonymousDecision(String scope, String path, String required) {
        if (anonymousGrants.isEmpty()) {
            return Decision.UNAUTHORIZED;
        }
        String repository = scope == null || scope.isBlank() ? "*" : scope;
        for (Map.Entry<String, List<String>> grant : anonymousGrants.entrySet()) {
            if (!covers(grant.getKey(), repository, path)) {
                continue;
            }
            for (String token : grant.getValue()) {
                if (grantedBy(token, required)) {
                    return Decision.ALLOWED;
                }
            }
        }
        return Decision.UNAUTHORIZED;
    }

    /**
     * Whether {@code subject} carries {@code required} for {@code scope} on the in-repository {@code path}.
     *
     * <p>The same matching a presented key takes - {@link #covers} and {@link #grantedBy} over the subject's own
     * grants object - reached without a secret, because a principal does not present one: a person is
     * authenticated by the sign-in mechanism and authorized here. That is the whole of "one vocabulary, several
     * holders": a per-tenant console role and a key scoped to that tenant become the same grant, matched by the
     * same code, instead of two models that have to be kept agreeing by hand.
     *
     * <p>Only the kinds {@link #setGrant(String, Subject, String, String)} will write are answered, and for the
     * same reason: answering about the keyless caller here would silently return FORBIDDEN for a subject whose
     * rights are real but held in configuration, which reads as a decision and is an absence.
     *
     * <p><b>Three point reads, and it stays three however a directory is organised.</b> A principal holds what
     * they were granted directly, what the deployment granted them, and what their groups grant - and that third
     * one is read as the pre-computed union in their {@code derived} document rather than by enumerating groups
     * here. The enumeration is real, it is just paid on the write that changes it; doing it here would make the
     * cost of every request a function of how many groups an operator has, which is the unbounded read the
     * bounded-read rule forbids on the hottest path in the product.
     */
    public Decision authorize(String tenant, Subject subject, String scope, String path, String required)
            throws IOException {
        enforceable(subject);
        if (store == null) {
            return Decision.ALLOWED;
        }
        freshen();   // another node's grant or revocation, at most EPOCH_TTL old - see freshen()
        String repository = scope == null || scope.isBlank() ? "*" : scope;
        // The tenant's own grant first, then the deployment-wide one. Two point reads rather than one, and
        // deliberately not a set of per-tenant rows: an operator who administers the deployment holds their rights
        // in a tenant created after they were granted them, which a per-tenant set could only manage by being
        // rewritten every time a tenant appears.
        if (holds(read(grantsPath(tenant, subject)), repository, path, required)
                || (!DEPLOYMENT.equals(tenant)
                        && holds(read(grantsPath(DEPLOYMENT, subject)), repository, path, required))) {
            return Decision.ALLOWED;
        }
        // What their groups grant, as the union a write already computed. Only a principal has one: a credential
        // is not a member of anything, and a group's own document is what this reads through rather than about.
        if (subject.kind() == Kind.PRINCIPAL
                && holds(read(derivedPath(tenant, subject)), repository, path, required)) {
            return Decision.ALLOWED;
        }
        return Decision.FORBIDDEN;
    }

    /** Whether a grants object carries {@code required} for this repository and path - the matching a presented key
     *  takes, applied to whichever subject row the caller is asking about. */
    private boolean holds(Properties grants, String repository, String path, String required) {
        if (grants == null) {
            return false;
        }
        for (String grantScope : grants.stringPropertyNames()) {
            if (!covers(grantScope, repository, path)) {
                continue;
            }
            for (String token : grants.getProperty(grantScope).split(",")) {
                if (grantedBy(token, required)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Whether {@code subject} carries {@code required} for {@code scope}, on no particular path. */
    public Decision authorize(String tenant, Subject subject, String scope, String required) throws IOException {
        return authorize(tenant, subject, scope, null, required);
    }

    /**
     * Forget everything this node has cached about who holds what, and tell every other node to do the same.
     *
     * <p><b>For a writer that changed the auth store without going through this class.</b> Every mutation here
     * invalidates the entry it wrote and bumps the epoch, so a peer clears within {@link #EPOCH_TTL}. A caller
     * that deletes auth keys directly - the tenant purge does, because it removes whole namespaces through the
     * store rather than a credential at a time - leaves this node's cache holding grants whose objects are gone.
     * Reads are answered from that cache until it ages out, which for a purge means a deleted tenant's
     * credentials still authorize and its console members still read as members.
     *
     * <p>It is deliberately blunt: a purge removes an unbounded set of keys, so there is no entry list to
     * invalidate, and clearing costs one re-read of whatever is asked for next.
     */
    public void forget() throws IOException {
        if (cache == null) {
            return;
        }
        cache.clear();
        mutated();
    }

    /** Whether a strictly-opt-in anonymous role is configured (a non-empty {@code anonymous-rights}); the console and
     *  {@code /api/capabilities} read the raw value to advertise it, this is the plain predicate for a decision. */
    public boolean anonymousEnabled() {
        return !anonymousGrants.isEmpty();
    }

    /** Whether a grant scope - a repository ({@code *} matching any), optionally narrowed by a {@code :<prefix>} path
     *  prefix - covers a request for {@code repository} on {@code path}. A prefix grant matches only a non-null path
     *  at or under the prefix on a segment boundary, so {@code maven/com/acme} covers {@code maven/com/acme/x} but not
     *  {@code maven/com/acmexyz}; a bare repository grant covers any path. */
    private static boolean covers(String grantScope, String repository, String path) {
        int colon = grantScope.indexOf(':');
        String repositoryPart = colon < 0 ? grantScope : grantScope.substring(0, colon);
        if (!repositoryPart.equals("*") && !repositoryPart.equals(repository)) {
            return false;
        }
        if (colon < 0) {
            return true;
        }
        if (path == null) {
            return false;
        }
        String prefix = trimPath(grantScope.substring(colon + 1));
        String target = trimPath(path);
        return target.equals(prefix) || target.startsWith(prefix + "/");
    }

    private static String trimPath(String path) {
        String value = path.strip();
        if (value.endsWith("/*")) {
            value = value.substring(0, value.length() - 2);
        }
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == '/') {
            start++;
        }
        while (end > start && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(start, end);
    }

    /** Whether a granted token confers a required {@code <surface>:<verb>}: the exact token, the per-surface
     *  wildcard {@code <surface>:*}, or the all-privileges {@code *}. An unknown token confers nothing. */
    private static boolean grantedBy(String granted, String required) {
        String trimmed = granted.trim();
        if (trimmed.equals("*") || trimmed.equals(required)) {
            return true;
        }
        int colon = required.indexOf(':');
        return colon > 0 && trimmed.equals(required.substring(0, colon) + ":*");
    }

    /** Set {@code key}'s rights for {@code scope} by key, replacing any held for that scope; for provisioning when
     *  the secret is still in hand. */
    public void grant(String key, String scope, String... rights) throws IOException {
        setGrant(tenantOf(key), hash(key), scope, String.join(",", rights));
    }

    /** Grant every privilege for {@code scope} by key - the all-privileges {@code *} token, an owner/admin key. */
    public void grantAll(String key, String scope) throws IOException {
        setGrant(tenantOf(key), hash(key), scope, "*");
    }

    /**
     * Provision {@code key} as the deployment's bootstrap credential - the {@code jenreg.bootstrap-key} contract: a
     * non-expiring key labelled {@code bootstrap} holding every privilege on every repository of the tenant the key
     * itself names. Idempotent, since the key's own hash is its identity: re-provisioning the same key on every boot
     * converges rather than accumulating. A blank key provisions nothing and answers {@code null}; a malformed one
     * is refused, because an operator who set it expects a working key and a silently dropped typo would leave them
     * locked out with no line saying why.
     *
     * @return the tenant the key was provisioned for, or {@code null} for a blank key
     * @throws IllegalArgumentException if the key is not of the form {@code jenk_<tenant>.<secret><checksum>}
     */
    public String bootstrap(String key) throws IOException {
        if (key == null || key.isBlank()) {
            return null;
        }
        String stripped = key.strip();
        // tenantOf answers null rather than throwing for anything it does not recognise, so the check is on the answer.
        String tenant = tenantOf(stripped);
        if (tenant == null || tenant.isBlank()) {
            throw new IllegalArgumentException("jenreg.bootstrap-key is not a well-formed key: it must look like "
                    + "jenk_<tenant>.<secret><checksum>, since the tenant it provisions is read out of the key itself");
        }
        String hash = hash(stripped);
        provision(tenant, hash, "bootstrap", null);
        setGrant(tenant, hash, "*", "*");
        return tenant;
    }

    /** The credential hashes provisioned for a tenant, for the management surface to list. */
    public List<String> credentials(String tenant) {
        if (store == null) {
            return List.of();
        }
        return store.list(kindPrefix(tenant, Kind.CREDENTIAL));
    }

    private static String kindPrefix(String tenant, Kind kind) {
        return AUTH + "/" + tenant + "/" + kind.segment();
    }

    /** One page of a tenant's credential hashes, in key order: at most {@code limit} names strictly after
     *  {@code after} ({@code null} from the start) and the name to continue from, {@code null} on the last page. The
     *  face a management surface pages through, never the whole listing. It names credentials and nothing else:
     *  they used to sit directly under the tenant beside its {@code policy} and {@code quota} objects, so this
     *  listing returned those too and a caller had to know that {@link #credential} answers empty for them. */
    public CredentialPage credentials(String tenant, String after, int limit) {
        if (store == null) {
            return new CredentialPage(List.of(), null);
        }
        List<String> names = new ArrayList<>();
        store.page(kindPrefix(tenant, Kind.CREDENTIAL),
                after == null ? "" : after, ArtifactStore.oneMoreThan(limit), names::add);
        boolean more = names.size() > limit;
        List<String> page = more ? names.subList(0, limit) : names;
        return new CredentialPage(List.copyOf(page), more ? page.getLast() : null);
    }

    /** One page of credential hashes and the cursor the next page resumes after ({@code null} when exhausted). */
    public record CredentialPage(List<String> hashes, String next) {
    }

    /** A credential's metadata and grants, or empty if neither is present. */
    public Optional<Credential> credential(String tenant, String hash) throws IOException {
        Properties grants = read(grantsPath(tenant, hash));
        Properties metadata = read(metadataPath(tenant, hash));
        if (grants == null && metadata == null) {
            return Optional.empty();
        }
        Map<String, String> scopes = new TreeMap<>();
        if (grants != null) {
            for (String scope : grants.stringPropertyNames()) {
                scopes.put(scope, grants.getProperty(scope));
            }
        }
        String label = metadata == null ? null : metadata.getProperty("label");
        String allowedAddresses = metadata == null ? null : metadata.getProperty("allowed-ips");
        String lastUsedAddress = metadata == null ? null : metadata.getProperty("lastUsedAddress");
        long useCount = metadata == null ? 0 : Long.parseLong(metadata.getProperty("useCount", "0"));
        return Optional.of(new Credential(hash, label,
                instant(metadata, "created"), instant(metadata, "expires"), instant(metadata, "lastUsed"),
                lastUsedAddress, useCount, allowedAddresses, scopes));
    }

    /** One page of a tenant's subject ids of one kind, in key order: at most {@code limit} ids strictly after
     *  {@code after} ({@code null} from the start), and the id to continue from ({@code null} on the last page).
     *  The ids are decoded, so a caller sees {@code github/octocat} rather than the segment it is keyed under -
     *  and passes the decoded one straight back as the cursor. */
    public SubjectPage subjects(String tenant, Kind kind, String after, int limit) {
        if (store == null) {
            return new SubjectPage(List.of(), null);
        }
        freshen();   // a subject provisioned on another node is a member this listing must not omit
        List<String> names = new ArrayList<>();
        store.page(kindPrefix(tenant, kind),
                after == null ? "" : segment(after), ArtifactStore.oneMoreThan(limit), names::add);
        boolean more = names.size() > limit;
        List<String> page = more ? names.subList(0, limit) : names;
        List<String> ids = new ArrayList<>(page.size());
        for (String name : page) {
            ids.add(unsegment(name));
        }
        return new SubjectPage(List.copyOf(ids), more ? ids.getLast() : null);
    }

    /** One page of subject ids and the cursor the next page resumes after ({@code null} when exhausted). */
    public record SubjectPage(List<String> ids, String next) {
    }

    /**
     * The scope-to-rights map a subject holds in {@code tenant}, empty when it holds none.
     *
     * <p>It freshens first, for the same reason {@link #authorize} does and it is not optional here: reads go
     * through a {@link StoreCache}, so a grant written by another node - or by another {@code Authorization} over
     * the same store, which is what a console request and a provisioning path are - would otherwise be answered
     * from a cached absence until the entry aged out. The console's per-request role check is this method, so a
     * missing freshen reads as "not a member" for the cache's lifetime, which is a membership that silently does
     * not exist.
     */
    public Map<String, String> grants(String tenant, Subject subject) throws IOException {
        freshen();
        Properties grants = read(grantsPath(tenant, subject));
        if (grants == null) {
            return Map.of();
        }
        Map<String, String> scopes = new TreeMap<>();
        for (String scope : grants.stringPropertyNames()) {
            scopes.put(scope, grants.getProperty(scope));
        }
        return Map.copyOf(scopes);
    }

    /** A subject's human label - a credential's name, a person's display login - or empty when it has none.
     *  Freshens for the reason {@link #grants} does: it is read beside the grants, on the same request. */
    public Optional<String> label(String tenant, Subject subject) throws IOException {
        freshen();
        Properties metadata = read(metadataPath(tenant, subject));
        String label = metadata == null ? null : metadata.getProperty("label");
        return label == null || label.isBlank() ? Optional.empty() : Optional.of(label);
    }

    /** Set a subject's human label, leaving the rest of its metadata alone. A blank value removes it. */
    public void setLabel(String tenant, Subject subject, String label) throws IOException {
        require();
        mutate(metadataPath(tenant, subject), metadata -> {
            metadata.putIfAbsent("created", Instant.now().toString());
            if (label == null || label.isBlank()) {
                metadata.remove("label");
            } else {
                metadata.setProperty("label", label.trim());
            }
        });
    }

    /** Remove a subject entirely - every grant it holds and its metadata - so nothing of it is left to read back
     *  as a holder with no rights. Silent when there was nothing there, which is what makes a repeated revoke and
     *  a revoke racing another one both answer the same way. */
    public void removeSubject(String tenant, Subject subject) throws IOException {
        require();
        List<String> orphaned = subject.kind() == Kind.GROUP ? allMembers(tenant, subject.id()) : List.of();
        cache.delete(grantsPath(tenant, subject));
        cache.delete(metadataPath(tenant, subject));
        if (subject.kind() == Kind.PRINCIPAL) {
            cache.delete(derivedPath(tenant, subject));
        }
        for (String member : orphaned) {
            cache.delete(memberPath(tenant, subject.id(), member));
        }
        mutated();
        // After the deletes, and read from the list taken before them: a member re-derived while the group's
        // grants were still readable would be handed back the rights this call is removing.
        for (String member : orphaned) {
            rederive(tenant, member);
        }
    }

    /** Every member of a group, for the two callers that must act on the whole set rather than a page of it. */
    private List<String> allMembers(String tenant, String group) {
        List<String> all = new ArrayList<>();
        for (String cursor = null;;) {
            SubjectPage page = members(tenant, group, cursor, DERIVE_PAGE);
            all.addAll(page.ids());
            if (page.next() == null) {
                return all;
            }
            cursor = page.next();
        }
    }

    /**
     * Put {@code principal} in {@code group}, and give them the group's rights from the next request.
     *
     * <p>The group is not required to exist first, and that is deliberate rather than lax: a group with members and
     * no grants confers nothing, so there is no state here in which an unmade decision reads as access. It is also
     * the order an identity provider pushes in, which would otherwise need a create-then-fill dance this cannot
     * make atomic anyway.
     *
     * <p>Re-deriving the one principal is part of the write. A membership that took effect on the next repair
     * rather than the next request would be a grant that is real in the store and absent from every decision,
     * which is the failure mode this whole model exists to remove.
     */
    public void addMember(String tenant, String group, String principal) throws IOException {
        require();
        Subject member = Subject.principal(principal);   // rejects a traversal id before it reaches a key
        Properties recorded = new Properties();
        recorded.setProperty("joined", Instant.now().toString());
        write(memberPath(tenant, group, member.id()), recorded);
        rederive(tenant, member.id());
    }

    /** Take {@code principal} out of {@code group}, and take the group's rights off them from the next request.
     *  Silent when they were not a member, so a repeated removal and one racing another answer the same way. */
    public void removeMember(String tenant, String group, String principal) throws IOException {
        require();
        Subject member = Subject.principal(principal);
        remove(memberPath(tenant, group, member.id()));
        rederive(tenant, member.id());
    }

    /** One page of a group's member ids, in key order - the same shape and cursor rule as {@link #subjects}. */
    public SubjectPage members(String tenant, String group, String after, int limit) {
        if (store == null) {
            return new SubjectPage(List.of(), null);
        }
        freshen();
        List<String> names = new ArrayList<>();
        store.page(membersPrefix(tenant, group),
                after == null ? "" : segment(after), ArtifactStore.oneMoreThan(limit), names::add);
        boolean more = names.size() > limit;
        List<String> page = more ? names.subList(0, limit) : names;
        List<String> ids = new ArrayList<>(page.size());
        for (String name : page) {
            ids.add(unsegment(name));
        }
        return new SubjectPage(List.copyOf(ids), more ? ids.getLast() : null);
    }

    /**
     * Recompute what {@code principal} holds through the groups of {@code tenant}, and write it down.
     *
     * <p>This is the fan-out, moved off the authorization path and paid where it is affordable. It walks the
     * tenant's groups - an operator-created set, so tens rather than millions - and unions the grants of the ones
     * this principal belongs to. A scope granted by two groups keeps both their rights, joined, because a union is
     * the only answer that does not depend on which group was read first.
     *
     * <p>Idempotent, so the repair and the write path can both call it and a second call changes nothing. It
     * writes even when the union is empty, because the absence of the document and an empty one mean different
     * things to a reader that has to distinguish "no groups" from "never derived".
     */
    public void rederive(String tenant, String principal) throws IOException {
        require();
        Subject subject = Subject.principal(principal);
        Properties union = new Properties();
        for (String group : groups(tenant)) {
            if (!store.exists(memberPath(tenant, group, subject.id()))) {
                continue;
            }
            Properties held = read(grantsPath(tenant, Subject.group(group)));
            if (held == null) {
                continue;
            }
            for (String scope : held.stringPropertyNames()) {
                String already = union.getProperty(scope);
                union.setProperty(scope, already == null ? held.getProperty(scope)
                        : already + "," + held.getProperty(scope));
            }
        }
        write(derivedPath(tenant, subject), union);
    }

    /**
     * Recompute every member of {@code group} - what a change to the group's own rights obliges.
     *
     * <p>Bounded by the group's membership rather than by the tenant's population, and off the request path: a
     * grant an operator gives a group of five hundred costs five hundred small writes once, against five hundred
     * fan-outs on every request for as long as the grant stands.
     */
    public void rederiveGroup(String tenant, String group) throws IOException {
        for (String cursor = null;;) {
            SubjectPage page = members(tenant, group, cursor, DERIVE_PAGE);
            for (String member : page.ids()) {
                rederive(tenant, member);
            }
            if (page.next() == null) {
                return;
            }
            cursor = page.next();
        }
    }

    /**
     * Recompute every principal of {@code tenant} - the repair, for the drift a partial write leaves behind.
     *
     * <p>A node that dies between writing a group's grants and re-deriving the last of its members leaves those
     * members holding what the group used to grant. Nothing detects that on a read, because a stale derived
     * document is a well-formed one; so it is recomputed on a cadence rather than checked. Every principal, not
     * every member of every group, because a principal whose last group dropped them has a derived document
     * nothing else would ever revisit.
     */
    public void rederive(String tenant) throws IOException {
        for (String cursor = null;;) {
            SubjectPage page = subjects(tenant, Kind.PRINCIPAL, cursor, DERIVE_PAGE);
            for (String principal : page.ids()) {
                rederive(tenant, principal);
            }
            if (page.next() == null) {
                return;
            }
            cursor = page.next();
        }
    }

    /**
     * Recompute every principal of every tenant this store holds grants for, and never fail a boot doing it.
     *
     * <p><b>The repair runs at start-up, because start-up is when the drift it repairs has just happened.</b> The
     * only way a derived document goes stale is a node dying between writing a group's grants and re-deriving the
     * last of its members - every ordinary path re-derives before it returns - and a process that died is a
     * process that comes back. A weekly sweep would leave the window open for a week to fix something a restart
     * closes in seconds, and would need a scheduler the free core does not have: the walk's consumers are handed a
     * repository-scoped store and no tenant, and the free rebuild driver is switched off in the composition that
     * has a scheduler of its own.
     *
     * <p>Best effort by construction. A read-only deployment cannot write a derived document and must still start;
     * so must a deployment whose store is briefly unreachable. What a failure costs is the repair, not the boot,
     * and it says so in the log rather than in an exception nobody can act on at that moment.
     *
     * <p>The tenants come from the auth space itself rather than from the tenancy SPI, which is what keeps this one
     * method rather than one per tenancy mode: a tenant with no subjects has nothing to re-derive, and a tenant
     * that has any is here by definition.
     */
    public void repairDerivedGrants() {
        if (store == null) {
            return;
        }
        try {
            for (String tenant : store.list(AUTH)) {
                rederive(tenant);
            }
        } catch (IOException | RuntimeException failed) {
            // Deliberately everything, for the reason the javadoc gives: a store that refuses this write is a
            // deployment that must still serve, and the cost of the failure is a group grant that may be stale
            // until the next start - not a node that will not come up.
            LOGGER.log(System.Logger.Level.WARNING, "Could not repair group-derived grants at start-up; a member "
                    + "whose derivation was interrupted may hold what their group used to grant until this "
                    + "succeeds", failed);
        }
    }

    /** Every group name in {@code tenant}. Operator-created and therefore enumerable - and read whole only off
     *  the request path, which is the difference between this and the fan-out {@link #derivedPath} removes. */
    private List<String> groups(String tenant) {
        List<String> all = new ArrayList<>();
        for (String cursor = null;;) {
            SubjectPage page = subjects(tenant, Kind.GROUP, cursor, DERIVE_PAGE);
            all.addAll(page.ids());
            if (page.next() == null) {
                return all;
            }
            cursor = page.next();
        }
    }

    /** How many subjects a derivation walk takes at a time. Small objects, off the request path: the page exists
     *  so the walk is bounded in memory, not because the count is tuned. */
    private static final int DERIVE_PAGE = 500;

    /** Record a freshly minted credential's metadata (created now, an optional label and optional expiry); the
     *  caller has already hashed the key. Grants are added with {@link #setGrant}. */
    public void provision(String tenant, String hash, String label, Instant expires) throws IOException {
        Properties metadata = new Properties();
        metadata.setProperty("created", Instant.now().toString());
        if (label != null && !label.isBlank()) {
            metadata.setProperty("label", label.trim());
        }
        if (expires != null) {
            metadata.setProperty("expires", expires.toString());
        }
        write(metadataPath(tenant, hash), metadata);
    }

    /** Set the rights for {@code scope} on a credential by hash, replacing any held for that scope. */
    public void setGrant(String tenant, String hash, String scope, String tokens) throws IOException {
        setGrant(tenant, Subject.credential(hash), scope, tokens);
    }

    /**
     * Set the rights for {@code scope} on any subject, replacing any held for that scope.
     *
     * <p>This was private until each kind became enforceable, on the rule that a caller able to grant to a subject
     * {@link #authorize} does not resolve would write a row that reads as access and confers none. That is now
     * true of every kind but {@link Kind#ANONYMOUS}, which still takes its rights from configuration and is
     * therefore still refused - a security surface may answer "no", but it may not answer "yes" and mean nothing.
     *
     * <p>A {@link Kind#GROUP} grant re-derives the group's members before it returns, so it is in force on the
     * next request rather than at the next repair. That is the cost of the grant rather than of the requests
     * after it: one small write per member, once, against a fan-out on every authorization for as long as the
     * grant stands.
     */
    public void setGrant(String tenant, Subject subject, String scope, String tokens) throws IOException {
        enforceable(subject);
        require();
        mutate(grantsPath(tenant, subject), grants -> grants.setProperty(scope, tokens));
        if (subject.kind() == Kind.GROUP) {
            rederiveGroup(tenant, subject.id());
        }
    }

    /** Remove the rights for {@code scope} on a credential by hash. */
    public void removeGrant(String tenant, String hash, String scope) throws IOException {
        removeGrant(tenant, Subject.credential(hash), scope);
    }

    /** Whether a subject's grants are consulted by {@link #authorize} - and therefore whether writing one means
     *  anything. See {@link #setGrant(String, Subject, String, String)} for why this refuses rather than stores. */
    private static void enforceable(Subject subject) {
        if (subject.kind() == Kind.ANONYMOUS) {
            throw new IllegalArgumentException("Rights cannot be granted to the anonymous subject yet: authorize "
                    + "does not resolve one, so the grant would read as access and confer none. The keyless "
                    + "caller's rights still arrive from configuration.");
        }
    }

    /** Remove the rights for {@code scope} on any subject, re-deriving a group's members so the removal is in
     *  force on the next request for exactly the reason {@link #setGrant} re-derives them. Public for the reason
     *  {@link #setGrant} is: a surface that can give a group rights has to be able to take them back. */
    public void removeGrant(String tenant, Subject subject, String scope) throws IOException {
        require();
        mutate(grantsPath(tenant, subject), grants -> grants.remove(scope));
        if (subject.kind() == Kind.GROUP) {
            rederiveGroup(tenant, subject.id());
        }
    }

    /** Set or clear a credential's expiry; {@code null} removes it (the key no longer expires) unless the tenant
     *  policy caps the lifetime, in which case a cleared or too-distant expiry is pulled back to the ceiling. */
    public void setExpiry(String tenant, String hash, Instant expires) throws IOException {
        require();
        Instant capped = cap(expires, policy(tenant).maxLifetime());
        Properties metadata = read(metadataPath(tenant, hash));
        if (metadata == null) {
            metadata = new Properties();
            metadata.setProperty("created", Instant.now().toString());
        }
        if (capped == null) {
            metadata.remove("expires");
        } else {
            metadata.setProperty("expires", capped.toString());
        }
        write(metadataPath(tenant, hash), metadata);
    }

    /** Set or clear ({@code null}/blank) a credential's source-IP allowlist: comma-separated CIDRs or plain
     *  addresses. A request whose source address lies in none of them is forbidden even with a valid key. */
    public void setAllowedAddresses(String tenant, String hash, String addresses) throws IOException {
        require();
        Properties metadata = read(metadataPath(tenant, hash));
        if (metadata == null) {
            metadata = new Properties();
            metadata.setProperty("created", Instant.now().toString());
        }
        if (addresses == null || addresses.isBlank()) {
            metadata.remove("allowed-ips");
        } else {
            metadata.setProperty("allowed-ips", addresses.trim());
        }
        write(metadataPath(tenant, hash), metadata);
    }

    /** Whether a credential's source-IP allowlist admits {@code clientAddress}: true when it sets none (the common
     *  case) and when the address falls in a listed CIDR or matches a listed plain address; false otherwise, so a
     *  stolen key is useless off its network. A malformed or unprovisioned key is left to the grant check. */
    public boolean addressAllowed(String key, String clientAddress) throws IOException {
        if (store == null || !wellFormed(key)) {
            return true;
        }
        Properties metadata = read(metadataPath(tenantOf(key), hash(key)));   // the entry authorize() just filled
        String allowed = metadata == null ? null : metadata.getProperty("allowed-ips");
        if (allowed == null || allowed.isBlank()) {
            return true;
        }
        if (clientAddress == null) {
            return false;
        }
        for (String cidr : allowed.split(",")) {
            if (inRange(clientAddress.trim(), cidr.trim())) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code address} lies within {@code cidr} (a {@code network/bits} range, or a plain address matched in
     *  full). A small hand-rolled matcher (this SPI module is Spring-free, so it cannot reach for spring-security-web's
     *  {@code IpAddressMatcher}): it parses both sides with {@link InetAddress#getByName} - which accepts a numeric
     *  IPv4 or IPv6 literal without a DNS lookup, exactly the inputs a source-IP allowlist and a client address carry -
     *  and compares the {@code bits} most-significant masked bits of the network and address bytes. IPv4 and IPv6 never
     *  match each other (their byte lengths differ); a malformed CIDR, a bad prefix length, or an unparseable/blank
     *  address never matches (any failure yields {@code false}). Behaviour matches the previous
     *  {@code new IpAddressMatcher(cidr).matches(address)}: a bare address is a full-length (/32 or /128) match, an
     *  in-range address inside a listed CIDR matches, one outside does not. */
    private static boolean inRange(String address, String cidr) {
        try {
            if (address == null || cidr == null || cidr.isBlank()) {
                return false;
            }
            int slash = cidr.indexOf('/');
            String networkText = (slash < 0 ? cidr : cidr.substring(0, slash)).trim();
            if (networkText.isEmpty()) {
                return false;
            }
            byte[] network = InetAddress.getByName(networkText).getAddress();
            byte[] target = InetAddress.getByName(address.trim()).getAddress();
            if (network.length != target.length) {
                return false;                     // an IPv4 range never covers an IPv6 address, or vice versa
            }
            int maxBits = network.length * 8;
            int bits = slash < 0 ? maxBits : Integer.parseInt(cidr.substring(slash + 1).trim());
            if (bits < 0 || bits > maxBits) {
                return false;
            }
            int fullBytes = bits / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (network[i] != target[i]) {
                    return false;
                }
            }
            int remainingBits = bits % 8;
            if (remainingBits != 0) {
                int mask = (0xFF << (8 - remainingBits)) & 0xFF;
                if ((network[fullBytes] & mask) != (target[fullBytes] & mask)) {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException | java.net.UnknownHostException failure) {
            return false;
        }
    }

    /** The real client address for a request given its TCP {@code peer} and any {@code X-Forwarded-For}. A forwarded
     *  header is trusted only when {@code peer} is itself one of {@code trustedProxies}: then the rightmost forwarded
     *  hop that is not also a trusted proxy is the client (walking back through the proxy chain). Otherwise the peer is
     *  the client - a forwarded header from an untrusted source is ignored, so the source-IP allowlist cannot be
     *  spoofed by a client that sets its own {@code X-Forwarded-For}. */
    public static String clientAddress(String peer, String forwardedFor, List<String> trustedProxies) {
        if (!trustedProxy(peer, trustedProxies)) {
            return peer;
        }
        if (forwardedFor == null || forwardedFor.isBlank()) {
            return peer;
        }
        String[] hops = forwardedFor.split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            String hop = hops[i].trim();
            if (!hop.isEmpty() && !withinAny(hop, trustedProxies)) {
                return hop;
            }
        }
        return peer;
    }

    /** Whether {@code peer} is one of the deployment's trusted reverse proxies - the one condition under which any
     *  header the peer forwarded ({@code X-Forwarded-For}, {@code X-Forwarded-Proto}, {@code X-Forwarded-Host}) is
     *  believed. An empty or absent list trusts nobody. */
    public static boolean trustedProxy(String peer, List<String> trustedProxies) {
        return peer != null && trustedProxies != null && !trustedProxies.isEmpty() && withinAny(peer, trustedProxies);
    }

    private static boolean withinAny(String address, List<String> cidrs) {
        for (String cidr : cidrs) {
            if (inRange(address, cidr.trim())) {
                return true;
            }
        }
        return false;
    }

    /** Stamp a credential's last use - the time, the source {@code address} (kept when {@code null}) and a count
     *  raised by {@code increment} - for the off-request usage tracker, which batches so the store sees at most one
     *  write per credential per day. A revoked credential (no metadata) is silently skipped.
     *
     *  <p>Unlike the other metadata writers this one <em>accumulates</em> {@code useCount} and runs on the usage
     *  tracker's background worker, so it compare-and-sets the metadata document rather than a blind
     *  load-mutate-write: a concurrent flush on another replica, or a racing admin edit (an operator setting an
     *  expiry or a source-IP allowlist on the same credential), would otherwise last-writer-win - silently dropping
     *  a count increment, or reverting the just-set expiry/allowlist that this automated flush read before it. On a
     *  conflict the loop re-reads the latest metadata and re-applies the delta, so both survive; a persistently
     *  contended counter forfeits this attempt rather than looping forever.
     *
     *  <p>Returns whether the increment was settled: {@code true} when it was written, or when the credential is
     *  revoked (no metadata) and there is nothing to record; {@code false} when every compare-and-set attempt lost to
     *  contention. The caller keeps the delta on {@code false} and re-applies it on the next flush, so a contended
     *  write defers the increment rather than dropping it. */
    public boolean recordUsed(String tenant, String hash, Instant when, String address, long increment)
            throws IOException {
        require();
        // Retries.tryUpdate, not update: the usage count is informational and the tracker keeps the delta on a loss
        // and re-applies it on the next flush, so a contended write defers the increment rather than dropping it.
        boolean landed = Retries.tryUpdate(store, metadataPath(tenant, hash), current -> {
            if (current.isEmpty()) {
                return null;                       // a revoked credential (no metadata) is settled - nothing to record
            }
            Properties metadata = new Properties();
            metadata.load(new ByteArrayInputStream(current.get().content()));
            metadata.setProperty("lastUsed", when.toString());
            if (address != null) {
                metadata.setProperty("lastUsedAddress", address);
            }
            metadata.setProperty("useCount",
                    Long.toString(Long.parseLong(metadata.getProperty("useCount", "0")) + increment));
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            metadata.store(bytes, null);
            return bytes.toByteArray();
        });
        cache.invalidate(metadataPath(tenant, hash));   // written past the cache: the node's next read must see it
        mutated();                                      // ...and every other node's, within EPOCH_TTL
        return landed;
    }

    /** Revoke a credential by hash: delete its grants and metadata, so the next request is forbidden. */
    public void revoke(String tenant, String hash) throws IOException {
        require();
        cache.delete(grantsPath(tenant, hash));
        remove(metadataPath(tenant, hash));   // the pair is one revocation; remove() bumps for both
    }

    /** Revoke the credential a raw {@code key} resolves to, for when a key is reported leaked: the {@code jenk_}
     *  checksum and tenant are read straight off the key, so a malformed or unknown key revokes nothing. Returns
     *  whether a provisioned credential was actually revoked. */
    public boolean revokeLeaked(String key) throws IOException {
        if (!wellFormed(key)) {
            return false;
        }
        String tenant = tenantOf(key);
        String hash = hash(key);
        if (credential(tenant, hash).isEmpty()) {
            return false;
        }
        revoke(tenant, hash);
        return true;
    }

    /** A freshly minted successor key and the expiry stamped on it. */
    public record Rotated(String key, Instant expires) {
    }

    /** Rotate a credential: mint a successor that inherits the same label, grants and source-IP allowlist with a fresh
     *  default lifetime, and set the old credential to expire after {@code overlap} (7 days when null) so callers can
     *  swap over with no downtime. Returns the successor's raw key (shown once); the old hash keeps working until the
     *  overlap elapses, then expires on its own. */
    public Rotated rotate(String tenant, String hash, Duration overlap) throws IOException {
        require();
        Credential previous = credential(tenant, hash).orElseThrow(
                () -> new IllegalArgumentException("No such credential to rotate"));
        String key = mint(tenant);
        String successor = hash(key);
        Instant expires = mintExpiry(tenant, null, false);
        provision(tenant, successor, previous.label(), expires);
        for (Map.Entry<String, String> grant : previous.grants().entrySet()) {
            setGrant(tenant, successor, grant.getKey(), grant.getValue());
        }
        if (previous.allowedAddresses() != null) {
            setAllowedAddresses(tenant, successor, previous.allowedAddresses());
        }
        setExpiry(tenant, hash, Instant.now().plus(overlap == null ? Duration.ofDays(7) : overlap));
        return new Rotated(key, expires);
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * A freshly minted key for {@code tenant}, in the scannable form {@code jenk_<tenant>.<secret><checksum>}. The
     * {@code jenk_} prefix and the trailing CRC32 checksum let a secret scanner recognise a leaked Jenesis key and
     * validate it offline (so a partner scanner can report it for revocation), and let the server
     * reject a malformed or truncated key with no store lookup. The tenant travels in the key so resolution stays
     * stateless; only the key's SHA-256 hash is ever stored.
     */
    public static String mint(String tenant) {
        byte[] secret = new byte[24];
        RANDOM.nextBytes(secret);
        String body = "jenk_" + tenant + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        return body + checksum(body);
    }

    /** Whether {@code key} is a well-formed Jenesis key - the {@code jenk_} prefix, a tenant, a secret and a matching
     *  trailing checksum - checked before any store lookup so a malformed or truncated key is cheap to reject. */
    public static boolean wellFormed(String key) {
        if (key == null || !key.startsWith("jenk_")) {
            return false;
        }
        int dot = key.indexOf('.');
        if (dot <= 5 || key.length() <= dot + 7) {
            return false;
        }
        int split = key.length() - 6;
        return key.substring(split).equals(checksum(key.substring(0, split)));
    }

    /** The tenant carried by a {@code jenk_}-prefixed key, or {@code null} if it is not one. */
    public static String tenantOf(String key) {
        if (key == null || !key.startsWith("jenk_")) {
            return null;
        }
        int dot = key.indexOf('.');
        return dot > 5 ? key.substring(5, dot) : null;
    }

    private static String checksum(String body) {
        CRC32 crc = new CRC32();
        crc.update(body.getBytes(StandardCharsets.UTF_8));
        long value = crc.getValue();
        byte[] bytes = {(byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value};
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // Hashing runs on every authorized request (authorize/provisioned hash the key), so the digest is reused per
    // thread rather than paying a JCA provider lookup (getInstance) on each call; digest(byte[]) resets the instance
    // after use, so a reused one is safe. Mirrors the per-thread digest the dependents index already keeps.
    private static final ThreadLocal<MessageDigest> SHA_256 = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    });

    /** The lowercase hex SHA-256 of a key, the only form of it that is ever persisted. */
    public static String hash(String key) {
        return HexFormat.of().formatHex(SHA_256.get().digest(key.getBytes(StandardCharsets.UTF_8)));
    }

    private void require() {
        if (store == null) {
            throw new IllegalStateException("Cannot manage credentials on an anonymous authorization");
        }
    }

    /** {@link #CACHE_TTL_SETTING} as the deployment set it, else {@link #DEFAULT_CACHE_TTL}. Its own dial rather
     *  than the shared {@code cache.ttl} because the trade differs from a listing's: this cache is read on every
     *  request <em>and</em> the thing it holds is a security decision. */
    private static Duration cacheTtl() {
        String configured = Features.settings().apply(CACHE_TTL_SETTING);
        return configured == null || configured.isBlank() ? DEFAULT_CACHE_TTL : StoreCache.ttl(configured);
    }

    /**
     * Drop this node's credential cache if another node has changed a credential since it last looked.
     *
     * <p>Called before a decision is read, and it is what makes a fifteen-minute credential ttl safe: without it a
     * grant or a **revocation** made on one node reaches the others only when their entries expire, and
     * {@code POST /api/admin/caches/clear} cannot help because it clears one node. The epoch is one small document
     * that every credential mutation bumps; a node re-reads it at most once per {@link #EPOCH_TTL} and clears its
     * cache when the token has moved. Revocation latency therefore follows the epoch's window rather than the
     * credential's, and the cost is one read every few seconds spread across every request the node answers.
     *
     * <p>An unreadable epoch clears the cache and reads through: that costs latency, never a stale grant.
     */
    private void freshen() {
        if (epoch == null) {
            return;
        }
        long now = System.nanoTime();
        long last = seenAt;
        if (last != 0 && now - last < EPOCH_TTL.toNanos()) {
            return;
        }
        String token;
        try {
            token = epoch.current();
        } catch (IOException unreadable) {
            cache.clear();   // fail closed: better a re-read than a decision from a cache we cannot vouch for
            seenAt = now;
            return;
        }
        seenAt = now;
        String previous = seenEpoch;
        if (previous != null && !previous.equals(token)) {
            cache.clear();
        }
        seenEpoch = token;
    }

    /** Mark the deployment's credentials changed, so every other node drops its cache within {@link #EPOCH_TTL}.
     *  Called from the one place each mutation funnels through, never at the call sites, so a future write cannot
     *  forget it. A failed bump is not swallowed: a caller who was told their revocation landed must not have it
     *  reach one node only. */
    /**
     * Drop every node's authorization cache, not only this one's.
     *
     * <p>This is what {@code POST /api/admin/caches/clear} was missing. {@link StoreCache#clearAll()} empties the
     * caches of the node that served the request, which is the right shape for a listing - but the reason an
     * operator reaches for that button is almost always a revoked credential another node is still honouring
     * inside its ttl, and that is precisely the case a node-local clear cannot fix. The endpoint therefore
     * promised something it could not do.
     *
     * <p>It is not a fan-out, which is why it is allowed: nothing is pushed to any node. One small document is
     * bumped, and every node notices within the epoch's own few-second ttl on the read it was going to make
     * anyway - the same pull that already carries a revocation between nodes.
     *
     * @return whether anything was invalidated. An {@linkplain #anonymous() open} deployment holds no grants and
     *         keeps no epoch, so there is nothing to invalidate and it answers {@code false} - which the operator
     *         surfaces report rather than claiming a fleet-wide clear that did not happen.
     */
    public boolean invalidateAcrossNodes() throws IOException {
        if (epoch == null) {
            return false;
        }
        mutated();
        return true;
    }

    private void mutated() throws IOException {
        if (epoch != null) {
            epoch.bump();
            seenEpoch = null;   // this node has just changed things; do not clear on its own bump
            seenAt = 0;
        }
    }

    private Properties read(String path) throws IOException {
        Optional<ArtifactStore.Versioned> object = cache.readVersioned(path);
        if (object.isEmpty()) {
            return null;
        }
        Properties properties = new Properties();
        properties.load(new ByteArrayInputStream(object.get().content()));
        return properties;
    }

    /**
     * Read a document, change it, write it back - under compare-and-set, re-reading on a loss.
     *
     * <p>These writes used to be a plain read-modify-write: read the grants object, set one scope, put the whole
     * object back unconditionally. Two administrators granting <em>different</em> scopes to one subject at the same
     * moment therefore raced, and the loser's grant was overwritten with no error and nothing to notice it by -
     * on the object that decides what a caller may do. It is the shape the project's own rule names: a
     * read-modify-write on a store with no atomic update is a compare-and-set through {@link Retries}, which
     * re-reads and re-applies rather than clobbering, and throws when it has genuinely lost rather than pretending
     * it kept the record.
     *
     * <p>The write goes to the store rather than through the cache, so the node's own next read is invalidated
     * explicitly and every other node's within the epoch's ttl - the same pair {@link #recordUsed} makes.
     */
    private void mutate(String path, Consumer<Properties> change) throws IOException {
        Retries.update(store, path, current -> {
            Properties properties = new Properties();
            if (current.isPresent()) {
                properties.load(new ByteArrayInputStream(current.get().content()));
            }
            change.accept(properties);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            properties.store(bytes, null);
            return bytes.toByteArray();
        });
        cache.invalidate(path);
        mutated();
    }

    private void write(String path, Properties properties) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        properties.store(bytes, null);
        cache.write(path, bytes.toByteArray());
        mutated();
    }

    /** Remove one credential document and mark the deployment changed - the delete half of {@link #write}, so both
     *  directions of a mutation bump the epoch from one place. */
    private void remove(String path) throws IOException {
        cache.delete(path);
        mutated();
    }

    private static Instant instant(Properties properties, String key) {
        if (properties == null) {
            return null;
        }
        String value = properties.getProperty(key);
        return value == null ? null : Instant.parse(value);
    }

    private static Duration duration(Properties properties, String key) {
        if (properties == null) {
            return null;
        }
        String value = properties.getProperty(key);
        return value == null ? null : Duration.parse(value);
    }

    private static String grantsPath(String tenant, String hash) {
        return grantsPath(tenant, Subject.credential(hash));
    }

    private static String grantsPath(String tenant, Subject subject) {
        return subjectPath(tenant, subject) + "/grants";
    }

    private static String metadataPath(String tenant, String hash) {
        return metadataPath(tenant, Subject.credential(hash));
    }

    private static String metadataPath(String tenant, Subject subject) {
        return subjectPath(tenant, subject) + "/metadata";
    }

    /**
     * Where a group's members live: one small object per member, under the group's own subject path.
     *
     * <p>One object each rather than one document listing them all, which is the shape this store learned the hard
     * way on console membership. A single document cannot be paged, is re-read whole on every lost compare-and-set,
     * and makes two administrators adding two <em>unrelated</em> people contend; a group is exactly where that
     * hurts, because the thing that fills one is an identity provider pushing a few hundred members at once.
     */
    private static String memberPath(String tenant, String group, String principal) {
        return membersPrefix(tenant, group) + "/" + segment(principal);
    }

    private static String membersPrefix(String tenant, String group) {
        return subjectPath(tenant, Subject.group(group)) + "/members";
    }

    /**
     * Where a principal's <em>effective</em> group rights are kept: the union of every group they belong to, as one
     * document read by one point read.
     *
     * <p>It exists because the honest computation is a fan-out. Effective rights are a union over the caller's own
     * grants and every group's, and enumerating a principal's groups on the authorization path would make the cost
     * of a request a function of how an operator organises their directory - which is the unbounded read the
     * project's own rule forbids, on the hottest path there is. So it takes the shape the stored listings take:
     * computed off the request path, maintained by every write that could change it, and read as it is.
     *
     * <p>Derived state can drift - a node that dies between writing a group's grants and re-deriving the last of
     * its members leaves that member stale - so it is repaired rather than trusted, and the repair is
     * {@link #rederive}. It is deliberately a separate document from {@code grants}: a direct grant and a grant
     * held through a group are different facts about a person, one revocable on its own and one not, and a
     * repair that recomputed a document holding both would erase the half it does not own.
     */
    private static String derivedPath(String tenant, Subject subject) {
        return subjectPath(tenant, subject) + "/derived";
    }

    /**
     * Where one subject's documents live: {@code .system/auth/<tenant>/<kind>/<id>}.
     *
     * <p>The kind segment is what makes a grant's holder part of the key rather than a convention, and it also
     * repairs a listing that was wrong: credentials used to sit directly under the tenant, beside that tenant's
     * {@code policy}, {@code quota} and {@code roles} objects, so enumerating them returned those too and
     * {@link #credential} answered empty for them. Under a kind segment the enumeration names credentials and
     * nothing else.
     */
    private static String subjectPath(String tenant, Subject subject) {
        return kindPrefix(tenant, subject.kind()) + "/" + segment(subject.id());
    }

    /**
     * One subject id as one key segment: a slash becomes {@code %2F} and everything else is left alone.
     *
     * <p>Deliberately the identity for an id that needs no encoding, which is what makes it safe to introduce
     * over a store that already holds credentials: a hash is hex, so its key is byte-for-byte the key it was
     * before this existed. Only a principal's provider-qualified id - {@code github/octocat} - is rewritten, and
     * it had no key before.
     *
     * <p>A percent is escaped first, so the encoding is reversible and two different ids cannot collide on one
     * key: without it {@code a%2Fb} and {@code a/b} would both key as {@code a%2Fb}, which on an authorization
     * path means one person's grants answering for another's.
     */
    private static String segment(String id) {
        return id.indexOf('%') < 0 && id.indexOf('/') < 0
                ? id
                : id.replace("%", "%25").replace("/", "%2F");
    }

    /** The id a key segment names - {@link #segment} read backwards, so an enumeration hands back the id a caller
     *  granted rather than the shape it is stored under. The slash is decoded before the percent, mirroring the
     *  order the encoder escapes them in. */
    private static String unsegment(String name) {
        return name.indexOf('%') < 0 ? name : name.replace("%2F", "/").replace("%25", "%");
    }

    private static String policyPath(String tenant) {
        return AUTH + "/" + tenant + "/policy";
    }

    private static String quotaPath(String tenant) {
        return AUTH + "/" + tenant + "/quota";
    }

    private static String rateLimitPath(String tenant) {
        return AUTH + "/" + tenant + "/ratelimit";
    }

    private static String oidcPath(String tenant) {
        return AUTH + "/" + tenant + "/oidc";
    }

    private static String rolesPath(String tenant) {
        return AUTH + "/" + tenant + "/roles";
    }
}
