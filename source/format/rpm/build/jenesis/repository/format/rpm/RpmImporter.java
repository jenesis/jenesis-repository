package build.jenesis.repository.format.rpm;

import module java.base;
import build.jenesis.repository.blobs.ReplayExchange;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;

/**
 * Imports an RPM/yum repository (Nexus/Artifactory {@code yum}) from an incumbent manager. A {@code .rpm} is
 * self-describing - the RPM header at the front carries the name, version, release, epoch, architecture and license -
 * so the importer replays it as a push through {@link RpmFormat#handle}, which reads only that header, streams the
 * package into the content-addressed store and precomputes its {@code primary.xml} {@code <package>} stanza; the
 * replayed push joins that stanza into the stored {@code repodata} itself.
 *
 * <p>Unlike the buffered language importers (a {@code .gem}/{@code .nupkg} whose whole body is read to parse its
 * manifest), the {@code .rpm} body is <b>streamed</b> straight from the source into the CAS - the format materialises
 * only the header - so an arbitrarily large package never lands in heap. A yum layout has no standard sub-directory
 * convention (unlike apt's {@code pool/<component>}), and the filename is the NEVRA the repodata keys on, so the
 * package migrates to {@code /rpm/rpm/<file>.rpm} under a single repository. One of the format importers,
 * discovered through the same {@code RepositoryImporter} SPI the built-in importers use.
 */
public final class RpmImporter implements RepositoryImporter {

    @Override
    public boolean imports(String format) {
        return format.equals("yum") || format.equals("rpm");
    }

    @Override
    public Optional<ArtifactDescriptor> importTarget(String path) {
        // RepositoryImporter clause 4: a source path is as client-supplied as a request path, so a
        // traversal-shaped one is refused by name rather than echoed into the descriptor the import edge
        // screens and the trail records (the fix retrofitted here - §13).
        String relative = RepositoryImporter.importablePath(path, "rpm");
        if (!relative.toLowerCase(Locale.ROOT).endsWith(".rpm")) {
            return Optional.empty();
        }
        // The .rpm's target coordinate under /rpm/rpm/<file>, the same repo path importArtifact lays it out at, so the
        // edge screens the real yum NEVRA coordinate RpmFormat parses from the filename.
        return new RpmFormat().describe(publishPath(relative));
    }

    @Override
    public void importArtifact(String path, InputStream content, ArtifactStore store) throws IOException {
        // RepositoryImporter clause 4: a source path is as client-supplied as a request path, so a
        // traversal-shaped one is refused by name rather than echoed into the descriptor the import edge
        // screens and the trail records (the fix retrofitted here - §13).
        String relative = RepositoryImporter.importablePath(path, "rpm");
        if (!relative.toLowerCase(Locale.ROOT).endsWith(".rpm")) {
            return;
        }
        new RpmFormat().handle(new ReplayExchange(publishPath(relative), content), store);
    }

    /** The yum repository path a source {@code .rpm} migrates to: a single {@code rpm} repository keyed by its
     *  filename NEVRA, so the {@code <location href>} the client follows matches where it is served. */
    private static String publishPath(String path) {
        int slash = path.lastIndexOf('/');
        return "/rpm/rpm/" + (slash < 0 ? path : path.substring(slash + 1));
    }

}
