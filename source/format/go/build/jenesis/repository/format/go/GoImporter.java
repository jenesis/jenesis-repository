package build.jenesis.repository.format.go;

import module java.base;
import build.jenesis.repository.blobs.HostedMarker;
import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;

/**
 * Imports a Go module repository from an incumbent manager. An asset is one file of the
 * {@code <module>/@v/<version>.{info,mod,zip}} trio, which is exactly the key {@link GoFormat} reads, so it is stored
 * verbatim and the version list is generated on its first read.
 */
public final class GoImporter implements RepositoryImporter {

    @Override
    public boolean imports(String format) {
        return format.equals("go") || format.equals("golang");
    }

    @Override
    public Optional<ArtifactDescriptor> importTarget(String path) {
        // RepositoryImporter clause 4: a traversal-shaped source path is refused by name.
        String relative = RepositoryImporter.importablePath(path, "go");
        if (relative.startsWith("go/")) {
            relative = relative.substring("go/".length());
        }
        // The module zip's coordinate, so the edge screens it; the .info and .mod describe empty and are laid out
        // unscreened.
        return new GoFormat().describe("/go/" + relative);
    }

    @Override
    public void importArtifact(String path, InputStream content, ArtifactStore store) throws IOException {
        // RepositoryImporter clause 4: a traversal-shaped source path is refused by name.
        String relative = RepositoryImporter.importablePath(path, "go");
        if (relative.startsWith("go/")) {
            relative = relative.substring("go/".length());
        }
        int at = relative.indexOf("/@v/");
        if (at < 0) {
            return;
        }
        new Blobs(store).write("go/" + relative, content);
        // An import is a hosted publish, so the module's hosted marker is stamped.
        HostedMarker.mark(store, GoFormat.hostedKey(relative.substring(0, at)));
    }
}
