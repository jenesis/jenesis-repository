package build.jenesis.repository.format.gems;

import module java.base;
import build.jenesis.repository.blobs.ReplayExchange;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.OwnerOnly;

/**
 * Imports a RubyGems repository (Nexus {@code rubygems}) from an incumbent manager. A {@code .gem} is
 * self-describing - its gzipped YAML gemspec carries the name, version and dependencies - so the importer replays
 * it as a {@code gem push} through {@link RubyGemsFormat#handle}, which stores the gem and the precomputed compact
 * index line; each push rewrites the stored {@code /info} and {@code /versions} documents. The other compact-index
 * assets of the source repository are derived and skipped. One of the language importers, discovered
 * through the same {@code RepositoryImporter} SPI the built-in importers use.
 */
public final class RubyGemsImporter implements RepositoryImporter {

    @Override
    public boolean imports(String format) {
        return format.equals("rubygems") || format.equals("gems");
    }

    @Override
    public Optional<ArtifactDescriptor> importTarget(String path) {
        // RepositoryImporter clause 4: a source path is as client-supplied as a request path, so a
        // traversal-shaped one is refused by name rather than echoed into the descriptor the import edge
        // screens and the trail records (the fix retrofitted here - §13).
        String relative = RepositoryImporter.importablePath(path, "rubygems");
        String file = relative.substring(relative.lastIndexOf('/') + 1);
        // The gem's target coordinate under /rubygems/gems/<file>, so the edge screens the real RubyGems coordinate the
        // format parses from the gem filename. Empty for a non-.gem source asset (the derived compact-index files),
        // which the walk lays out unscreened and importArtifact then skips.
        return new RubyGemsFormat().describe("/rubygems/gems/" + file);
    }

    @Override
    public void importArtifact(String path, InputStream content, ArtifactStore store) throws IOException {
        // RepositoryImporter clause 4: a source path is as client-supplied as a request path, so a
        // traversal-shaped one is refused by name rather than echoed into the descriptor the import edge
        // screens and the trail records (the fix retrofitted here - §13).
        String relative = RepositoryImporter.importablePath(path, "rubygems");
        if (!relative.endsWith(".gem")) {
            return;
        }
        // The coordinate the import edge screened this asset under is derived from the .gem FILENAME (importTarget); the
        // name RubyGemsFormat stores/serves it under is read from the embedded gemspec. They MUST agree - otherwise a
        // gem screened under one name (e.g. "innocent") would be stored and served under the gemspec's own name (e.g.
        // "evil-payload"), a screen-label bypass, the way Composer/CocoaPods refuse a manifest that disagrees with the
        // deploy path. The push endpoint carries no path coordinate (gem push is coordinate-less), so the check lives
        // here, in the importer.
        Optional<ArtifactDescriptor> screened = importTarget(path);
        if (screened.isEmpty() || screened.get().coordinate() == null) {
            // A filename with no version-looking suffix is not screened under a coordinate (describe returns the
            // coordinate-less variant): replay unchanged - the walk lays such an asset out unscreened.
            new RubyGemsFormat().handle(ReplayExchange.post("/rubygems/api/v1/gems", content), store);
            return;
        }
        // Spool the (unbounded) .gem to a temp file so its gemspec front can be read once and the body replayed once,
        // without buffering it on the heap or writing a rejected gem into the store.
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
                // The gemspec name disagrees with the name the edge screened from the filename: refuse rather than
                // store it under a name the gate never saw (screen-label bypass).
                return;
            }
            try (InputStream in = Files.newInputStream(spool)) {
                new RubyGemsFormat().handle(ReplayExchange.post("/rubygems/api/v1/gems", in), store);
            }
        } finally {
            Files.deleteIfExists(spool);
        }
    }

    /** The owner-only import spool ({@link OwnerOnly}): the buffered {@code .gem} is the plaintext artifact body,
     *  and must not sit world-readable in the shared temp directory for the import's life. The {@code newOutputStream}
     *  write opens it in place (truncate), preserving the mode. */
    private static Path ownerOnlyTemp(String prefix, String suffix) throws IOException {
        return OwnerOnly.createTempFile(prefix, suffix);
    }

}
