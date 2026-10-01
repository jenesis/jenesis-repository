package build.jenesis.repository.format.gems;

import module java.base;
import build.jenesis.repository.blobs.ReplayExchange;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.OwnerOnly;

/**
 * Imports a RubyGems repository from an incumbent manager. A {@code .gem} is self-describing, so it is replayed as a
 * {@code gem push} through {@link RubyGemsFormat#handle}; the source's compact-index assets are derived and skipped.
 */
public final class RubyGemsImporter implements RepositoryImporter {

    @Override
    public boolean imports(String format) {
        return format.equals("rubygems") || format.equals("gems");
    }

    @Override
    public Optional<ArtifactDescriptor> importTarget(String path) {
        // RepositoryImporter clause 4: a traversal-shaped source path is refused by name.
        String relative = RepositoryImporter.importablePath(path, "rubygems");
        String file = relative.substring(relative.lastIndexOf('/') + 1);
        // The gem's coordinate from its filename, so the edge screens it; empty for the derived compact-index files.
        return new RubyGemsFormat().describe("/rubygems/gems/" + file);
    }

    @Override
    public void importArtifact(String path, InputStream content, ArtifactStore store) throws IOException {
        // RepositoryImporter clause 4: a traversal-shaped source path is refused by name.
        String relative = RepositoryImporter.importablePath(path, "rubygems");
        if (!relative.endsWith(".gem")) {
            return;
        }
        // The edge screened the name from the filename and the store keys the gemspec's; they must agree, or a gem
        // screened as one name would serve as another. The push endpoint names no coordinate, so the check is here.
        Optional<ArtifactDescriptor> screened = importTarget(path);
        if (screened.isEmpty() || screened.get().coordinate() == null) {
            // A filename with no version-looking suffix was screened coordinate-less: replayed unchanged.
            new RubyGemsFormat().handle(ReplayExchange.post("/rubygems/api/v1/gems", content), store);
            return;
        }
        // Spooled to a temp file, so the gemspec is read once and the body replayed once without heap buffering or
        // storing a rejected gem.
        Path spool = ownerOnlyTemp("gems-import-", ".gem");
        try {
            try (OutputStream out = Files.newOutputStream(spool)) {
                content.transferTo(out);
            }
            RubyGemsFormat.Spec spec;
            try (InputStream in = Files.newInputStream(spool)) {
                spec = RubyGemsFormat.parse(RubyGemsFormat.gemspec(in));
            }
            if (spec == null || !spec.name().equals(screened.get().coordinate())) {
                // The gemspec name disagrees with the screened name: refused.
                return;
            }
            try (InputStream in = Files.newInputStream(spool)) {
                new RubyGemsFormat().handle(ReplayExchange.post("/rubygems/api/v1/gems", in), store);
            }
        } finally {
            Files.deleteIfExists(spool);
        }
    }

    /** The owner-only import spool ({@link OwnerOnly}): the buffered {@code .gem} must not sit world-readable in the
     *  shared temp directory. The write truncates in place, keeping the mode. */
    private static Path ownerOnlyTemp(String prefix, String suffix) throws IOException {
        return OwnerOnly.createTempFile(prefix, suffix);
    }

}
