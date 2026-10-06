package build.jenesis.repository.closure.contract.test;

import module java.base;
import build.jenesis.repository.closure.testkit.ClosureContract;
import build.jenesis.repository.closure.testkit.ClosureFixture;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;

/** Maven Resolver over the POMs the walk holds. */
final class MavenResolverFixture implements ClosureFixture {

    @Override
    public String source() {
        return "maven-resolver";
    }

    @Override
    public String ecosystem() {
        return "Maven";
    }

    @Override
    public Named release() {
        return MavenReleases.APP;
    }

    @Override
    public Named dependency(int index) {
        return MavenReleases.dependency(index);
    }

    @Override
    public void publish(ArtifactStore store, List<Named> dependencies, Instant now) throws IOException {
        MavenReleases.release(store, release(), dependencies, now);
    }

    /** A release with a jar and no POM: nothing the resolver reads, so the declaration walk answers instead. */
    @Override
    public void bare(ArtifactStore store, Instant now) throws IOException {
        Publication publication = new Publication(store);
        publication.link(MavenReleases.jar(release()),
                publication.storeBlob(new ByteArrayInputStream(new byte[]{1})));
    }

    @Override
    public void hold(ArtifactStore store, Named dependency, Instant now) throws IOException {
        MavenReleases.release(store, dependency, List.of(), now);
    }

    @Override
    public String path(Named dependency) {
        return MavenReleases.pom(dependency);
    }

    @Override
    public Map<ClosureContract.Property, String> unsupported() {
        return Map.of();
    }
}
