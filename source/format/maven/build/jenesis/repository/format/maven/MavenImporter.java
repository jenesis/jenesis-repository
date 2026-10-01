package build.jenesis.repository.format.maven;

import module java.base;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;

/**
 * Imports a Maven repository from an incumbent manager: each asset path is a coordinate, published under
 * {@code /maven/...} as a deploy would be through {@link MavenFormat#layout}. {@code maven-metadata.xml} and its
 * checksums are skipped, since the repository computes them under an opt-in setting ({@link MavenMetadata}).
 */
public final class MavenImporter implements RepositoryImporter {

    @Override
    public boolean imports(String sourceFormat) {
        return sourceFormat.equals("maven2") || sourceFormat.equals("maven");
    }

    @Override
    public Optional<ArtifactDescriptor> importTarget(String sourcePath) {
        // RepositoryImporter clause 4: a traversal-shaped source path is refused by name here, since describe() falls
        // back to the raw path for one that is not a full coordinate.
        String relative = RepositoryImporter.importablePath(sourcePath, "maven");
        // The descriptor MavenFormat parses from the /maven/ path the asset lands on; empty for a maven-metadata.xml,
        // which importArtifact skips.
        return new MavenFormat().describe("/maven/" + relative);
    }

    @Override
    public void importArtifact(String path, InputStream content, ArtifactStore store) throws IOException {
        String relative = RepositoryImporter.importablePath(path, "maven");
        String name = relative.substring(relative.lastIndexOf('/') + 1);
        if (name.startsWith("maven-metadata.xml")) {
            return;
        }
        // layout streams the content to storage and reads a modular jar's module name back from there.
        MavenFormat.layout(store, "/maven/" + relative, content);
    }
}
