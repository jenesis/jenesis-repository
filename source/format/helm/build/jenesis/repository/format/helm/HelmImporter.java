package build.jenesis.repository.format.helm;

import module java.base;
import build.jenesis.repository.blobs.ReplayExchange;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;

/**
 * Imports a classic Helm chart repository, replaying each {@code .tgz} through {@link HelmFormat}'s own
 * {@code PUT charts/<name>-<version>.tgz} path so an exported repository round-trips. Only the archives migrate:
 * {@code index.yaml} is derived, so importing one would overwrite a maintained document. The coordinate is read from
 * {@code Chart.yaml} by the publish, which also refuses an archive whose metadata disagrees with the file name. A path
 * of this format's own served shape, {@code <repository>/charts/<file>.tgz} - another deployment's listing - keeps its
 * repository; every other chart lands in one {@code /helm/helm/...} repository.
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
        // RepositoryImporter clause 4: a traversal-shaped source path is refused by name.
        String relative = RepositoryImporter.importablePath(path, REPO);
        if (!relative.endsWith(TGZ)) {
            return Optional.empty();
        }
        int slash = relative.lastIndexOf('/');
        String file = slash < 0 ? relative : relative.substring(slash + 1);
        if (file.isEmpty() || file.equals(TGZ)) {
            return Optional.empty();
        }
        // The coordinate is left to the publish, which reads Chart.yaml and judges this path against it.
        String[] segments = relative.split("/");
        String repository = segments.length == 3 && segments[1].equals("charts") ? segments[0] : REPO;
        return Optional.of(ArtifactDescriptor.at(HelmFormat.ECOSYSTEM, "/helm/" + repository + "/charts/" + file));
    }

    @Override
    public void importArtifact(String path, InputStream content, ArtifactStore store) throws IOException {
        // Replayed through the format's own PUT, so an import is screened exactly as a publish is.
        Optional<ArtifactDescriptor> target = importTarget(path);
        if (target.isEmpty()) {
            return;                 // index.yaml is derived, and a non-chart asset has no coordinate to file under
        }
        new HelmFormat().handle(new ReplayExchange(target.get().path(), content), store);
    }
}
