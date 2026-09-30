package build.jenesis.repository.search.lucene.test;

import module java.base;
import build.jenesis.repository.format.ArtifactLayout;
import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.store.ArtifactStore;

/**
 * A test-only {@link RepositoryFormat} with an {@link ArtifactLayout} for the {@code fake} ecosystem, so the search
 * sweep's license backfill can reverse-map a {@code fake} coordinate to the request path its artifact occupies (the
 * step {@code StoreRepositoryInventory.paths} performs through the owning format) and then re-read that artifact's
 * metadata. It serves nothing - {@link #handle} is never reached in the sweep - and claims only {@code /fake/} paths,
 * so it leaves every other ecosystem's releases to resolve as unknown.
 */
public final class FakeLicensedFormat implements RepositoryFormat, ArtifactLayout {

    @Override
    public String name() {
        return "fake";
    }

    @Override
    public boolean handles(String path) {
        return path.startsWith("/fake/");
    }

    @Override
    public void serve(FormatExchange exchange, ArtifactStore store) {
        throw new UnsupportedOperationException("the fake format serves nothing; it only lays out coordinates");
    }

    @Override
    public String ecosystem() {
        return "fake";
    }

    @Override
    public Optional<build.jenesis.repository.store.ArtifactDescriptor> describe(String path) {
        return Optional.empty();                        // unused by the sweep, which reads the version documents directly
    }

    @Override
    public List<String> paths(String coordinate, String version, ArtifactStore store) {
        return List.of("/fake/" + coordinate + "/" + version);
    }
}
