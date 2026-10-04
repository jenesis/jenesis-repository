package build.jenesis.repository.format.homebrew;

import module java.base;
import build.jenesis.repository.blobs.ReplayExchange;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;

/**
 * Imports a bottle domain, {@code <repo>/<bottle>}, as another deployment of this product lists one: each bottle, and
 * the attestations document beside one, is replayed as {@link HomebrewFormat}'s own {@code PUT}, so it is stored and
 * linked as a publish is. Neither incumbent hosts Homebrew, so another deployment is the one source there is.
 */
public final class HomebrewImporter implements RepositoryImporter {

    @Override
    public boolean imports(String sourceFormat) {
        return sourceFormat.equals("homebrew");
    }

    @Override
    public Optional<ArtifactDescriptor> importTarget(String sourcePath) {
        // RepositoryImporter clause 4: a traversal-shaped source path is refused by name.
        String relative = RepositoryImporter.importablePath(sourcePath, "homebrew");
        // A path that is no bottle is declined rather than screened under a coordinate it does not carry.
        return new HomebrewFormat().describe("/homebrew/" + relative).filter(bottle -> bottle.coordinate() != null);
    }

    @Override
    public void importArtifact(String path, InputStream content, ArtifactStore store) throws IOException {
        String relative = RepositoryImporter.importablePath(path, "homebrew");
        new HomebrewFormat().handle(new ReplayExchange("/homebrew/" + relative, content), store);
    }
}
