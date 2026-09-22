package build.jenesis.repository.format.debian;

import module java.base;
import build.jenesis.repository.blobs.ReplayExchange;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;

/**
 * Imports a Debian/apt repository (Nexus/Artifactory {@code apt}) from an incumbent manager. A {@code .deb} is
 * self-describing - its {@code control} stanza carries the package, version and architecture - so the importer replays
 * it as a push through {@link DebianFormat#handle}, which reads that stanza, stores the file in the pool and precomputes
 * the {@code Packages} stanza; the stored indexes follow on the push. A Nexus/Artifactory apt path is
 * {@code <...>/pool/<component>/<...>/<file>.deb}, so the component and filename carry over; the suite is a repo-layout
 * choice the {@code .deb} does not carry, so it defaults to {@code stable}. One of the format importers,
 * delegated to by {@link DebianFormat}, which carries the same {@code RepositoryImporter} capability the built-in
 * importers use.
 */
public final class DebianImporter implements RepositoryImporter {

    @Override
    public boolean imports(String format) {
        return format.equals("debian") || format.equals("apt");
    }

    @Override
    public Optional<ArtifactDescriptor> importTarget(String path) {
        // RepositoryImporter clause 4: a source path is as client-supplied as a request path, so a
        // traversal-shaped one is refused by name rather than echoed into the descriptor the import edge
        // screens and the trail records (the fix retrofitted here - §13).
        String relative = RepositoryImporter.importablePath(path, "debian");
        if (!relative.toLowerCase(Locale.ROOT).endsWith(".deb")) {
            return Optional.empty();
        }
        // The .deb's target coordinate under /debian/stable/pool/<component>/<file>, the same pool path importArtifact
        // lays it out at, so the edge screens the real Debian coordinate DebianFormat parses from the filename.
        return new DebianFormat().describe(publishPath(relative));
    }

    @Override
    public void importArtifact(String path, InputStream content, ArtifactStore store) throws IOException {
        // RepositoryImporter clause 4: a source path is as client-supplied as a request path, so a
        // traversal-shaped one is refused by name rather than echoed into the descriptor the import edge
        // screens and the trail records (the fix retrofitted here - §13).
        String relative = RepositoryImporter.importablePath(path, "debian");
        if (!relative.toLowerCase(Locale.ROOT).endsWith(".deb")) {
            return;
        }
        new DebianFormat().handle(new ReplayExchange(publishPath(relative), content), store);
    }

    private static String publishPath(String path) {
        String[] segments = path.split("/");
        String component = "main";
        for (int index = 0; index + 1 < segments.length; index++) {
            if (segments[index].equals("pool")) {
                component = segments[index + 1];
                break;
            }
        }
        return "/debian/stable/pool/" + component + "/" + segments[segments.length - 1];
    }

}
