package build.jenesis.repository.format.winget;

import module java.base;
import build.jenesis.repository.blobs.ReplayExchange;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;

/**
 * Imports a winget REST source, replaying each asset through {@link WingetFormat}'s own publish paths so an exported
 * repository round-trips and an imported manifest is validated as a published one is.
 *
 * <p>The version manifests and the installer bytes migrate; the {@code information} document, the search index and the
 * assembled {@code packageManifests} answers are derived. A source path is mapped by its trailing
 * {@code .../manifests/<id>/<version>} or {@code .../installers/<id>/<version>/<file>} segments; an identifier and a
 * version contain no {@code /}, so this is unambiguous. A path of the served shape, {@code <registry>/manifests/...} or
 * {@code <registry>/installers/...} - another deployment's listing - keeps its registry; every other package lands in
 * one {@code /winget/winget/...} registry.
 */
public final class WingetImporter implements RepositoryImporter {

    /** The registry a package lands in when its path names none. */
    private static final String REPO = "winget";

    private static final String MANIFESTS = "manifests";
    private static final String INSTALLERS = "installers";

    public WingetImporter() {
    }

    @Override
    public boolean imports(String format) {
        return format.equals("winget");
    }

    @Override
    public Optional<ArtifactDescriptor> importTarget(String path) {
        // RepositoryImporter clause 4: a traversal-shaped source path is refused by name.
        String relative = RepositoryImporter.importablePath(path, REPO);
        // Split on segments, so a path that begins with manifests/... or installers/... matches as well as a nested
        // one.
        String[] segments = relative.split("/", -1);
        for (int at = 0; at < segments.length; at++) {
            // A path of the served shape, <registry>/<manifests|installers>/..., keeps its registry.
            String registry = at == 1 ? segments[0] : REPO;
            if (segments[at].equals(MANIFESTS) && segments.length - at == 3) {
                return descriptor(registry, segments[at + 1], segments[at + 2], null);
            }
            if (segments[at].equals(INSTALLERS) && segments.length - at == 4) {
                return descriptor(registry, segments[at + 1], segments[at + 2], segments[at + 3]);
            }
        }
        return Optional.empty();
    }

    /** The target descriptor for one asset: a manifest when {@code file} is null, an installer when it is not. */
    private static Optional<ArtifactDescriptor> descriptor(String registry, String identifier, String version,
                                                           String file) {
        if (identifier.isEmpty() || version.isEmpty() || (file != null && file.isEmpty())) {
            return Optional.empty();
        }
        boolean prerelease = version.indexOf('-') >= 0;
        return Optional.of(file == null
                ? new ArtifactDescriptor(WingetFormat.ECOSYSTEM, identifier, version,
                        "/winget/" + registry + "/manifests/" + identifier + "/" + version,
                        "application/json", prerelease, null, -1L)
                : new ArtifactDescriptor(WingetFormat.ECOSYSTEM, identifier, version,
                        "/winget/" + registry + "/installers/" + identifier + "/" + version + "/" + file,
                        "application/octet-stream", prerelease, null, -1L));
    }

    @Override
    public void importArtifact(String path, InputStream content, ArtifactStore store) throws IOException {
        // Replayed through the format's own publish routes, validated and streamed as a client publish is.
        Optional<ArtifactDescriptor> target = importTarget(path);
        if (target.isEmpty()) {
            // Not a manifest or an installer: nothing a client reads is imported.
            return;
        }
        new WingetFormat().handle(new ReplayExchange(target.get().path(), content), store);
    }
}
