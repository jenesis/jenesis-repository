package build.jenesis.repository.ui.store;

import module java.base;

import build.jenesis.repository.ui.CurrentTenant;
import build.jenesis.repository.cleanup.Release;
import build.jenesis.repository.format.RepositoryType;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.scope.Scopes;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.RepositoryDocument;
import build.jenesis.repository.store.StoreCache;
import build.jenesis.repository.store.ServableNames;
import io.micrometer.observation.ObservationRegistry;

/**
 * The console's listing of the artifact repository, scoped to the signed-in user's tenant: the tenant's named
 * repositories and, per repository, its storage namespaces and its published releases. The browse, search and
 * compliance-review families it once also held now live in sibling services in this package - {@link RepositoryBrowse}
 * (the browse tree, artifact detail, search, license inventory and published-index card) and {@code ComplianceReview}
 * (quarantine review, the vulnerability and findings panels, the license blast radius and dependents) - alongside the
 * write and lifecycle families - {@link TenantLimits} (quota and rate limit), {@link RepositoryLifecycle} (staging,
 * retention, pins, cleanup and forwarding retry) and {@link RepositoryImports} (migration jobs) - all over the same
 * {@link TenantScope} confinement to the repository {@link ArtifactStore} scoped to {@code <tenant>/<repo>}. The
 * console's own per-tenant role model authorizes these calls (see SecurityConfig); the repository's key-based
 * authorization is the path for programmatic clients.
 */
public class RepositoryAdmin extends TenantScope {

    public RepositoryAdmin(ArtifactStore repositoryStore, CurrentTenant current, ObservationRegistry observations) {
        super(repositoryStore, current, observations);
    }

    /**
     * The named repositories in the current tenant as the console's navigation lists them: from a node-local cache
     * kept for {@code jenreg.cache.ttl}, five minutes by default, rather than from a store listing per page view.
     *
     * <p>The sidebar asks on every page of the Repositories group, and the set changes only when an operator
     * defines a repository or a first publish creates one - so a new repository appears here within the ttl, and the
     * Repositories screen, which reads the store itself, shows it at once. The console's cache-clear control drops
     * the listing with every other cache.
     */
    public List<String> listedRepositories() throws IOException {
        List<String> repositories = new ArrayList<>();
        for (String name : StoreCache.of("repositories", root, StoreCache.configuredTtl()).list(tenant())) {
            if (validRepository(name)) {
                repositories.add(name);
            }
        }
        return repositories;
    }

    /** The named repositories in the current tenant. */
    public List<String> repositories() {
        List<String> repositories = new ArrayList<>();
        for (String name : root.scope(tenant()).list("")) {
            if (validRepository(name)) {
                repositories.add(name);
            }
        }
        return repositories;
    }

    /** The top-level storage namespaces of a repository - the first key segment its stored objects sit under. A
     *  format writes its layout under its own request prefix, which is the top-level key its content occupies
     *  ({@code npm/...}, {@code pypi/...}, the Maven layout under {@code publish/...}, content-addressed bytes
     *  under {@code blobs/...}), so the console maps a namespace a format claims to that format's icon and leaves
     *  the bookkeeping namespaces unmarked. A single prefix listing, never a tree scan. */
    public List<String> namespaces(String repository) {
        return scope(repository).list("").stream().filter(name -> !name.equals(Scopes.REPOSITORY)).toList();
    }

    /** The format a repository holds, or empty for one created before repositories held a format - which answers
     *  no request until it is given one. Read through the node's cache, so a listing of every repository costs no
     *  store read in the steady state. */
    public Optional<String> format(String repository) throws IOException {
        return RepositoryDocument.cached(root, tenant(), repository).map(RepositoryDocument::format);
    }

    /**
     * What a repository's pages say about it first: the format it holds and the URL a client reaches it at -
     * {@code /repository/<tenant>/<repository>/}, or {@code /v2/<tenant>/<repository>/} for a type the OCI registry
     * mounts. Empty for a repository that holds no format, which answers no URL.
     */
    public Optional<Identity> identity(String repository) throws IOException {
        Optional<String> format = format(repository);
        if (format.isEmpty()) {
            return Optional.empty();
        }
        String root = RepositoryType.installed(format.get()).map(RepositoryType::mount)
                .filter("/v2"::equals).orElse("/repository");
        return Optional.of(new Identity(format.get(), root + "/" + tenant() + "/" + repository + "/"));
    }

    /** The format a repository holds and the URL a client reaches it at. */
    public record Identity(String format, String url) {
    }

    /**
     * The most-recently-published releases of a repository (up to {@code limit}, newest first) and the total published
     * count. A bounded window streamed through a size-limited heap over the published set, so the detail hub renders a
     * recent slice rather than buffering and emitting one table row per release of a repository with a very large
     * published set - the full, paged list is the browse page. The stream is complete here (a walk-less inventory).
     */
    public Releases recentReleases(String repository, int limit) throws IOException {
        StoreRepositoryInventory inventory = inventory(repository);
        List<Release> shown = new ArrayList<>();
        String after = null;
        boolean more = true;
        // The newest-first index is read a page at a time and screened as it goes: a row whose release has since
        // been held or removed is skipped, and the next page is asked for only while the window is not yet full -
        // never a walk of the publish facts to find the newest few hundred.
        while (shown.size() < limit && more) {
            StoreRepositoryInventory.ReleasePage page = inventory.recent(after, limit - shown.size());
            for (Release release : page.releases()) {
                if (shown.size() < limit && inventory.disclosable(release.ecosystem(), release.coordinate(),
                        release.version(), ServableNames.Policy.HIDE_WITHHELD_AND_GONE)) {
                    shown.add(release);
                }
            }
            after = page.next();
            more = after != null;
        }
        return new Releases(List.copyOf(shown), more);
    }

    public record Releases(List<Release> shown, boolean more) {
    }
}
