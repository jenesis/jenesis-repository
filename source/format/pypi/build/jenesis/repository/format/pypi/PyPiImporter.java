package build.jenesis.repository.format.pypi;

import module java.base;
import build.jenesis.repository.blobs.HostedMarker;
import build.jenesis.repository.blobs.Blobs;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;

/**
 * Imports a PyPI repository from an incumbent manager. A distribution file is stored under
 * {@code pypi/<project>/files/<filename>}, where {@link PyPiFormat} keeps an upload, and the Simple pages are generated
 * on first read. The project is taken from the filename, a migrated asset carrying no upload form.
 */
public final class PyPiImporter implements RepositoryImporter {

    @Override
    public boolean imports(String format) {
        return format.equals("pypi");
    }

    @Override
    public Optional<ArtifactDescriptor> importTarget(String path) {
        // The served coordinate is derived from the filename alone, which names project and version, not from the
        // source path: a deep incumbent path would otherwise leak its slashes into the parsed version. Empty for a file
        // that is no distribution. RepositoryImporter clause 4: a traversal-shaped source path is refused by name.
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
        // RepositoryImporter clause 4: a traversal-shaped source path is refused by name.
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
        // An import is a hosted publish, so the project's hosted marker is stamped.
        HostedMarker.mark(store, PyPiFormat.hostedKey(project));
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
