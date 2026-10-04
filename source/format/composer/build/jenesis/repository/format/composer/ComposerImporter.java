package build.jenesis.repository.format.composer;

import module java.base;
import build.jenesis.repository.blobs.ReplayExchange;
import build.jenesis.repository.format.ProxyFormat;
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
 * archive whose {@code composer.json} name disagrees with the path. A path of this format's own served shape,
 * {@code <registry>/dists/<vendor>/<package>/<version>.zip} - another deployment's listing, or an index walked at a
 * registry's root ({@link ProxyFormat#repository}) - keeps its registry; every other package lands in one
 * {@code /composer/composer/...} registry. Their declared {@code require} and {@code license} are carried into the
 * metadata.
 */
public final class ComposerImporter implements RepositoryImporter {

    /** The registry a package lands in when its path names none. */
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
        return new ComposerFormat().describe("/composer/" + registry(segments) + "/dists/" + vendor + "/" + pkg + "/"
                + file);
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
        new ComposerFormat().handle(new ReplayExchange(
                "/composer/" + registry(segments) + "/" + vendor + "/" + pkg + "/" + version, content), store);
    }

    /** The registry a path of the served shape names, else the single one migrated packages land in. */
    private static String registry(String[] segments) {
        return segments.length == 5 && segments[1].equals("dists") ? segments[0] : REPO;
    }

}
