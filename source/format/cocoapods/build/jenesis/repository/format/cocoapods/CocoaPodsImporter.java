package build.jenesis.repository.format.cocoapods;

import module java.base;
import build.jenesis.repository.blobs.ReplayExchange;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;

/**
 * Imports a CocoaPods registry (Nexus/Artifactory {@code cocoapods}) from an incumbent manager. The migrated assets are
 * the pod zip archives; the root {@code CocoaPods-version.yml}, the sharded {@code all_pods_versions_<a>_<b>_<c>.txt}
 * version listings and each version's {@code Specs/.../<name>.podspec.json} a client reads are derived, regenerated
 * by {@link CocoaPodsFormat} from each pod's precomputed stanza, so they are not imported (an asset that is not a
 * {@code .zip} - a {@code .txt} listing, a {@code .podspec.json} or the {@code CocoaPods-version.yml} - is skipped). A
 * pod's coordinate is its {@code <name>} and {@code version}, taken from the source path's trailing
 * {@code .../<name>/<version>/<file>.zip} segments - the exact shape this format serves an archive at
 * ({@code pods/<name>/<version>/<name>.zip}), so a re-import of an exported CocoaPods repository round-trips; a
 * CocoaPods version string never contains a {@code /}, so the version directory is unambiguous.
 *
 * <p>Each archive is replayed as a push through {@link CocoaPodsFormat#handle} - the same raw-body
 * {@code PUT /cocoapods/<repo>/<name>/<version>} the format serves - so the archive <b>streams</b> straight into the
 * content-addressed store (the format materialises only the small {@code .podspec.json} it reads back from the
 * just-stored blob, never buffering the payload), exactly as the Composer, Conda and RPM importers stream their
 * archives and unlike the buffered {@code .gem}/{@code .nupkg}/{@code .deb} language importers; an arbitrarily large pod
 * never lands in heap. The format validates that the archive's embedded {@code .podspec.json} {@code name}/{@code
 * version} match the deploy path, so a source asset whose path does not encode its true coordinate is refused rather
 * than mis-filed (the way the Cargo importer skips an unparseable crate filename). All pods migrate to a single
 * {@code /cocoapods/cocoapods/...} registry (the flat migration the {@code /rpm/rpm/...}, {@code /cargo/cargo/...},
 * {@code /conda/conda/...} and {@code /composer/composer/...} importers use) whose CDN metadata is derived.
 *
 * <p>Because the format reads the {@code .podspec.json} back from the stored archive, the pod's declared
 * {@code dependencies}/{@code license} carry into the regenerated podspec, so a migrated pod resolves with its
 * dependencies - unlike the Cargo importer, whose edges live in an unparseable embedded {@code Cargo.toml}. SPI-only -
 * the importer reuses the format's own publish path rather than reimplementing it. One of the language
 * importers, delegated to by {@link CocoaPodsFormat}, which carries the same {@code RepositoryImporter} capability the
 * built-in importers use.
 */
public final class CocoaPodsImporter implements RepositoryImporter {

    /** The single registry migrated pods land in, so the on-read metadata and download paths sit under one repo. */
    private static final String REPO = "cocoapods";

    private static final String ZIP = ".zip";

    @Override
    public boolean imports(String format) {
        return format.equals("cocoapods");
    }

    @Override
    public Optional<ArtifactDescriptor> importTarget(String path) {
        // RepositoryImporter clause 4: a source path is as client-supplied as a request path, so a
        // traversal-shaped one is refused by name rather than echoed into the descriptor the import edge
        // screens and the trail records (the fix retrofitted here - §13).
        String relative = RepositoryImporter.importablePath(path, "cocoapods");
        if (!relative.toLowerCase(Locale.ROOT).endsWith(ZIP)) {
            return Optional.empty();
        }
        // The pod's target coordinate under /cocoapods/cocoapods/pods/<name>/<version>/<file>.zip, the path
        // CocoaPodsFormat serves and describes, so the edge screens the real CocoaPods coordinate. The <name> and
        // <version> are taken from the source path's TRAILING <name>/<version>/<file>.zip segments - the exact ones
        // importArtifact keys the storage on - and the canonical served pods/ path is rebuilt from them, NOT by
        // splicing the whole raw source path in behind the registry. A live incumbent lays a pod at a bare
        // <name>/<version>/<file>.zip (no pods/ segment) or a deeper asset path; passing that straight to describe
        // (which recognises only a pods/-rooted download) screened the real pod coordinate-less/empty, degrading it to
        // a raw blob on a later hold release even though importArtifact filed it correctly from the same trailing
        // segments. Empty for a path with no trailing <name>/<version>/<file>.zip shape, exactly the assets
        // importArtifact skips.
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
        // RepositoryImporter clause 4: a source path is as client-supplied as a request path, so a
        // traversal-shaped one is refused by name rather than echoed into the descriptor the import edge
        // screens and the trail records (the fix retrofitted here - §13).
        String relative = RepositoryImporter.importablePath(path, "cocoapods");
        if (!relative.toLowerCase(Locale.ROOT).endsWith(ZIP)) {
            // Only the pod zip archives are migrated; a CocoaPods-version.yml, an all_pods_versions_*.txt listing or a
            // Specs/.../*.podspec.json asset is derived metadata the format regenerates, not imported.
            return;
        }
        String[] segments = relative.split("/");
        if (segments.length < 3) {
            // Without a trailing <name>/<version>/<file>.zip the coordinate is not discoverable from the path, so the
            // asset is skipped rather than filed under a guessed coordinate (the way the Cargo/Composer importers skip
            // an asset they cannot key).
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
