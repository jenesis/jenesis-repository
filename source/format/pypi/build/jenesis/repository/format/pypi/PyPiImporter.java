package build.jenesis.repository.format.pypi;

import module java.base;
import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;

/**
 * Imports a PyPI repository (Nexus {@code pypi}) from an incumbent manager. A distribution file is stored under
 * {@code pypi/<project>/files/<filename>}, the project normalized per PEP 503, exactly where {@link PyPiFormat}
 * keeps an uploaded distribution; the stored Simple pages are generated from those files when first read. The
 * project is taken from the distribution filename (a wheel or sdist names the project before its version), since a
 * migrated asset carries no upload form. One of the language importers, discovered through the same
 * {@code RepositoryImporter} SPI the built-in importers use.
 */
public final class PyPiImporter implements RepositoryImporter {

    @Override
    public boolean imports(String format) {
        return format.equals("pypi");
    }

    @Override
    public Optional<ArtifactDescriptor> importTarget(String path) {
        // Describe the distribution's canonical served coordinate /pypi/simple/<project>/<filename>, derived from the
        // filename alone - NOT the raw source path. A live incumbent manager lays a distribution at a deep asset path
        // (Nexus: packages/<name>/<version>/<file>); prepending that whole path made PyPiFormat parse a version that
        // still carried the path's slashes (e.g. "demo/1.0.0/acme_demo"), which the inventory then rejected as a
        // non-traversal-free segment, failing the import. The filename alone names the project and version (a wheel/
        // sdist encodes both), exactly as importArtifact derives the storage layout below. Empty for an asset that is
        // not a recognised distribution - the walk lays it out unscreened and importArtifact imports the rest.
        // RepositoryImporter clause 4: a source path is as client-supplied as a request path, so a
        // traversal-shaped one is refused by name rather than echoed into the descriptor the import edge
        // screens and the trail records (the fix retrofitted here - §13).
        String relative = RepositoryImporter.importablePath(path, "pypi");
        String filename = relative.substring(relative.lastIndexOf('/') + 1);
        String project = normalize(project(filename));
        if (project.isEmpty()) {
            return Optional.empty();
        }
        return new PyPiFormat().describe("/pypi/simple/" + project + "/" + filename);
    }

    @Override
    public void importArtifact(String path, InputStream content, ArtifactStore store) throws IOException {
        // RepositoryImporter clause 4: a source path is as client-supplied as a request path, so a
        // traversal-shaped one is refused by name rather than echoed into the descriptor the import edge
        // screens and the trail records (the fix retrofitted here - §13).
        String relative = RepositoryImporter.importablePath(path, "pypi");
        String filename = relative.substring(relative.lastIndexOf('/') + 1);
        if (!(filename.endsWith(".whl") || filename.endsWith(".tar.gz")
                || filename.endsWith(".zip") || filename.endsWith(".egg"))) {
            return;
        }
        String project = normalize(project(filename));
        if (project.isEmpty()) {
            return;
        }
        new Blobs(store).write("pypi/" + project + "/files/" + filename, content);
        // An import is a hosted publish (exactly as a twine upload is), so stamp the per-project hosted marker the
        // Simple-index gate keys on - otherwise an imported project's index would miss locally as if it were a proxy.
        PyPiFormat.markHosted(store, PyPiFormat.hostedKey(project));
    }

    /** The project name of a distribution filename: the text before the first hyphen that begins the version. */
    private static String project(String filename) {
        for (int index = 0; index < filename.length() - 1; index++) {
            if (filename.charAt(index) == '-' && Character.isDigit(filename.charAt(index + 1))) {
                return filename.substring(0, index);
            }
        }
        return "";
    }

    private static String normalize(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[-_.]+", "-");
    }
}
