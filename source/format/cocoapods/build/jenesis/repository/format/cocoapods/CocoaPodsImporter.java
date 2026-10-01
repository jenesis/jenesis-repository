package build.jenesis.repository.format.cocoapods;

import module java.base;
import build.jenesis.repository.blobs.ReplayExchange;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;

/**
 * Imports a CocoaPods registry from an incumbent manager. The assets are the pod zips; the CDN metadata is derived, and
 * any non-zip asset is skipped. The coordinate is the source path's trailing {@code .../<name>/<version>/<file>.zip}
 * segments, the shape this format serves an archive at, so an export re-imports; a version never contains {@code /}.
 *
 * <p>Each archive is replayed as the format's own {@code PUT}, so it streams into the store, and the format refuses one
 * whose podspec disagrees with the path. All pods land in one {@code /cocoapods/cocoapods/...} registry, their declared
 * dependencies and licence carried into the regenerated podspec.
 */
public final class CocoaPodsImporter implements RepositoryImporter {

    /** The single registry migrated pods land in. */
    private static final String REPO = "cocoapods";

    private static final String ZIP = ".zip";

    @Override
    public boolean imports(String format) {
        return format.equals("cocoapods");
    }

    @Override
    public Optional<ArtifactDescriptor> importTarget(String path) {
        // RepositoryImporter clause 4: a traversal-shaped source path is refused by name.
        String relative = RepositoryImporter.importablePath(path, "cocoapods");
        if (!relative.toLowerCase(Locale.ROOT).endsWith(ZIP)) {
            return Optional.empty();
        }
        // The coordinate from the trailing <name>/<version>/<file>.zip, the segments importArtifact files by, with the
        // served pods/ path rebuilt from them, so a bare or deep incumbent path still screens the real coordinate.
        // Empty without that shape, the assets importArtifact skips.
        String[] segments = relative.split("/");
        if (segments.length < 3) {
            return Optional.empty();
        }
        String file = segments[segments.length - 1];
        String version = segments[segments.length - 2];
        String name = segments[segments.length - 3];
        return new CocoaPodsFormat().describe(
                "/cocoapods/" + REPO + "/pods/" + name + "/" + version + "/" + file);
    }

    @Override
    public void importArtifact(String path, InputStream content, ArtifactStore store) throws IOException {
        // RepositoryImporter clause 4: a traversal-shaped source path is refused by name.
        String relative = RepositoryImporter.importablePath(path, "cocoapods");
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
        String version = segments[segments.length - 2];
        String name = segments[segments.length - 3];
        if (name.isEmpty() || version.isEmpty()) {
            return;
        }
        new CocoaPodsFormat().handle(
                new ReplayExchange("/cocoapods/" + REPO + "/" + name + "/" + version, content), store);
    }

}
