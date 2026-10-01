package build.jenesis.repository.format.conan;

import module java.base;
import build.jenesis.repository.blobs.ReplayExchange;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;

/**
 * Imports a Conan registry from an incumbent manager. The coordinate and client-computed revision are in the path, so
 * the assets are the revision files at the v2 path {@link ConanFormat} serves them from; an asset of neither file shape
 * - an index, a {@code ping} answer, a path without {@code v2/conans/} - is skipped.
 *
 * <p>The reference tail is replayed unchanged as {@code PUT /conan/<repo>/v2/conans/<tail>} through
 * {@link ConanFormat#handle}, so the revisions are preserved and the file streams into the store. All packages land in
 * one {@code /conan/conan/...} registry whose index the replayed uploads maintain. No licence is reconstructed: Conan
 * declares it only in its {@code conanfile.py}.
 */
public final class ConanImporter implements RepositoryImporter {

    /** The single registry migrated packages land in. */
    private static final String REPO = "conan";

    /** The v2 REST marker rooting every revision file, as {@link ConanFormat} routes on it; the reference tail follows
     *  it. */
    private static final String MARKER = "v2/conans/";

    @Override
    public boolean imports(String format) {
        return format.equals("conan");
    }

    @Override
    public Optional<ArtifactDescriptor> importTarget(String path) {
        // RepositoryImporter clause 4: a traversal-shaped source path is refused by name.
        String relative = RepositoryImporter.importablePath(path, "conan");
        int marker = relative.indexOf(MARKER);
        if (marker < 0) {
            return Optional.empty();
        }
        // The coordinate under the v2 path importArtifact lays the file at, so the edge screens it; empty for a derived
        // index.
        return new ConanFormat().describe(
                "/conan/" + REPO + "/" + MARKER + relative.substring(marker + MARKER.length()));
    }

    @Override
    public void importArtifact(String path, InputStream content, ArtifactStore store) throws IOException {
        // RepositoryImporter clause 4: a traversal-shaped source path is refused by name.
        String relative = RepositoryImporter.importablePath(path, "conan");
        int marker = relative.indexOf(MARKER);
        if (marker < 0) {
            // No v2/conans/ root: not a revision file.
            return;
        }
        String tail = relative.substring(marker + MARKER.length());
        String[] t = tail.split("/", -1);
        // The two file shapes the format serves: a recipe file (8 segments) and a package file (12); anything else is
        // an index.
        boolean recipeFile = t.length == 8 && t[4].equals("revisions") && t[6].equals("files");
        boolean packageFile = t.length == 12 && t[4].equals("revisions") && t[6].equals("packages")
                && t[8].equals("revisions") && t[10].equals("files");
        if (!recipeFile && !packageFile) {
            return;
        }
        // Replayed unchanged, revisions included, so the file streams in at the key it is served from.
        new ConanFormat().handle(new ReplayExchange("/conan/" + REPO + "/" + MARKER + tail, content), store);
    }

}
