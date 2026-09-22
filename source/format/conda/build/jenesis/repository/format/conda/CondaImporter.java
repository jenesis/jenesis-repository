package build.jenesis.repository.format.conda;

import module java.base;
import build.jenesis.repository.blobs.ReplayExchange;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;

/**
 * Imports a Conda channel (Nexus/Artifactory {@code conda}) from an incumbent manager. The migrated assets are the
 * {@code .conda} / {@code .tar.bz2} packages; the {@code repodata.json} a client reads is derived metadata,
 * maintained by {@link CondaFormat} from each package's precomputed record, so it is not imported. A conda
 * channel organises packages by platform {@code subdir} ({@code linux-64}, {@code noarch}, {@code osx-arm64}, ...) as
 * {@code <subdir>/<file>}, so the source path's parent directory is the subdir and its last segment the filename - the
 * package migrates to {@code /conda/conda/<subdir>/<file>}, keeping the subdir so the stored {@code repodata.json} the
 * client fetches under that subdir names it (a package with no parent directory carries no discoverable subdir and is
 * skipped rather than mis-filed, the way the Cargo importer skips an unparseable crate filename).
 *
 * <p>Each package is replayed as a push through {@link CondaFormat#handle} - the same plain-body
 * {@code PUT /conda/<repo>/<subdir>/<file>} the format serves - so the archive <b>streams</b> straight into the
 * content-addressed store (the format materialises only the small {@code info/index.json} it reads back from the
 * just-stored blob, never buffering the payload), exactly as the RPM importer streams a {@code .rpm} and unlike the
 * buffered {@code .gem}/{@code .nupkg}/{@code .deb} language importers; an arbitrarily large package never lands in
 * heap. All packages migrate to a single {@code /conda/conda/...} channel (the yum-style flat migration
 * {@code /rpm/rpm/...} uses). SPI-only - the importer reuses the format's own publish path rather than reimplementing
 * it. One of the format importers, delegated to by {@link CondaFormat}, which carries the same
 * {@code RepositoryImporter} capability the built-in importers use.
 */
public final class CondaImporter implements RepositoryImporter {

    /** The single channel migrated packages land in, so the stored {@code repodata.json} sits under one repo. */
    private static final String REPO = "conda";

    @Override
    public boolean imports(String format) {
        return format.equals("conda");
    }

    @Override
    public Optional<ArtifactDescriptor> importTarget(String path) {
        // RepositoryImporter clause 4: a source path is as client-supplied as a request path, so a
        // traversal-shaped one is refused by name rather than echoed into the descriptor the import edge
        // screens and the trail records (the fix retrofitted here - §13).
        String relative = RepositoryImporter.importablePath(path, "conda");

        // The package's target coordinate under /conda/conda/<subdir>/<file>, the same channel path importArtifact
        // lays it out at, so the edge screens the real Conda coordinate CondaFormat parses from the filename. Empty
        // for a non-package or subdir-less path the format does not model, which the walk lays out unscreened.
        return new CondaFormat().describe("/conda/" + REPO + "/" + relative);
    }

    @Override
    public void importArtifact(String path, InputStream content, ArtifactStore store) throws IOException {
        // RepositoryImporter clause 4: a source path is as client-supplied as a request path, so a
        // traversal-shaped one is refused by name rather than echoed into the descriptor the import edge
        // screens and the trail records (the fix retrofitted here - §13).
        String relative = RepositoryImporter.importablePath(path, "conda");
        String lower = relative.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".conda") && !lower.endsWith(".tar.bz2")) {
            return;
        }
        int slash = relative.lastIndexOf('/');
        if (slash <= 0) {
            // No <subdir>/<file> layout: the package's platform is not discoverable from the path, so it is skipped
            // rather than filed under a guessed subdir (which would hide it from the repodata the client fetches).
            return;
        }
        String file = relative.substring(slash + 1);
        String subdir = relative.substring(relative.lastIndexOf('/', slash - 1) + 1, slash);
        new CondaFormat().handle(new ReplayExchange("/conda/" + REPO + "/" + subdir + "/" + file, content), store);
    }

}
