package build.jenesis.repository.format.nuget;

import module java.base;
import build.jenesis.repository.blobs.ReplayExchange;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.OwnerOnly;

/**
 * Imports a NuGet repository from an incumbent manager. A {@code .nupkg} is self-describing, so it is replayed as a
 * push through {@link NuGetFormat#handle}, which accepts a raw package body.
 */
public final class NuGetImporter implements RepositoryImporter {

    @Override
    public boolean imports(String format) {
        return format.equals("nuget");
    }

    @Override
    public Optional<ArtifactDescriptor> importTarget(String path) {
        // RepositoryImporter clause 4: a traversal-shaped source path is refused by name.
        String relative = RepositoryImporter.importablePath(path, "nuget");
        if (!relative.toLowerCase(Locale.ROOT).endsWith(".nupkg")) {
            return Optional.empty();
        }
        // The coordinate is the trailing <id>/<version>/<file>.nupkg segments, the flat-container shape, so a deep
        // incumbent path still screens the true coordinate. A path without that shape screens nothing and is left to
        // the .nuspec replay.
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
        // RepositoryImporter clause 4: a traversal-shaped source path is refused by name.
        String relative = RepositoryImporter.importablePath(path, "nuget");
        if (!relative.toLowerCase(Locale.ROOT).endsWith(".nupkg")) {
            return;
        }
        // The edge screened the id from the path, and the store keys the .nuspec's id; they must agree, or a package
        // screened under one id would serve under another. Only the id is compared, since NuGet normalises the version.
        Optional<ArtifactDescriptor> screened = importTarget(path);
        if (screened.isEmpty()) {
            // A path without the trailing shape was not screened by the edge: replayed as a push.
            new NuGetFormat().handle(new ReplayExchange("/nuget/v3/package", content), store);
            return;
        }
        // Spooled to a temp file so the .nuspec is read once and the body replayed once, without heap buffering or
        // storing a rejected package.
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
                // The .nuspec id disagrees with the screened id: refused.
                return;
            }
            try (InputStream in = Files.newInputStream(spool)) {
                new NuGetFormat().handle(new ReplayExchange("/nuget/v3/package", in), store);
            }
        } finally {
            Files.deleteIfExists(spool);
        }
    }

    /** The owner-only import spool ({@link OwnerOnly}): the buffered {@code .nupkg} must not sit world-readable in the
     *  shared temp directory. The write truncates in place, keeping the mode. */
    private static Path ownerOnlyTemp(String prefix, String suffix) throws IOException {
        return OwnerOnly.createTempFile(prefix, suffix);
    }

}
