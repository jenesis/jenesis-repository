package build.jenesis.repository.ui.store;

import module java.base;

import build.jenesis.repository.ui.CurrentTenant;
import build.jenesis.repository.format.RepositoryType;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.scope.Scopes;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.RepositoryDocument;
import build.jenesis.repository.store.RepositoryRemoval;
import build.jenesis.repository.store.StoreCache;
import build.jenesis.repository.store.ServableNames;
import io.micrometer.observation.ObservationRegistry;

/**
 * The console's listing of the artifact repository, scoped to the signed-in user's tenant: the tenant's named
 * repositories and, per repository, its storage namespaces and what it holds. The browse, search and
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
     * kept for {@code jenrepo.cache.ttl}, five minutes by default, rather than from a store listing per page view.
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

    /** A repository's own document - its format, when it was created, its description - or empty for one that has
     *  none: created before repositories held a format, or being deleted. Read through the node's cache. */
    public Optional<RepositoryDocument> document(String repository) throws IOException {
        return RepositoryDocument.cached(root, tenant(), repository);
    }

    /** Whether a repository is being deleted: one point probe, asked only of a repository that has no document. */
    public boolean removing(String repository) throws IOException {
        return RepositoryRemoval.removing(scope(repository));
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

    /** The path a client names within {@code repository} for a path its format lays out - the path the API takes. */
    public String servedPath(String repository, String formatPath) throws IOException {
        return format(repository).flatMap(RepositoryType::installed).map(type -> type.servedPath(formatPath))
                .orElse(formatPath);
    }

    /** The format a repository holds and the URL a client reaches it at. */
    public record Identity(String format, String url) {
    }

    /**
     * The versions a repository most recently took in (up to {@code limit}, newest first): the releases published
     * into it and the copies it cached from its upstreams, each read from its own newest-first index a window at a
     * time and merged by when it arrived. Two bounded windows rather than a walk, so the detail hub renders a recent
     * slice of a repository of any size - the full, paged list is the browse page and a coordinate's own page - and
     * {@code more} says when either index held more than the slice shows.
     */
    public Held recentHoldings(String repository, int limit) throws IOException {
        StoreRepositoryInventory inventory = inventory(repository);
        Window releases = window(inventory, limit, (after, size) -> {
            StoreRepositoryInventory.ReleasePage page = inventory.recent(after, size);
            return new StoreRepositoryInventory.HoldingPage(
                    page.releases().stream().map(StoreRepositoryInventory.Holding::of).toList(), page.next());
        });
        Window cached = window(inventory, limit, inventory::cached);
        List<StoreRepositoryInventory.Holding> merged = new ArrayList<>(releases.shown());
        merged.addAll(cached.shown());
        merged.sort(Comparator.comparing(StoreRepositoryInventory.Holding::at,
                Comparator.nullsLast(Comparator.reverseOrder())));
        boolean more = releases.more() || cached.more() || merged.size() > limit;
        return new Held(List.copyOf(merged.subList(0, Math.min(limit, merged.size()))), more);
    }

    /** One index's newest-first window: at most {@code limit} holdings still served, and whether the index held more.
     *  The index is read a page at a time and screened as it goes - a row whose version has since been held or
     *  removed is skipped - and the next page is asked for only while the window is not yet full. */
    private static Window window(StoreRepositoryInventory inventory, int limit, Pages pages) throws IOException {
        List<StoreRepositoryInventory.Holding> shown = new ArrayList<>();
        String after = null;
        boolean more = true;
        while (shown.size() < limit && more) {
            StoreRepositoryInventory.HoldingPage page = pages.page(after, limit - shown.size());
            for (StoreRepositoryInventory.Holding holding : page.holdings()) {
                if (shown.size() < limit && inventory.disclosable(holding.ecosystem(), holding.coordinate(),
                        holding.version(), ServableNames.Policy.HIDE_WITHHELD_AND_GONE)) {
                    shown.add(holding);
                }
            }
            after = page.next();
            more = after != null;
        }
        return new Window(shown, more);
    }

    @FunctionalInterface
    private interface Pages {
        StoreRepositoryInventory.HoldingPage page(String after, int limit) throws IOException;
    }

    private record Window(List<StoreRepositoryInventory.Holding> shown, boolean more) {
    }

    /** The recent slice a screen renders, and whether there is more than it shows. */
    public record Held(List<StoreRepositoryInventory.Holding> shown, boolean more) {
    }
}
