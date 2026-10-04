package build.jenesis.repository.format.jenesis;

import module java.base;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;

/**
 * Imports a Jenesis module repository, {@code module/<name>/<version>/<file>.jar}: each jar is laid out as
 * {@link JenesisFormat}'s own {@code PUT} lays it out, so a module's latest pointer follows the versions imported as it
 * follows the versions published. The latest pointer and the {@code artifact/} views are derived rather than
 * published, so a source listing them has them skipped, as a {@code PUT} to them is refused.
 */
public final class JenesisImporter implements RepositoryImporter {

    @Override
    public boolean imports(String sourceFormat) {
        return sourceFormat.equals("jenesis");
    }

    @Override
    public Optional<ArtifactDescriptor> importTarget(String sourcePath) {
        // RepositoryImporter clause 4: a traversal-shaped source path is refused by name.
        String relative = RepositoryImporter.importablePath(sourcePath, "jenesis");
        return new JenesisFormat().describe("/" + relative);
    }

    @Override
    public void importArtifact(String path, InputStream content, ArtifactStore store) throws IOException {
        String relative = RepositoryImporter.importablePath(path, "jenesis");
        JenesisFormat.publish("/" + relative, content, new Publication(store), store);
    }
}
