package build.jenesis.repository.compliance.scan;

import module java.base;
import build.jenesis.repository.closure.ReliedOn;
import build.jenesis.repository.store.ArtifactStore;

/**
 * Whether a published version relies on a vulnerable version, which a vulnerability ranking puts first: what the
 * closures resolved so far reach, through the relied-on index ({@link ReliedOn#relied}).
 */
@FunctionalInterface
public interface Reached {

    /** Nothing is reached: the ranking orders by its signals alone. */
    Reached NONE = (_, _, _) -> false;

    /** Whether something relies on {@code version} of {@code coordinate} of {@code ecosystem}. */
    boolean reached(String ecosystem, String coordinate, String version) throws IOException;

    /** The relied-on index over {@code store}, a repository's, and {@code tenant}, its tenant's whole store where the
     *  caller reaches it. */
    static Reached over(ArtifactStore store, Optional<ArtifactStore> tenant) {
        return (ecosystem, coordinate, version) -> ReliedOn.relied(store, tenant, ecosystem, coordinate, version);
    }
}
