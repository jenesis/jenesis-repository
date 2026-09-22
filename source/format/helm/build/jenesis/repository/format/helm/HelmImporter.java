package build.jenesis.repository.format.helm;

import module java.base;
import build.jenesis.repository.blobs.ReplayExchange;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;

/**
 * Imports a classic Helm chart repository, replaying each {@code .tgz} through {@link HelmFormat}'s own
 * {@code PUT charts/<name>-<version>.tgz} path so an exported repository round-trips.
 *
 * <p>Only the archives migrate. {@code index.yaml} is derived - the format rebuilds it from each chart's stored
 * stanza - so importing one would overwrite a maintained document with a snapshot of somebody else's, and an asset
 * that is not a {@code .tgz} is skipped. Each chart's coordinate is read from the {@code Chart.yaml} inside it by the
 * publish path itself rather than parsed out of the file name here, which is the same reason the publish reads it
 * there: a chart name may contain hyphens, so the file name does not say where the version begins. The file name is
 * still carried through, because the publish checks the archive's metadata against it and refuses a mismatch.
 *
 * <p>All charts migrate into a single {@code /helm/helm/...} repository, the flat migration the RPM, Cargo, Conda and
 * Composer importers use. SPI-only: the importer reuses the format's publish path rather than reimplementing it.
 */
public final class HelmImporter implements RepositoryImporter {

    private static final String REPO = "helm";

    private static final String TGZ = ".tgz";

    public HelmImporter() {
    }

    @Override
    public boolean imports(String format) {
        return format.equals("helm");
    }

    @Override
    public Optional<ArtifactDescriptor> importTarget(String path) {
        // RepositoryImporter clause 4: a source path is as client-supplied as a request path, so a traversal-shaped
        // one is refused by name rather than echoed into the descriptor the import edge screens.
        String relative = RepositoryImporter.importablePath(path, REPO);
        if (!relative.endsWith(TGZ)) {
            return Optional.empty();
        }
        int slash = relative.lastIndexOf('/');
        String file = slash < 0 ? relative : relative.substring(slash + 1);
        if (file.isEmpty() || file.equals(TGZ)) {
            return Optional.empty();
        }
        // The coordinate is deliberately left to the publish, which reads Chart.yaml; the descriptor names the path
        // the replay PUTs to, and the archive's own metadata decides whether that path was the truth.
        return Optional.of(ArtifactDescriptor.at(HelmFormat.ECOSYSTEM, "/helm/" + REPO + "/charts/" + file));
    }

    @Override
    public void importArtifact(String path, InputStream content, ArtifactStore store) throws IOException {
        // Replayed through the format's own PUT, which reads Chart.yaml out of the archive and refuses one whose
        // metadata disagrees with the file name - so an import is screened exactly as a publish is.
        Optional<ArtifactDescriptor> target = importTarget(path);
        if (target.isEmpty()) {
            return;                 // index.yaml is derived, and a non-chart asset has no coordinate to file under
        }
        new HelmFormat().handle(new ReplayExchange(target.get().path(), content), store);
    }
}
