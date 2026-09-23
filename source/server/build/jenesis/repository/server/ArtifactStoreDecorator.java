package build.jenesis.repository.server;

import build.jenesis.repository.store.ArtifactStore;

/**
 * A wrapper a composition layers over the resolved artifact store, applied by
 * {@link RepositoryAutoConfiguration} between the backend it resolved and the quota and read-only wrappers it
 * puts on top. An embedder contributes one as a bean to meter what the store is asked for, to remember what a
 * node already learned, or to add any other concern that belongs around every store call - without redeclaring
 * the store bean and rebuilding the stack underneath it.
 *
 * <p><strong>Why a seam rather than a replacement.</strong> A composition that wanted metering used to declare
 * its own {@code artifactStore} bean, which meant restating the resolution, the quota wrapper and the read-only
 * wrapper in order to add one layer - and the restatement drifted: the deployment-wide quota was dropped from
 * the copy, so a cap an operator set was silently not applied there. A contribution cannot drift from the stack
 * it is layered into, because it does not contain it.
 *
 * <p>Decorators apply in {@link org.springframework.core.annotation.Order} order, innermost first: the one with
 * the lowest order sits closest to the backend and therefore sees every call the ones above it do not answer.
 * That ordering is the whole meaning of a memo above a meter - a read the memo answers is a read the meter must
 * not count, because it never reached the store.
 *
 * <p>A composition with none installed gets the resolved store unchanged, which is the free product's stack.
 */
@FunctionalInterface
public interface ArtifactStoreDecorator {

    /**
     * Wrap {@code store} and return the wrapper, or return {@code store} to decline.
     *
     * <p>Called once, on the boot thread, before anything has been served. An implementation may read
     * configuration and install itself where a static accessor needs it, but must not touch the store: a
     * decorator that reads at composition time turns a boot into a store call, and on a cold deployment into a
     * failure nobody attributes to it.
     */
    ArtifactStore decorate(ArtifactStore store);
}
