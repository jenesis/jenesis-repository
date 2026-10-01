package build.jenesis.repository.format.rpm;

import module java.base;
import build.jenesis.repository.blobs.ReplayExchange;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;

/**
 * Imports an RPM/yum repository from an incumbent manager. A {@code .rpm} is self-describing, so it is replayed as a
 * push through {@link RpmFormat#handle}, which streams the body into the content-addressed store and reads only the
 * header. A yum layout has no standard sub-directory convention and the filename is the NEVRA the repodata keys on, so
 * a package migrates to {@code /rpm/rpm/<file>.rpm} under a single repository.
 */
public final class RpmImporter implements RepositoryImporter {

    @Override
    public boolean imports(String format) {
        return format.equals("yum") || format.equals("rpm");
    }

    @Override
    public Optional<ArtifactDescriptor> importTarget(String path) {
        // RepositoryImporter clause 4: a traversal-shaped source path is refused by name.
        String relative = RepositoryImporter.importablePath(path, "rpm");
        if (!relative.toLowerCase(Locale.ROOT).endsWith(".rpm")) {
            return Optional.empty();
        }
        // The path importArtifact lays the file out at, so the edge screens the NEVRA RpmFormat parses.
        return new RpmFormat().describe(publishPath(relative));
    }

    @Override
    public void importArtifact(String path, InputStream content, ArtifactStore store) throws IOException {
        // RepositoryImporter clause 4: a traversal-shaped source path is refused by name.
        String relative = RepositoryImporter.importablePath(path, "rpm");
        if (!relative.toLowerCase(Locale.ROOT).endsWith(".rpm")) {
            return;
        }
        new RpmFormat().handle(new ReplayExchange(publishPath(relative), content), store);
    }

    /** The path a source {@code .rpm} migrates to: the single {@code rpm} repository, by filename, so the
     *  {@code <location href>} matches where it is served. */
    private static String publishPath(String path) {
        int slash = path.lastIndexOf('/');
        return "/rpm/rpm/" + (slash < 0 ? path : path.substring(slash + 1));
    }

}
