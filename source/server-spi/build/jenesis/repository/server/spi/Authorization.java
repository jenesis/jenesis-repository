package build.jenesis.repository.server.spi;

import module java.base;

import build.jenesis.repository.store.ArtifactStore;
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
 *
 * <p>This type holds the credentials and subjects, their grants, and the decisions over them. The rest of the
 * credential space hangs off it, each concern its own type: how long a credential lives ({@link #lifetimes()}), a
 * tenant's ceilings ({@link #quotas()}, {@link #rateLimits()}), its OIDC trusts ({@link #trusts()}), its named roles
 * ({@link #roles()}) and its groups ({@link #groups()}). They are reached through an authorization rather than built
 * on their own because every one of them reads and writes through the same cache and the same deployment-wide epoch,
 * so a change to any of them reaches every node the way a revocation does.
 */
public final class Authorization {

    public static final String CACHE_READ = "cache:read";

    public static final String CACHE_WRITE = "cache:write";

    public static final String REPOSITORY_READ = "repository:read";

    public static final String REPOSITORY_WRITE = "repository:write";

    public static final String MANAGE_READ = "manage:read";

    public static final String MANAGE_WRITE = "manage:write";

    /** How long a credential document is cached: {@code jenreg.auth.cache-ttl}, fifteen minutes by default. */
    public static final String CACHE_TTL_SETTING = "auth.cache-ttl";

    /** The default, deliberately longer than the deployment-wide {@code cache.ttl}: an authorization happens on
     *  every request, and the epoch the credential space keeps is what keeps a long ttl from also meaning a long
     *  revocation window. */
    public static final String DEFAULT_CACHE_TTL_TEXT = "PT15M";

    public static final Duration DEFAULT_CACHE_TTL = Duration.parse(DEFAULT_CACHE_TTL_TEXT);

    /** The store the space reads, kept to build a reconfigured copy; {@code null} for an open deployment. */
    private final ArtifactStore store;

    private final CredentialSpace space;

    private final AnonymousRights anonymous;

    private final CredentialLifetimes lifetimes;

    private final TenantCeiling quotas;

    private final TenantCeiling rateLimits;

    private final OidcTrusts trusts;

    private final Roles roles;

    private final GroupMembership groups;

    private Authorization(ArtifactStore store) {
        this(store, CredentialLifetimes.DEFAULT_LIFETIME, null, AnonymousRights.NONE);
    }

    private Authorization(ArtifactStore store, Duration defaultLifetime, Duration maxLifetime,
                          AnonymousRights anonymous) {
        this.store = store;
        this.space = new CredentialSpace(store, store == null ? null : cacheTtl());
        this.anonymous = anonymous;
        this.lifetimes = new CredentialLifetimes(space, defaultLifetime, maxLifetime);
        this.quotas = TenantCeiling.quota(space);
        this.rateLimits = TenantCeiling.rateLimit(space);
        this.trusts = new OidcTrusts(space);
        this.roles = new Roles(space);
        this.groups = new GroupMembership(space);
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

    /** The strictly-opt-in anonymous role: return a copy of this authorization that grants a keyless caller
     *  the rights in {@code rights} (a comma-list in the existing grant grammar - a bare {@code <surface>:<verb>} token
     *  granted on every repository, or a {@code <repository>=<token>} entry scoped to one named repository, or the
     *  all-privileges {@code *}). A blank value grants nothing, so a keyless request is rejected exactly as it is today.
     *  Only an enforcing authorization consults it; an anonymous (open) one already allows everything. */
    public Authorization withAnonymousRights(String rights) {
        return new Authorization(store, lifetimes.defaultLifetime(), lifetimes.maxLifetime(),
                AnonymousRights.parse(rights));
    }

    /** The deployment-wide default lifetime for a credential minted without an explicit expiry (90 days unless
     *  overridden); a tenant policy may narrow it further (see {@link CredentialLifetimes#policy}). */
    public Authorization withDefaultLifetime(Duration defaultLifetime) {
        CredentialLifetimes.requirePositive(defaultLifetime, "default", true);
        return new Authorization(store, defaultLifetime, lifetimes.maxLifetime(), anonymous);
    }

    /** The deployment-wide ceiling on a credential's lifetime (none unless set); no credential of any tenant may
     *  outlive it, and a tenant policy can only cap further, never beyond it. */
    public Authorization withMaxLifetime(Duration maxLifetime) {
        CredentialLifetimes.requirePositive(maxLifetime, "maximum", false);
        return new Authorization(store, lifetimes.defaultLifetime(), maxLifetime, anonymous);
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
                    CredentialLifetimes.configured(defaultLifetime.strip(), "credential-default-lifetime"));
        }
        if (maxLifetime != null && !maxLifetime.isBlank()) {
            configured = configured.withMaxLifetime(
                    CredentialLifetimes.configured(maxLifetime.strip(), "credential-max-lifetime"));
        }
        return configured;
    }

    /** How long this deployment's credentials live: its default and ceiling, and each tenant's policy under them. */
    public CredentialLifetimes lifetimes() {
        return lifetimes;
    }

    /** Each tenant's stored-content quota, in bytes; none set is unlimited. */
    public TenantCeiling quotas() {
        return quotas;
    }

    /** Each tenant's request rate ceiling, in permits per minute; none set is the deployment default. */
    public TenantCeiling rateLimits() {
        return rateLimits;
    }

    /** Each tenant's OIDC trusts, by which an id-token is exchanged for a short-lived credential. */
    public OidcTrusts trusts() {
        return trusts;
    }

    /** Each tenant's named roles. */
    public Roles roles() {
        return roles;
    }

    /** Each tenant's groups: who is in which, and the rights that membership derives. */
    public GroupMembership groups() {
        return groups;
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
        space.freshen();   // another node's grant or revocation, at most EPOCH_TTL old - see CredentialSpace
        // The one choke-point: an enforcing deployment that sees a request with NO credential decides it
        // against the strictly-opt-in anonymous grant set, reusing the exact matching a minted credential uses (no
        // second code path). Default (empty grants) => UNAUTHORIZED, byte-for-byte today's keyless rejection. A
        // present-but-malformed key is NOT keyless: it stays a failed authentication attempt below.
        if (key == null || key.isBlank()) {
            return anonymous.decide(scope, path, required);
        }
        if (!wellFormed(key)) {
            return Decision.UNAUTHORIZED;
        }
        String tenant = tenantOf(key);
        String hash = hash(key);
        Instant expires = instant(space.read(CredentialSpace.metadataPath(tenant, hash)), "expires");
        if (expires != null && Instant.now().isAfter(expires)) {
            return Decision.UNAUTHORIZED;
        }
        Properties grants = space.read(CredentialSpace.grantsPath(tenant, hash));
        if (grants == null) {
            return Decision.FORBIDDEN;
        }
        // The same matching a subject's grants take, so a scope's expiry ends what it granted whoever holds it.
        return GrantMatching.holds(grants, GrantMatching.repository(scope), path, required)
                ? Decision.ALLOWED
                : Decision.FORBIDDEN;
    }

    /**
     * Whether {@code key} is a credential this deployment accepts, whatever it grants - the question a request that
     * names no repository asks, which is the OCI registry's version probe ({@code GET /v2/}). A client asks it to learn
     * whether its credential is accepted before it names any image, so answering it by a repository's grants would
     * refuse every credential scoped to a repository. A keyless request is {@code ALLOWED} when the deployment grants
     * anonymous access to anything; an expired or malformed key is {@code UNAUTHORIZED}, an unprovisioned one
     * {@code FORBIDDEN}, exactly as {@link #authorize(String, String, String, String)} answers them.
     */
    public Decision authenticated(String key) throws IOException {
        if (store == null) {
            return Decision.ALLOWED;
        }
        space.freshen();
        if (key == null || key.isBlank()) {
            return anonymous.enabled() ? Decision.ALLOWED : Decision.UNAUTHORIZED;
        }
        if (!wellFormed(key)) {
            return Decision.UNAUTHORIZED;
        }
        String tenant = tenantOf(key);
        String hash = hash(key);
        Instant expires = instant(space.read(CredentialSpace.metadataPath(tenant, hash)), "expires");
        if (expires != null && Instant.now().isAfter(expires)) {
            return Decision.UNAUTHORIZED;
        }
        return space.read(CredentialSpace.grantsPath(tenant, hash)) == null ? Decision.FORBIDDEN : Decision.ALLOWED;
    }

    /**
     * Whether {@code subject} carries {@code required} for {@code scope} on the in-repository {@code path}.
     *
     * <p>The same matching a presented key takes, over the subject's own grants object - reached without a secret,
     * because a principal does not present one: a person is authenticated by the sign-in mechanism and authorized
     * here. That is the whole of "one vocabulary, several holders": a per-tenant console role and a key scoped to
     * that tenant become the same grant, matched by the same code, instead of two models that have to be kept
     * agreeing by hand.
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
        space.freshen();   // another node's grant or revocation, at most EPOCH_TTL old - see CredentialSpace
        String repository = GrantMatching.repository(scope);
        // The tenant's own grant first, then the deployment-wide one. Two point reads rather than one, and
        // deliberately not a set of per-tenant rows: an operator who administers the deployment holds their rights
        // in a tenant created after they were granted them, which a per-tenant set could only manage by being
        // rewritten every time a tenant appears.
        if (GrantMatching.holds(space.read(CredentialSpace.grantsPath(tenant, subject)), repository, path, required)
                || (!DEPLOYMENT.equals(tenant) && GrantMatching.holds(
                        space.read(CredentialSpace.grantsPath(DEPLOYMENT, subject)), repository, path, required))) {
            return Decision.ALLOWED;
        }
        // What their groups grant, as the union a write already computed. Only a principal has one: a credential
        // is not a member of anything, and a group's own document is what this reads through rather than about.
        if (subject.kind() == Kind.PRINCIPAL && GrantMatching.holds(
                space.read(CredentialSpace.derivedPath(tenant, subject)), repository, path, required)) {
            return Decision.ALLOWED;
        }
        return Decision.FORBIDDEN;
    }

    /** Whether {@code subject} carries {@code required} for {@code scope}, on no particular path. */
    public Decision authorize(String tenant, Subject subject, String scope, String required) throws IOException {
        return authorize(tenant, subject, scope, null, required);
    }

    /**
     * Forget everything this node has cached about who holds what, and tell every other node to do the same.
     *
     * <p><b>For a writer that changed the auth store without going through this class.</b> Every mutation here
     * invalidates the entry it wrote and bumps the epoch, so a peer clears within a few seconds. A caller that
     * deletes auth keys directly - the tenant purge does, because it removes whole namespaces through the store
     * rather than a credential at a time - leaves this node's cache holding grants whose objects are gone. Reads
     * are answered from that cache until it ages out, which for a purge means a deleted tenant's credentials still
     * authorize and its console members still read as members.
     *
     * <p>It is deliberately blunt: a purge removes an unbounded set of keys, so there is no entry list to
     * invalidate, and clearing costs one re-read of whatever is asked for next.
     */
    public void forget() throws IOException {
        space.forget();
    }

    /**
     * Drop every node's authorization cache, not only this one's.
     *
     * <p>This is what {@code POST /api/admin/caches/clear} needs beside the node-local clear.
     * {@link StoreCache#clearAll()} empties the caches of the node that served the request, which is the right shape
     * for a listing - but the reason an operator reaches for that button is almost always a revoked credential
     * another node is still honouring inside its ttl, and that is precisely the case a node-local clear cannot fix.
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
        return space.invalidateAcrossNodes();
    }

    /** Whether a strictly-opt-in anonymous role is configured (a non-empty {@code anonymous-rights}); the console and
     *  {@code /api/capabilities} read the raw value to advertise it, this is the plain predicate for a decision. */
    public boolean anonymousEnabled() {
        return anonymous.enabled();
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
        return store.list(CredentialSpace.kindPrefix(tenant, Kind.CREDENTIAL));
    }

    /** One page of a tenant's credential hashes, in key order: at most {@code limit} names strictly after
     *  {@code after} ({@code null} from the start) and the name to continue from, {@code null} on the last page. The
     *  face a management surface pages through, never the whole listing. It names credentials and nothing else:
     *  they sit under a kind segment, apart from the tenant's {@code policy} and {@code quota} objects, so a caller
     *  never has to know that {@link #credential} answers empty for those. */
    public CredentialPage credentials(String tenant, String after, int limit) {
        if (store == null) {
            return new CredentialPage(List.of(), null);
        }
        List<String> names = new ArrayList<>();
        store.page(CredentialSpace.kindPrefix(tenant, Kind.CREDENTIAL),
                after == null ? "" : after, ArtifactStore.oneMoreThan(limit), names::add);
        boolean more = names.size() > limit;
        List<String> page = more ? names.subList(0, limit) : names;
        return new CredentialPage(List.copyOf(page), more ? page.getLast() : null);
    }

    /** One page of credential hashes and the cursor the next page resumes after ({@code null} when exhausted). */
    public record CredentialPage(List<String> hashes, String next) {
    }

    /** A credential's metadata and grants, or empty if neither is present. The grants are its scopes and their
     *  rights; a scope's expiry is bookkeeping beside the grant, not a scope, and is not listed as one. */
    public Optional<Credential> credential(String tenant, String hash) throws IOException {
        Properties grants = space.read(CredentialSpace.grantsPath(tenant, hash));
        Properties metadata = space.read(CredentialSpace.metadataPath(tenant, hash));
        if (grants == null && metadata == null) {
            return Optional.empty();
        }
        Map<String, String> scopes = new TreeMap<>();
        if (grants != null) {
            for (String scope : grants.stringPropertyNames()) {
                if (!scope.startsWith(GrantMatching.EXPIRES)) {
                    scopes.put(scope, grants.getProperty(scope));
                }
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
        return space.subjects(CredentialSpace.kindPrefix(tenant, kind), after, limit);
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
        space.freshen();
        Properties grants = space.read(CredentialSpace.grantsPath(tenant, subject));
        if (grants == null) {
            return Map.of();
        }
        Instant now = Instant.now();
        Map<String, String> scopes = new TreeMap<>();
        for (String scope : grants.stringPropertyNames()) {
            // A lapsed grant is not shown as a grant. It authorizes nothing, and a surface that listed it would
            // have an operator revoking what already ended while believing it was live.
            if (scope.startsWith(GrantMatching.EXPIRES) || GrantMatching.expired(grants, scope, now)) {
                continue;
            }
            scopes.put(scope, grants.getProperty(scope));
        }
        return Map.copyOf(scopes);
    }

    /** The scope-to-rights map a principal holds in {@code tenant} through the groups it is a member of - the union a
     *  membership or group-grant write already derived, which {@link #authorize} reads - empty when it holds none.
     *  A surface that asks what someone may do reads this beside {@link #grants}, or a right a group confers is one
     *  the request path honours and the surface does not see. */
    public Map<String, String> derivedGrants(String tenant, Subject subject) throws IOException {
        space.freshen();
        if (subject.kind() != Kind.PRINCIPAL) {
            return Map.of();
        }
        Properties derived = space.read(CredentialSpace.derivedPath(tenant, subject));
        if (derived == null) {
            return Map.of();
        }
        Map<String, String> scopes = new TreeMap<>();
        for (String scope : derived.stringPropertyNames()) {
            if (!scope.startsWith(GrantMatching.EXPIRES)) {
                scopes.put(scope, derived.getProperty(scope));
            }
        }
        return Map.copyOf(scopes);
    }

    /** A subject's human label - a credential's name, a person's display login - or empty when it has none.
     *  Freshens for the reason {@link #grants} does: it is read beside the grants, on the same request. */
    public Optional<String> label(String tenant, Subject subject) throws IOException {
        space.freshen();
        Properties metadata = space.read(CredentialSpace.metadataPath(tenant, subject));
        String label = metadata == null ? null : metadata.getProperty("label");
        return label == null || label.isBlank() ? Optional.empty() : Optional.of(label);
    }

    /** Set a subject's human label, leaving the rest of its metadata alone. A blank value removes it. */
    public void setLabel(String tenant, Subject subject, String label) throws IOException {
        space.require();
        space.mutate(CredentialSpace.metadataPath(tenant, subject), metadata -> {
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
        space.require();
        List<String> orphaned = subject.kind() == Kind.GROUP ? groups.allMembers(tenant, subject.id()) : List.of();
        space.delete(CredentialSpace.grantsPath(tenant, subject));
        space.delete(CredentialSpace.metadataPath(tenant, subject));
        if (subject.kind() == Kind.PRINCIPAL) {
            space.delete(CredentialSpace.derivedPath(tenant, subject));
        }
        for (String member : orphaned) {
            space.delete(CredentialSpace.memberPath(tenant, subject.id(), member));
        }
        space.mutated();
        // After the deletes, and read from the list taken before them: a member re-derived while the group's
        // grants were still readable would be handed back the rights this call is removing.
        for (String member : orphaned) {
            groups.rederive(tenant, member);
        }
    }

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
        space.write(CredentialSpace.metadataPath(tenant, hash), metadata);
    }

    /** Set the rights for {@code scope} on a credential by hash, replacing any held for that scope. */
    public void setGrant(String tenant, String hash, String scope, String tokens) throws IOException {
        setGrant(tenant, Subject.credential(hash), scope, tokens);
    }

    /**
     * Set the rights for {@code scope} on any subject, replacing any held for that scope.
     *
     * <p>A caller able to grant to a subject {@link #authorize} does not resolve would write a row that reads as
     * access and confers none. Every kind is enforceable but {@link Kind#ANONYMOUS}, which takes its rights from
     * configuration and is therefore refused - a security surface may answer "no", but it may not answer "yes" and
     * mean nothing.
     *
     * <p>A {@link Kind#GROUP} grant re-derives the group's members before it returns, so it is in force on the
     * next request rather than at the next repair. That is the cost of the grant rather than of the requests
     * after it: one small write per member, once, against a fan-out on every authorization for as long as the
     * grant stands.
     */
    public void setGrant(String tenant, Subject subject, String scope, String tokens) throws IOException {
        setGrant(tenant, subject, scope, tokens, null);
    }

    /**
     * The same grant, ending at {@code expires} - so a right may be time-boxed and not only a key.
     *
     * <p>Expiry was a field on a credential, which meant it could only ever time-box a <em>secret</em>. An identity
     * is a session rather than a credential, so "a contractor until the end of March" or "an elevation that lapses
     * on its own" had nothing to attach to: the only way to end a person's access was to remember to remove it.
     * On the grant it applies to every holder, because the grant is what every holder holds.
     *
     * <p>{@code null} never expires, which is what an ordinary grant is.
     */
    public void setGrant(String tenant, Subject subject, String scope, String tokens, Instant expires)
            throws IOException {
        enforceable(subject);
        space.require();
        space.mutate(CredentialSpace.grantsPath(tenant, subject), grants -> {
            grants.setProperty(scope, tokens);
            if (expires == null) {
                grants.remove(GrantMatching.EXPIRES + scope);
            } else {
                grants.setProperty(GrantMatching.EXPIRES + scope, expires.toString());
            }
        });
        if (subject.kind() == Kind.GROUP) {
            groups.rederiveGroup(tenant, subject.id());
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
        space.require();
        space.mutate(CredentialSpace.grantsPath(tenant, subject), grants -> grants.remove(scope));
        if (subject.kind() == Kind.GROUP) {
            groups.rederiveGroup(tenant, subject.id());
        }
    }

    /** Set or clear a credential's expiry; {@code null} removes it (the key no longer expires) unless the tenant
     *  policy caps the lifetime, in which case a cleared or too-distant expiry is pulled back to the ceiling. */
    public void setExpiry(String tenant, String hash, Instant expires) throws IOException {
        space.require();
        Instant capped = lifetimes.capped(tenant, expires);
        Properties metadata = space.read(CredentialSpace.metadataPath(tenant, hash));
        if (metadata == null) {
            metadata = new Properties();
            metadata.setProperty("created", Instant.now().toString());
        }
        if (capped == null) {
            metadata.remove("expires");
        } else {
            metadata.setProperty("expires", capped.toString());
        }
        space.write(CredentialSpace.metadataPath(tenant, hash), metadata);
    }

    /** Set or clear ({@code null}/blank) a credential's source-IP allowlist: comma-separated CIDRs or plain
     *  addresses. A request whose source address lies in none of them is forbidden even with a valid key. */
    public void setAllowedAddresses(String tenant, String hash, String addresses) throws IOException {
        space.require();
        Properties metadata = space.read(CredentialSpace.metadataPath(tenant, hash));
        if (metadata == null) {
            metadata = new Properties();
            metadata.setProperty("created", Instant.now().toString());
        }
        if (addresses == null || addresses.isBlank()) {
            metadata.remove("allowed-ips");
        } else {
            metadata.setProperty("allowed-ips", addresses.trim());
        }
        space.write(CredentialSpace.metadataPath(tenant, hash), metadata);
    }

    /** Whether a credential's source-IP allowlist admits {@code clientAddress}: true when it sets none (the common
     *  case) and when the address falls in a listed CIDR or matches a listed plain address; false otherwise, so a
     *  stolen key is useless off its network. A malformed or unprovisioned key is left to the grant check. */
    public boolean addressAllowed(String key, String clientAddress) throws IOException {
        if (store == null || !wellFormed(key)) {
            return true;
        }
        // the entry authorize() just filled
        Properties metadata = space.read(CredentialSpace.metadataPath(tenantOf(key), hash(key)));
        String allowed = metadata == null ? null : metadata.getProperty("allowed-ips");
        if (allowed == null || allowed.isBlank()) {
            return true;
        }
        return ClientAddresses.admits(allowed, clientAddress);
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
        space.require();
        // tryUpdate, not update: the usage count is informational and the tracker keeps the delta on a loss and
        // re-applies it on the next flush, so a contended write defers the increment rather than dropping it.
        return space.tryUpdate(CredentialSpace.metadataPath(tenant, hash), current -> {
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
    }

    /** Revoke a credential by hash: delete its grants and metadata, so the next request is forbidden. */
    public void revoke(String tenant, String hash) throws IOException {
        space.require();
        space.delete(CredentialSpace.grantsPath(tenant, hash));
        // The pair is one revocation: remove() bumps the epoch once, for both.
        space.remove(CredentialSpace.metadataPath(tenant, hash));
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
     *  overlap elapses, then expires on its own. The grants document is copied whole, so a time-boxed grant stays
     *  time-boxed on the successor and ends when it would have ended on the original. */
    public Rotated rotate(String tenant, String hash, Duration overlap) throws IOException {
        space.require();
        Credential previous = credential(tenant, hash).orElseThrow(
                () -> new IllegalArgumentException("No such credential to rotate"));
        String key = mint(tenant);
        String successor = hash(key);
        Instant expires = lifetimes.mintExpiry(tenant, null, false);
        provision(tenant, successor, previous.label(), expires);
        Properties grants = space.read(CredentialSpace.grantsPath(tenant, hash));
        if (grants != null && !grants.isEmpty()) {
            space.mutate(CredentialSpace.grantsPath(tenant, successor), copy -> copy.putAll(grants));
        }
        if (previous.allowedAddresses() != null) {
            setAllowedAddresses(tenant, successor, previous.allowedAddresses());
        }
        setExpiry(tenant, hash, Instant.now().plus(overlap == null ? CredentialLifetimes.DEFAULT_OVERLAP : overlap));
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

    /** {@link #CACHE_TTL_SETTING} as the deployment set it, else {@link #DEFAULT_CACHE_TTL}. Its own dial rather
     *  than the shared {@code cache.ttl} because the trade differs from a listing's: this cache is read on every
     *  request <em>and</em> the thing it holds is a security decision. */
    private static Duration cacheTtl() {
        String configured = Features.settings().apply(CACHE_TTL_SETTING);
        return configured == null || configured.isBlank() ? DEFAULT_CACHE_TTL : StoreCache.ttl(configured);
    }

    private static Instant instant(Properties properties, String key) {
        if (properties == null) {
            return null;
        }
        String value = properties.getProperty(key);
        return value == null ? null : Instant.parse(value);
    }
}
