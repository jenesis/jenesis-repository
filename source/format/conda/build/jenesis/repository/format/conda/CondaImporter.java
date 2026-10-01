package build.jenesis.repository.format.conda;

import module java.base;
import build.jenesis.repository.blobs.ReplayExchange;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;

/**
 * Imports a conda channel from an incumbent manager. The assets are the {@code .conda} and {@code .tar.bz2} packages;
 * {@code repodata.json} is derived and not imported. A source path's parent is the subdir and its last segment the
 * file, so a package migrates to {@code /conda/conda/<subdir>/<file>}; one without a parent directory is skipped rather
 * than mis-filed. Each is replayed as the format's own {@code PUT}, so it streams into the store.
 */
public final class CondaImporter implements RepositoryImporter {

    /** The single channel migrated packages land in. */
    private static final String REPO = "conda";

    @Override
    public boolean imports(String format) {
        return format.equals("conda");
    }

    @Override
    public Optional<ArtifactDescriptor> importTarget(String path) {
        // RepositoryImporter clause 4: a traversal-shaped source path is refused by name.
        String relative = RepositoryImporter.importablePath(path, "conda");

        // The coordinate under the path importArtifact lays the package at, so the edge screens it; empty for a
        // non-package or subdir-less path.
        return new CondaFormat().describe("/conda/" + REPO + "/" + relative);
    }

    @Override
    public void importArtifact(String path, InputStream content, ArtifactStore store) throws IOException {
        // RepositoryImporter clause 4: a traversal-shaped source path is refused by name.
        String relative = RepositoryImporter.importablePath(path, "conda");
        String lower = relative.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".conda") && !lower.endsWith(".tar.bz2")) {
            return;
        }
        int slash = relative.lastIndexOf('/');
        if (slash <= 0) {
            // No <subdir>/<file> layout: the platform is unknown, so the package is skipped rather than hidden under a
            // guessed subdir.
            return;
        }
        String file = relative.substring(slash + 1);
        String subdir = relative.substring(relative.lastIndexOf('/', slash - 1) + 1, slash);
        new CondaFormat().handle(new ReplayExchange("/conda/" + REPO + "/" + subdir + "/" + file, content), store);
    }

}
