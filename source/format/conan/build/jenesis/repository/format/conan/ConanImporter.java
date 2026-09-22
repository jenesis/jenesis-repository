package build.jenesis.repository.format.conan;

import module java.base;
import build.jenesis.repository.blobs.ReplayExchange;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;

/**
 * Imports a Conan (C/C++) registry (Nexus/Artifactory {@code conan}) from an incumbent manager. Conan is a streaming,
 * revision-addressed file store - a recipe or package is a set of files uploaded to a client-computed revision, the
 * coordinate carried in the request path and never inside the bytes - so the migrated assets are those revision files
 * themselves, at the exact v2 REST path {@link ConanFormat} serves them from
 * ({@code v2/conans/<name>/<version>/<user>/<channel>/revisions/<rrev>/files/<file>}, and its package equivalent under
 * {@code .../packages/<package_id>/revisions/<prev>/files/<file>}). An asset whose source path is not one of those two
 * file-download shapes - a {@code latest} / {@code revisions} / {@code files} index the format maintains itself, a
 * {@code ping} / authenticate response, or any path with no {@code v2/conans/} marker - is skipped, since it carries no
 * revision file to migrate.
 *
 * <p>The revision hash is content-derived (a hash of the exported sources / the built binary the client computed), so it
 * must be preserved verbatim: this importer takes the whole reference tail after {@code v2/conans/} - name, version,
 * user, channel, revision, and, for a package file, the package id and package revision - and replays it unchanged as a
 * {@code PUT /conan/<repo>/v2/conans/<tail>} through {@link ConanFormat#handle}, so the file <b>streams</b> straight
 * into the content-addressed store (the format never opens an archive - the coordinate is in the path) and an
 * arbitrarily large {@code conan_package.tgz} never lands in heap, exactly as the RPM, Cargo, Conda, Composer and
 * CocoaPods importers stream their archives and unlike the buffered {@code .gem}/{@code .nupkg}/{@code .deb} language
 * importers. Because the reference (with its revisions) round-trips unchanged, a re-import of an exported Conan
 * repository resolves identically - the client-computed recipe and package revisions the {@code latest}/{@code
 * revisions} index is generated from are the same ones it was uploaded to. All packages migrate to a single
 * {@code /conan/conan/...} registry (the flat migration the {@code /rpm/rpm/...}, {@code /cargo/cargo/...},
 * {@code /conda/conda/...}, {@code /composer/composer/...} and {@code /cocoapods/cocoapods/...} importers use) whose
 * revision index the replayed uploads maintain.
 *
 * <p>SPI-only - the importer reuses the format's own publish path rather than reimplementing it, and depends only on the
 * {@code RepositoryImporter} SPI, exactly as the built-in importers do. Conan declares a package's license only in its
 * {@code conanfile.py} Python recipe, for which no modular, permissive parser is on the path (the reason the sibling
 * {@code compliance/conan} inspector reports no license), so - like the Cargo importer's unparseable {@code Cargo.toml} -
 * no license is reconstructed here; the pull-through proxy is the license-and-dependency-faithful route.
 */
public final class ConanImporter implements RepositoryImporter {

    /** The single registry migrated packages land in, so the stored revision index and download paths sit under one
     *  repo (the flat-migration convention the RPM/Cargo/Conda/Composer/CocoaPods importers share). */
    private static final String REPO = "conan";

    /** The Conan v2 REST marker that roots every revision file, the same constant {@link ConanFormat} routes on. The
     *  reference tail (coordinate + revision + file) is everything after it. */
    private static final String MARKER = "v2/conans/";

    @Override
    public boolean imports(String format) {
        return format.equals("conan");
    }

    @Override
    public Optional<ArtifactDescriptor> importTarget(String path) {
        // RepositoryImporter clause 4: a source path is as client-supplied as a request path, so a
        // traversal-shaped one is refused by name rather than echoed into the descriptor the import edge
        // screens and the trail records (the fix retrofitted here - §13).
        String relative = RepositoryImporter.importablePath(path, "conan");
        int marker = relative.indexOf(MARKER);
        if (marker < 0) {
            return Optional.empty();
        }
        // The revision file's target coordinate under /conan/conan/v2/conans/<tail>, the same v2 REST path
        // importArtifact lays it out at, so the edge screens the real Conan coordinate ConanFormat parses. Empty for a
        // path that is not a recipe/package file download (a derived index), which the walk lays out
        // unscreened and importArtifact then skips.
        return new ConanFormat().describe(
                "/conan/" + REPO + "/" + MARKER + relative.substring(marker + MARKER.length()));
    }

    @Override
    public void importArtifact(String path, InputStream content, ArtifactStore store) throws IOException {
        // RepositoryImporter clause 4: a source path is as client-supplied as a request path, so a
        // traversal-shaped one is refused by name rather than echoed into the descriptor the import edge
        // screens and the trail records (the fix retrofitted here - §13).
        String relative = RepositoryImporter.importablePath(path, "conan");
        int marker = relative.indexOf(MARKER);
        if (marker < 0) {
            // No v2/conans/ root: not a Conan revision file (a stray asset, or a layout this importer does not model).
            return;
        }
        String tail = relative.substring(marker + MARKER.length());
        String[] t = tail.split("/", -1);
        // The exact two file-download shapes the format serves (and describe/fileRef recognise): a recipe file
        // .../<name>/<version>/<user>/<channel>/revisions/<rrev>/files/<file> (8 segments) and a package file
        // .../packages/<package_id>/revisions/<prev>/files/<file> (12). Anything else is a derived index.
        boolean recipeFile = t.length == 8 && t[4].equals("revisions") && t[6].equals("files");
        boolean packageFile = t.length == 12 && t[4].equals("revisions") && t[6].equals("packages")
                && t[8].equals("revisions") && t[10].equals("files");
        if (!recipeFile && !packageFile) {
            return;
        }
        // Replay the reference (with its client-computed revisions) unchanged, so the file streams into the CAS at the
        // same key it is served from; the format traversal-guards each segment, so a malformed segment is refused there.
        new ConanFormat().handle(new ReplayExchange("/conan/" + REPO + "/" + MARKER + tail, content), store);
    }

}
