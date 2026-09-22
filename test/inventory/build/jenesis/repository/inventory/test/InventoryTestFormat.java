package build.jenesis.repository.inventory.test;

import module java.base;
import build.jenesis.repository.format.ArtifactLayout;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;

/**
 * A minimal self-contained format for the inventory suite: it owns {@code /test/<coordinate>/<version>/<file>} paths
 * and describes their neutral coordinate from the path alone, so the inventory's publish record, the reconcile walk
 * legs and the layout-driven eviction resolve an ecosystem, coordinate and version without any real ecosystem module
 * on the test path. Discovered by the inventory's {@code ServiceLoader}; never serves, so {@link #handle} throws.
 */
public final class InventoryTestFormat implements RepositoryFormat, ArtifactLayout {

    static final String PREFIX = "/test/";
    static final String ECOSYSTEM = "test";

    /** The request path a coordinate version's single artifact occupies. */
    static String path(String coordinate, String version) {
        return PREFIX + coordinate + "/" + version + "/" + coordinate + "-" + version + ".bin";
    }

    @Override
    public String name() {
        return "test";
    }

    @Override
    public boolean handles(String path) {
        return path.startsWith(PREFIX);
    }

    @Override
    public void serve(FormatExchange exchange, ArtifactStore store) {
        throw new UnsupportedOperationException("the test format never serves");
    }

    @Override
    public String ecosystem() {
        return ECOSYSTEM;
    }

    @Override
    public Optional<ArtifactDescriptor> describe(String path) {
        String[] parts = path.substring(PREFIX.length()).split("/");
        if (parts.length < 3) {
            return Optional.of(ArtifactDescriptor.at(ECOSYSTEM, path));
        }
        return Optional.of(new ArtifactDescriptor(ECOSYSTEM, parts[0], parts[1], path,
                "application/octet-stream", parts[1].endsWith("-SNAPSHOT"), null, -1L));
    }

    @Override
    public List<String> paths(String coordinate, String version) {
        return List.of(PREFIX + coordinate + "/" + version);
    }

    @Override
    public List<String> paths(String coordinate, String version, ArtifactStore store) {
        return paths(coordinate, version);
    }
}
