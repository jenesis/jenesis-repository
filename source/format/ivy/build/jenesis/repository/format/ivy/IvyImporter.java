package build.jenesis.repository.format.ivy;

import module java.base;
import build.jenesis.repository.blobs.ReplayExchange;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;

/**
 * Imports an Ivy repository laid out as Gradle publishes one, {@code <organisation>/<module>/<revision>/<file>}: each file
 * is replayed as {@link IvyFormat}'s own {@code PUT}, so it is stored, linked and listed among its module's revisions
 * as a publish is. A path off that layout is refused by the format, as a publish of it would be.
 */
public final class IvyImporter implements RepositoryImporter {

    @Override
    public boolean imports(String sourceFormat) {
        return sourceFormat.equals("ivy");
    }

    @Override
    public Optional<ArtifactDescriptor> importTarget(String sourcePath) {
        // RepositoryImporter clause 4: a traversal-shaped source path is refused by name.
        String relative = RepositoryImporter.importablePath(sourcePath, "ivy");
        return new IvyFormat().describe("/ivy/" + relative);
    }

    @Override
    public void importArtifact(String path, InputStream content, ArtifactStore store) throws IOException {
        String relative = RepositoryImporter.importablePath(path, "ivy");
        new IvyFormat().handle(new ReplayExchange("/ivy/" + relative, content), store);
    }
}
