package build.jenesis.repository.closure.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.repository.closure.spi.ClosureWalk;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.ArtifactStoreProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which member of a walk holds what a closure places in a repository: the empty name is the version's own repository,
 * a fallback is found by its name past the first member, and a name the walk does not reach - the own repository's
 * among them, since a closure never names it - finds nothing.
 */
class ClosureWalkHolderTest {

    @TempDir
    Path root;

    @Test
    void a_closure_s_repository_names_the_member_holding_it() {
        ArtifactStore tenant = ArtifactStoreProvider.resolve("filesystem",
                key -> "jenrepo.filesystem.root".equals(key) ? root.toString() : null).scope("default");
        ClosureWalk.Member own = new ClosureWalk.Member("releases", tenant.scope("releases"));
        ClosureWalk.Member proxy = new ClosureWalk.Member("proxy", tenant.scope("proxy"));
        ClosureWalk.Member mirror = new ClosureWalk.Member("mirror", tenant.scope("mirror"));
        ClosureWalk walk = new ClosureWalk(List.of(own, proxy, mirror));

        assertThat(walk.holder("")).contains(own);
        assertThat(walk.holder("proxy")).contains(proxy);
        assertThat(walk.holder("mirror")).contains(mirror);
        assertThat(walk.holder("releases")).as("the own repository is the empty name, never its own").isEmpty();
        assertThat(walk.holder("elsewhere")).isEmpty();
    }
}
