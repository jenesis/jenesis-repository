package build.jenesis.repository.ui.store;

import module java.base;

import build.jenesis.repository.ui.CurrentTenant;
import build.jenesis.repository.format.RepositoryType;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.scope.Scopes;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.RepositoryDocument;
import build.jenesis.repository.store.RepositoryRemoval;
import build.jenesis.repository.store.ServableNames;
import io.micrometer.observation.ObservationRegistry;

/**
 * The console's listing of the signed-in tenant's repositories: their names, documents, storage namespaces and recent
 * holdings, over the {@link TenantScope} confinement. The console's role model authorizes these calls.
 */
public class RepositoryAdmin extends TenantScope {

    public RepositoryAdmin(ArtifactStore repositoryStore, CurrentTenant current, ObservationRegistry observations) {
        super(repositoryStore, current, observations);
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

    /** The top-level storage namespaces of a repository, one prefix listing, which the console marks with the format
     *  claiming each. */
    public List<String> namespaces(String repository) {
        return scope(repository).list("").stream().filter(name -> !name.equals(Scopes.REPOSITORY)).toList();
    }

    /** A repository's own document, through the node's cache, or empty for one holding no format or being deleted. */
    public Optional<RepositoryDocument> document(String repository) throws IOException {
        return RepositoryDocument.cached(root, tenant(), repository);
    }

    /** Whether a repository is being deleted: one point probe, asked only of a repository that has no document. */
    public boolean removing(String repository) throws IOException {
        return RepositoryRemoval.removing(scope(repository));
    }

    /** The format a repository holds, through the node's cache, or empty for one holding none, which answers no
     *  request. */
    public Optional<String> format(String repository) throws IOException {
        return RepositoryDocument.cached(root, tenant(), repository).map(RepositoryDocument::format);
    }

    /**
     * A repository's format and client URL ({@code /repository/<tenant>/<repository>/}, or {@code /v2/...} for an OCI
     * type); empty for one holding no format.
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
     * The versions a repository most recently took in, up to {@code limit}, newest first: two bounded windows over the
     * newest-first indexes of releases and cached copies, merged by arrival; {@code more} says either held more.
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

    /** One index's newest-first window of at most {@code limit} holdings still served, read a page at a time while
     *  the window is not full. */
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
