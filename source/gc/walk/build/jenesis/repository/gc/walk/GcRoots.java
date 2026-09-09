package build.jenesis.repository.gc.walk;

import module java.base;

import build.jenesis.repository.format.BlobReferences;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Known;

/**
 * Where a store's serving pointers live, as the collector must be told them: every namespace a live pointer can sit
 * in, or an unknown answer that stops the sweep.
 *
 * <p><b>Why this is a seam rather than a constant.</b> {@link #declared} has all the layout knowledge there is -
 * {@code publish}, plus whatever each installed format lends through {@link BlobReferences}. What it cannot do is
 * notice that a store holds content for a format nobody has installed any more, which is the one case where a
 * complete-looking root list is wrong and a sweep would delete a live blob. Answering that needs a durable record
 * of the ecosystems the store has seen, which a deployment may or may not keep, so it is contributed rather than
 * assumed: a deployment that keeps one refuses instead of guessing, and one that does not is not made to invent it.
 *
 * <p>Deletion is unrecoverable, so the contract is asymmetric. An implementation may always answer unknown and cost
 * only a deferred sweep; it may answer known only when it can account for everything stored.
 */
@FunctionalInterface
public interface GcRoots {

    /** The pointer roots of this store, or an unknown answer naming why the sweep must not run. */
    Known<List<String>> roots(ArtifactStore store) throws IOException;

    /** The contributed answer where one is installed, else {@link #declared}. */
    static GcRoots installed() {
        Iterator<GcRoots> discovered = ServiceLoader.load(GcRoots.class).iterator();
        return discovered.hasNext() ? discovered.next() : GcRoots::declared;
    }

    /** {@code publish} plus every installed format's lent blob roots, always as a known answer - the one
     *  computation of that set ({@link BlobReferences#pointerRoots}), so what a walk enumerates and what a
     *  collector judges against cannot drift apart. */
    static Known<List<String>> declared(ArtifactStore store) {
        return Known.known(BlobReferences.pointerRoots());
    }
}
