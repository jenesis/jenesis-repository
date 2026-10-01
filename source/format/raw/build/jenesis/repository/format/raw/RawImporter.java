package build.jenesis.repository.format.raw;

import module java.base;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.store.ArtifactStore;

/**
 * Imports a generic repository (Nexus {@code raw}, Artifactory {@code generic}): a raw asset has no ecosystem layout,
 * so its path is kept verbatim under {@code /raw/...} and its bytes stored content-addressed, exactly as a {@code PUT}
 * to {@link RawFormat} would.
 */
public final class RawImporter implements RepositoryImporter {

    @Override
    public boolean imports(String sourceFormat) {
        return sourceFormat.equals("raw") || sourceFormat.equals("generic");
    }

    @Override
    public Optional<ArtifactDescriptor> importTarget(String sourcePath) {
        // RepositoryImporter clause 4: the descriptor composed here is what the import edge screens, records and
        // diverts on, so "/../x" is refused by name rather than echoed as "/raw/../x". Empty would mean "lay this out
        // unscreened".
        String relative = RepositoryImporter.importablePath(sourcePath, "raw");
        // A raw asset has no coordinate; the /raw/ path it will serve from is its screen identity.
        return Optional.of(ArtifactDescriptor.at("raw", "/raw/" + relative));
    }

    @Override
    public void importArtifact(String path, InputStream content, ArtifactStore store) throws IOException {
        String relative = RepositoryImporter.importablePath(path, "raw");
        Publication publication = new Publication(store);
        // Layout only: the import walk has screened the asset, so this stores it content-addressed and links its /raw/
        // path.
        Publication.Blob blob = publication.stored(content);
        publication.link("/raw/" + relative, blob.hash(), blob.size());
    }
}
