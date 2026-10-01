package build.jenesis.repository.format.composer;

import module java.base;
import build.jenesis.repository.blobs.ReplayExchange;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;

/**
 * Imports a Composer registry from an incumbent manager. The assets are the package zips; {@code packages.json} and the
 * {@code p2} files are derived, and any non-zip asset is skipped. The coordinate is the source path's trailing
 * {@code <vendor>/<package>/<version>.zip} segments, the shape this format serves a dist at, so an export re-imports; a
 * version never contains {@code /}.
 *
 * <p>Each archive is replayed as the format's own {@code PUT}, so it streams into the store, and the format refuses an
 * archive whose {@code composer.json} name disagrees with the path. All packages land in one
 * {@code /composer/composer/...} registry, their declared {@code require} and {@code license} carried into the
 * metadata.
 */
public final class ComposerImporter implements RepositoryImporter {

    /** The single registry migrated packages land in. */
    private static final String REPO = "composer";

    private static final String ZIP = ".zip";

    @Override
    public boolean imports(String format) {
        return format.equals("composer");
    }

    @Override
    public Optional<ArtifactDescriptor> importTarget(String path) {
        // RepositoryImporter clause 4: a traversal-shaped source path is refused by name.
        String relative = RepositoryImporter.importablePath(path, "composer");
        if (!relative.toLowerCase(Locale.ROOT).endsWith(ZIP)) {
            return Optional.empty();
        }
        // The dist coordinate from the trailing <vendor>/<package>/<version>.zip, the segments importArtifact files by,
        // so a deep incumbent path still screens the real coordinate. Empty without that shape, the assets
        // importArtifact skips.
        String[] segments = relative.split("/");
        if (segments.length < 3) {
            return Optional.empty();
        }
        String file = segments[segments.length - 1];
        String pkg = segments[segments.length - 2];
        String vendor = segments[segments.length - 3];
        return new ComposerFormat().describe("/composer/" + REPO + "/dists/" + vendor + "/" + pkg + "/" + file);
    }

    @Override
    public void importArtifact(String path, InputStream content, ArtifactStore store) throws IOException {
        // RepositoryImporter clause 4: a traversal-shaped source path is refused by name.
        String relative = RepositoryImporter.importablePath(path, "composer");
        if (!relative.toLowerCase(Locale.ROOT).endsWith(ZIP)) {
            // Only the zips migrate; metadata is regenerated.
            return;
        }
        String[] segments = relative.split("/");
        if (segments.length < 3) {
            // Without the trailing shape the coordinate is unknown, so the asset is skipped rather than filed under a
            // guess.
            return;
        }
        String file = segments[segments.length - 1];
        String version = file.substring(0, file.length() - ZIP.length());
        String pkg = segments[segments.length - 2];
        String vendor = segments[segments.length - 3];
        if (version.isEmpty()) {
            return;
        }
        new ComposerFormat().handle(
                new ReplayExchange("/composer/" + REPO + "/" + vendor + "/" + pkg + "/" + version, content), store);
    }

}
