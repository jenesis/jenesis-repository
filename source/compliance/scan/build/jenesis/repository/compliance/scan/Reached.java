package build.jenesis.repository.compliance.scan;

import module java.base;
import build.jenesis.repository.closure.ReliedOn;
import build.jenesis.repository.store.ArtifactStore;

/**
 * Whether a published version relies on a vulnerable version, which a vulnerability ranking puts first: what the
 * closures resolved so far reach, through the relied-on index ({@link ReliedOn#relied}) - and a token that moves
 * whenever that answer may have, which the ranking folds into its freshness stamp so a new dependent reorders it on
 * the next rebuild rather than at the next full scan.
 */
public interface Reached {

    /** Nothing is reached: the ranking orders by its signals alone, and nothing moves it. */
    Reached NONE = new Reached() {
        @Override
        public boolean reached(String ecosystem, String coordinate, String version) {
            return false;
        }

        @Override
        public String epoch() {
            return "";
        }
    };

    /** Whether something relies on {@code version} of {@code coordinate} of {@code ecosystem}. */
    boolean reached(String ecosystem, String coordinate, String version) throws IOException;

    /** The token every change to what {@link #reached} answers moves; {@code ""} where nothing ever changed it. */
    String epoch() throws IOException;

    /** The relied-on index over {@code store}, a repository's, and {@code tenant}, its tenant's whole store where the
     *  caller reaches it. */
    static Reached over(ArtifactStore store, Optional<ArtifactStore> tenant) {
        return new Reached() {
            @Override
            public boolean reached(String ecosystem, String coordinate, String version) throws IOException {
                return ReliedOn.relied(store, tenant, ecosystem, coordinate, version);
            }

            @Override
            public String epoch() throws IOException {
                String own = ReliedOn.epoch(store).current();
                return tenant.isEmpty() ? own : own + '/' + ReliedOn.epoch(tenant.get().scope(ReliedOn.SPACE)).current();
            }
        };
    }
}
