package build.jenesis.repository.format.winget;

import module java.base;
import build.jenesis.repository.blobs.ReplayExchange;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;

/**
 * Imports a winget REST source from an incumbent manager, replaying each asset through {@link WingetFormat}'s own
 * publish paths so an exported repository round-trips.
 *
 * <p>Two kinds of asset carry, because a winget package is two things: the version manifest, which is stored, and the
 * installer bytes, which are streamed into the content-addressed store. Everything a client reads is derived - the
 * source {@code information} document, the search index and the assembled {@code packageManifests} response - and so
 * is regenerated rather than imported. A source path is mapped by its trailing
 * {@code .../manifests/<id>/<version>} or {@code .../installers/<id>/<version>/<file>} segments, which are the exact
 * shapes this format publishes and serves at; a winget {@code PackageIdentifier} and version contain no {@code /}, so
 * the segmentation is unambiguous.
 *
 * <p>All packages migrate into a single {@code /winget/winget/...} registry, the flat migration the RPM, Cargo, Conda
 * and Composer importers use, whose read documents are then derived by the format. SPI-only: the importer reuses the
 * format's own publish path rather than reimplementing it, so an imported manifest is validated against its
 * coordinate exactly as a published one is.
 */
public final class WingetImporter implements RepositoryImporter {

    /** The single registry migrated packages land in, so the derived documents sit under one repo. */
    private static final String REPO = "winget";

    private static final String MANIFESTS = "manifests";
    private static final String INSTALLERS = "installers";

    public WingetImporter() {
    }

    @Override
    public boolean imports(String format) {
        return format.equals("winget");
    }

    @Override
    public Optional<ArtifactDescriptor> importTarget(String path) {
        // RepositoryImporter clause 4: a source path is as client-supplied as a request path, so a traversal-shaped
        // one is refused by name rather than echoed into the descriptor the import edge screens.
        String relative = RepositoryImporter.importablePath(path, REPO);
        // Split on segments rather than searching for "/manifests/": an incumbent lays its assets at a bare
        // manifests/... or installers/... with no leading slash, and matching the delimited form silently skipped
        // exactly those - the shape the importer census feeds it.
        String[] segments = relative.split("/", -1);
        for (int at = 0; at < segments.length; at++) {
            if (segments[at].equals(MANIFESTS) && segments.length - at == 3) {
                return descriptor(segments[at + 1], segments[at + 2], null);
            }
            if (segments[at].equals(INSTALLERS) && segments.length - at == 4) {
                return descriptor(segments[at + 1], segments[at + 2], segments[at + 3]);
            }
        }
        return Optional.empty();
    }

    /** The target descriptor for one asset: a manifest when {@code file} is null, an installer when it is not. */
    private static Optional<ArtifactDescriptor> descriptor(String identifier, String version, String file) {
        if (identifier.isEmpty() || version.isEmpty() || (file != null && file.isEmpty())) {
            return Optional.empty();
        }
        boolean prerelease = version.indexOf('-') >= 0;
        return Optional.of(file == null
                ? new ArtifactDescriptor(WingetFormat.ECOSYSTEM, identifier, version,
                        "/winget/" + REPO + "/manifests/" + identifier + "/" + version,
                        "application/json", prerelease, null, -1L)
                : new ArtifactDescriptor(WingetFormat.ECOSYSTEM, identifier, version,
                        "/winget/" + REPO + "/installers/" + identifier + "/" + version + "/" + file,
                        "application/octet-stream", prerelease, null, -1L));
    }

    @Override
    public void importArtifact(String path, InputStream content, ArtifactStore store) throws IOException {
        // Replayed through the format's own publish routes, so an imported manifest is validated against its
        // coordinate exactly as a published one is and an imported installer streams into the store the same way.
        Optional<ArtifactDescriptor> target = importTarget(path);
        if (target.isEmpty()) {
            // Everything a client reads - the information document, the search index, an assembled
            // packageManifests answer - is derived and regenerated rather than imported.
            return;
        }
        new WingetFormat().handle(new ReplayExchange(target.get().path(), content), store);
    }
}
