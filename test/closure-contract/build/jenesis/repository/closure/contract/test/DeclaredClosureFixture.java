package build.jenesis.repository.closure.contract.test;

import module java.base;
import build.jenesis.repository.closure.testkit.ClosureContract;
import build.jenesis.repository.closure.testkit.ClosureFixture;
import build.jenesis.repository.store.ArtifactStore;

/** The walk over each held version's declarations, over Maven releases whose declarations the publish recorded. */
final class DeclaredClosureFixture implements ClosureFixture {

    @Override
    public String source() {
        return "declarations";
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

    @Override
    public void bare(ArtifactStore store, Instant now) {
        throw new UnsupportedOperationException("the declaration walk always answers");
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
        return Map.of(ClosureContract.Property.NOTHING_TO_SAY_IS_EMPTY, "the last source asked, so it always "
                + "answers - an undeclared closure for a version declaring nothing - which ClosureAnswerTest proves");
    }
}
