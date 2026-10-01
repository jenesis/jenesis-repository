package build.jenesis.repository.format.huggingface;

import module java.base;
import build.jenesis.repository.blobs.ReplayExchange;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;

/**
 * Imports a Hugging Face registry from an incumbent manager. The coordinate is in the resolve path, never the bytes, so
 * the migrated assets are the revision files at the path {@link HuggingFaceFormat} serves them from
 * ({@code [datasets/|spaces/]<repo_id>/resolve/<revision>/<path>}); an asset with no {@code /resolve/} segment, such as
 * a derived {@code api/...} index, is skipped.
 *
 * <p>The path is replayed unchanged, its revision preserved, as a {@code PUT /huggingface/<repo>/<path>} through
 * {@link HuggingFaceFormat#handle}, so the file streams into the content-addressed store. All files land in one
 * {@code /huggingface/huggingface/...} registry whose index the replayed uploads maintain. No licence is reconstructed:
 * a repository declares it in a model card no single file carries.
 */
public final class HuggingFaceImporter implements RepositoryImporter {

    /** The single registry migrated files land in. */
    private static final String REPO = "huggingface";

    /** The segment rooting every revision file: before it the (optionally type-prefixed) {@code repo_id}, after it
     *  {@code <revision>/<path>}. */
    private static final String RESOLVE = "/resolve/";

    @Override
    public boolean imports(String format) {
        // Artifactory names the type "huggingfaceml"; the bare name is accepted too.
        return format.equals("huggingfaceml") || format.equals("huggingface");
    }

    @Override
    public Optional<ArtifactDescriptor> importTarget(String path) {
        // RepositoryImporter clause 4: a traversal-shaped source path is refused by name, and a leading slash, as newer
        // Nexus listings report, is stripped.
        String relative = RepositoryImporter.importablePath(path, "huggingface");
        if (relative.indexOf(RESOLVE) < 0) {
            return Optional.empty();
        }
        // The coordinate under the path importArtifact lays the file at, so the edge screens it; empty for an api/...
        // index.
        return new HuggingFaceFormat().describe("/huggingface/" + REPO + "/" + relative);
    }

    @Override
    public void importArtifact(String path, InputStream content, ArtifactStore store) throws IOException {
        // RepositoryImporter clause 4: a traversal-shaped source path is refused by name.
        String relative = RepositoryImporter.importablePath(path, "huggingface");
        int marker = relative.indexOf(RESOLVE);
        if (marker < 0) {
            // No /resolve/ segment: not a revision file.
            return;
        }
        String repoId = relative.substring(0, marker);
        String tail = relative.substring(marker + RESOLVE.length());
        // A revision and at least one file segment must follow the marker, and the repo_id must be non-empty.
        if (repoId.isEmpty() || tail.indexOf('/') <= 0) {
            return;
        }
        // Replayed unchanged, so the file streams in at the key it is served from; the format guards each segment.
        new HuggingFaceFormat().handle(new ReplayExchange("/huggingface/" + REPO + "/" + relative, content), store);
    }

}
