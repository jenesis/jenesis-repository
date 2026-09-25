package build.jenesis.repository.hooks.testkit;

import module java.base;

import build.jenesis.repository.format.ArtifactLayout;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;

/**
 * A minimal publish-namespace format over the {@code /kit/} request paths the shared contract publishes into, so the
 * shipped hooks that key on a <em>coordinate</em> have one to key on.
 *
 * <p>This is not a convenience. The retroactive hold kinds and the findings reaper write their records under
 * {@code holds/<kind>/<ecosystem>/<coordinate>/<version>}, which they resolve through
 * {@code StoreRepositoryInventory.describe(path)}; a path no installed format claims resolves to no coordinate and
 * every one of them is then a no-op, so the whole hold-release contract would pass vacuously. The kit's paths are
 * synthetic on purpose (it publishes {@code ArtifactDescriptor.at("kit", path)}), so the format that claims them has
 * to be synthetic too, which is what gives the hold kinds a resolvable coordinate without dragging a real ecosystem
 * module onto the path.
 *
 * <p>It serves nothing and lays nothing out: {@code Publication} is driven directly by the kit, and a format here
 * would be a second publish choreography.
 */
public final class HookTestFormat implements RepositoryFormat, ArtifactLayout {

    /** The request-path prefix the kit publishes every check's artifact under. */
    public static final String PREFIX = "/kit/";

    /** The ecosystem the kit stamps on its descriptors, so the format and the publish agree. */
    public static final String ECOSYSTEM = "kit";

    /** One version for every kit path: the kit's paths carry no version segment, and a hold record is keyed by
     *  {@code (ecosystem, coordinate, version)} - so the coordinate is the path's tail and the version is fixed. */
    public static final String VERSION = "1.0";

    @Override
    public String name() {
        return ECOSYSTEM;
    }

    @Override
    public boolean handles(String path) {
        return path.startsWith(PREFIX);
    }

    @Override
    public void serve(FormatExchange exchange, ArtifactStore store) {
        throw new UnsupportedOperationException("the hook contract drives Publication directly; this format never serves");
    }

    @Override
    public String ecosystem() {
        return ECOSYSTEM;
    }

    @Override
    public Optional<ArtifactDescriptor> describe(String path) {
        if (!handles(path)) {
            return Optional.empty();
        }
        String tail = path.substring(PREFIX.length());
        if (tail.isEmpty()) {
            return Optional.of(ArtifactDescriptor.at(ECOSYSTEM, path));
        }
        return Optional.of(new ArtifactDescriptor(ECOSYSTEM, tail.replace('/', ':'), VERSION, path,
                "application/octet-stream", false, null, -1L));
    }

    @Override
    public List<String> paths(String coordinate, String version) {
        return VERSION.equals(version) ? List.of(PREFIX + coordinate.replace(':', '/')) : List.of();
    }

    @Override
    public List<String> paths(String coordinate, String version, ArtifactStore store) {
        return paths(coordinate, version);
    }
}
