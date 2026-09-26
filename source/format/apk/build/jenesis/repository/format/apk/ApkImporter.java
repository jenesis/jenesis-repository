package build.jenesis.repository.format.apk;

import module java.base;
import build.jenesis.repository.blobs.ReplayExchange;
import build.jenesis.repository.format.RepositoryImporter;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;

/**
 * Imports an Alpine repository laid out the way Alpine's own mirrors and Artifactory's Alpine repositories lay one
 * out, {@code <branch>/<repository>/<architecture>/<name>-<version>.apk}, replaying each package through
 * {@link ApkFormat}'s own {@code PUT /apk/<repository>/<architecture>/<file>} so it is read, indexed and screened
 * exactly as a publish is.
 *
 * <p>Only the packages migrate. {@code APKINDEX.tar.gz} is derived, and signed with this repository's own key, so an
 * incumbent's index - signed with a key no client of this repository holds - is skipped with every other asset that
 * is not a package. The coordinate is the one {@link ApkFormat#describe} splits out of the file name, and the publish
 * refuses a package whose {@code .PKGINFO} disagrees with it.
 *
 * <p>The branch is not a level this format has, so the trailing three segments are what an import keys on: the same
 * repository and architecture from two branches land in one, where a version both hold with different bytes is
 * refused as the republish it is rather than one silently replacing the other.
 */
public final class ApkImporter implements RepositoryImporter {

    private static final String APK = ".apk";

    @Override
    public boolean imports(String format) {
        // Artifactory names the package type after the distribution, this product after the client.
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
