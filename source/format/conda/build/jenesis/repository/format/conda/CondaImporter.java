package build.jenesis.repository.format.conda;

import module java.base;
import build.jenesis.repository.blobs.ReplayExchange;
import build.jenesis.repository.format.ProxyFormat;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;

/**
 * Imports a conda channel from an incumbent manager. The assets are the {@code .conda} and {@code .tar.bz2} packages;
 * {@code repodata.json} is derived and not imported. A source path's parent is the subdir and its last segment the
 * file. A path of this format's own served shape, {@code <channel>/<subdir>/<file>} - another deployment's listing, or
 * an index walked at a channel's root ({@link ProxyFormat#repository}) - keeps its channel; any other package migrates
 * to {@code /conda/conda/<subdir>/<file>}, and one without a parent directory is skipped rather than mis-filed. Each is
 * replayed as the format's own {@code PUT}, so it streams into the store.
 */
public final class CondaImporter implements RepositoryImporter {

    /** The channel a package lands in when its path names none. */
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
        return publishPath(relative).flatMap(new CondaFormat()::describe);
    }

    @Override
    public void importArtifact(String path, InputStream content, ArtifactStore store) throws IOException {
        // RepositoryImporter clause 4: a traversal-shaped source path is refused by name.
        String relative = RepositoryImporter.importablePath(path, "conda");
        String lower = relative.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".conda") && !lower.endsWith(".tar.bz2")) {
            return;
        }
        Optional<String> target = publishPath(relative);
        if (target.isPresent()) {
            new CondaFormat().handle(new ReplayExchange(target.get(), content), store);
        }
    }

    /** Where a package migrates: the channel a path of the served shape names, else the single channel, under its
     *  subdir. Empty without a {@code <subdir>/<file>} layout: the platform is unknown, so the package is skipped
     *  rather than hidden under a guessed subdir. */
    private static Optional<String> publishPath(String relative) {
        String[] segments = relative.split("/");
        if (segments.length < 2) {
            return Optional.empty();
        }
        String channel = segments.length == 3 ? segments[0] : REPO;
        return Optional.of("/conda/" + channel + "/" + segments[segments.length - 2] + "/"
                + segments[segments.length - 1]);
    }

}
