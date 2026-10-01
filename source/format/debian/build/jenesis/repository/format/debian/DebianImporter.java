package build.jenesis.repository.format.debian;

import module java.base;
import build.jenesis.repository.blobs.ReplayExchange;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;

/**
 * Imports a Debian/apt repository from an incumbent manager. A {@code .deb} is self-describing, so it is replayed as a
 * push through {@link DebianFormat#handle}. The source path {@code <...>/pool/<component>/<...>/<file>.deb} carries the
 * component and filename; the suite is not carried, so it defaults to {@code stable}.
 */
public final class DebianImporter implements RepositoryImporter {

    @Override
    public boolean imports(String format) {
        return format.equals("debian") || format.equals("apt");
    }

    @Override
    public Optional<ArtifactDescriptor> importTarget(String path) {
        // RepositoryImporter clause 4: a traversal-shaped source path is refused by name.
        String relative = RepositoryImporter.importablePath(path, "debian");
        if (!relative.toLowerCase(Locale.ROOT).endsWith(".deb")) {
            return Optional.empty();
        }
        // The pool path importArtifact lays the file out at, so the edge screens the coordinate DebianFormat parses.
        return new DebianFormat().describe(publishPath(relative));
    }

    @Override
    public void importArtifact(String path, InputStream content, ArtifactStore store) throws IOException {
        // RepositoryImporter clause 4: a traversal-shaped source path is refused by name.
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
