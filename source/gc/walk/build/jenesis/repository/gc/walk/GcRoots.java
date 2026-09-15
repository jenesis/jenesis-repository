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
 * <h2>Contract</h2>
 *
 * <ol>
 * <li><b>The answer is asymmetric, because deletion is unrecoverable.</b> An implementation may ALWAYS answer
 * unknown, and costs only a deferred sweep by doing so; it may answer known only when it can account for
 * everything the store holds. An implementation that is unsure answers unknown - guessing here deletes a live
 * blob, and there is nothing to undo it with.</li>
 * <li><b>Known means complete, not best-effort.</b> A returned list must name every namespace a live pointer can
 * sit in, including those of a format this deployment no longer installs. A list that is merely everything the
 * caller could think of is an unknown answer wearing a known answer's clothes.</li>
 * <li><b>Thread-safety.</b> One instance per deployment, discovered once and called from the collector's own
 * threads; implementations are stateless or safely shared, as every SPI here is.</li>
 * <li><b>Failure is unknown, never empty.</b> A read that cannot complete - a store that will not answer, a
 * record that will not parse - is an unknown answer naming why. An empty list means "this store has no roots",
 * which is a statement that every blob in it is garbage.</li>
 * <li><b>Discovery.</b> Through {@link #installed()} alone, which answers the contributed implementation where a
 * deployment installs one and {@link #declared} otherwise. Nothing else loads the service.</li>
 * </ol>
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
