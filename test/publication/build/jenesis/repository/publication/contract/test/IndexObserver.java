package build.jenesis.repository.publication.contract.test;

import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.PublicationObserver;

import module java.base;

/**
 * The best-effort archetype: a derived index of served paths, written inline from the callback and healed by a sweep
 * over the {@code publish/} pointer namespace.
 *
 * <p>It is the shape the {@code PublicationObserver} contract's two-route rule is written for - live events for the
 * steady state, a full re-derivation for the gaps - and the reason its delivery class is
 * {@code BEST_EFFORT_REPAIRED} rather than anything stronger: the callback fires after the commit point, so a crash
 * in that window leaves an artifact serving that this index never heard of, and only the sweep closes it.
 *
 * <p>The row body is the request path, not the content hash, so that a converged projection can be declared without
 * knowing what bytes a check happened to publish. The hash is still asserted: a descriptor arriving without its
 * content-addressed identity records a row that no converged projection matches, which fails loudly rather than
 * quietly recording a half-identified artifact.
 */
public final class IndexObserver implements PublicationObserver {

    /** The key space this surface owns. */
    public static final String SPACE = "kitindex";

    @Override
    public void onPublished(ArtifactDescriptor artifact, ArtifactStore store) throws IOException {
        Keys.upsert(store, SPACE + "/" + Keys.slug(artifact.path()), row(artifact));
    }

    @Override
    public void onDeleted(ArtifactDescriptor artifact, ArtifactStore store) throws IOException {
        store.delete(SPACE + "/" + Keys.slug(artifact.path()));
    }

    /** What one row holds. A publish whose descriptor carries no blob identity is recorded as such rather than
     *  silently indistinguishable from a complete one - the contract promises the identity is stamped on by the time
     *  {@code onPublished} fires, and a surface that shrugged at its absence could never notice it stopped being. */
    static String row(ArtifactDescriptor artifact) {
        return artifact.hash() == null ? artifact.path() + " (no blob identity)" : artifact.path();
    }
}
