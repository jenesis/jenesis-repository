package build.jenesis.repository.inventory.test;

import module java.base;

import build.jenesis.repository.format.ArtifactLayout;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;

/**
 * A layout that is installed and declares an ecosystem, and can place nothing.
 *
 * <p>It exists for one state that neither of the other stubs can reach: a format IS on the module path for the
 * ecosystem, so the "no installed layout" branch does not fire, and yet asking it where a version lives yields
 * nothing. That is not a contrived shape. It is what a layout whose path mapping is configured <em>per repository</em>
 * answers when it is asked through the store-free overload, which is the overload the disclosure screen uses
 * deliberately so that a search never opens a blob; and it is what any layout answers for a coordinate it cannot
 * place.
 *
 * <p>Answering empty from both overloads and from {@code describe} is the whole of it - the point is the silence, not
 * a second layout scheme.
 */
public final class InventoryUnplaceableFormat implements RepositoryFormat, ArtifactLayout {

    static final String ECOSYSTEM = "unplaceable";

    @Override
    public String name() {
        return "unplaceable";
    }

    @Override
    public boolean handles(String path) {
        return path.startsWith("/unplaceable/");
    }

    @Override
    public void serve(FormatExchange exchange, ArtifactStore store) {
        throw new UnsupportedOperationException("the unplaceable test format never serves");
    }

    @Override
    public String ecosystem() {
        return ECOSYSTEM;
    }

    @Override
    public Optional<ArtifactDescriptor> describe(String path) {
        return Optional.empty();
    }

    @Override
    public List<String> paths(String coordinate, String version, ArtifactStore store) {
        return List.of();
    }
}
