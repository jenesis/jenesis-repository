package build.jenesis.repository.format.huggingface;

import module java.base;
import build.jenesis.repository.blobs.ReplayExchange;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;

/**
 * Imports a Hugging Face registry (Nexus/Artifactory {@code huggingfaceml}) from an incumbent manager. Hugging Face is a
 * streaming, revision-addressed file store - a repository file is uploaded to a git revision, its {@code {repo_id,
 * revision}} coordinate carried in the {@code resolve} request path and never inside the bytes - so the migrated assets
 * are those revision files themselves, at the exact resolve path {@link HuggingFaceFormat} serves them from
 * ({@code [datasets/|spaces/]<repo_id>/resolve/<revision>/<path>}). An asset whose source path is not a resolve/download
 * path - a derived {@code api/...} repo-info / file-tree index, or any path with no {@code /resolve/} segment -
 * is skipped, since it carries no revision file to migrate.
 *
 * <p>The revision is the client's own (a branch like {@code main} or an immutable commit sha), so it must be preserved
 * verbatim: this importer takes the whole resolve path - the (optionally {@code datasets/}/{@code spaces/}-prefixed)
 * {@code repo_id}, the revision and the (possibly nested) file path - and replays it unchanged as a
 * {@code PUT /huggingface/<repo>/<path>} through {@link HuggingFaceFormat#handle}, so the file <b>streams</b> straight
 * into the content-addressed store (the format never opens an archive - the coordinate is in the path) and an
 * arbitrarily large {@code model.safetensors} never lands in heap, exactly as the RPM, Cargo, Conda, Composer, CocoaPods
 * and Conan importers stream their assets and unlike the buffered {@code .gem}/{@code .nupkg}/{@code .deb} language
 * importers. Because the resolve path (with its revision) round-trips unchanged, a re-import resolves identically - the
 * client-uploaded revision the derived {@code api/...} index enumerates from is the same one it was uploaded
 * to. All files migrate to a single {@code /huggingface/huggingface/...} registry (the flat migration the
 * {@code /rpm/rpm/...}, {@code /cargo/cargo/...}, {@code /conda/conda/...}, {@code /composer/composer/...},
 * {@code /cocoapods/cocoapods/...} and {@code /conan/conan/...} importers use) whose repo-info / tree index the
 * replayed uploads maintain.
 *
 * <p>SPI-only - the importer reuses the format's own publish path rather than reimplementing it, and depends only on the
 * {@code RepositoryImporter} SPI, exactly as the built-in importers do. Hugging Face declares a repository's license
 * only in a model card's YAML front matter / {@code config.json}, which each individually-fetched file does not carry
 * (the reason the sibling {@code compliance/huggingface} inspector reports no license), so - like the Cargo and Conan
 * importers' unreadable manifests - no license is reconstructed here; the pull-through proxy is the metadata-faithful
 * route.
 */
public final class HuggingFaceImporter implements RepositoryImporter {

    /** The single registry migrated files land in, so the on-read repo-info index and resolve paths sit under one repo
     *  (the flat-migration convention the RPM/Cargo/Conda/Composer/CocoaPods/Conan importers share). */
    private static final String REPO = "huggingface";

    /** The resolve marker that roots every revision file, the same segment {@link HuggingFaceFormat} routes and
     *  {@code describe}s on. Everything before it is the (optionally type-prefixed) {@code repo_id}; everything after is
     *  the {@code <revision>/<path>}. */
    private static final String RESOLVE = "/resolve/";

    @Override
    public boolean imports(String format) {
        // Artifactory names the type "huggingfaceml"; accept the bare "huggingface" too, mirroring the yum/rpm and
        // cargo/crates aliasing the other importers use.
        return format.equals("huggingfaceml") || format.equals("huggingface");
    }

    @Override
    public Optional<ArtifactDescriptor> importTarget(String path) {
        // A Nexus 3.71+ (H2/PostgreSQL datastore) listing reports the asset path absolute, with a leading slash; strip
        // the single leading slash to the repository-relative shape first (the sibling Conda/Go importers do the same),
        // so the served path is /huggingface/huggingface/<repo_id>/... and not a coordinate-breaking double slash.
        // RepositoryImporter clause 4: a source path is as client-supplied as a request path, so a
        // traversal-shaped one is refused by name rather than echoed into the descriptor the import edge
        // screens and the trail records (the fix retrofitted here - §13).
        String relative = RepositoryImporter.importablePath(path, "huggingface");
        if (relative.indexOf(RESOLVE) < 0) {
            return Optional.empty();
        }
        // The resolve file's target coordinate under /huggingface/huggingface/<repo_id>/resolve/<revision>/<path>, the
        // same path importArtifact lays it out at, so the edge screens the real Hugging Face coordinate the format
        // parses. Empty for a non-resolve asset (a derived api/... index), which the walk lays out unscreened.
        return new HuggingFaceFormat().describe("/huggingface/" + REPO + "/" + relative);
    }

    @Override
    public void importArtifact(String path, InputStream content, ArtifactStore store) throws IOException {
        // RepositoryImporter clause 4: a source path is as client-supplied as a request path, so a
        // traversal-shaped one is refused by name rather than echoed into the descriptor the import edge
        // screens and the trail records (the fix retrofitted here - §13).
        String relative = RepositoryImporter.importablePath(path, "huggingface");
        int marker = relative.indexOf(RESOLVE);
        if (marker < 0) {
            // No /resolve/ segment: not a revision file (a generated-on-read api/... index, or an unmodelled asset).
            return;
        }
        String repoId = relative.substring(0, marker);
        String tail = relative.substring(marker + RESOLVE.length());
        // The revision and at least one file path segment must follow the marker for a resolve download path; and the
        // repo_id must be non-empty. Anything else is not a file this format serves.
        if (repoId.isEmpty() || tail.indexOf('/') <= 0) {
            return;
        }
        // Replay the resolve path (its client-uploaded revision preserved) unchanged, so the file streams into the CAS
        // at the same key it is served from; the format traverse-guards each segment, so a malformed segment is
        // refused there rather than steering a write.
        new HuggingFaceFormat().handle(new ReplayExchange("/huggingface/" + REPO + "/" + relative, content), store);
    }

}
