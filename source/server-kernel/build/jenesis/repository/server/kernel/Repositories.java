package build.jenesis.repository.server.kernel;

import module java.base;
import jakarta.servlet.http.HttpServletRequest;
import build.jenesis.repository.server.PresentedKey;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.cleanup.RetentionPolicy;
import build.jenesis.repository.cleanup.RetentionProvider;
import build.jenesis.repository.cleanup.RetentionSweeper;
import build.jenesis.repository.scope.Scopes;
import build.jenesis.repository.staging.Staging;
import build.jenesis.repository.staging.StagingProvider;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.RepositoryDocument;
import build.jenesis.repository.format.RepositoryType;
import build.jenesis.repository.store.Listings;
import build.jenesis.repository.store.QuotaArtifactStore;

/**
 * Resolves a request to its isolated artifact space and builds the per-space components, so one deployment serves
 * many tenants and many named repositories - exactly the cache's stateless multi-tenancy. The tenant rides in the
 * key ({@code jenk_<tenant>.<secret>}, the configured default when a request carries none); the repository is named in
 * the URL path. Storage is the root store scoped to {@code <tenant>/<repo>}, a confined subspace, so two tenants -
 * or two repositories - never see each other's artifacts. Tenant and repository names are validated as
 * traversal-free path segments before they ever scope the store.
 *
 * <p>What this hands out is the scoped store, the quota and the two answers a definition gives
 * ({@link RepositoryDefinitions}); the staging lifecycle and the retention engine, both SPIs, are resolved once and
 * offered here because every surface that promotes or sweeps asks the same question. It used to hand out the
 * inventory, the quarantine log and the gated repository as well - each a {@code new X(store)} over the scoped store
 * - which made the kernel require the inventory, the gate and the router for three one-line factories, and every
 * web adapter drag them. A feature's own factory is the feature's: {@code new StoreRepositoryInventory(store)},
 * {@code new QuarantineLog(store)}, {@code new GatedRepository(writable)} over the store this resolves.
 */
public final class Repositories {

    /** The tenant-key request header, the stable home shared by the core controllers, the security chain
     *  (the tenancy module's {@code RepositoryAuthorizationManager} and {@code MultiTenantRouting}, the telemetry download filter) and the
     *  discovered per-feature {@code web} adapters - the tenant it carries is resolved by {@link #tenant(String)}. */
    public static final String KEY = "Jenesis-Repository-Key";

    /** The header a NuGet client pushes with ({@code dotnet nuget push --api-key}), the one credential carrier the
     *  ecosystem clients use that is neither the native header nor {@code Authorization}. */
    public static final String NUGET_API_KEY = "X-NuGet-ApiKey";

    private final ArtifactStore root;
    private final Authorization authorization;
    private final LiveConfig live;
    private final Optional<StagingProvider.Factory> staging;
    private final Optional<RetentionSweeper> retentionSweeper;
    private final RepositoryDefinitions definitions;

    /** Every repository hosted ({@link RepositoryDefinitions#HOSTED}): a composition without the router, and the
     *  unit tests. */
    public Repositories(ArtifactStore root, Authorization authorization, LiveConfig live,
                        Optional<StagingProvider.Factory> staging, Optional<RetentionSweeper> retentionSweeper) {
        this(root, authorization, live, staging, retentionSweeper, RepositoryDefinitions.HOSTED);
    }

    public Repositories(ArtifactStore root, Authorization authorization, LiveConfig live,
                        Optional<StagingProvider.Factory> staging, Optional<RetentionSweeper> retentionSweeper,
                        RepositoryDefinitions definitions) {
        this.root = root;
        this.authorization = authorization;
        this.live = live;
        this.staging = staging;
        this.retentionSweeper = retentionSweeper;
        this.definitions = definitions;
    }

    /** The two answers the deployment's repository definitions give the kernel. */
    public RepositoryDefinitions definitions() {
        return definitions;
    }

    /** Whether a staging module is installed on this deployment. */
    /**
     * The key a request presents, read the way the security chain reads it ({@link PresentedKey}: the native
     * header first, then a well-formed key carried as a bearer token or a Basic password in {@code Authorization})
     * plus the NuGet push header - so every ecosystem client's own way of carrying a credential reaches the same
     * credential model. {@code null} when the request presents none.
     */
    public static String key(HttpServletRequest request) {
        String presented = PresentedKey.from(request);
        if (presented != null) {
            return presented;
        }
        String nuget = request.getHeader(NUGET_API_KEY);
        return nuget != null && Authorization.wellFormed(nuget) ? nuget : null;
    }

    public boolean stagingInstalled() {
        return staging.isPresent();
    }

    /** The retention engine, or empty when no retention module is installed - the cleanup and retention endpoints
     *  then answer that retention is not available on this deployment. */
    public Optional<RetentionSweeper> retentionSweeper() {
        return retentionSweeper;
    }

    /** The tenant a key resolves to - the one carried in a {@code jenk_<tenant>.}-prefixed key, or the default
     *  when there is no key or it is not well-formed. */
    public String tenant(String key) {
        String tenant = Authorization.tenantOf(key);
        return tenant == null ? live.defaultTenant() : tenant;
    }

    /**
     * Whether {@code name} may scope the store as a tenant or a repository: the shared {@link Scopes#valid} rule - a
     * traversal-free segment that is not one of the reserved key spaces sitting beside the tenant and repository
     * scopes ({@code auth/}, {@code config/}, {@code audit/}, {@code locks/}, {@code quota/}).
     *
     * <p>This is the one validity gate every routing, listing, scoping and publish site funnels through, so a request
     * can never read, forge or reset another tenant's credentials, settings, audit history, lease or quota by
     * colliding with a reserved key. It delegates to {@link Scopes} rather than restating the set, because the
     * console's tenant lifecycle and the tenants SPI must answer this question identically - and while each kept its
     * own copy they did not.
     */
    public static boolean valid(String name) {
        return Scopes.valid(name);
    }

    /** The tenants that hold artifact space: the valid top-level folders. {@link #valid} is the scope-name shape,
     *  which has no dot, so everything the product owns under {@code .system} is excluded by the rule that gates
     *  creation rather than by a list - the same exclusion the console's tenant listing and the tenants SPI apply.
     *  A product-owned key at the root would be enumerated as a tenant here, which is why there is none. */
    public List<String> tenants() {
        List<String> tenants = new ArrayList<>();
        for (String entry : root.list("")) {
            if (valid(entry)) {
                tenants.add(entry);
            }
        }
        return tenants;
    }


    /** Whether a repository has a hardened upstream fallback ({@code fallback <url> harden}): an untrusted-upstream leg
     *  that spools and fully screens every fetched body before releasing a byte. Drives the console's "hardened" badge
     *  and gates the hardened verdict/refusal/drift panel. A repository with no definition, or with no hardened
     *  upstream fallback, is not hardened. */
    public boolean hardened(String repository) {
        return definitions.hardened(repository);
    }


    /** The staging operations over a repository, or empty when no staging module is installed - the endpoints then
     *  answer that staging is not available on this deployment. */
    public Optional<Staging> staging(String tenant, String repository) throws IOException {
        if (staging.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(staging.get().over(writable(tenant, repository)));
    }

    /** The tenant's configured storage quota in bytes, or {@code 0} when unlimited. */
    public long quotaLimit(String tenant) throws IOException {
        return authorization.quota(tenant);
    }

    /** The tenant's currently counted stored-content bytes (across its repositories). */
    public long quotaUsed(String tenant) throws IOException {
        return new QuotaArtifactStore(root.scope(tenant), 0).used();
    }

    /** Recount the tenant's stored content across its repositories and store it as the authoritative usage, so a
     *  freshly set quota starts from the truth and any drift from out-of-band deletes is corrected. Returns the
     *  total. Each repository's flat {@code blobs/} namespace is paged through {@link ArtifactStore#page} in
     *  bounded strides - {@code QuotaArtifactStore.recompute} idiom - so a millions-entry namespace never
     *  materialises as one {@code List}. */
    public long recomputeQuota(String tenant) throws IOException {
        ArtifactStore tenantScope = root.scope(tenant);
        long total = 0L;
        for (String repository : tenantScope.list("")) {
            if (!valid(repository)) {
                continue;
            }
            ArtifactStore repositoryStore = tenantScope.scope(repository);
            // The size each blob's listing entry already carried; only a backend whose listing carries none is asked
            // again, per blob - over an object store that was one HEAD per blob on every recount.
            Listings blobs = Listings.over(repositoryStore, "blobs");
            for (ArtifactStore.Listed blob = blobs.next(); blob != null; blob = blobs.next()) {
                long size = blob.size().isPresent() ? blob.size().getAsLong() : repositoryStore.size(blob.key());
                if (size > 0) {
                    total += size;
                }
            }
        }
        new QuotaArtifactStore(tenantScope, 0).store(total);
        return total;
    }

    /** A tenant's repository store wrapped to meter and cap stored content against the tenant's quota; an unlimited
     *  tenant gets the plain scoped store, so only quota'd tenants pay for the metering. The counter lives on the
     *  tenant scope, so every repository's blobs count against one tenant-wide limit. */
    public ArtifactStore writable(String tenant, String repository) throws IOException {
        long limit = authorization.quota(tenant);
        return limit > 0
                ? new QuotaArtifactStore(root.scope(tenant), limit).scope(repository)
                : store(tenant, repository);
    }



    /** Whether this deployment proxies missed reads to upstreams. */
    public boolean proxying() {
        return live.proxy();
    }



    /**
     * The type {@code tenant}'s {@code repository} holds, read through the node's cache, or empty when it holds none
     * this deployment installs.
     */
    public Optional<RepositoryType> type(String tenant, String repository) throws IOException {
        return RepositoryDocument.cached(root, tenant, repository)
                .flatMap(document -> RepositoryType.installed(document.format()));
    }

    /**
     * A path the API names an artifact by - the path within the repository, what a client appends to the
     * repository's URL - as the repository's format lays it out, with its mount put back. Every surface that names an
     * artifact takes the path a client uses; this is where that path meets what the store records. A repository that
     * holds no installed format leaves it as it is.
     */
    public String formatPath(String tenant, String repository, String path) throws IOException {
        return type(tenant, repository).map(type -> type.formatPath(path)).orElse(path);
    }

    /** The inverse of {@link #formatPath}: what the API reports for a path the store records. */
    public String servedPath(String tenant, String repository, String formatPath) throws IOException {
        return type(tenant, repository).map(type -> type.servedPath(formatPath)).orElse(formatPath);
    }

    /** The artifact space confined to a tenant's named repository, for a format plugin to read and write. */
    public ArtifactStore store(String tenant, String repository) {
        return root.scope(tenant).scope(repository);
    }

    /** A tenant's plain (unquota'd, unmetered) root scope - the store under which its reserved sub-scopes live, the VEX
     *  space among them. Kept free of the {@code VexStore} type so the neutral server names no VEX plugin: the VEX web
     *  surface (which owns the vex module) applies the reserved {@code .vex} sub-scope and wraps it. The tenant is
     *  validated by the caller as a traversal-safe segment before it scopes the store. */
    /** The store every tenant's scope is taken from - what a node-wide cache over repository documents is keyed on. */
    public ArtifactStore root() {
        return root;
    }

    public ArtifactStore tenantScope(String tenant) {
        return root.scope(tenant);
    }
}
