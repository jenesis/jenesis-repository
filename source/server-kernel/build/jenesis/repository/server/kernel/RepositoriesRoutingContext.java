package build.jenesis.repository.server.kernel;

import module java.base;

import build.jenesis.repository.server.RoutingContext;
import build.jenesis.repository.store.ArtifactStore;

/**
 * Answers a {@link RoutingContext}'s questions out of {@link Repositories} - the one adapter between the free
 * core's routing seam and this kernel's view of what a repository is.
 *
 * <p>It is a named type rather than an anonymous class in the application's configuration because it is not only
 * the application that needs it: every routing suite builds one too, and an adapter written twice is an adapter
 * whose two halves disagree about the case that matters. The case that matters here is the last method - a
 * repository this deployment holds no definition for is <em>hosted</em>, and therefore writable. Each of the three
 * routings used to open-code that null check, which is three places for it to be got wrong on the path that
 * decides whether a publish is accepted.
 */
public final class RepositoriesRoutingContext implements RoutingContext {

    private final ArtifactStore root;
    private final Repositories repositories;
    private final String defaultTenant;
    private final String defaultRepository;
    private final UnaryOperator<String> config;

    public RepositoriesRoutingContext(ArtifactStore root, Repositories repositories, String defaultTenant,
                                      String defaultRepository, UnaryOperator<String> config) {
        this.root = root;
        this.repositories = repositories;
        this.defaultTenant = defaultTenant;
        this.defaultRepository = defaultRepository;
        this.config = config;
    }

    @Override
    public ArtifactStore root() {
        return root;
    }

    @Override
    public String config(String key) {
        return config.apply(key);
    }

    @Override
    public String defaultTenant() {
        return defaultTenant;
    }

    @Override
    public String defaultRepository() {
        return defaultRepository;
    }

    @Override
    public String tenantOf(String key) {
        return repositories.tenant(key);
    }

    @Override
    public ArtifactStore store(String tenant, String repository) throws IOException {
        return repositories.writable(tenant, repository);
    }

    @Override
    public boolean writable(String repository) {
        // Undefined means hosted, which is writable; only a definition can say otherwise - a proxy, a group view,
        // or one marked read-only.
        return repositories.definitions().writable(repository);
    }
}
