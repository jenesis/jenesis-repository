package build.jenesis.repository.format.go;

import module java.base;
import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;

/**
 * Imports a Go module repository (Nexus {@code go}) from an incumbent manager. A Go asset is one file of the
 * {@code <module>/@v/<version>.{info,mod,zip}} trio, and that path is exactly the {@code go/<module>/@v/...} store
 * key {@link GoFormat} reads, so the asset is stored verbatim and the stored version list is generated from the
 * imported {@code .info} files on its first read. One of the language importers, discovered through the same
 * {@code RepositoryImporter} SPI the Maven and OCI importers use.
 */
public final class GoImporter implements RepositoryImporter {

    @Override
    public boolean imports(String format) {
        return format.equals("go") || format.equals("golang");
    }

    @Override
    public Optional<ArtifactDescriptor> importTarget(String path) {
        // RepositoryImporter clause 4: a source path is as client-supplied as a request path, so a
        // traversal-shaped one is refused by name rather than echoed into the descriptor the import edge
        // screens and the trail records (the fix retrofitted here - §13).
        String relative = RepositoryImporter.importablePath(path, "go");
        if (relative.startsWith("go/")) {
            relative = relative.substring("go/".length());
        }
        // The module zip's target coordinate under /go/<module>/@v/<version>.zip, so the edge screens the real Go
        // coordinate. GoFormat describes only the .zip (the artifact carrying the module); the sibling .info/.mod of
        // the trio describe empty and the walk lays them out unscreened - the surface the format itself screens.
        return new GoFormat().describe("/go/" + relative);
    }

    @Override
    public void importArtifact(String path, InputStream content, ArtifactStore store) throws IOException {
        // RepositoryImporter clause 4: a source path is as client-supplied as a request path, so a
        // traversal-shaped one is refused by name rather than echoed into the descriptor the import edge
        // screens and the trail records (the fix retrofitted here - §13).
        String relative = RepositoryImporter.importablePath(path, "go");
        if (relative.startsWith("go/")) {
            relative = relative.substring("go/".length());
        }
        int at = relative.indexOf("/@v/");
        if (at < 0) {
            return;
        }
        new Blobs(store).write("go/" + relative, content);
        // An import is a hosted publish (exactly as a PUT is), so stamp the per-module hosted marker the version
        // discovery gate keys on - otherwise an imported module's @v/list would miss locally as if it were a proxy.
        GoFormat.markHosted(store, GoFormat.hostedKey(relative.substring(0, at)));
    }
}
