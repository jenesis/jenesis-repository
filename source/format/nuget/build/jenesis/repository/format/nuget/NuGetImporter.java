package build.jenesis.repository.format.nuget;

import module java.base;
import build.jenesis.repository.blobs.ReplayExchange;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.OwnerOnly;

/**
 * Imports a NuGet repository (Nexus {@code nuget}) from an incumbent manager. A {@code .nupkg} is self-describing -
 * its embedded {@code .nuspec} carries the id and version - so the importer replays it as a {@code nuget push}
 * through {@link NuGetFormat#handle} (whose push accepts a raw package body, not only twine-style multipart), which
 * stores it under {@code nuget/<id>/<version>/...}; the replayed push rewrites the stored flat-container version
 * index itself. One of the language importers, discovered through the same {@code RepositoryImporter} SPI
 * the built-in importers use.
 */
public final class NuGetImporter implements RepositoryImporter {

    @Override
    public boolean imports(String format) {
        return format.equals("nuget");
    }

    @Override
    public Optional<ArtifactDescriptor> importTarget(String path) {
        // RepositoryImporter clause 4: a source path is as client-supplied as a request path, so a
        // traversal-shaped one is refused by name rather than echoed into the descriptor the import edge
        // screens and the trail records (the fix retrofitted here - §13).
        String relative = RepositoryImporter.importablePath(path, "nuget");
        if (!relative.toLowerCase(Locale.ROOT).endsWith(".nupkg")) {
            return Optional.empty();
        }
        // The id/version are taken from the source path's TRAILING <id>/<version>/<file>.nupkg segments - the flat-
        // container shape NuGetFormat serves and importArtifact's embedded .nuspec keys the store on - NOT by splicing
        // the whole raw source path behind v3-flatcontainer/. A deep incumbent path (a Nexus channel/store prefix)
        // otherwise made describe read id/version from the FIRST two segments and screen under the wrong coordinate
        // (e.g. "channel"/"Newtonsoft.Json" instead of "newtonsoft.json"/"13.0.1"), degrading the screen. The trailing
        // segments name the true coordinate, robust at depth. Empty for a path with no trailing <id>/<version>/<file>
        // shape (a flat single-file .nupkg), left to importArtifact's .nuspec replay - exactly as before.
        String[] segments = relative.split("/");
        if (segments.length < 3) {
            return Optional.empty();
        }
        String file = segments[segments.length - 1];
        String version = segments[segments.length - 2];
        String id = segments[segments.length - 3];
        return new NuGetFormat().describe("/nuget/v3-flatcontainer/" + id + "/" + version + "/" + file);
    }

    @Override
    public void importArtifact(String path, InputStream content, ArtifactStore store) throws IOException {
        // RepositoryImporter clause 4: a source path is as client-supplied as a request path, so a
        // traversal-shaped one is refused by name rather than echoed into the descriptor the import edge
        // screens and the trail records (the fix retrofitted here - §13).
        String relative = RepositoryImporter.importablePath(path, "nuget");
        if (!relative.toLowerCase(Locale.ROOT).endsWith(".nupkg")) {
            return;
        }
        // The coordinate the import edge screened this asset under is derived from its source PATH (importTarget); the
        // id NuGetFormat stores/serves it under is read from the embedded .nuspec. They MUST agree - otherwise a
        // .nupkg screened under one id (e.g. "innocent") would be stored and served under the .nuspec's own id (e.g.
        // "evil-payload"), a screen-label bypass, the way Composer/CocoaPods refuse a manifest that disagrees with the
        // deploy path. NuGet's push endpoint carries no path coordinate (nuget push is coordinate-less), so the check
        // must live here, in the importer. Only the id is compared: like Composer's name-vs-path check, the version
        // segment is the path's own and NuGet normalises it in the flat container, so comparing it would false-refuse
        // a legitimately non-normalised .nuspec version.
        Optional<ArtifactDescriptor> screened = importTarget(path);
        if (screened.isEmpty()) {
            // A path with no trailing <id>/<version>/<file> shape is not screened by the edge (flat single-file .nupkg):
            // replay as before - importTarget declining it is the pre-existing, separately tracked flat-file behaviour.
            new NuGetFormat().handle(new ReplayExchange("/nuget/v3/package", content), store);
            return;
        }
        // Spool the (possibly large) .nupkg to a temp file so its .nuspec can be read once and the body replayed once,
        // without buffering it on the heap or writing a rejected package into the store.
        Path spool = ownerOnlyTemp("nuget-import-", ".nupkg");
        try {
            try (OutputStream out = Files.newOutputStream(spool)) {
                content.transferTo(out);
            }
            String[] coordinate;
            try (InputStream in = Files.newInputStream(spool)) {
                coordinate = NuGetFormat.coordinate(in);
            }
            if (coordinate == null
                    || !coordinate[0].toLowerCase(Locale.ROOT).equals(screened.get().coordinate())) {
                // The .nuspec id disagrees with the id the edge screened from the path: refuse rather than store it
                // under an id the gate never saw (screen-label bypass).
                return;
            }
            try (InputStream in = Files.newInputStream(spool)) {
                new NuGetFormat().handle(new ReplayExchange("/nuget/v3/package", in), store);
            }
        } finally {
            Files.deleteIfExists(spool);
        }
    }

    /** The owner-only import spool ({@link OwnerOnly}): the buffered {@code .nupkg} is the plaintext artifact body,
     *  and must not sit world-readable in the shared temp directory for the import's life. The {@code newOutputStream}
     *  write opens it in place (truncate), preserving the mode. */
    private static Path ownerOnlyTemp(String prefix, String suffix) throws IOException {
        return OwnerOnly.createTempFile(prefix, suffix);
    }

}
