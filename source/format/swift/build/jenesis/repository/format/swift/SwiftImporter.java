package build.jenesis.repository.format.swift;

import module java.base;
import build.jenesis.repository.blobs.ReplayExchange;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.multipart.MultipartForm;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;

/**
 * Imports a Swift registry laid out as Artifactory lays one out - {@code <scope>/<name>/<name>-<version>.zip} -
 * replaying each archive through {@link SwiftFormat}'s own publish, so a release is screened, indexed and made
 * immutable as a client's is. Only the archives migrate; the documents, manifests and identifier lookup derive from
 * what a publish stores. The name is the archive's directory, and the file must be that name, a hyphen, the version and
 * {@code .zip}, which splits a hyphenated name in the one place it can. Those releases land in one
 * {@code /swift/swift/...} registry; another deployment's listing names an archive as it serves it,
 * {@code <registry>/<scope>/<name>/<version>.zip}, and keeps its registry.
 */
public final class SwiftImporter implements RepositoryImporter {

    /** The registry a release lands in when its path names none. */
    private static final String REPO = "swift";

    private static final String ZIP = ".zip";

    @Override
    public boolean imports(String format) {
        return format.equals("swift");
    }

    @Override
    public Optional<ArtifactDescriptor> importTarget(String path) {
        return release(path).map(release -> new SwiftFormat().describe(release + ZIP).orElseThrow());
    }

    @Override
    public void importArtifact(String path, InputStream content, ArtifactStore store) throws IOException {
        Optional<String> release = release(path);
        if (release.isEmpty()) {
            return;                 // derived documents and other assets carry no release
        }
        String file = path.substring(path.lastIndexOf('/') + 1);
        MultipartForm form = MultipartForm.create().file("source-archive", file, "application/zip", -1,
                () -> content);
        try (InputStream body = form.open()) {
            new SwiftFormat().handle(ReplayExchange.put(release.get(), body,
                    Map.of("Content-Type", form.contentType())), store);
        }
    }

    /** The release path {@code /swift/<registry>/<scope>/<name>/<version>} an archive's source path names, or empty for
     *  any other path: an incumbent's {@code <scope>/<name>/<name>-<version>.zip} lands in the single registry, and a
     *  path of this format's own served shape, {@code <registry>/<scope>/<name>/<version>.zip}, keeps its registry. */
    private static Optional<String> release(String path) {
        String[] segments = RepositoryImporter.importablePath(path, "swift").split("/");
        if (segments.length < 3) {
            return Optional.empty();
        }
        String scope = segments[segments.length - 3], name = segments[segments.length - 2];
        String file = segments[segments.length - 1];
        if (!file.endsWith(ZIP)) {
            return Optional.empty();
        }
        if (file.startsWith(name + "-") && file.length() > name.length() + 1 + ZIP.length()) {
            String version = file.substring(name.length() + 1, file.length() - ZIP.length());
            return Optional.of("/swift/" + REPO + "/" + scope + "/" + name + "/" + version);
        }
        if (segments.length == 4 && file.length() > ZIP.length()) {
            return Optional.of("/swift/" + segments[0] + "/" + scope + "/" + name + "/"
                    + file.substring(0, file.length() - ZIP.length()));
        }
        return Optional.empty();
    }
}
