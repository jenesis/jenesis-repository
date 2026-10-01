package build.jenesis.repository.format.apk;

import module java.base;
import build.jenesis.repository.blobs.ReplayExchange;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;

/**
 * Imports an Alpine repository laid out as Alpine's mirrors and Artifactory lay one out,
 * {@code <branch>/<repository>/<architecture>/<name>-<version>.apk}, replaying each package through {@link ApkFormat}'s
 * {@code PUT} so it is read, indexed and screened as a publish is. Only packages migrate: the index is derived and
 * signed with this repository's key. The branch is not a level here, so two branches' same repository and architecture
 * land in one, and a version both hold with different bytes is refused as a republish.
 */
public final class ApkImporter implements RepositoryImporter {

    private static final String APK = ".apk";

    @Override
    public boolean imports(String format) {
        // Artifactory names the type after the distribution, this product after the client.
        return format.equals("apk") || format.equals("alpine");
    }

    @Override
    public Optional<ArtifactDescriptor> importTarget(String path) {
        String[] segments = RepositoryImporter.importablePath(path, "apk").split("/");
        if (segments.length < 3 || !segments[segments.length - 1].endsWith(APK)
                || segments[segments.length - 1].equals(APK)) {
            return Optional.empty();
        }
        String repository = segments[segments.length - 3], architecture = segments[segments.length - 2];
        return new ApkFormat().describe("/apk/" + repository + "/" + architecture + "/" + segments[segments.length - 1]);
    }

    @Override
    public void importArtifact(String path, InputStream content, ArtifactStore store) throws IOException {
        Optional<ArtifactDescriptor> target = importTarget(path);
        if (target.isEmpty()) {
            return;                 // an index is derived, and anything else has no package to lay out
        }
        new ApkFormat().handle(new ReplayExchange(target.get().path(), content), store);
    }
}
