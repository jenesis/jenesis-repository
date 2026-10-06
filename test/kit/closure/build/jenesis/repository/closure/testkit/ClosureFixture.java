package build.jenesis.repository.closure.testkit;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;

/**
 * How one {@code ClosureSource} is driven through {@link ClosureContract}: the release it is asked about, published the
 * way its source reads one, and a dependency held the way its source finds one. Everything the checks share - asking the
 * source, holding a copy for review, the bound, the failing store - is the kit's, so a fixture says only what is
 * particular to its source.
 */
public interface ClosureFixture {

    /** A package of the fixture's ecosystem at one version. */
    record Named(String coordinate, String version) {
    }

    /** The name of the installed source this fixture drives. */
    String source();

    /** The ecosystem the release is of. */
    String ecosystem();

    /** The release the source is asked about. */
    Named release();

    /** The {@code index}-th dependency a release names - a distinct package for every index. */
    Named dependency(int index);

    /** Publishes {@link #release()} into {@code store} as of {@code now}, naming each of {@code dependencies} - at
     *  exactly its version - where its source reads what a release names. */
    void publish(ArtifactStore store, List<Named> dependencies, Instant now) throws IOException;

    /** Publishes {@link #release()} into {@code store} as of {@code now} carrying nothing its source reads. */
    void bare(ArtifactStore store, Instant now) throws IOException;

    /** Makes {@code dependency} a version {@code store} holds and serves, as its source finds one, declaring nothing
     *  of its own. */
    void hold(ArtifactStore store, Named dependency, Instant now) throws IOException;

    /** The path of a file of {@code dependency} as its format places it - what a hold on it names. */
    String path(Named dependency);

    /** The properties this source does not have, each with the reason and where the claim is proven instead. */
    Map<ClosureContract.Property, String> unsupported();
}
